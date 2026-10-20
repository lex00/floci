package io.github.hectorvent.floci.services.arcregionswitch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORParser;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ArcRegionSwitchIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "ArcRegionSwitch.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final CBORFactory CBOR_FACTORY = new CBORFactory();

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", TARGET + action).body(body)
                .when().post("/")
                .then();
    }

    @Test
    void planLifecycleOverJson() {
        String arn = call("CreatePlan", """
                {"name":"json-plan","regions":["us-east-1","us-west-2"],"recoveryApproach":"activePassive",
                 "primaryRegion":"us-east-1","executionRole":"arn:aws:iam::000000000000:role/switch",
                 "workflows":[{"workflowTargetAction":"activate","workflowTargetRegion":"us-west-2","steps":[]}],
                 "tags":{"team":"sre"}}
                """)
                .statusCode(200)
                .body("plan.arn", startsWith("arn:aws:arc-region-switch::000000000000:plan/json-plan:"))
                .body("plan.owner", equalTo("000000000000"))
                .body("plan.version", equalTo("1"))
                .body("plan.updatedAt", instanceOf(Number.class))
                .body("plan.workflows[0].workflowTargetRegion", equalTo("us-west-2"))
                .extract().path("plan.arn");

        call("ListPlans", "{}").statusCode(200).body("plans.arn", hasItem(arn));
        call("ListTagsForResource", "{\"arn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("resourceTags.team", equalTo("sre"));

        call("UpdatePlan", """
                {"arn":"%s","description":"updated","executionRole":"arn:aws:iam::000000000000:role/switch",
                 "workflows":[{"workflowTargetAction":"deactivate"}]}
                """.formatted(arn))
                .statusCode(200)
                .body("plan.description", equalTo("updated"))
                .body("plan.version", equalTo("2"));

        call("DeletePlan", "{\"arn\":\"" + arn + "\"}").statusCode(200);
        call("GetPlan", "{\"arn\":\"" + arn + "\"}")
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createAndGetOverRpcV2CborTagsUpdatedAtAsATimestamp() throws Exception {
        ObjectNode create = (ObjectNode) JSON.readTree("""
                {"name":"cbor-plan","regions":["us-east-1","us-west-2"],"recoveryApproach":"activeActive",
                 "executionRole":"arn:aws:iam::000000000000:role/switch","workflows":[]}
                """);
        byte[] created = rpcV2("CreatePlan", create);
        String arn = new ObjectMapper(CBOR_FACTORY).readTree(created).path("plan").path("arn").asText();
        assertTrue(arn.contains(":plan/cbor-plan:"), arn);

        ObjectNode get = JSON.createObjectNode().put("arn", arn);
        byte[] read = rpcV2("GetPlan", get);
        assertEquals(1, updatedAtTag(read));

        rpcV2("DeletePlan", get);
    }

    @Test
    void executionWaitsForManualApproval() {
        String arn = call("CreatePlan", """
                {"name":"exec-plan","regions":["us-east-1","us-west-2"],"recoveryApproach":"activePassive",
                 "primaryRegion":"us-east-1","executionRole":"arn:aws:iam::000000000000:role/switch",
                 "workflows":[{"workflowTargetAction":"activate","workflowTargetRegion":"us-west-2","steps":[
                   {"name":"approve","executionBlockType":"ManualApproval",
                    "executionBlockConfiguration":{"executionApprovalConfig":{"approvalRole":"arn:aws:iam::000000000000:role/a"}}},
                   {"name":"dns","executionBlockType":"Route53HealthCheck",
                    "executionBlockConfiguration":{"route53HealthCheckConfig":{"hostedZoneId":"Z123","recordName":"app.example.com",
                     "crossAccountRole":"arn:aws:iam::000000000000:role/a"}}}]}]}
                """)
                .statusCode(200).extract().path("plan.arn");

        String executionId = call("StartPlanExecution",
                "{\"planArn\":\"" + arn + "\",\"targetRegion\":\"us-west-2\",\"action\":\"activate\"}")
                .statusCode(200)
                .body("activateRegion", equalTo("us-west-2"))
                .body("deactivateRegion", equalTo("us-east-1"))
                .extract().path("executionId");
        String execution = "{\"planArn\":\"" + arn + "\",\"executionId\":\"" + executionId + "\"}";
        call("GetPlanExecution", execution)
                .statusCode(200)
                .body("executionState", equalTo("pendingManualApproval"))
                .body("stepStates[0].status", equalTo("pendingApproval"))
                .body("plan.arn", equalTo(arn));
        call("StartPlanExecution",
                "{\"planArn\":\"" + arn + "\",\"targetRegion\":\"us-west-2\",\"action\":\"activate\"}")
                .statusCode(400)
                .body("__type", equalTo("IllegalStateException"));

        call("ApprovePlanExecutionStep", "{\"planArn\":\"" + arn + "\",\"executionId\":\"" + executionId
                + "\",\"stepName\":\"approve\",\"approval\":\"approve\"}").statusCode(200);
        call("GetPlanExecution", execution)
                .statusCode(200)
                .body("executionState", equalTo("completed"))
                .body("stepStates[1].status", equalTo("completed"));
        call("ListPlanExecutions", "{\"planArn\":\"" + arn + "\"}")
                .statusCode(200).body("items.executionId", hasItem(executionId));
        call("ListPlanExecutionEvents", execution)
                .statusCode(200).body("items.type", hasItem("executionSucceeded"));
        call("ListRoute53HealthChecks", "{\"arn\":\"" + arn + "\"}")
                .statusCode(200).body("healthChecks.region", hasItem("us-west-2"));
        call("GetPlanEvaluationStatus", "{\"planArn\":\"" + arn + "\"}")
                .statusCode(200).body("evaluationState", equalTo("passed"));

        call("DeletePlan", "{\"arn\":\"" + arn + "\"}").statusCode(200);
    }

    private static byte[] rpcV2(String operation, ObjectNode body) throws Exception {
        return given()
                .contentType("application/cbor")
                .accept("application/cbor")
                .header("smithy-protocol", "rpc-v2-cbor")
                .body(new ObjectMapper(CBOR_FACTORY).writeValueAsBytes(body))
                .when().post("/service/ArcRegionSwitch/operation/" + operation)
                .then().statusCode(200).header("smithy-protocol", "rpc-v2-cbor")
                .extract().asByteArray();
    }

    private static int updatedAtTag(byte[] cbor) throws Exception {
        try (CBORParser parser = CBOR_FACTORY.createParser(cbor)) {
            while (parser.nextToken() != null) {
                if ("updatedAt".equals(parser.currentName()) && parser.currentToken().isNumeric()) {
                    return parser.getCurrentTag();
                }
            }
        }
        return -1;
    }
}
