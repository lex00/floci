package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class EksPodIdentityAssociationIntegrationTest {
    private static final String JSON = "application/json";
    private static final String ROLE = "arn:aws:iam::000000000000:role/pod-role";

    @Test
    void associationLifecycleUsesRestJsonRoutes() {
        String cluster = "pia-" + UUID.randomUUID().toString().substring(0, 8);
        String path = "/clusters/" + cluster + "/pod-identity-associations";
        given().contentType(JSON)
                .body("{\"name\":\"" + cluster + "\",\"roleArn\":\"arn:aws:iam::000000000000:role/eks-role\","
                        + "\"version\":\"1.29\"}")
                .post("/clusters").then().statusCode(200);
        try {
            Map<String, Object> request = Map.of("namespace", "default", "serviceAccount", "app",
                    "roleArn", ROLE, "tags", Map.of("team", "platform"));
            String id = given().contentType(JSON).body(request).post(path).then().statusCode(200)
                    .contentType(containsString(JSON))
                    .body("association.clusterName", equalTo(cluster))
                    .body("association.namespace", equalTo("default"))
                    .body("association.serviceAccount", equalTo("app"))
                    .body("association.roleArn", equalTo(ROLE))
                    .body("association.associationId", startsWith("a-"))
                    .body("association.associationArn", containsString(":podidentityassociation/" + cluster + "/a-"))
                    .body("association.tags.team", equalTo("platform"))
                    .body("association.disableSessionTags", equalTo(false))
                    .body("association.createdAt", instanceOf(Number.class))
                    .extract().path("association.associationId");
            String arn = given().get(path + "/" + id).then().statusCode(200)
                    .body("association.associationId", equalTo(id))
                    .extract().path("association.associationArn");

            given().contentType(JSON).body(request).post(path).then().statusCode(409)
                    .body("__type", equalTo("ResourceInUseException"));

            given().get(path).then().statusCode(200)
                    .body("associations", hasSize(1))
                    .body("associations[0].associationId", equalTo(id))
                    .body("associations[0].namespace", equalTo("default"))
                    .body("associations[0].serviceAccount", equalTo("app"))
                    .body("nextToken", nullValue());
            given().queryParam("namespace", "other").get(path).then().statusCode(200)
                    .body("associations", empty());

            given().contentType(JSON).body(Map.of("roleArn", "arn:aws:iam::000000000000:role/next"))
                    .post(path + "/" + id).then().statusCode(200)
                    .body("association.roleArn", equalTo("arn:aws:iam::000000000000:role/next"))
                    .body("association.tags.team", equalTo("platform"));

            String encodedArn = URLEncoder.encode(arn, StandardCharsets.UTF_8);
            given().contentType(JSON).body(Map.of("tags", Map.of("owner", "me")))
                    .urlEncodingEnabled(false).post("/tags/" + encodedArn).then().statusCode(200);
            given().urlEncodingEnabled(false).get("/tags/" + encodedArn).then().statusCode(200)
                    .body("tags.owner", equalTo("me"))
                    .body("tags.team", equalTo("platform"));

            given().delete(path + "/" + id).then().statusCode(200)
                    .body("association.associationId", equalTo(id));
            given().get(path + "/" + id).then().statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            given().delete("/clusters/" + cluster).then().statusCode(200);
        }
    }

    @Test
    void missingClusterIsResourceNotFound() {
        given().contentType(JSON)
                .body(Map.of("namespace", "default", "serviceAccount", "app", "roleArn", ROLE))
                .post("/clusters/no-such-cluster/pod-identity-associations").then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));
    }
}
