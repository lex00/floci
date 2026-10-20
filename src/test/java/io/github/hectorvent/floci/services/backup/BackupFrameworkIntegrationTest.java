package io.github.hectorvent.floci.services.backup;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class BackupFrameworkIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/backup/aws4_request";

    @Test
    void frameworkLifecycle() {
        String arn = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"FrameworkName":"probe_framework","FrameworkDescription":"probe",
                 "FrameworkControls":[{"ControlName":"BACKUP_RECOVERY_POINT_MINIMUM_RETENTION_CHECK",
                   "ControlInputParameters":[{"ParameterName":"requiredRetentionDays","ParameterValue":"35"}],
                   "ControlScope":{"ComplianceResourceTypes":["EBS"]}}],
                 "IdempotencyToken":"token-1","FrameworkTags":{"env":"test"}}""")
        .when()
            .post("/audit/frameworks")
        .then()
            .statusCode(200)
            .body("FrameworkName", equalTo("probe_framework"))
            .body("FrameworkArn", containsString(":framework:probe_framework-"))
            .extract().path("FrameworkArn");

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/audit/frameworks/probe_framework")
        .then()
            .statusCode(200)
            .body("DeploymentStatus", equalTo("COMPLETED"))
            .body("FrameworkStatus", equalTo("ACTIVE"))
            .body("CreationTime", notNullValue())
            .body("FrameworkControls[0].ControlInputParameters[0].ParameterValue", equalTo("35"));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/tags/" + arn)
        .then()
            .statusCode(200)
            .body("Tags.env", equalTo("test"));

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"FrameworkDescription\":\"changed\",\"FrameworkControls\":"
                    + "[{\"ControlName\":\"BACKUP_PLAN_MIN_FREQUENCY_AND_MIN_RETENTION_CHECK\"}]}")
        .when()
            .put("/audit/frameworks/probe_framework")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/audit/frameworks")
        .then()
            .statusCode(200)
            .body("Frameworks.FrameworkName", hasItem("probe_framework"))
            .body("Frameworks.find { it.FrameworkName == 'probe_framework' }.FrameworkDescription",
                    equalTo("changed"));

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/audit/frameworks/probe_framework")
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/audit/frameworks/probe_framework")
        .then()
            .statusCode(400)
            .body(containsString("ResourceNotFoundException"));
    }

    @Test
    void duplicateFrameworkNameIsRejected() {
        String body = "{\"FrameworkName\":\"dup_framework\",\"FrameworkControls\":[{\"ControlName\":\"X\"}]}";
        given().header("Authorization", AUTH).contentType("application/json").body(body)
            .when().post("/audit/frameworks").then().statusCode(200);
        given().header("Authorization", AUTH).contentType("application/json").body(body)
            .when().post("/audit/frameworks").then().statusCode(400)
            .body(containsString("AlreadyExistsException"));
    }
}
