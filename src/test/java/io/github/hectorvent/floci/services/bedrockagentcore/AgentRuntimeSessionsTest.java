package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRuntimeSessionsTest {

    private final FakeLauncher launcher = new FakeLauncher();
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentRuntimeSessions sessions;

    @AfterEach
    void stopServers() {
        if (sessions != null) {
            sessions.shutdown();
        }
        launcher.servers.forEach(server -> server.stop(0));
    }

    @Test
    void invokeForwardsPayloadSessionAndContentType() throws Exception {
        sessions = new AgentRuntimeSessions(launcher, Duration.ofMinutes(5), Duration.ofSeconds(10));

        AgentRuntimeSessions.Invocation invocation = sessions.invoke(target("HTTP"), "session-a",
                Map.of("Content-Type", "application/json", "X-Amzn-Trace-Id", "Root=1-abc"),
                "{\"prompt\":\"hi\"}".getBytes(StandardCharsets.UTF_8));

        assertEquals(200, invocation.status());
        assertEquals("application/json", invocation.headers().get("Content-Type"));
        String body = read(invocation.body());
        assertEquals("POST /invocations session=session-a type=application/json trace=Root=1-abc "
                + "body={\"prompt\":\"hi\"}", body);
        assertEquals(8080, launcher.launches.getFirst().containerPort());
        assertEquals("DEFAULT", launcher.launches.getFirst().endpointName());
    }

    @Test
    void sessionReusesItsContainerAndOtherSessionsGetTheirOwn() throws Exception {
        sessions = new AgentRuntimeSessions(launcher, Duration.ofMinutes(5), Duration.ofSeconds(10));

        read(sessions.invoke(target("HTTP"), "session-a", Map.of(), new byte[0]).body());
        read(sessions.invoke(target("HTTP"), "session-a", Map.of(), new byte[0]).body());
        read(sessions.invoke(target("HTTP"), "session-b", Map.of(), new byte[0]).body());

        assertEquals(2, launcher.launches.size());
        assertEquals(2, sessions.sessionCount());
    }

    @Test
    void eventStreamArrivesAsTheRuntimeWritesIt() throws Exception {
        sessions = new AgentRuntimeSessions(launcher, Duration.ofMinutes(5), Duration.ofSeconds(10));

        AgentRuntimeSessions.Invocation invocation = sessions.invoke(target("HTTP"), "session-s",
                Map.of(), "stream".getBytes(StandardCharsets.UTF_8));

        assertEquals("text/event-stream", invocation.headers().get("Content-Type"));
        assertEquals("data: one\n\ndata: two\n\n", read(invocation.body()));
    }

    @Test
    void mcpRuntimesListenOnTheMcpPortAndPath() throws Exception {
        sessions = new AgentRuntimeSessions(launcher, Duration.ofMinutes(5), Duration.ofSeconds(10));

        String body = read(sessions.invoke(target("MCP"), "session-m", Map.of(), new byte[0]).body());

        assertTrue(body.startsWith("POST /mcp "), body);
        assertEquals(8000, launcher.launches.getFirst().containerPort());
    }

    @Test
    void runtimeErrorStatusIsRuntimeClientError() {
        sessions = new AgentRuntimeSessions(launcher, Duration.ofMinutes(5), Duration.ofSeconds(10));

        AwsException error = assertThrows(AwsException.class, () -> sessions.invoke(target("HTTP"), "session-e",
                Map.of(), "fail".getBytes(StandardCharsets.UTF_8)));

        assertEquals("RuntimeClientError", error.getErrorCode());
        assertEquals(424, error.getHttpStatus());
        assertTrue(error.getMessage().contains("(500)"), error.getMessage());
    }

    @Test
    void runtimeThatNeverAnswersItsPingFailsToStart() {
        launcher.pingStatus = 503;
        sessions = new AgentRuntimeSessions(launcher, Duration.ofMinutes(5), Duration.ofSeconds(1));

        AwsException error = assertThrows(AwsException.class,
                () -> sessions.invoke(target("HTTP"), "session-p", Map.of(), new byte[0]));

        assertEquals("RuntimeClientError", error.getErrorCode());
        assertEquals(1, launcher.stopped.size());
        assertEquals(0, sessions.sessionCount());
    }

    @Test
    void idleSessionsAreStopped() throws Exception {
        sessions = new AgentRuntimeSessions(launcher, Duration.ofSeconds(1), Duration.ofSeconds(10));

        read(sessions.invoke(target("HTTP"), "session-i", Map.of(), new byte[0]).body());

        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (launcher.stopped.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertEquals(1, launcher.stopped.size());
        assertEquals(0, sessions.sessionCount());
    }

    @Test
    void stoppingARuntimeStopsEachOfItsSessions() throws Exception {
        sessions = new AgentRuntimeSessions(launcher, Duration.ofMinutes(5), Duration.ofSeconds(10));
        read(sessions.invoke(target("HTTP"), "session-a", Map.of(), new byte[0]).body());
        read(sessions.invoke(target("HTTP"), "session-b", Map.of(), new byte[0]).body());

        sessions.stopRuntime("000000000000", "us-east-1", "agent_rt-abcdefghij");

        assertEquals(2, launcher.stopped.size());
        assertEquals(0, sessions.sessionCount());
    }

    @Test
    void codeEntryPointRunsUnderPython() throws Exception {
        assertEquals(List.of("python", "main.py"),
                AgentRuntimeDockerLauncher.codeCommand(mapper.readTree("[\"main.py\"]")));
        assertEquals(List.of("opentelemetry-instrument", "python", "main.py"),
                AgentRuntimeDockerLauncher.codeCommand(
                        mapper.readTree("[\"opentelemetry-instrument\", \"python\", \"main.py\"]")));
        assertEquals("/aws/bedrock-agentcore/runtimes/agent_rt-abcdefghij-DEFAULT",
                AgentRuntimeDockerLauncher.logGroup("agent_rt-abcdefghij", "DEFAULT"));
    }

    private AgentRuntimeSessions.Target target(String protocol) throws IOException {
        AgentRuntime runtime = new AgentRuntime();
        runtime.setAgentRuntimeId("agent_rt-abcdefghij");
        runtime.setAgentRuntimeName("agent_rt");
        AgentRuntimeVersion version = new AgentRuntimeVersion();
        version.setVersion("1");
        version.setProtocolConfiguration(mapper.readTree("{\"serverProtocol\":\"" + protocol + "\"}"));
        return new AgentRuntimeSessions.Target("000000000000", "us-east-1", runtime, version, "DEFAULT");
    }

    private static String read(InputStream body) throws IOException {
        try (InputStream in = body) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Starts a JDK HTTP server per launch that answers like an agent following the runtime contract. */
    private static final class FakeLauncher implements AgentRuntimeLauncher {
        private final List<Launch> launches = new CopyOnWriteArrayList<>();
        private final List<RunningRuntime> stopped = new CopyOnWriteArrayList<>();
        private final List<HttpServer> servers = new CopyOnWriteArrayList<>();
        private volatile int pingStatus = 200;

        @Override
        public RunningRuntime start(Launch launch) {
            launches.add(launch);
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
                server.createContext("/", this::handle);
                server.start();
                servers.add(server);
                return new RunningRuntime("container-" + launches.size(), "127.0.0.1",
                        server.getAddress().getPort(), () -> server.stop(0));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void stop(RunningRuntime runtime) {
            stopped.add(runtime);
            try {
                runtime.cleanup().close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders("/ping".equals(path) ? pingStatus : 405, -1);
                exchange.close();
                return;
            }
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("fail".equals(payload)) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            if ("stream".equals(payload)) {
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write("data: one\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    out.write("data: two\n\n".getBytes(StandardCharsets.UTF_8));
                }
                return;
            }
            String reply = "POST " + path
                    + " session=" + exchange.getRequestHeaders().getFirst(AgentRuntimeSessions.SESSION_HEADER)
                    + " type=" + exchange.getRequestHeaders().getFirst("Content-Type")
                    + " trace=" + exchange.getRequestHeaders().getFirst("X-Amzn-Trace-Id")
                    + " body=" + payload;
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
