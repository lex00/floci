package io.github.hectorvent.floci.services.opensearchserverless;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class OpenSearchServerlessIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "OpenSearchServerless.";

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
    void securityConfigCreateReadUpdateDelete() {
        String id = call("CreateSecurityConfig", """
                {"name":"it-saml","type":"saml","description":"first",
                 "samlOptions":{"metadata":"<EntityDescriptor/>","sessionTimeout":60}}
                """)
                .statusCode(200)
                .body("securityConfigDetail.id", equalTo("saml/000000000000/it-saml"))
                .body("securityConfigDetail.type", equalTo("saml"))
                .body("securityConfigDetail.samlOptions.sessionTimeout", equalTo(60))
                .body("securityConfigDetail.createdDate", instanceOf(Long.class))
                .extract().path("securityConfigDetail.id");

        String version = call("GetSecurityConfig", "{\"id\":\"" + id + "\"}")
                .statusCode(200)
                .body("securityConfigDetail.description", equalTo("first"))
                .extract().path("securityConfigDetail.configVersion");

        call("ListSecurityConfigs", "{\"type\":\"saml\"}")
                .statusCode(200)
                .body("securityConfigSummaries.id", hasItem(id));

        call("UpdateSecurityConfig", """
                {"id":"%s","configVersion":"%s","description":"second"}
                """.formatted(id, version))
                .statusCode(200)
                .body("securityConfigDetail.description", equalTo("second"))
                .body("securityConfigDetail.configVersion", not(equalTo(version)));

        call("UpdateSecurityConfig", """
                {"id":"%s","configVersion":"%s","description":"third"}
                """.formatted(id, version))
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        call("DeleteSecurityConfig", "{\"id\":\"" + id + "\"}").statusCode(200);

        call("GetSecurityConfig", "{\"id\":\"" + id + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void collectionFollowsItsEncryptionPolicy() {
        call("CreateCollection", "{\"name\":\"it-orders\"}")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));

        call("CreateSecurityPolicy", """
                {"name":"it-orders-enc","type":"encryption",
                 "policy":"{\\"Rules\\":[{\\"ResourceType\\":\\"collection\\",\\"Resource\\":[\\"collection/it-orders\\"]}],\\"AWSOwnedKey\\":true}"}
                """)
                .statusCode(200)
                .body("securityPolicyDetail.policy.AWSOwnedKey", equalTo(true));

        String collectionId = call("CreateCollection", """
                {"name":"it-orders","type":"SEARCH","tags":[{"key":"team","value":"data"}]}
                """)
                .statusCode(200)
                .body("createCollectionDetail.status", equalTo("CREATING"))
                .body("createCollectionDetail.kmsKeyArn", equalTo("auto"))
                .extract().path("createCollectionDetail.id");

        String arn = call("BatchGetCollection", "{\"ids\":[\"" + collectionId + "\"]}")
                .statusCode(200)
                .body("collectionDetails[0].status", equalTo("ACTIVE"))
                .body("collectionDetails[0].collectionEndpoint", startsWith("https://" + collectionId + "."))
                .body("collectionDetails[0].arn", endsWith(":collection/" + collectionId))
                .extract().path("collectionDetails[0].arn");

        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags[0].key", equalTo("team"));

        call("ListCollections", "{\"collectionFilters\":{\"name\":\"it-orders\"}}")
                .statusCode(200)
                .body("collectionSummaries.id", hasItem(collectionId));

        call("DeleteCollection", "{\"id\":\"" + collectionId + "\"}")
                .statusCode(200)
                .body("deleteCollectionDetail.status", equalTo("DELETING"));

        call("BatchGetCollection", "{\"ids\":[\"" + collectionId + "\"]}")
                .statusCode(200)
                .body("collectionErrorDetails[0].errorCode", equalTo("NOT_FOUND"));

        call("DeleteSecurityPolicy", "{\"name\":\"it-orders-enc\",\"type\":\"encryption\"}").statusCode(200);
    }
}
