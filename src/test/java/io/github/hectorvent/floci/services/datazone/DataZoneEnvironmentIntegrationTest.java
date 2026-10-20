package io.github.hectorvent.floci.services.datazone;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class DataZoneEnvironmentIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/datazone/aws4_request, "
            + "SignedHeaders=host, Signature=0";

    private static RequestSpecification dz() {
        return given().header("Authorization", AUTH).contentType("application/json");
    }

    @Test
    void environmentLifecycleFromAManagedBlueprint() {
        String domainId = dz().body("{\"name\": \"env-domain\"}")
            .when().post("/v2/domains").then().statusCode(201).extract().path("id");
        dz().body("{\"description\": \"updated\"}")
            .when().put("/v2/domains/" + domainId)
            .then().statusCode(200).body("description", equalTo("updated"));
        String projectId = dz().body("{\"name\": \"env-project\"}")
            .when().post("/v2/domains/" + domainId + "/projects").then().statusCode(201).extract().path("id");
        dz().body("{\"description\": \"analytics\"}")
            .when().patch("/v2/domains/" + domainId + "/projects/" + projectId)
            .then().statusCode(200).body("description", equalTo("analytics"));

        String blueprintId = dz().queryParam("managed", true).queryParam("name", "DefaultDataLake")
            .when().get("/v2/domains/" + domainId + "/environment-blueprints")
            .then().statusCode(200)
            .body("items", hasSize(1))
            .body("items[0].provider", equalTo("Amazon DataZone"))
            .extract().path("items[0].id");
        dz().body("{\"enabledRegions\": [\"us-east-1\"], "
                + "\"provisioningRoleArn\": \"arn:aws:iam::000000000000:role/provisioning\"}")
            .when().put("/v2/domains/" + domainId + "/environment-blueprint-configurations/" + blueprintId)
            .then().statusCode(200).body("enabledRegions", hasItem("us-east-1"));

        String profileId = dz().body("""
                {"name": "lake", "environmentBlueprintIdentifier": "%s", "projectIdentifier": "%s",
                 "awsAccountId": "000000000000", "awsAccountRegion": "us-east-1"}""".formatted(blueprintId, projectId))
            .when().post("/v2/domains/" + domainId + "/environment-profiles")
            .then().statusCode(201).body("environmentBlueprintId", equalTo(blueprintId))
            .extract().path("id");
        String environmentId = dz().body("""
                {"name": "dev", "projectIdentifier": "%s", "environmentProfileIdentifier": "%s"}"""
                .formatted(projectId, profileId))
            .when().post("/v2/domains/" + domainId + "/environments")
            .then().statusCode(201)
            .body("status", equalTo("ACTIVE"))
            .body("createdAt", notNullValue())
            .extract().path("id");
        dz().queryParam("projectIdentifier", projectId)
            .when().get("/v2/domains/" + domainId + "/environments")
            .then().statusCode(200).body("items.id", hasItem(environmentId));
        dz().when().delete("/v2/domains/" + domainId + "/environment-profiles/" + profileId)
            .then().statusCode(409);

        dz().when().delete("/v2/domains/" + domainId + "/environments/" + environmentId).then().statusCode(204);
        dz().when().get("/v2/domains/" + domainId + "/environments/" + environmentId).then().statusCode(404);
        dz().when().delete("/v2/domains/" + domainId + "/environment-profiles/" + profileId).then().statusCode(204);
        dz().when().delete("/v2/domains/" + domainId).then().statusCode(202);
    }

    @Test
    void formTypesAssetTypesAndUserProfiles() {
        String domainId = dz().body("{\"name\": \"types-domain\"}")
            .when().post("/v2/domains").then().statusCode(201).extract().path("id");
        String projectId = dz().body("{\"name\": \"types-project\"}")
            .when().post("/v2/domains/" + domainId + "/projects").then().statusCode(201).extract().path("id");

        dz().body("""
                {"name": "QualityForm", "owningProjectIdentifier": "%s",
                 "model": {"smithy": "structure QualityForm { score: Integer }"}}""".formatted(projectId))
            .when().post("/v2/domains/" + domainId + "/form-types")
            .then().statusCode(201).body("revision", equalTo("1"));
        dz().body("""
                {"name": "Report", "owningProjectIdentifier": "%s",
                 "formsInput": {"quality": {"typeIdentifier": "QualityForm", "typeRevision": "1"}}}"""
                .formatted(projectId))
            .when().post("/v2/domains/" + domainId + "/asset-types")
            .then().statusCode(201).body("formsOutput.quality.typeName", equalTo("QualityForm"));
        dz().when().delete("/v2/domains/" + domainId + "/form-types/QualityForm").then().statusCode(409);
        dz().when().delete("/v2/domains/" + domainId + "/asset-types/Report").then().statusCode(204);
        dz().when().delete("/v2/domains/" + domainId + "/form-types/QualityForm").then().statusCode(204);

        String role = "arn:aws:iam::000000000000:role/analyst";
        dz().body("{\"userIdentifier\": \"" + role + "\", \"userType\": \"IAM_ROLE\"}")
            .when().post("/v2/domains/" + domainId + "/user-profiles")
            .then().statusCode(201).body("type", equalTo("IAM")).body("details.iam.arn", equalTo(role));
        String encoded = URLEncoder.encode(role, StandardCharsets.UTF_8);
        dz().urlEncodingEnabled(false).body("{\"status\": \"ACTIVATED\"}")
            .when().put("/v2/domains/" + domainId + "/user-profiles/" + encoded)
            .then().statusCode(200).body("status", equalTo("ACTIVATED"));
        dz().urlEncodingEnabled(false)
            .when().get("/v2/domains/" + domainId + "/user-profiles/" + encoded)
            .then().statusCode(200).body("status", equalTo("ACTIVATED"));
    }
}
