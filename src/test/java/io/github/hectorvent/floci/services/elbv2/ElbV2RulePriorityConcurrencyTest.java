package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.elbv2.model.Action;
import io.github.hectorvent.floci.services.elbv2.model.Rule;
import io.github.hectorvent.floci.services.elbv2.model.RuleCondition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

/**
 * Concurrent CreateRule calls on ONE listener.
 *
 * <p>Real ELBv2 raises {@code PriorityInUse} for exactly one condition: another rule on the same
 * listener already holds the priority asked for. Two things have to hold for that to be true here,
 * and neither did.
 *
 * <p>Every rule ARN a listener owns lives in one plain {@link ArrayList} in the
 * {@code listenerToRules} index. {@code createRule} appends to it while its own priority check, and
 * every other reader on the listener, walks it - so a create running beside another create can throw
 * {@link java.util.ConcurrentModificationException} out of the walk. The emulator answers HTTP 500
 * {@code InternalFailure}, which the AWS SDKs retry; when the throw happened after the rule was
 * already committed to the index, the retry finds the priority its own first attempt inserted and
 * comes back {@code PriorityInUse} for a priority nothing else ever asked for. That is
 * INTENTIUS/choudoufu#673: terraform-aws-modules/alb's complete-alb example declares priorities 3, 4
 * and 5000 per listener, terraform issues those creates at once under its default parallelism of 10,
 * and the apply fails naming a conflict the configuration does not contain.
 *
 * <p>The check itself is also not atomic: reading the index for a taken priority and inserting the
 * new rule are separate steps, so two creates asking for the SAME priority can both read it free and
 * both land, leaving a listener holding two rules at one priority - a state AWS never produces.
 *
 * <p>Both tests release every writer off one barrier, so they fail on the unsynchronised
 * implementation essentially every run rather than occasionally.
 */
@ExtendWith(MockitoExtension.class)
class ElbV2RulePriorityConcurrencyTest {

    private static final String REGION = "us-west-2";

    // Application Load Balancers require subnets in at least two Availability Zones.
    private static final List<String> ALB_SUBNETS = List.of("subnet-a", "subnet-b");

    /** Enough concurrent creates that a torn index walk is near-certain when the append is racy. */
    private static final int WRITERS = 24;

    /** Repeats: a fresh listener each round, because the index is per listener. */
    private static final int ROUNDS = 20;

    @Mock
    ElbV2DataPlane dataPlane;

    @Mock
    ElbV2HealthChecker healthChecker;

    @Mock
    Ec2Service ec2Service;

    private ElbV2Service service;
    private String lbArn;
    private String tgArn;

