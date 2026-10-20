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

    @Test
    void accessLifecycleEndpointsGroupsIndexesAndSettings() {
        String version = call("CreateAccessPolicy", """
                {"type":"data","name":"it-readers","policy":"[{\\"Rules\\":[{\\"ResourceType\\":\\"index\\",\
                \\"Resource\\":[\\"index/it-*/*\\"],\\"Permission\\":[\\"aoss:ReadDocument\\"]}],\
                \\"Principal\\":[\\"arn:aws:iam::000000000000:role/reader\\"]}]"}""")
                .statusCode(200)
                .body("accessPolicyDetail.policy[0].Rules[0].ResourceType", equalTo("index"))
                .extract().path("accessPolicyDetail.policyVersion");
        call("UpdateAccessPolicy", "{\"type\":\"data\",\"name\":\"it-readers\",\"policyVersion\":\"" + version
                + "\",\"description\":\"readers\"}")
                .statusCode(200)
                .body("accessPolicyDetail.description", equalTo("readers"));
        call("ListAccessPolicies", "{\"type\":\"data\"}")
                .statusCode(200)
                .body("accessPolicySummaries.name", hasItem("it-readers"));
        call("DeleteAccessPolicy", "{\"type\":\"data\",\"name\":\"it-readers\"}").statusCode(200);
        call("GetAccessPolicy", "{\"type\":\"data\",\"name\":\"it-readers\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        call("CreateLifecyclePolicy", """
                {"type":"retention","name":"it-keep","policy":"{\\"Rules\\":[{\\"ResourceType\\":\\"index\\",\
                \\"Resource\\":[\\"index/it-logs/*\\"],\\"MinIndexRetention\\":\\"14d\\"}]}"}""")
                .statusCode(200);
        call("BatchGetEffectiveLifecyclePolicy",
                "{\"resourceIdentifiers\":[{\"type\":\"retention\",\"resource\":\"index/it-logs/app\"}]}")
                .statusCode(200)
                .body("effectiveLifecyclePolicyDetails[0].policyName", equalTo("it-keep"));

        String endpointId = call("CreateVpcEndpoint",
                "{\"name\":\"it-endpoint\",\"vpcId\":\"vpc-1\",\"subnetIds\":[\"subnet-1\"]}")
                .statusCode(200)
                .body("createVpcEndpointDetail.status", equalTo("ACTIVE"))
                .extract().path("createVpcEndpointDetail.id");
        call("BatchGetVpcEndpoint", "{\"ids\":[\"" + endpointId + "\"]}")
                .statusCode(200)
                .body("vpcEndpointDetails[0].vpcId", equalTo("vpc-1"));
        call("DeleteVpcEndpoint", "{\"id\":\"" + endpointId + "\"}")
                .statusCode(200)
                .body("deleteVpcEndpointDetail.status", equalTo("DELETING"));

        String groupArn = call("CreateCollectionGroup",
                "{\"name\":\"it-group\",\"standbyReplicas\":\"DISABLED\",\"tags\":[{\"key\":\"team\",\"value\":\"search\"}]}")
                .statusCode(200)
                .extract().path("createCollectionGroupDetail.arn");
        call("ListTagsForResource", "{\"resourceArn\":\"" + groupArn + "\"}")
                .statusCode(200)
                .body("tags[0].value", equalTo("search"));

        call("CreateSecurityPolicy", """
                {"type":"encryption","name":"it-indexed-enc","policy":"{\\"Rules\\":[{\\"ResourceType\\":\\"collection\\",\
                \\"Resource\\":[\\"collection/it-indexed\\"]}],\\"AWSOwnedKey\\":true}"}""")
                .statusCode(200);
        String collectionId = call("CreateCollection", "{\"name\":\"it-indexed\",\"type\":\"SEARCH\"}")
                .statusCode(200)
                .extract().path("createCollectionDetail.id");
        call("CreateIndex", "{\"id\":\"" + collectionId + "\",\"indexName\":\"docs\","
                + "\"indexSchema\":{\"mappings\":{\"properties\":{\"title\":{\"type\":\"text\"}}}}}")
                .statusCode(200);
        call("GetIndex", "{\"id\":\"" + collectionId + "\",\"indexName\":\"docs\"}")
                .statusCode(200)
                .body("indexSchema.mappings.properties.title.type", equalTo("text"));

        call("UpdateAccountSettings", "{\"capacityLimits\":{\"maxSearchCapacityInOCU\":20}}")
                .statusCode(200)
                .body("accountSettingsDetail.capacityLimits.maxSearchCapacityInOCU", equalTo(20));
        call("GetPoliciesStats", "{}")
                .statusCode(200)
                .body("LifecyclePolicyStats.RetentionPolicyCount", equalTo(1));
    }
}
