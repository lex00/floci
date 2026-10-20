package io.github.hectorvent.floci.services.organizations;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
@TestProfile(TagPolicyEnforcementIntegrationTest.EnforcingProfile.class)
class TagPolicyEnforcementIntegrationTest {

    private static final String ACCOUNT = "777777777777";
    private static final String POLICY = """
            {"tags":{"costcenter":{"tag_key":{"@@assign":"CostCenter"},
              "tag_value":{"@@assign":["100","200*"]},
              "enforced_for":{"@@assign":["ec2:ALL_SUPPORTED","sqs:queue"]}}}}""";

    public static class EnforcingProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.organizations.tag-policy-enforcement-enabled", "true");
        }
    }

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void attachedTagPolicyRefusesNoncompliantEc2AndSqsTags() throws Exception {
        organizations("CreateOrganization", "{\"FeatureSet\":\"ALL\"}").when().post("/").then().statusCode(200);
        String rootId = organizations("ListRoots", "{}").when().post("/").then().statusCode(200)
                .extract().path("Roots[0].Id");
        organizations("EnablePolicyType", "{\"RootId\":\"" + rootId + "\",\"PolicyType\":\"TAG_POLICY\"}")
                .when().post("/").then().statusCode(200);
        String policyId = organizations("CreatePolicy", new ObjectMapper().writeValueAsString(Map.of(
                "Name", "cost-center", "Description", "cost center", "Type", "TAG_POLICY", "Content", POLICY)))
                .when().post("/").then().statusCode(200).extract().path("Policy.PolicySummary.Id");
        organizations("AttachPolicy", "{\"PolicyId\":\"" + policyId + "\",\"TargetId\":\"" + rootId + "\"}")
                .when().post("/").then().statusCode(200);

        String vpcId = ec2().formParam("Action", "CreateVpc").formParam("CidrBlock", "10.70.0.0/16")
                .when().post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        tagVpc(vpcId, "CostCenter", "300").then().statusCode(400)
                .body(containsString("<Code>TagPolicyViolation</Code>"));
        tagVpc(vpcId, "costcenter", "100").then().statusCode(400)
                .body(containsString("<Code>TagPolicyViolation</Code>"));
        tagVpc(vpcId, "CostCenter", "100").then().statusCode(200);
        tagVpc(vpcId, "CostCenter", "2001").then().statusCode(200);
        tagVpc(vpcId, "Team", "anything").then().statusCode(200);

        sqs("CreateQueue").formParam("QueueName", "tag-policy-queue")
                .formParam("Tag.1.Key", "CostCenter").formParam("Tag.1.Value", "999")
                .when().post("/").then().statusCode(400).body(containsString("TagPolicyViolation"));
        sqs("CreateQueue").formParam("QueueName", "tag-policy-queue")
                .formParam("Tag.1.Key", "CostCenter").formParam("Tag.1.Value", "100")
                .when().post("/").then().statusCode(200);
    }

    private Response tagVpc(String vpcId, String key, String value) {
        return ec2().formParam("Action", "CreateTags").formParam("ResourceId.1", vpcId)
                .formParam("Tag.1.Key", key).formParam("Tag.1.Value", value).when().post("/");
    }

    private static RequestSpecification organizations(String action, String body) {
        return given()
                .header("Authorization", auth("organizations"))
                .header("X-Amz-Target", "AWSOrganizationsV20161128." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body);
    }

    private static RequestSpecification ec2() {
        return given().header("Authorization", auth("ec2"));
    }

    private static RequestSpecification sqs(String action) {
        return given().header("Authorization", auth("sqs")).formParam("Action", action);
    }

    private static String auth(String service) {
        return "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260822/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
