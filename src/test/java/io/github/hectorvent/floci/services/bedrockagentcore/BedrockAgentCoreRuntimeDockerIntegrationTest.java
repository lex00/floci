package io.github.hectorvent.floci.services.bedrockagentcore;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code InvokeAgentRuntime} in the {@code docker} runtime mode, with the container replaced by a
 * JDK HTTP server: the call reaches the runtime's {@code /invocations} with its session id, the
 * answer and its content type come back, and deleting the runtime stops its container.
 */
@QuarkusTest
@TestProfile(BedrockAgentCoreRuntimeDockerIntegrationTest.DockerModeProfile.class)
class BedrockAgentCoreRuntimeDockerIntegrationTest {

    private static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";
    private static final String SESSION_ID = "session-0000000000000000000000000000dkr";
    private static final String CREATE = """
            {
              "agentRuntimeName": "dockerAgent",
              "agentRuntimeArtifact": {"containerConfiguration": {"containerUri": "agent:latest"}},
              "networkConfiguration": {"networkMode": "PUBLIC"},
              "environmentVariables": {"MODEL": "local"},
              "roleArn": "arn:aws:iam::000000000000:role/agent"
            }""";

    public static final class DockerModeProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.bedrock-agent-core.runtime-mode", "docker");
        }
    }

    @InjectMock
    AgentRuntimeDockerLauncher launcher;

    private HttpServer agent;

    @BeforeEach
    void startAgent() throws IOException {
        agent = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        agent.createContext("/ping", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        agent.createContext("/invocations", exchange -> {
            String payload = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] reply = ("{\"echo\":" + payload + ",\"session\":\""
                    + exchange.getRequestHeaders().getFirst(SESSION_HEADER) + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        agent.start();
        when(launcher.start(any())).thenReturn(new AgentRuntimeLauncher.RunningRuntime(
                "container-1", "127.0.0.1", agent.getAddress().getPort(), () -> { }));
    }

    @AfterEach
    void stopAgent() {
        agent.stop(0);
    }

    @Test
    void invokeRunsTheRuntimeAndDeleteStopsIt() {
        JsonPath runtime = given().contentType("application/json").body(CREATE)
                .when().put("/runtimes/")
                .then().statusCode(202)
                .extract().jsonPath();
        String runtimeId = runtime.getString("agentRuntimeId");

        given().header(SESSION_HEADER, SESSION_ID)
                .contentType("application/json")
                .body("{\"prompt\":\"hi\"}")
                .when().post("/runtimes/" + runtime.getString("agentRuntimeArn") + "/invocations")
                .then().statusCode(200)
                .header("Content-Type", startsWith("application/json"))
                .header(SESSION_HEADER, SESSION_ID)
                .body("echo.prompt", equalTo("hi"))
                .body("session", equalTo(SESSION_ID));

        ArgumentCaptor<AgentRuntimeLauncher.Launch> launch = ArgumentCaptor.forClass(AgentRuntimeLauncher.Launch.class);
        verify(launcher).start(launch.capture());
        assertEquals(runtimeId, launch.getValue().runtimeId());
        assertEquals("local", launch.getValue().version().getEnvironmentVariables().get("MODEL"));
        assertEquals(8080, launch.getValue().containerPort());

        given().when().delete("/runtimes/" + runtimeId + "/").then().statusCode(202);
        verify(launcher, timeout(5000)).stop(any());
    }

    @Test
    void invokeUnknownRuntimeIsNotFound() {
        String unknown =
                "arn:aws:bedrock-agentcore:us-east-1:000000000000:agent/00000000-0000-0000-0000-000000000000:1";
        given().contentType("application/json").body("{}")
                .when().post("/runtimes/" + unknown + "/invocations")
                .then().statusCode(404);
    }
}
