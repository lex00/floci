package io.github.hectorvent.floci.services.qbusiness;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class QBusinessIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/qbusiness/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void applicationLifecycleWithTags() {
        String arn = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"displayName": "helpdesk", "identityType": "AWS_IAM_IDC",
                 "description": "Help desk assistant", "tags": [{"key": "team", "value": "it"}]}
                """)
        .when()
            .post("/applications")
        .then()
            .statusCode(200)
            .extract().path("applicationArn");
        String id = arn.substring(arn.lastIndexOf('/') + 1);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/applications/" + id)
        .then()
            .statusCode(200)
            .body("displayName", equalTo("helpdesk"))
            .body("status", equalTo("ACTIVE"))
            .body("applicationArn", equalTo("arn:aws:qbusiness:us-east-1:000000000000:application/" + id));

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"description\": \"Updated\"}")
        .when()
            .put("/applications/" + id)
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("applications.applicationId", hasItem(id));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/v1/tags/" + arn)
        .then()
            .statusCode(200)
            .body("tags[0].key", equalTo("team"))
            .body("tags[0].value", equalTo("it"));

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/applications/" + id)
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/applications/" + id)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("applications.applicationId", not(hasItem(id)));
    }

    @Test
    void appConfigApplicationsStayOnTheirOwnRoute() {
        given()
            .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/appconfig/aws4_request")
        .when()
            .get("/applications")
        .then()
            .statusCode(200)
            .body("Items", notNullValue());
    }
}
