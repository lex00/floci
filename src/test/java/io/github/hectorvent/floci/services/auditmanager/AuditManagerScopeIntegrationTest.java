package io.github.hectorvent.floci.services.auditmanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Floci serves Audit Manager frameworks, controls, assessments and their evidence. A request
 * signed for the {@code auditmanager} credential scope on any other path has to come back as the clean
 * {@code UnknownOperationException} a rest-json SDK can deserialize, never as an S3 XML error
 * from the path-style wildcard routes those paths would otherwise match.
 */
@QuarkusTest
class AuditManagerScopeIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String authorization() {
        return "AWS4-HMAC-SHA256 Credential=test/20260918/us-east-1/auditmanager"
                + "/aws4_request, SignedHeaders=host;x-amz-date, Signature=deadbeef";
    }

    @Test
    void startFrameworkShareIsRejectedAsUnknownOperation() {
        given()
            .header("Authorization", authorization())
            .contentType("application/json")
            .body("{\"destinationAccount\":\"111111111111\",\"destinationRegion\":\"us-east-1\"}")
        .when()
            .post("/assessmentFrameworks/11111111-1111-1111-1111-111111111111/shareRequests")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void getInsightsIsRejectedAsUnknownOperation() {
        given()
            .header("Authorization", authorization())
        .when()
            .get("/insights")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void getSettingsIsRejectedAsUnknownOperation() {
        given()
            .header("Authorization", authorization())
        .when()
            .get("/settings/ALL")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"));
    }
}
