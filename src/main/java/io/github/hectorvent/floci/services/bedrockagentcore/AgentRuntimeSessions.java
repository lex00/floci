package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.services.bedrockagentcore.AgentRuntimeLauncher.Launch;
import io.github.hectorvent.floci.services.bedrockagentcore.AgentRuntimeLauncher.RunningRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreControlService;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The warm containers behind {@code InvokeAgentRuntime} when the runtime mode is {@code docker}. Each
 * runtime session gets its own container, started by its first invocation and stopped once it has
 * been idle for the configured timeout, when its runtime is deleted, or when Floci stops.
 *
 * <p>Invocations follow the AgentCore runtime contract for the runtime's server protocol: HTTP and
 * AG-UI servers listen on 8080 and take {@code POST /invocations} once {@code GET /ping} answers
 * 200, MCP servers listen on 8000 at {@code /mcp}, and A2A servers on 9000 at {@code /}.
 */
@ApplicationScoped
public class AgentRuntimeSessions implements ContainerTeardown {

    static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";
    /** Request headers AgentCore passes to the runtime. */
    static final List<String> FORWARDED_REQUEST_HEADERS = List.of("Content-Type", "Accept", SESSION_HEADER,
            "X-Amzn-Bedrock-AgentCore-Runtime-User-Id", "Mcp-Session-Id", "Mcp-Protocol-Version",
            "X-Amzn-Trace-Id", "traceparent", "tracestate", "baggage");
    /** Response headers of the runtime that the InvokeAgentRuntime response carries. */
    static final List<String> FORWARDED_RESPONSE_HEADERS = List.of("Content-Type", "Mcp-Session-Id",
            "Mcp-Protocol-Version", "X-Amzn-Trace-Id", "traceparent", "tracestate", "baggage");
    private static final Logger LOG = Logger.getLogger(AgentRuntimeSessions.class);
    private static final Duration READY_POLL = Duration.ofMillis(200);
    private static final Duration INVOKE_TIMEOUT = Duration.ofMinutes(15);

    /** Where a runtime's server listens and what it answers, by server protocol. */
    record Contract(int port, String invokePath, String pingPath) {
        static Contract of(JsonNode protocolConfiguration) {
            String protocol = protocolConfiguration == null
                    ? "HTTP" : protocolConfiguration.path("serverProtocol").asText("HTTP");
            return switch (protocol) {
                case "MCP" -> new Contract(8000, "/mcp", null);
                case "A2A" -> new Contract(9000, "/", null);
                default -> new Contract(8080, "/invocations", "/ping");
            };
        }
    }

    /** The runtime version and endpoint an invocation resolved to. */
    public record Target(String accountId, String region, AgentRuntime runtime, AgentRuntimeVersion version,
                         String endpointName) {
    }

    /**
     * The runtime's answer. {@code body} streams as the runtime writes it; closing it releases the
     * session for the idle timer.
     */
    public record Invocation(int status, Map<String, String> headers, InputStream body) {
    }

    private static final class Session {
        private final String key;
        private final CompletableFuture<RunningRuntime> ready = new CompletableFuture<>();
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicLong lastUsed = new AtomicLong(System.nanoTime());
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final long createdAt = System.nanoTime();
        private final long idleNanos;
        private final long maxLifetimeNanos;

        private Session(String key, Duration idle, Duration maxLifetime) {
            this.key = key;
            this.idleNanos = idle.toNanos();
            this.maxLifetimeNanos = maxLifetime.toNanos();
        }

        private boolean expired(long now) {
            return now - createdAt >= maxLifetimeNanos;
        }

        private void reserve() {
            inFlight.incrementAndGet();
            lastUsed.set(System.nanoTime());
        }

        private void release() {
            lastUsed.set(System.nanoTime());
            inFlight.decrementAndGet();
        }
    }

    private static final Duration DEFAULT_MAX_LIFETIME = Duration.ofHours(8);

