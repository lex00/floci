package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.iot.model.IotTopicRuleDestination;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IotBillingGroupServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private HttpServer server;
    private final CompletableFuture<JsonNode> received = new CompletableFuture<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/confirm", exchange -> {
            received.complete(mapper.readTree(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private IotBillingGroupService service(boolean autoConfirm) {
        return new IotBillingGroupService(new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver("us-east-1", "000000000000"), autoConfirm, "http://floci:4566", mapper,
                HttpClient.newHttpClient());
    }

    private JsonNode httpDestination() {
        return mapper.createObjectNode().set("destinationConfiguration", mapper.createObjectNode()
                .set("httpUrlConfiguration", mapper.createObjectNode().put("confirmationUrl",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/confirm")));
    }

    @Test
    void confirmationMessageReachesUrlAndItsTokenEnablesTheDestination() throws Exception {
        IotBillingGroupService service = service(false);
        IotTopicRuleDestination created = service.createTopicRuleDestination(httpDestination(), "us-east-1");

        JsonNode message = received.get(10, TimeUnit.SECONDS);
        assertEquals("DestinationConfirmation", message.path("messageType").asText());
        assertEquals(created.getArn(), message.path("arn").asText());
        String token = message.path("confirmationToken").asText();
        assertEquals("http://floci:4566/confirmdestination/" + token, message.path("enableUrl").asText());
        assertEquals("IN_PROGRESS", service.getTopicRuleDestination(created.getArn(), "us-east-1").getStatus());

        service.confirmTopicRuleDestination(token);
        assertEquals("ENABLED", service.getTopicRuleDestination(created.getArn(), "us-east-1").getStatus());
        assertThrows(AwsException.class, () -> service.confirmTopicRuleDestination(token));
    }

    @Test
    void autoConfirmEnablesWithoutPostingTheConfirmation() throws Exception {
        IotBillingGroupService service = service(true);
        IotTopicRuleDestination created = service.createTopicRuleDestination(httpDestination(), "us-east-1");

        assertEquals("IN_PROGRESS", created.getStatus());
        assertEquals("ENABLED", service.getTopicRuleDestination(created.getArn(), "us-east-1").getStatus());
        Thread.sleep(200);
        assertEquals(false, received.isDone());
    }
}
