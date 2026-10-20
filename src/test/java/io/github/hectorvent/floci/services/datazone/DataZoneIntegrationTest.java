package io.github.hectorvent.floci.services.datazone;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataZoneIntegrationTest {

    private static final String JSON = "application/json";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/datazone/aws4_request, "
            + "SignedHeaders=host, Signature=0";

    private static String domainId;
    private static String domainArn;
    private static String projectId;
    private static String glossaryId;
    private static String termId;

    @Test
    @Order(1)
    void createDomainAnswersAvailable() {
        Response created = given()
            .header("Authorization", AUTH)
            .contentType(JSON)
            .body("""
                {"name": "it-domain",
                 "domainExecutionRole": "arn:aws:iam::000000000000:role/service-role/AmazonDataZoneDomainExecution",
                 "tags": {"team": "data"}}
                """)
        .when()
            .post("/v2/domains")
        .then()
            .statusCode(201)
            .body("id", matchesPattern("dzd_[a-z0-9]{14}"))
            .body("status", equalTo("AVAILABLE"))
            .body("tags.team", equalTo("data"))
            .extract().response();
        domainId = created.path("id");
        domainArn = created.path("arn");

        given().header("Authorization", AUTH)
        .when().get("/v2/domains/" + domainId)
        .then()
            .statusCode(200)
            .body("name", equalTo("it-domain"))
            .body("arn", equalTo(domainArn))
            .body("createdAt", notNullValue());

        given().header("Authorization", AUTH)
        .when().get("/v2/domains")
        .then()
            .statusCode(200)
            .body("items.id", hasItem(domainId));
    }

    @Test
    @Order(2)
    void tagsRideTheSharedTagsPath() {
        given().header("Authorization", AUTH).contentType(JSON)
            .body("{\"tags\": {\"env\": \"local\"}}")
        .when().post("/tags/" + domainArn)
        .then().statusCode(200);

        given().header("Authorization", AUTH)
        .when().get("/tags/" + domainArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("data"))
            .body("tags.env", equalTo("local"));
    }

    @Test
    @Order(3)
    void createProjectGlossaryAndTerm() {
        projectId = given().header("Authorization", AUTH).contentType(JSON)
            .body("{\"name\": \"governance\"}")
        .when().post("/v2/domains/" + domainId + "/projects")
        .then()
            .statusCode(201)
            .body("domainId", equalTo(domainId))
            .extract().path("id");

        glossaryId = given().header("Authorization", AUTH).contentType(JSON)
            .body("{\"name\": \"business\", \"owningProjectIdentifier\": \"" + projectId + "\"}")
        .when().post("/v2/domains/" + domainId + "/glossaries")
        .then()
            .statusCode(201)
            .body("status", equalTo("ENABLED"))
            .body("owningProjectId", equalTo(projectId))
            .extract().path("id");

        termId = given().header("Authorization", AUTH).contentType(JSON)
            .body("{\"glossaryIdentifier\": \"" + glossaryId + "\", \"name\": \"revenue\","
                    + " \"shortDescription\": \"Money in\"}")
        .when().post("/v2/domains/" + domainId + "/glossary-terms")
        .then()
            .statusCode(201)
            .body("status", equalTo("ENABLED"))
            .body("glossaryId", equalTo(glossaryId))
            .extract().path("id");

        given().header("Authorization", AUTH)
        .when().get("/v2/domains/" + domainId + "/glossary-terms/" + termId)
        .then()
            .statusCode(200)
            .body("id", equalTo(termId))
            .body("domainId", equalTo(domainId))
            .body("name", equalTo("revenue"))
            .body("shortDescription", equalTo("Money in"));
    }

    @Test
    @Order(4)
    void termInAMissingGlossaryIsNotFound() {
        given().header("Authorization", AUTH).contentType(JSON)
            .body("{\"glossaryIdentifier\": \"nosuchglossary\", \"name\": \"x\"}")
        .when().post("/v2/domains/" + domainId + "/glossary-terms")
        .then()
            .statusCode(404)
            .header("X-Amzn-Errortype", "ResourceNotFoundException")
            .body("message", notNullValue());
    }

    @Test
    @Order(5)
    void disableThenDeleteTermAsTheProviderDoes() {
        given().header("Authorization", AUTH)
        .when().delete("/v2/domains/" + domainId + "/glossary-terms/" + termId)
        .then().statusCode(409);

        given().header("Authorization", AUTH).contentType(JSON)
            .body("{\"status\": \"DISABLED\"}")
        .when().patch("/v2/domains/" + domainId + "/glossary-terms/" + termId)
        .then()
            .statusCode(200)
            .body("status", equalTo("DISABLED"));

        given().header("Authorization", AUTH)
        .when().delete("/v2/domains/" + domainId + "/glossary-terms/" + termId)
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().get("/v2/domains/" + domainId + "/glossary-terms/" + termId)
        .then().statusCode(404);
    }

    @Test
    @Order(6)
    void deleteGlossaryProjectAndDomain() {
        given().header("Authorization", AUTH).contentType(JSON)
            .body("{\"status\": \"DISABLED\"}")
        .when().patch("/v2/domains/" + domainId + "/glossaries/" + glossaryId)
        .then().statusCode(200).body("status", equalTo("DISABLED"));

        given().header("Authorization", AUTH)
        .when().delete("/v2/domains/" + domainId + "/glossaries/" + glossaryId)
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().delete("/v2/domains/" + domainId + "/projects/" + projectId)
        .then().statusCode(204);

        given().header("Authorization", AUTH)
        .when().delete("/v2/domains/" + domainId)
        .then()
            .statusCode(202)
            .body("status", equalTo("DELETING"));

        given().header("Authorization", AUTH)
        .when().get("/v2/domains/" + domainId)
        .then().statusCode(404);
    }
}
