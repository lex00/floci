package io.github.hectorvent.floci.services.auditmanager;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuditManagerIntegrationTest {

    private static final String UNKNOWN_CONTROL = "11111111-1111-1111-1111-111111111111";

    private static String controlId;
    private static String frameworkId;
    private static String frameworkArn;

    @Test
    @Order(1)
    void createControl() {
        controlId = given()
            .contentType("application/json")
            .body("""
                {"name": "probe-control",
                 "controlMappingSources": [{"sourceName": "manual", "sourceSetUpOption": "Procedural_Controls_Mapping",
                                            "sourceType": "MANUAL"}]}
                """)
        .when()
            .post("/controls")
        .then()
            .statusCode(200)
            .body("control.type", equalTo("Custom"))
            .body("control.controlSources", equalTo("MANUAL"))
            .body("control.controlMappingSources[0].sourceId", notNullValue())
            .extract().path("control.id");
    }

    @Test
    @Order(2)
    void createFrameworkThroughTheDoubleSlashPathTheCliSends() {
        frameworkArn = given()
            .urlEncodingEnabled(false)
            .contentType("application/json")
            .body("""
                {"name": "probe-framework", "description": "probe",
                 "controlSets": [{"name": "cs1", "controls": [{"id": "%s"}, {"id": "%s"}]}],
                 "tags": {"team": "audit"}}
                """.formatted(controlId, UNKNOWN_CONTROL))
        .when()
            .post("//assessmentFrameworks")
        .then()
            .statusCode(200)
            .body("framework.name", equalTo("probe-framework"))
            .body("framework.type", equalTo("Custom"))
            .body("framework.controlSets[0].name", equalTo("cs1"))
            .body("framework.controlSets[0].id", notNullValue())
            .body("framework.controlSets[0].controls[0].name", equalTo("probe-control"))
            .body("framework.controlSets[0].controls[1].id", equalTo(UNKNOWN_CONTROL))
            .body("framework.tags.team", equalTo("audit"))
            .extract().path("framework.arn");
        frameworkId = frameworkArn.substring(frameworkArn.lastIndexOf('/') + 1);
    }

    @Test
    @Order(3)
    void getAndListFramework() {
        given()
        .when()
            .get("/assessmentFrameworks/" + frameworkId)
        .then()
            .statusCode(200)
            .body("framework.id", equalTo(frameworkId))
            .body("framework.createdAt", notNullValue());

        given()
            .urlEncodingEnabled(false)
            .queryParam("frameworkType", "Custom")
        .when()
            .get("//assessmentFrameworks")
        .then()
            .statusCode(200)
            .body("frameworkMetadataList.id", hasItem(frameworkId));

        given()
            .queryParam("frameworkType", "Standard")
        .when()
            .get("/assessmentFrameworks")
        .then()
            .statusCode(200)
            .body("frameworkMetadataList", hasSize(0));
    }

    @Test
    @Order(4)
    void updateFramework() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "probe-framework-2",
                 "controlSets": [{"name": "cs2", "controls": [{"id": "%s"}]}]}
                """.formatted(controlId))
        .when()
            .put("/assessmentFrameworks/" + frameworkId)
        .then()
            .statusCode(200)
            .body("framework.name", equalTo("probe-framework-2"))
            .body("framework.controlSets", hasSize(1))
            .body("framework.controlSets[0].name", equalTo("cs2"));
    }

    @Test
    @Order(5)
    void tagAndUntagFramework() {
        given()
            .contentType("application/json")
            .body("{\"tags\": {\"env\": \"dev\"}}")
        .when()
            .post("/tags/" + frameworkArn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/" + frameworkArn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo("dev"))
            .body("tags.team", equalTo("audit"));

        given()
            .queryParam("tagKeys", "env")
        .when()
            .delete("/tags/" + frameworkArn)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/tags/" + frameworkArn)
        .then()
            .statusCode(200)
            .body("tags.env", equalTo(null));
    }

    @Test
    @Order(6)
    void listFrameworksRequiresFrameworkType() {
        given()
        .when()
            .get("/assessmentFrameworks")
        .then()
            .statusCode(400)
            .body("message", notNullValue());
    }

    @Test
    @Order(7)
    void deleteFrameworkAndControl() {
        given()
        .when()
            .delete("/assessmentFrameworks/" + frameworkId)
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/assessmentFrameworks/" + frameworkId)
        .then()
            .statusCode(404);

        given()
        .when()
            .delete("/controls/" + controlId)
        .then()
            .statusCode(200);

        given()
            .queryParam("controlType", "Custom")
        .when()
            .get("/controls")
        .then()
            .statusCode(200)
            .body("controlMetadataList.id.flatten()", hasSize(0));
    }
}
