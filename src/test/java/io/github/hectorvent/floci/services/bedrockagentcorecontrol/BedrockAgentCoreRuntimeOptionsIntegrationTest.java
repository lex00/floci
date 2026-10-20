package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** VPC mode, inbound JWT authorization, request header allowlists and session lifecycle on runtimes. */
@QuarkusTest
class BedrockAgentCoreRuntimeOptionsIntegrationTest {

    private static final String ARTIFACT = "\"agentRuntimeArtifact\":{\"containerConfiguration\":{\"containerUri\":\"x:latest\"}},"
            + "\"roleArn\":\"arn:aws:iam::000000000000:role/agent\"";

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private ValidatableResponse create(String name, String extra) {
        return given().contentType("application/json")
                .body("{\"agentRuntimeName\":\"" + name + "\"," + ARTIFACT + "," + extra + "}")
                .when().put("/runtimes/").then();
    }

    private JsonPath created(String name, String extra) {
        JsonPath runtime = create(name, extra).statusCode(202).extract().jsonPath();
        String id = runtime.getString("agentRuntimeId");
        cleanup.register(() -> given().when().delete("/runtimes/" + id + "/").then().statusCode(202));
        return runtime;
    }

    @Test
    void vpcModeNeedsSubnetsAndSecurityGroups() {
        create("vpcMissing", "\"networkConfiguration\":{\"networkMode\":\"VPC\"}")
                .statusCode(400).body(containsString("subnets"));
        create("publicWithConfig", "\"networkConfiguration\":{\"networkMode\":\"PUBLIC\","
                + "\"networkModeConfig\":{\"subnets\":[\"subnet-0123456789abcdef0\"],\"securityGroups\":[\"sg-0123456789abcdef0\"]}}")
                .statusCode(400);

        String id = created("vpcAgent", "\"networkConfiguration\":{\"networkMode\":\"VPC\","
                + "\"networkModeConfig\":{\"subnets\":[\"subnet-0123456789abcdef0\"],\"securityGroups\":[\"sg-0123456789abcdef0\"]}}")
                .getString("agentRuntimeId");
        given().when().get("/runtimes/" + id + "/").then().statusCode(200)
                .body("networkConfiguration.networkModeConfig.subnets", contains("subnet-0123456789abcdef0"))
                .body("lifecycleConfiguration.idleRuntimeSessionTimeout", equalTo(900))
                .body("lifecycleConfiguration.maxLifetime", equalTo(28800));
    }

    @Test
    void lifecycleAndHeaderAllowlistAreValidatedAndReturned() {
        String network = "\"networkConfiguration\":{\"networkMode\":\"PUBLIC\"}";
        create("idleTooLong", network + ",\"lifecycleConfiguration\":{\"idleRuntimeSessionTimeout\":3600,\"maxLifetime\":600}")
                .statusCode(400);
        create("idleTooShort", network + ",\"lifecycleConfiguration\":{\"idleRuntimeSessionTimeout\":10}")
                .statusCode(400);
        create("badHeader", network + ",\"requestHeaderConfiguration\":{\"requestHeaderAllowlist\":[\"X-Other\"]}")
                .statusCode(400);

        String id = created("optionsAgent", network
                + ",\"lifecycleConfiguration\":{\"idleRuntimeSessionTimeout\":120,\"maxLifetime\":3600}"
                + ",\"requestHeaderConfiguration\":{\"requestHeaderAllowlist\":[\"X-Amzn-Bedrock-AgentCore-Runtime-Custom-Tenant\"]}")
                .getString("agentRuntimeId");
        given().when().get("/runtimes/" + id + "/").then().statusCode(200)
                .body("lifecycleConfiguration.idleRuntimeSessionTimeout", equalTo(120))
                .body("lifecycleConfiguration.maxLifetime", equalTo(3600))
                .body("requestHeaderConfiguration.requestHeaderAllowlist",
                        contains("X-Amzn-Bedrock-AgentCore-Runtime-Custom-Tenant"));
    }

    @Test
    void jwtRuntimeRefusesSigV4Invocations() {
        String network = "\"networkConfiguration\":{\"networkMode\":\"PUBLIC\"}";
        create("badDiscovery", network + ",\"authorizerConfiguration\":{\"customJWTAuthorizer\":"
                + "{\"discoveryUrl\":\"https://issuer.example\"}}").statusCode(400);

        String arn = created("jwtAgent", network + ",\"authorizerConfiguration\":{\"customJWTAuthorizer\":"
                + "{\"discoveryUrl\":\"https://issuer.example/.well-known/openid-configuration\"}}")
                .getString("agentRuntimeArn");
        given().contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/bedrock-agentcore/aws4_request")
                .body("{}")
                .when().post("/runtimes/" + arn + "/invocations")
                .then().statusCode(403).body(containsString("Authorization method mismatch"));
    }
}
