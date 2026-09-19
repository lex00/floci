package io.github.hectorvent.floci.services.auditmanager;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * Floci implements no Audit Manager operation yet, so every request signed for the
 * {@code auditmanager} credential scope has to come back as the clean
 * {@code UnknownOperationException} a rest-json SDK can deserialize, never as an S3 XML
 * error from the path-style wildcard routes those paths would otherwise match.
 *
 * <p>{@code AwsProtocolClaimFilter} gives that for free by rejecting any REST request whose
 * SigV4 scope names a service absent from {@code ResolvedServiceCatalog}. Registering an
 * operationless Audit Manager controller in the catalog would take these paths off that
 * pre-matching guard and leave them to the narrower post-matching
 * {@code AwsRestRouteScopeFilter}, which only covers paths S3's wildcards actually claim.
 * This test pins the behaviour such a registration would cost.
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
    void createAssessmentIsRejectedAsUnknownOperation() {
        given()
            .header("Authorization", authorization())
            .contentType("application/json")
            .body("{\"name\":\"probe\"}")
        .when()
            .post("/assessments")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void getAssessmentIsRejectedAsUnknownOperation() {
        given()
            .header("Authorization", authorization())
        .when()
            .get("/assessments/11111111-1111-1111-1111-111111111111")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"));
    }

    @Test
    void listControlsIsRejectedAsUnknownOperation() {
        given()
            .header("Authorization", authorization())
        .when()
            .get("/controls?controlType=Standard")
        .then()
            .statusCode(404)
            .contentType(containsString("application/json"))
            .header("X-Amzn-Errortype", "UnknownOperationException")
            .body("__type", equalTo("UnknownOperationException"));
    }
}
