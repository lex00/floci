package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.MetricDatum;
import io.github.hectorvent.floci.services.route53.Route53Service.OwnedHealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Probes Route 53 health checks of the HTTP, HTTPS, string-match and TCP types on their request
 * interval. Each probe's result is the observation every checker region reports through
 * {@code GetHealthCheckStatus}. {@code FailureThreshold}, {@code Inverted} and {@code Disabled}
 * decide the status, which goes to CloudWatch as {@code AWS/Route53 HealthCheckStatus}.
 *
 * <p>Probe state lives in memory and starts over when Floci restarts. A deleted health check's
 * state is dropped on the next tick. In mock mode, and for the types that watch other health
 * checks, alarms or recovery controls, a check reports one fixed successful observation.
 */
@ApplicationScoped
public class Route53HealthChecker {

    private static final Logger LOG = Logger.getLogger(Route53HealthChecker.class);

    /** Route 53's default checker regions, used when a health check names none. */
    static final List<String> DEFAULT_REGIONS = List.of( // partition-literal: Route 53 checker regions
            "us-east-1", "us-west-1", "us-west-2", "eu-west-1",
            "ap-southeast-1", "ap-southeast-2", "ap-northeast-1", "sa-east-1");
    private static final String METRIC_REGION = "us-east-1"; // partition-literal: Route 53 metrics region
    private static final long DEFAULT_TICK_MS = 1000;

    public record Observation(String region, String ipAddress, String status, Instant checkedTime) {}

    private static final class Entry {
        final HealthCheckProbeState state = new HealthCheckProbeState();
        final AtomicBoolean inFlight = new AtomicBoolean();
        volatile long nextDueMillis;
        volatile Route53HealthProbe.Result last;
        volatile Instant lastCheckedTime;
    }

    private final Route53Service service;
    private final CloudWatchMetricsService metrics;
    private final boolean probing;
    private final Long probeIntervalOverrideMs;
    private final Route53HealthProbe probe = new Route53HealthProbe();
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private ScheduledExecutorService ticker;
    private ExecutorService workers;

    @Inject
    public Route53HealthChecker(Route53Service service, CloudWatchMetricsService metrics, EmulatorConfig config) {
        this.service = service;
        this.metrics = metrics;
        EmulatorConfig.Route53ServiceConfig r53 = config.services().route53();
        this.probing = r53.enabled() && !r53.mock();
        this.probeIntervalOverrideMs = r53.healthCheckProbeIntervalMs().filter(ms -> ms > 0).orElse(null);
    }

    void onStart(@Observes StartupEvent ignored) {
        if (!probing) {
            return;
        }
        long tickMs = probeIntervalOverrideMs != null ? Math.min(DEFAULT_TICK_MS, probeIntervalOverrideMs)
                : DEFAULT_TICK_MS;
        workers = Executors.newVirtualThreadPerTaskExecutor();
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "route53-health-checker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::tickSafely, tickMs, tickMs, TimeUnit.MILLISECONDS);
        LOG.debugv("Route 53 health checker started (tick every {0} ms)", tickMs);
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (ticker != null) {
            ticker.shutdownNow();
        }
        if (workers != null) {
            workers.shutdownNow();
        }
    }

    /** The observations {@code GetHealthCheckStatus} reports for a health check. */
    public List<Observation> observations(HealthCheck hc) {
        HealthCheckConfig cfg = hc.getConfig();
        List<String> regions = cfg != null && cfg.getRegions() != null && !cfg.getRegions().isEmpty()
                ? cfg.getRegions() : DEFAULT_REGIONS;
        if (!probing || cfg == null || !Route53HealthProbe.probes(cfg.getType())) {
            return List.of(new Observation(regions.getFirst(), checkerIp(0),
                    "Success: HTTP Status Code 200, OK", Instant.now()));
        }
        Entry entry = entries.get(hc.getId());
        if (entry == null || entry.last == null) {
            return List.of();
        }
        List<Observation> out = new ArrayList<>(regions.size());
        for (int i = 0; i < regions.size(); i++) {
            out.add(new Observation(regions.get(i), checkerIp(i), entry.last.status(), entry.lastCheckedTime));
        }
        return out;
    }

    void tickSafely() {
        try {
            tick(System.currentTimeMillis());
        } catch (Throwable t) {
            LOG.warn("Route 53 health checker tick failed", t);
        }
    }

    void tick(long nowMillis) {
        Map<String, OwnedHealthCheck> checks = service.allHealthChecksAcrossAccounts();
        entries.keySet().retainAll(checks.keySet());
        for (OwnedHealthCheck owned : checks.values()) {
            HealthCheck hc = owned.healthCheck();
            HealthCheckConfig cfg = hc.getConfig();
            if (cfg == null || !Route53HealthProbe.probes(cfg.getType())) {
                continue;
            }
            Entry entry = entries.computeIfAbsent(hc.getId(), id -> new Entry());
            if (nowMillis < entry.nextDueMillis || !entry.inFlight.compareAndSet(false, true)) {
                continue;
            }
            entry.nextDueMillis = nowMillis + intervalMillis(cfg);
            if (Boolean.TRUE.equals(cfg.getDisabled())) {
                entry.inFlight.set(false);
                publish(owned.accountId(), hc.getId(), cfg, entry);
                continue;
            }
            workers.execute(() -> runProbe(owned.accountId(), hc.getId(), cfg, entry));
        }
    }

    private void runProbe(String accountId, String id, HealthCheckConfig cfg, Entry entry) {
        try {
            Route53HealthProbe.Result result = probe.probe(id, cfg);
            entry.last = result;
            entry.lastCheckedTime = Instant.now();
            entry.state.record(result.success(), cfg.getFailureThreshold() != null ? cfg.getFailureThreshold() : 3);
            publish(accountId, id, cfg, entry);
        } finally {
            entry.inFlight.set(false);
        }
    }

    private void publish(String accountId, String id, HealthCheckConfig cfg, Entry entry) {
        boolean healthy = HealthCheckProbeState.reported(entry.state.healthy(),
                Boolean.TRUE.equals(cfg.getInverted()), Boolean.TRUE.equals(cfg.getDisabled()));
        // One sample per minute, as Route 53 publishes it; later probes in the minute replace it.
        long minute = Instant.now().getEpochSecond() / 60 * 60;
        MetricDatum datum = new MetricDatum();
        datum.setMetricName("HealthCheckStatus");
        datum.setValue(healthy ? 1.0 : 0.0);
        datum.setUnit("None");
        datum.setTimestamp(minute);
        datum.setDimensions(List.of(new Dimension("HealthCheckId", id)));
        try {
            metrics.publishMetricForAccount(accountId, "AWS/Route53", datum, METRIC_REGION, id + "-" + minute);
        } catch (RuntimeException e) {
            LOG.debugv(e, "Route 53 health check {0} status metric not published", id);
        }
    }

    private long intervalMillis(HealthCheckConfig cfg) {
        if (probeIntervalOverrideMs != null) {
            return probeIntervalOverrideMs;
        }
        int seconds = cfg.getRequestInterval() != null ? cfg.getRequestInterval() : 30;
        return seconds * 1000L;
    }

    /** A stable address per checker region, inside Route 53's published checker range 15.177.0.0/18. */
    private static String checkerIp(int index) {
        return "15.177." + (2 + index * 8) + ".10";
    }
}
