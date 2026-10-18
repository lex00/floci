package io.github.hectorvent.floci.services.eks;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * TagResource, UntagResource and ListTagsForResource on a node group and a Fargate profile.
 * Each of their ARNs ends in an id rather than the cluster name, so tagging them by ARN must find
 * the resource itself.
 */
@QuarkusTest
class EksSubResourceTaggingIntegrationTest {

    private static final String ACCOUNT = "246813579024";

    @Test
    void nodegroupAndFargateProfileTagsRoundTripByArn() {
        String name = "tag-" + UUID.randomUUID().toString().substring(0, 8);
        createCluster(name, "CONFIG_MAP");
        try {
            String nodegroupArn = eks().contentType("application/json")
                    .body(Map.of("nodegroupName", "ng", "nodeRole", "arn:aws:iam::" + ACCOUNT + ":role/node",
                            "subnets", new String[] {"subnet-1"}, "tags", Map.of("env", "test")))
                    .post("/clusters/" + name + "/node-groups").then().statusCode(200)
                    .extract().path("nodegroup.nodegroupArn");
            String profileArn = eks().contentType("application/json")
                    .body(Map.of("fargateProfileName", "fp",
                            "podExecutionRoleArn", "arn:aws:iam::" + ACCOUNT + ":role/pod",
                            "selectors", new Object[] {Map.of("namespace", "default")}))
                    .post("/clusters/" + name + "/fargate-profiles").then().statusCode(200)
                    .extract().path("fargateProfile.fargateProfileArn");

            roundTrip(nodegroupArn);
            eks().get("/clusters/" + name + "/node-groups/ng").then().statusCode(200)
                    .body("nodegroup.tags", equalTo(Map.of("env", "test", "team", "platform")));
            roundTrip(profileArn);
            eks().get("/clusters/" + name + "/fargate-profiles/fp").then().statusCode(200)
                    .body("fargateProfile.tags", equalTo(Map.of("team", "platform")));
            eks().get("/clusters/" + name).then().statusCode(200).body("cluster.tags", anEmptyMap());
        } finally {
            deleteCluster(name);
        }
    }

    @Test
    void anArnThatNamesNoResourceIsNotFound() {
        String name = "tag-" + UUID.randomUUID().toString().substring(0, 8);
        createCluster(name, "CONFIG_MAP");
        try {
            String nodegroupArn = eks().contentType("application/json")
                    .body(Map.of("nodegroupName", "ng", "nodeRole", "arn:aws:iam::" + ACCOUNT + ":role/node",
                            "subnets", new String[] {"subnet-1"}))
                    .post("/clusters/" + name + "/node-groups").then().statusCode(200)
                    .extract().path("nodegroup.nodegroupArn");
            String staleArn = nodegroupArn.substring(0, nodegroupArn.lastIndexOf('/') + 1) + UUID.randomUUID();
            String prefix = "arn:aws:eks:us-east-1:" + ACCOUNT + ":";
            for (String arn : new String[] {staleArn, prefix + "nodegroup/" + name + "/missing/" + UUID.randomUUID(),
                    prefix + "identityproviderconfig/" + name + "/oidc/idp/" + UUID.randomUUID(),
                    prefix + "nodegroup/no-such-cluster/ng/" + UUID.randomUUID()}) {
                eks().get("/tags/" + arn).then().statusCode(404)
                        .body("__type", equalTo("ResourceNotFoundException"));
                eks().contentType("application/json").body(Map.of("tags", Map.of("k", "v")))
                        .post("/tags/" + arn).then().statusCode(404);
            }
        } finally {
            deleteCluster(name);
        }
    }

    private static void roundTrip(String arn) {
        eks().contentType("application/json").body(Map.of("tags", Map.of("team", "platform", "tier", "web")))
                .post("/tags/" + arn).then().statusCode(204);
        eks().queryParam("tagKeys", "tier").delete("/tags/" + arn).then().statusCode(204);
        eks().get("/tags/" + arn).then().statusCode(200)
                .body("tags.team", equalTo("platform"))
                .body("tags", not(hasKey("tier")));
    }

    private static void createCluster(String name, String mode) {
        eks().contentType("application/json")
                .body(Map.of("name", name, "roleArn", "arn:aws:iam::" + ACCOUNT + ":role/cluster",
                        "accessConfig", Map.of("authenticationMode", mode,
                                "bootstrapClusterCreatorAdminPermissions", false)))
                .post("/clusters").then().statusCode(200);
    }

    private static void deleteCluster(String name) {
        eks().delete("/clusters/" + name).then().statusCode(200);
    }

    private static RequestSpecification eks() {
        return given().header("Authorization", auth("eks"));
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
                + "/20260920/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