    private final AgentRuntimeLauncher launcher;
    private final Duration idleTimeout;
    private final Duration startupTimeout;
    private final HttpClient http;
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "agentcore-runtime-sessions");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public AgentRuntimeSessions(EmulatorConfig config, AgentRuntimeDockerLauncher launcher,
                                BedrockAgentCoreControlService controlService) {
        this(launcher, Duration.ofSeconds(config.services().bedrockAgentCore().runtimeIdleTimeoutSeconds()),
                Duration.ofSeconds(config.services().bedrockAgentCore().runtimeStartupTimeoutSeconds()));
        controlService.onRuntimeDeleted((region, runtime) ->
                reaper.execute(() -> stopRuntime(runtime.getAccountId(), region, runtime.getAgentRuntimeId())));
    }

    AgentRuntimeSessions(AgentRuntimeLauncher launcher, Duration idleTimeout, Duration startupTimeout) {
        this.launcher = launcher;
        this.idleTimeout = idleTimeout;
        this.startupTimeout = startupTimeout;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        long period = Math.max(1, Math.min(30, idleTimeout.toSeconds() / 2 + 1));
        reaper.scheduleWithFixedDelay(this::reapIdle, period, period, TimeUnit.SECONDS);
    }

    /**
     * Sends the payload to the session's container, starting one when the session has none, and
     * returns the runtime's answer. A runtime that fails to start or answers with an error status is
     * a {@code RuntimeClientError}, as on AWS.
     */
    public Invocation invoke(Target target, String sessionId, Map<String, String> requestHeaders, byte[] payload) {
        Contract contract = Contract.of(target.version().getProtocolConfiguration());
        Session session = acquire(target, sessionId, contract);
        RunningRuntime running = session.ready.join();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(running, contract.invokePath()))
                .timeout(INVOKE_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload == null ? new byte[0] : payload));
        requestHeaders.forEach((name, value) -> {
            if (value != null && !value.isEmpty()) {
                request.header(name, value);
            }
        });
        request.setHeader(SESSION_HEADER, sessionId);
        HttpResponse<InputStream> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            session.release();
            discard(session);
            throw runtimeClientError("The runtime closed the connection: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.release();
            throw new AwsException("InternalServerException", "Interrupted while invoking the runtime", 500);
        }
        if (response.statusCode() >= 400) {
            drain(response.body());
            session.release();
            throw runtimeClientError("Received error (" + response.statusCode()
                    + ") from runtime. Please check your CloudWatch logs for more information.");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : FORWARDED_RESPONSE_HEADERS) {
            response.headers().firstValue(name).ifPresent(value -> headers.put(name, value));
        }
        return new Invocation(response.statusCode(), headers, new ReleasingStream(response.body(), session));
    }

    /** The number of sessions with a container, started or starting. */
    int sessionCount() {
        return sessions.size();
    }

    private Session acquire(Target target, String sessionId, Contract contract) {
        String key = sessionKey(target.accountId(), target.region(), target.runtime().getAgentRuntimeId())
                + target.version().getVersion() + "|" + sessionId;
        AtomicBoolean starter = new AtomicBoolean();
        JsonNode lifecycle = target.version().getLifecycleConfiguration();
        Duration idle = lifecycle != null && lifecycle.hasNonNull("idleRuntimeSessionTimeout")
                ? Duration.ofSeconds(lifecycle.get("idleRuntimeSessionTimeout").asLong()) : idleTimeout;
        Duration maxLifetime = lifecycle != null && lifecycle.hasNonNull("maxLifetime")
                ? Duration.ofSeconds(lifecycle.get("maxLifetime").asLong()) : DEFAULT_MAX_LIFETIME;
        List<Session> replaced = new ArrayList<>();
        Session session = sessions.compute(key, (k, current) -> {
            if (current != null && current.alive.get() && !current.expired(System.nanoTime())) {
                current.reserve();
                return current;
            }
            if (current != null) {
                replaced.add(current);
            }
            Session started = new Session(k, idle, maxLifetime);
            started.reserve();
            starter.set(true);
            return started;
        });
        replaced.forEach(old -> reaper.execute(() -> retire(old, "has reached its maximum lifetime")));
        if (starter.get()) {
            start(session, target, contract);
        }
        try {
            session.ready.get();
        } catch (ExecutionException e) {
            session.release();
            if (e.getCause() instanceof AwsException aws) {
                throw aws;
            }
            throw runtimeClientError("The runtime failed to start: " + e.getCause().getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            session.release();
            throw new AwsException("InternalServerException", "Interrupted while starting the runtime", 500);
        }
        return session;
    }

    private void start(Session session, Target target, Contract contract) {
        AgentRuntime runtime = target.runtime();
        RunningRuntime running = null;
        try {
            running = launcher.start(new Launch(target.accountId(), target.region(), runtime.getAgentRuntimeId(),
                    runtime.getAgentRuntimeName(), target.endpointName(), target.version(), contract.port()));
            awaitReady(running, contract);
            session.ready.complete(running);
        } catch (RuntimeException e) {
            session.alive.set(false);
            sessions.remove(session.key, session);
            if (running != null) {
                launcher.stop(running);
            }
            session.ready.completeExceptionally(e);
        }
        if (!session.alive.get() && running != null && session.ready.isDone()
                && !session.ready.isCompletedExceptionally()) {
            launcher.stop(running);
        }
    }

    /** Polls the runtime until it answers its ping, or for a protocol without one, until it answers at all. */
    private void awaitReady(RunningRuntime running, Contract contract) {
        long deadline = System.nanoTime() + startupTimeout.toNanos();
        String path = contract.pingPath() != null ? contract.pingPath() : contract.invokePath();
        HttpRequest ping = HttpRequest.newBuilder(uri(running, path)).timeout(Duration.ofSeconds(2)).GET().build();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> response = http.send(ping, HttpResponse.BodyHandlers.discarding());
                if (contract.pingPath() == null || response.statusCode() == 200) {
                    return;
                }
            } catch (IOException e) {
                LOG.tracev("AgentCore runtime at {0}:{1} not listening yet: {2}",
                        running.host(), running.port(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while the runtime started", e);
            }
            try {
                Thread.sleep(READY_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while the runtime started", e);
            }
        }
        throw runtimeClientError("The runtime did not answer " + path + " on port " + contract.port()
                + " within " + startupTimeout.toSeconds() + " seconds.");
    }

    /** Stops every session container of a runtime. */
    void stopRuntime(String accountId, String region, String runtimeId) {
        String prefix = sessionKey(accountId, region, runtimeId);
        for (Session session : new ArrayList<>(sessions.values())) {
            if (session.key.startsWith(prefix)) {
                discard(session);
            }
        }
    }

    private void discard(Session session) {
        sessions.remove(session.key, session);
        if (!session.alive.compareAndSet(true, false)) {
            return;
        }
        RunningRuntime running = session.ready.getNow(null);
        if (running != null) {
            launcher.stop(running);
        }
    }

    /** Stops each session idle past its runtime's idle timeout, or past its maximum lifetime. */
    private void reapIdle() {
        for (Session session : new ArrayList<>(sessions.values())) {
            AtomicReference<String> reason = new AtomicReference<>();
            sessions.computeIfPresent(session.key, (key, current) -> {
                long now = System.nanoTime();
                if (current == session && session.ready.isDone() && session.inFlight.get() == 0) {
                    if (now - session.lastUsed.get() >= session.idleNanos) {
                        reason.set("is idle");
                    } else if (session.expired(now)) {
                        reason.set("has reached its maximum lifetime");
                    }
                }
                return reason.get() != null ? null : current;
            });
            if (reason.get() != null) {
                retire(session, reason.get());
            }
        }
    }

    private void retire(Session session, String reason) {
        LOG.infov("Stopping the AgentCore runtime session {0}, which {1}", session.key, reason);
        try {
            session.alive.set(false);
            RunningRuntime running = session.ready.getNow(null);
            if (running != null) {
                launcher.stop(running);
            }
        } catch (RuntimeException e) {
            LOG.warnv(e, "Could not stop the AgentCore runtime session {0}", session.key);
        }
    }

    @Override
    public void stopManagedContainers() {
        new ArrayList<>(sessions.values()).forEach(this::discard);
    }

    @PreDestroy
    void shutdown() {
        stopManagedContainers();
        reaper.shutdownNow();
    }

    private static String sessionKey(String accountId, String region, String runtimeId) {
        return accountId + "|" + region + "|" + runtimeId + "|";
    }

    private static URI uri(RunningRuntime running, String path) {
        String host = running.host().contains(":") && !running.host().startsWith("[")
                ? "[" + running.host() + "]" : running.host();
        return URI.create("http://" + host + ":" + running.port() + path);
    }

    private static AwsException runtimeClientError(String message) {
        return new AwsException("RuntimeClientError", message, 424);
    }

    private static void drain(InputStream body) {
        try (InputStream in = body) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            LOG.debugv("Could not read the runtime's error body: {0}", e.getMessage());
        }
    }

    /** Releases the session once the caller has closed the runtime's answer. */
    private static final class ReleasingStream extends FilterInputStream {
        private final Session session;
        private final AtomicBoolean released = new AtomicBoolean();

        private ReleasingStream(InputStream in, Session session) {
            super(in);
            this.session = session;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (released.compareAndSet(false, true)) {
                    session.release();
                }
            }
        }
    }
}
