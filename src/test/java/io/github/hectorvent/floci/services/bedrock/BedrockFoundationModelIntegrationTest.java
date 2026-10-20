package io.github.hectorvent.floci.services.bedrock;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class BedrockFoundationModelIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260101/us-west-2/bedrock/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listFoundationModelsFiltersByProviderAndOutputModality() {
        given()
            .header("Authorization", AUTH)
            .queryParam("byProvider", "Anthropic")
            .queryParam("byOutputModality", "TEXT")
        .when()
            .get("/foundation-models")
        .then()
            .statusCode(200)
            .body("modelSummaries.modelId", hasItem("anthropic.claude-3-haiku-20240307-v1:0"))
            .body("modelSummaries.providerName", everyItem(equalTo("Anthropic")))
            .body("modelSummaries.modelArn", everyItem(startsWith("arn:aws:bedrock:us-west-2::foundation-model/")))
            .body("modelSummaries[0].modelLifecycle.status", equalTo("ACTIVE"));
    }

    @Test
    void listFoundationModelsRejectsAnUnknownModality() {
        given()
            .header("Authorization", AUTH)
            .queryParam("byOutputModality", "AUDIO")
        .when()
            .get("/foundation-models")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    void getFoundationModelByIdAndUnknownId() {
        given()
            .header("Authorization", AUTH)
        .when()
            .get("/foundation-models/amazon.titan-embed-text-v2:0")
        .then()
            .statusCode(200)
            .body("modelDetails.modelId", equalTo("amazon.titan-embed-text-v2:0"))
            .body("modelDetails.outputModalities", equalTo(List.of("EMBEDDING")));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/foundation-models/acme.unknown-v1")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void listAndGetSystemDefinedInferenceProfiles() {
        given()
            .header("Authorization", AUTH)
            .queryParam("type", "SYSTEM_DEFINED")
        .when()
            .get("/inference-profiles")
        .then()
            .statusCode(200)
            .body("inferenceProfileSummaries.inferenceProfileId",
                    hasItem("us.anthropic.claude-3-haiku-20240307-v1:0"))
            .body("inferenceProfileSummaries.inferenceProfileId", not(hasItem(startsWith("eu."))))
            .body("inferenceProfileSummaries.type", everyItem(equalTo("SYSTEM_DEFINED")));

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/inference-profiles/us.amazon.nova-lite-v1:0")
        .then()
            .statusCode(200)
            .body("inferenceProfileArn",
                    equalTo("arn:aws:bedrock:us-west-2:000000000000:inference-profile/us.amazon.nova-lite-v1:0"))
            .body("models.modelArn", hasItem("arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0"))
            .body("status", equalTo("ACTIVE"));
    }

    @Test
    void listInferenceProfilesPages() {
        String token = given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", "2")
        .when()
            .get("/inference-profiles")
        .then()
            .statusCode(200)
            .body("inferenceProfileSummaries.size()", equalTo(2))
            .extract().path("nextToken");

        given()
            .header("Authorization", AUTH)
            .queryParam("maxResults", "2")
            .queryParam("nextToken", token)
        .when()
            .get("/inference-profiles")
        .then()
            .statusCode(200)
            .body("inferenceProfileSummaries.size()", equalTo(2));
    }

    @Test
    void applicationInferenceProfileLifecycleWithTags() {
        String arn = given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("""
                {"inferenceProfileName": "team-haiku",
                 "description": "Cost tracking",
                 "modelSource": {"copyFrom":
                   "arn:aws:bedrock:us-west-2:000000000000:inference-profile/us.anthropic.claude-3-haiku-20240307-v1:0"},
                 "tags": [{"key": "team", "value": "ai"}]}
                """)
        .when()
            .post("/inference-profiles")
        .then()
            .statusCode(201)
            .body("status", equalTo("ACTIVE"))
            .body("inferenceProfileArn", startsWith(
                    "arn:aws:bedrock:us-west-2:000000000000:application-inference-profile/"))
            .extract().path("inferenceProfileArn");

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/inference-profiles/" + arn.substring(arn.lastIndexOf('/') + 1))
        .then()
            .statusCode(200)
            .body("inferenceProfileName", equalTo("team-haiku"))
            .body("type", equalTo("APPLICATION"))
            .body("models.size()", equalTo(3));

        given()
            .header("Authorization", AUTH)
            .queryParam("type", "APPLICATION")
        .when()
            .get("/inference-profiles")
        .then()
            .statusCode(200)
            .body("inferenceProfileSummaries.inferenceProfileArn", hasItem(arn));

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"resourceARN\": \"" + arn + "\"}")
        .when()
            .post("/listTagsForResource")
        .then()
            .statusCode(200)
            .body("tags[0].key", equalTo("team"));

        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"inferenceProfileName\": \"team-haiku\", \"modelSource\": {\"copyFrom\": "
                    + "\"arn:aws:bedrock:us-west-2::foundation-model/amazon.nova-lite-v1:0\"}}")
        .when()
            .post("/inference-profiles")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ConflictException"));

        given()
            .header("Authorization", AUTH)
        .when()
            .delete("/inference-profiles/" + arn)
        .then()
            .statusCode(200);

        given()
            .header("Authorization", AUTH)
        .when()
            .get("/inference-profiles/" + arn)
        .then()
            .statusCode(404);
    }

    @Test
    void createInferenceProfileFromAnUnknownModelIsNotFound() {
        given()
            .header("Authorization", AUTH)
            .contentType("application/json")
            .body("{\"inferenceProfileName\": \"ghost\", \"modelSource\": {\"copyFrom\": "
                    + "\"arn:aws:bedrock:us-west-2::foundation-model/acme.ghost-v1\"}}")
        .when()
            .post("/inference-profiles")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