    @BeforeEach
    void setUp() {
        service = new ElbV2Service();
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
        stubAlbSubnets(ec2Service);
        lbArn = service.createLoadBalancer(
                REGION, "race-lb", "internal", "application", "ipv4",
                ALB_SUBNETS, List.of("sg-a"), Map.of()).getLoadBalancerArn();
        tgArn = service.createTargetGroup(
                REGION, "race-tg", "HTTP", "HTTP1", 8080, "vpc-a", "instance",
                "HTTP", "traffic-port", true, "/health", 30, 5, 5, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
    }

    @Test
    void concurrentCreateRuleWithDistinctPrioritiesAllSucceed() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String listenerArn = createListener(9000 + round);
            List<Integer> priorities = distinctPriorities();

            Map<Integer, Throwable> failures = race(priorities,
                    priority -> service.createRule(REGION, listenerArn, List.of(pathPattern("/p" + priority + "/*")),
                            priority, List.of(forwardAction(tgArn)), Map.of()));

            assertTrue(failures.isEmpty(),
                    "round " + round + ": every one of the " + WRITERS + " concurrent creates asked for a "
                            + "priority no other rule on this listener holds, so every one must succeed; "
                            + describe(failures));
            assertEquals(priorities.stream().map(String::valueOf).sorted().collect(Collectors.toList()),
                    nonDefaultPriorities(listenerArn),
                    "round " + round + ": every accepted priority must be readable back");
        }
    }

    @Test
    void concurrentCreateRuleOnOnePriorityAdmitsExactlyOne() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String listenerArn = createListener(9500 + round);
            List<Integer> writers = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                writers.add(i);
            }

            Map<Integer, Throwable> failures = race(writers,
                    ignored -> service.createRule(REGION, listenerArn, List.of(pathPattern("/same/*")),
                            7, List.of(forwardAction(tgArn)), Map.of()));

            assertEquals(List.of("7"), nonDefaultPriorities(listenerArn),
                    "round " + round + ": " + WRITERS + " creates raced for priority 7, so the listener must "
                            + "end up holding it exactly once");
            assertEquals(WRITERS - 1, failures.size(),
                    "round " + round + ": exactly one of the " + WRITERS + " creates may be accepted; "
                            + describe(failures));
            for (Map.Entry<Integer, Throwable> e : failures.entrySet()) {
                assertTrue(e.getValue() instanceof AwsException aws && "PriorityInUse".equals(aws.getErrorCode()),
                        "round " + round + ": a losing create must be refused with PriorityInUse, got "
                                + e.getValue());
            }
        }
    }

    /**
     * Releases one thread per key off a barrier and returns the keys whose call threw, mapped to
     * what it threw. Nothing is rethrown: an emulator error is the measurement here, not an
     * accident of the harness.
     */
    private Map<Integer, Throwable> race(List<Integer> keys, java.util.function.IntConsumer writer)
            throws Exception {
        Map<Integer, Throwable> failures = new ConcurrentHashMap<>();
        CyclicBarrier start = new CyclicBarrier(keys.size());
        ExecutorService pool = Executors.newFixedThreadPool(keys.size());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int key : keys) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        writer.accept(key);
                    }
                    catch (RuntimeException e) {
                        failures.put(key, e);
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }
        finally {
            pool.shutdownNow();
        }
        return failures;
    }

    /** The estate's own priorities (3, 4, 5000) plus enough others to make the race reliable. */
    private static List<Integer> distinctPriorities() {
        List<Integer> priorities = new ArrayList<>(List.of(3, 4, 5000));
        for (int i = 0; priorities.size() < WRITERS; i++) {
            priorities.add(100 + i);
        }
        return priorities;
    }

    private List<String> nonDefaultPriorities(String listenerArn) {
        return service.describeRules(REGION, listenerArn, null).stream()
                .filter(r -> !r.isDefault())
                .map(Rule::getPriority)
                .sorted()
                .collect(Collectors.toList());
    }

    private static String describe(Map<Integer, Throwable> failures) {
        return failures.isEmpty() ? "none failed"
                : failures.size() + " failed: " + failures.entrySet().stream()
                        .map(e -> e.getKey() + " -> " + e.getValue())
                        .sorted()
                        .collect(Collectors.joining("; "));
    }

    private String createListener(int port) {
        return service.createListener(
                REGION, lbArn, "HTTP", port, null, List.of(),
                List.of(forwardAction(tgArn)), List.of(), Map.of()).getListenerArn();
    }

    private static Action forwardAction(String targetGroupArn) {
        Action action = new Action();
        action.setType("forward");
        action.setTargetGroupArn(targetGroupArn);
        return action;
    }

    private static RuleCondition pathPattern(String value) {
        RuleCondition condition = new RuleCondition();
        condition.setField("path-pattern");
        condition.setValues(List.of(value));
        condition.setPathPatternValues(List.of(value));
        return condition;
    }

    private static void stubAlbSubnets(Ec2Service ec2Service) {
        Subnet subnetA = subnet("subnet-a", REGION + "a");
        Subnet subnetB = subnet("subnet-b", REGION + "b");
        lenient().when(ec2Service.requireSubnet(REGION, "subnet-a")).thenReturn(subnetA);
        lenient().when(ec2Service.requireSubnet(REGION, "subnet-b")).thenReturn(subnetB);
        lenient().when(ec2Service.describeSubnets(eq(REGION), eq(ALB_SUBNETS), eq(Map.of())))
                .thenReturn(List.of(subnetA, subnetB));
    }

    private static Subnet subnet(String subnetId, String availabilityZone) {
        Subnet subnet = new Subnet();
        subnet.setSubnetId(subnetId);
        subnet.setAvailabilityZone(availabilityZone);
        return subnet;
    }
}
