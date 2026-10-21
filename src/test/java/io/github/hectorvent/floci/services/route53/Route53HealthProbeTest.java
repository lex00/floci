package io.github.hectorvent.floci.services.route53;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Route53HealthProbeTest {

    private final Route53HealthProbe probe = new Route53HealthProbe();
    private final AtomicReference<String> hostHeader = new AtomicReference<>();
    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            hostHeader.set(exchange.getRequestHeaders().getFirst("Host"));
            respond(exchange, 200, "service is UP and ready", false);
        });
        server.createContext("/chunked", exchange -> respond(exchange, 200, "chunked body says UP", true));
        server.createContext("/down", exchange -> respond(exchange, 503, "nope", false));
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
            respond(exchange, 301, "", false);
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void httpSuccessReportsStatusCodeAndReason() {
        Route53HealthProbe.Result result = probe.probe("hc-1", http("HTTP", "/ok"));
        assertTrue(result.success());
        assertEquals("Success: HTTP Status Code 200, OK", result.status());
        assertEquals("127.0.0.1:" + port, hostHeader.get());
    }

    @Test
    void fullyQualifiedDomainNameIsTheHostHeaderWhenAnIpIsGiven() {
        HealthCheckConfig cfg = http("HTTP", "/ok");
        cfg.setFullyQualifiedDomainName("app.example.invalid");
        assertTrue(probe.probe("hc-1", cfg).success());
        assertEquals("app.example.invalid:" + port, hostHeader.get());
    }

    @Test
    void serverErrorFailsAndRedirectSucceedsWithoutBeingFollowed() {
        Route53HealthProbe.Result down = probe.probe("hc-1", http("HTTP", "/down"));
        assertFalse(down.success());
        assertEquals("Failure: HTTP Status Code 503, Service Unavailable", down.status());
        assertTrue(probe.probe("hc-1", http("HTTP", "/moved")).success());
    }

    @Test
    void stringMatchSearchesTheBodyIncludingChunkedBodies() {
        HealthCheckConfig found = http("HTTP_STR_MATCH", "/ok");
        found.setSearchString("UP");
        assertTrue(probe.probe("hc-1", found).success());

        HealthCheckConfig chunked = http("HTTP_STR_MATCH", "/chunked");
        chunked.setSearchString("chunked body says UP");
        assertTrue(probe.probe("hc-1", chunked).success());

        HealthCheckConfig missing = http("HTTP_STR_MATCH", "/ok");
        missing.setSearchString("DOWN");
        Route53HealthProbe.Result result = probe.probe("hc-1", missing);
        assertFalse(result.success());
        assertThat(result.status(), startsWith("Failure: HTTP Status Code 200, OK."));
    }

    @Test
    void tcpSucceedsOnConnectAndRefusedConnectionsFail() throws IOException {
        HealthCheckConfig tcp = http("TCP", null);
        assertEquals("Success: TCP connection established.", probe.probe("hc-1", tcp).status());

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        HealthCheckConfig refused = http("HTTP", "/ok");
        refused.setPort(closedPort);
        Route53HealthProbe.Result result = probe.probe("hc-1", refused);
        assertFalse(result.success());
        assertThat(result.status(), startsWith("Failure: Connection refused."));
    }

    @Test
    void fullyQualifiedDomainNameIsResolvedOnlyWithoutAnIp() {
        HealthCheckConfig cfg = http("HTTP", "/ok");
        cfg.setIpAddress(null);
        cfg.setFullyQualifiedDomainName("app.example.invalid");
        Route53HealthProbe.Result result = probe.probe("hc-1", cfg);
        assertFalse(result.success());
        assertEquals("Failure: DNS resolution failed for app.example.invalid.", result.status());
    }

    @Test
    void tcpWithoutAPortFails() {
        HealthCheckConfig tcp = http("TCP", null);
        tcp.setPort(null);
        assertEquals("Failure: The health check has no Port.", probe.probe("hc-1", tcp).status());
    }

    private HealthCheckConfig http(String type, String path) {
        HealthCheckConfig cfg = new HealthCheckConfig();
        cfg.setType(type);
        cfg.setIpAddress("127.0.0.1");
        cfg.setPort(port);
        cfg.setResourcePath(path);
        return cfg;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int code, String body, boolean chunked)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, chunked ? 0 : (bytes.length == 0 ? -1 : bytes.length));
        try (OutputStream out = exchange.getResponseBody()) {
            if (chunked) {
                out.write(bytes, 0, 6);
                out.flush();
                out.write(bytes, 6, bytes.length - 6);
            } else if (bytes.length > 0) {
                out.write(bytes);
            }
        }
    }
}
