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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(TagPolicyEnforcementIntegrationTest.EnforcingProfile.class)
class TagPolicyEnforcementIntegrationTest {

    private static final String ACCOUNT = "777777777777";
    private static final String POLICY = """
            {"tags":{"costcenter":{"tag_key":{"@@assign":"CostCenter"},
              "tag_value":{"@@assign":["100","200*"]},
              "enforced_for":{"@@assign":["ec2:ALL_SUPPORTED","sqs:queue","sns:topic","dynamodb:table",
                "lambda:function","s3:bucket","scheduler:schedule-group"]}}}}""";

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
        attachTagPolicy();

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

    @Test
    void attachedTagPolicyRefusesNoncompliantTagsAtCreateAndAcrossServices() throws Exception {
        attachTagPolicy();

        ec2().formParam("Action", "CreateVpc").formParam("CidrBlock", "10.71.0.0/16")
                .formParam("TagSpecification.1.ResourceType", "vpc")
                .formParam("TagSpecification.1.Tag.1.Key", "CostCenter")
                .formParam("TagSpecification.1.Tag.1.Value", "300")
                .when().post("/").then().statusCode(400).body(containsString("<Code>TagPolicyViolation</Code>"));
        ec2().formParam("Action", "DescribeVpcs").formParam("Filter.1.Name", "cidr")
                .formParam("Filter.1.Value.1", "10.71.0.0/16")
                .when().post("/").then().statusCode(200).body(not(containsString("<vpcId>")));

        given().header("Authorization", auth("sns")).formParam("Action", "CreateTopic")
                .formParam("Name", "tag-policy-topic")
                .formParam("Tags.member.1.Key", "CostCenter").formParam("Tags.member.1.Value", "300")
                .when().post("/").then().statusCode(400).body(containsString("TagPolicyException"));

        dynamodb("CreateTable", """
                {"TableName":"tag-policy-table","BillingMode":"PAY_PER_REQUEST",
                 "AttributeDefinitions":[{"AttributeName":"id","AttributeType":"S"}],
                 "KeySchema":[{"AttributeName":"id","KeyType":"HASH"}],
                 "Tags":[{"Key":"CostCenter","Value":"300"}]}""")
                .when().post("/").then().statusCode(400).body("__type", containsString("ValidationException"));
        dynamodb("DescribeTable", "{\"TableName\":\"tag-policy-table\"}")
                .when().post("/").then().statusCode(400).body("__type", containsString("ResourceNotFoundException"));

        given().header("Authorization", auth("lambda")).contentType("application/json")
                .body(new ObjectMapper().writeValueAsString(Map.of(
                        "FunctionName", "tag-policy-fn", "Runtime", "python3.12", "Handler", "index.handler",
                        "Role", "arn:aws:iam::" + ACCOUNT + ":role/fn",
                        "Code", Map.of("ZipFile", Base64.getEncoder().encodeToString(zip())),
                        "Tags", Map.of("CostCenter", "300"))))
                .when().post("/2015-03-31/functions").then().statusCode(400)
                .body(containsString("InvalidParameterValueException"));

        s3().when().put("/tag-policy-bucket").then().statusCode(200);
        s3().contentType("application/xml")
                .body("<Tagging><TagSet><Tag><Key>CostCenter</Key><Value>300</Value></Tag></TagSet></Tagging>")
                .when().put("/tag-policy-bucket?tagging").then().statusCode(400).body(containsString("InvalidTag"));

        String groupArn = given().header("Authorization", auth("scheduler")).contentType("application/json")
                .body("{}").when().post("/schedule-groups/tag-policy-group")
                .then().statusCode(200).extract().path("ScheduleGroupArn");
        given().header("Authorization", auth("scheduler")).contentType("application/json")
                .body("{\"Tags\":[{\"Key\":\"CostCenter\",\"Value\":\"300\"}]}")
                .when().post("/tags/" + groupArn).then().statusCode(400).body(containsString("ValidationException"));
        given().header("Authorization", auth("scheduler")).contentType("application/json")
                .body("{\"Tags\":[{\"Key\":\"CostCenter\",\"Value\":\"100\"}]}")
                .when().post("/tags/" + groupArn).then().statusCode(lessThan(300));

        given().header("Authorization", auth("tagging"))
                .header("X-Amz-Target", "ResourceGroupsTaggingAPI_20170126.TagResources")
                .contentType("application/x-amz-json-1.1")
                .body("{\"ResourceARNList\":[\"" + groupArn + "\"],\"Tags\":{\"CostCenter\":\"300\"}}")
                .when().post("/").then().statusCode(200)
                .body("FailedResourcesMap.'" + groupArn + "'.ErrorCode", equalTo("InvalidParameterException"));
    }

    private static boolean tagPolicyAttached;

    private static synchronized void attachTagPolicy() throws Exception {
        if (tagPolicyAttached) {
            return;
        }
        tagPolicyAttached = true;
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
    }

    private static byte[] zip() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("index.py"));
            zip.write("def handler(event, context):\n    return event\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static RequestSpecification dynamodb(String action, String body) {
        return given().header("Authorization", auth("dynamodb"))
                .header("X-Amz-Target", "DynamoDB_20120810." + action)
                .contentType("application/x-amz-json-1.0")
                .body(body);
    }

    private static RequestSpecification s3() {
        return given().header("Authorization", auth("s3"));
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
