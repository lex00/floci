package io.github.hectorvent.floci.services.route53;

import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsService;
import io.github.hectorvent.floci.services.cloudwatch.metrics.model.Dimension;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.route53.Route53Client;
import software.amazon.awssdk.services.route53.model.GetHealthCheckStatusResponse;
import software.amazon.awssdk.services.route53.model.HealthCheckConfig;
import software.amazon.awssdk.services.route53.model.HealthCheckObservation;
import software.amazon.awssdk.services.route53.model.HealthCheckRegion;
import software.amazon.awssdk.services.route53.model.HealthCheckType;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
@TestProfile(Route53HealthCheckProbingIntegrationTest.ProbingProfile.class)
class Route53HealthCheckProbingIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Inject
    CloudWatchMetricsService metrics;

    @Test
    void httpHealthCheckFailsOnceItsEndpointStops() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        int port = server.getAddress().getPort();
        // Routed by Host, like floci-az's ingress: any other Host gets a 421.
        server.createContext("/health", exchange -> {
            boolean routed = ("app.example.invalid:" + port).equals(exchange.getRequestHeaders().getFirst("Host"));
            exchange.sendResponseHeaders(routed ? 200 : 421, -1);
            exchange.close();
        });

        try (Route53Client r53 = client()) {
            String id = r53.createHealthCheck(r -> r.callerReference(UUID.randomUUID().toString())
                    .healthCheckConfig(HealthCheckConfig.builder().type(HealthCheckType.HTTP)
                            .ipAddress("127.0.0.1").port(port).resourcePath("/health")
                            .fullyQualifiedDomainName("app.example.invalid")
                            .requestInterval(10).failureThreshold(1)
                            .regions(HealthCheckRegion.US_EAST_1, HealthCheckRegion.EU_WEST_1,
                                    HealthCheckRegion.AP_SOUTHEAST_1)
                            .build()))
                    .healthCheck().id();

            List<HealthCheckObservation> up = awaitStatus(r53, id, "Success: HTTP Status Code 200, OK");
            assertEquals(3, up.size());
            assertEquals(List.of("us-east-1", "eu-west-1", "ap-southeast-1"),
                    up.stream().map(HealthCheckObservation::regionAsString).toList());
            awaitHealthMetric(id, 1.0);

            server.stop(0);
            List<HealthCheckObservation> down = awaitStatus(r53, id, "Failure: Connection refused.");
            assertTrue(down.getFirst().statusReport().checkedTime().isAfter(up.getFirst().statusReport().checkedTime()));
            awaitHealthMetric(id, 0.0);

            r53.updateHealthCheck(r -> r.healthCheckId(id).inverted(true));
            assertEquals(HealthCheckType.HTTP, r53.getHealthCheck(r -> r.healthCheckId(id)).healthCheck()
                    .healthCheckConfig().type());
            awaitHealthMetric(id, 1.0);

            r53.deleteHealthCheck(r -> r.healthCheckId(id));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void calculatedHealthCheckKeepsTheFixedObservation() {
        try (Route53Client r53 = client()) {
            String id = r53.createHealthCheck(r -> r.callerReference(UUID.randomUUID().toString())
                    .healthCheckConfig(HealthCheckConfig.builder().type(HealthCheckType.CALCULATED)
                            .healthThreshold(0).build()))
                    .healthCheck().id();
            GetHealthCheckStatusResponse status = r53.getHealthCheckStatus(r -> r.healthCheckId(id));
            assertEquals("Success: HTTP Status Code 200, OK",
                    status.healthCheckObservations().getFirst().statusReport().status());
            r53.deleteHealthCheck(r -> r.healthCheckId(id));
        }
    }

    private static List<HealthCheckObservation> awaitStatus(Route53Client r53, String id, String prefix) {
        return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                .until(() -> r53.getHealthCheckStatus(r -> r.healthCheckId(id)).healthCheckObservations(),
                        observations -> !observations.isEmpty()
                                && observations.stream().allMatch(o -> o.statusReport().status().startsWith(prefix)));
    }

    private void awaitHealthMetric(String id, double expected) {
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            var points = metrics.getMetricStatistics("AWS/Route53", "HealthCheckStatus",
                    List.of(new Dimension("HealthCheckId", id)), Instant.now().minusSeconds(180),
                    Instant.now().plusSeconds(60), 60, List.of("Maximum"), null, "us-east-1");
            assertFalse(points.isEmpty());
            assertEquals(expected, points.getLast().maximum());
        });
    }

    private Route53Client client() {
        return Route53Client.builder().endpointOverride(endpoint).region(Region.AWS_GLOBAL)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(Apache5HttpClient.builder()).build();
    }

    public static final class ProbingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.route53.mock", "false",
                    "floci.services.route53.health-check-probe-interval-ms", "100");
        }
    }
}
