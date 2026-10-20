package io.github.hectorvent.floci.services.appstream;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class AppStreamIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/appstream/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "PhotonAdminProxyService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    @Test
    void stackLifecycleWithTags() {
        call("CreateStack", "{\"Name\":\"it-stack\",\"DisplayName\":\"Stack\",\"Tags\":{\"team\":\"euc\"}}")
                .statusCode(200)
                .body("Stack.Arn", equalTo("arn:aws:appstream:us-east-1:000000000000:stack/it-stack"))
                .body("Stack.UserSettings.Action", hasItem("FILE_UPLOAD"));

        call("DescribeStacks", "{\"Names\":[\"it-stack\"]}")
                .statusCode(200)
                .body("Stacks", hasSize(1))
                .body("Stacks[0].DisplayName", equalTo("Stack"));
        call("ListTagsForResource", "{\"ResourceArn\":\"arn:aws:appstream:us-east-1:000000000000:stack/it-stack\"}")
                .statusCode(200)
                .body("Tags.team", equalTo("euc"));
        call("UpdateStack", "{\"Name\":\"it-stack\",\"Description\":\"updated\"}")
                .statusCode(200)
                .body("Stack.Description", equalTo("updated"));

        call("DeleteStack", "{\"Name\":\"it-stack\"}").statusCode(200);
        call("DescribeStacks", "{\"Names\":[\"it-stack\"]}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void fleetStartStopAndAssociate() {
        call("CreateFleet", """
                {"Name":"it-fleet","InstanceType":"stream.standard.small","ImageName":"AppStream-WinServer2019",
                 "ComputeCapacity":{"DesiredInstances":1}}""")
                .statusCode(200)
                .body("Fleet.State", equalTo("STOPPED"));
        call("StartFleet", "{\"Name\":\"it-fleet\"}").statusCode(200);
        call("DescribeFleets", "{\"Names\":[\"it-fleet\"]}")
                .statusCode(200)
                .body("Fleets[0].State", equalTo("RUNNING"))
                .body("Fleets[0].ComputeCapacityStatus.Running", equalTo(1));

        call("CreateStack", "{\"Name\":\"it-fleet-stack\"}").statusCode(200);
        call("AssociateFleet", "{\"FleetName\":\"it-fleet\",\"StackName\":\"it-fleet-stack\"}").statusCode(200);
        call("ListAssociatedStacks", "{\"FleetName\":\"it-fleet\"}")
                .statusCode(200)
                .body("Names", hasItem("it-fleet-stack"));
        call("DisassociateFleet", "{\"FleetName\":\"it-fleet\",\"StackName\":\"it-fleet-stack\"}").statusCode(200);

        call("DeleteFleet", "{\"Name\":\"it-fleet\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceInUseException"));
        call("StopFleet", "{\"Name\":\"it-fleet\"}").statusCode(200);
        call("DeleteFleet", "{\"Name\":\"it-fleet\"}").statusCode(200);
        call("DeleteStack", "{\"Name\":\"it-fleet-stack\"}").statusCode(200);
    }

    @Test
    void userLifecycle() {
        String user = "{\"UserName\":\"it@example.com\",\"AuthenticationType\":\"USERPOOL\"}";
        call("CreateUser", "{\"UserName\":\"it@example.com\",\"AuthenticationType\":\"USERPOOL\","
                + "\"FirstName\":\"Ada\",\"MessageAction\":\"SUPPRESS\"}").statusCode(200);
        call("DisableUser", user).statusCode(200);
        call("DescribeUsers", "{\"AuthenticationType\":\"USERPOOL\"}")
                .statusCode(200)
                .body("Users.find { it.UserName == 'it@example.com' }.Enabled", equalTo(false))
                .body("Users.find { it.UserName == 'it@example.com' }.FirstName", equalTo("Ada"));
        call("DeleteUser", user).statusCode(200);
        call("DeleteUser", user)
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
