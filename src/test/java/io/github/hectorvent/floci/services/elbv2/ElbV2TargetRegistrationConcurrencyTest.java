package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent RegisterTargets calls on the SAME target group must all survive.
 *
 * <p>{@code registerTargets} read-modify-writes the target group's own target list. An
 * {@link ArrayList} mutated from several request threads at once tears in two ways: two
 * {@code add}s can land on the same slot, so one target is simply gone, and a {@code removeIf}
 * walking the list while another thread grows it can read a null slot or throw
 * {@link java.util.ConcurrentModificationException}. The first mode is the one a client sees,
 * because RegisterTargets answers with an empty success body: nothing is reported, and only a
 * later DescribeTargetHealth reveals the loss, as {@code Target.NotRegistered}.
 *
 * <p>Not hypothetical. terraform-aws-modules/alb's complete-alb example registers two
 * different EC2 instances into the one "ex-instance" target group - one through
 * {@code target_groups}, one through {@code additional_target_group_attachments} - and
 * terraform issues both concurrently under its default parallelism of 10. When the emulator
 * drops one, the apply still reports success and the NEXT plan proposes to create the
 * attachment that was lost, which is not a plan the configuration justifies. Measured against
 * the emulator before this fix with a plain AWS CLI probe and no terraform in the loop: 2 of
 * 25 rounds lost a target at concurrency 2 (48 of 50 registered), and 3 of 10 rounds lost one
 * at concurrency 5 (46 of 50 registered).
 *
 * <p>The tests below release every writer off one barrier, so they fail on the unsynchronised
 * implementation essentially every run rather than occasionally.
 */
@ExtendWith(MockitoExtension.class)
class ElbV2TargetRegistrationConcurrencyTest {

    private static final String REGION = "us-west-2";

    /** Enough concurrent writers that a dropped target is near-certain when the write is racy. */
    private static final int WRITERS = 24;

    /** Repeats: a fresh target group each round, because the race is per target group. */
    private static final int ROUNDS = 20;

    @Mock
    ElbV2DataPlane dataPlane;

    @Mock
    ElbV2HealthChecker healthChecker;

    @Mock
    Ec2Service ec2Service;

    private ElbV2Service service;

    @BeforeEach
    void setUp() {
        service = new ElbV2Service();
        service.dataPlane = dataPlane;
        service.healthChecker = healthChecker;
        service.regionResolver = new RegionResolver(REGION, "000000000000");
        service.ec2Service = ec2Service;
    }

    @Test
    void concurrentRegisterTargetsAllSurvive() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String tgArn = createTargetGroup("race-register-" + round);
            List<String> ids = race(round, id -> service.registerTargets(
                    REGION, tgArn, List.of(target(id, 80))));

            Set<String> registered = registeredIds(tgArn);
            assertEquals(new HashSet<>(ids), registered,
                    "round " + round + ": RegisterTargets answered success for every call, so every "
                            + "target must be registered; missing "
                            + ids.stream().filter(i -> !registered.contains(i)).collect(Collectors.toList()));
        }
    }

    @Test
    void concurrentDeregisterTargetsAllTakeEffect() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String tgArn = createTargetGroup("race-deregister-" + round);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                ids.add("10." + round + ".0." + i);
            }
            for (String id : ids) {
                service.registerTargets(REGION, tgArn, List.of(target(id, 80)));
            }
            assertEquals(WRITERS, registeredIds(tgArn).size(), "round " + round + ": sequential setup lost a target");

            race(round, id -> service.deregisterTargets(REGION, tgArn, List.of(target(id, 80))));

            assertTrue(registeredIds(tgArn).isEmpty(),
                    "round " + round + ": DeregisterTargets answered success for every call, so nothing "
                            + "must remain registered; left " + registeredIds(tgArn));
        }
    }

    /**
     * Releases {@link #WRITERS} threads off one barrier, each acting on its own target id, and
     * returns the ids. Any exception a writer threw is rethrown here, so a failure is never
     * mistaken for a lost write.
     */
    private List<String> race(int round, java.util.function.Consumer<String> writer) throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < WRITERS; i++) {
            ids.add("10." + round + ".0." + i);
        }
        CyclicBarrier start = new CyclicBarrier(WRITERS);
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (String id : ids) {
                futures.add(pool.submit(() -> {
                    start.await(10, TimeUnit.SECONDS);
                    writer.accept(id);
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        return ids;
    }

    private Set<String> registeredIds(String tgArn) {
        TargetGroup tg = service.describeTargetGroups(REGION, null, List.of(tgArn), null).getFirst();
        return tg.getTargets().stream().map(TargetDescription::getId).collect(Collectors.toSet());
    }

    private static TargetDescription target(String id, int port) {
        TargetDescription t = new TargetDescription();
        t.setId(id);
        t.setPort(port);
        return t;
    }

    private String createTargetGroup(String name) {
        return service.createTargetGroup(
                REGION, name, "HTTP", "HTTP1", 9999, "vpc-a", "ip",
                "HTTP", "traffic-port", true, "/v1/ready", 30, 5, 5, 2, "200",
                "ipv4", Map.of()).getTargetGroupArn();
    }
}
