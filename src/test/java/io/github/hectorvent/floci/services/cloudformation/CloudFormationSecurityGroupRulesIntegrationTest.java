package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A security group's rules and tags are properties of the template, not extras.
 *
 * The provisioner created the group from `GroupName`/`GroupDescription`/`VpcId`
 * and dropped `SecurityGroupIngress`, `SecurityGroupEgress` and `Tags`, so a
 * stack that declared rules produced a group that described as empty. Anything
 * comparing a template against live state — drift detection, a reconcile loop —
 * then reports every declared rule as missing, on a stack that just applied
 * cleanly.
 */
@QuarkusTest
class CloudFormationSecurityGroupRulesIntegrationTest {

    // Isolated to eu-north-1 so the group this test creates does not pollute the
    // shared in-memory Ec2Service state other regions' tests assert on.
    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-north-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/eu-north-1/ec2/aws4_request";

    @Test
    void declaredRulesAndTagsReachTheCreatedGroup() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-sg-rules-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {
                      "Type": "AWS::EC2::VPC",
                      "Properties": {"CidrBlock": "10.9.0.0/16"}
                    },
                    "AppSg": {
                      "Type": "AWS::EC2::SecurityGroup",
                      "Properties": {
                        "GroupDescription": "app tier",
                        "VpcId": {"Ref": "Vpc"},
                        "SecurityGroupIngress": [
                          {
                            "IpProtocol": "tcp",
                            "FromPort": 22,
                            "ToPort": 22,
                            "CidrIp": "203.0.113.0/24",
                            "Description": "ssh from the office"
                          }
                        ],
                        "SecurityGroupEgress": [
                          {"IpProtocol": "-1", "CidrIp": "0.0.0.0/0"}
                        ],
                        "Tags": [
                          {"Key": "app", "Value": "checkout"}
                        ]
                      }
                    }
                  },
                  "Outputs": {
                    "GroupId": {"Value": {"Ref": "AppSg"}}
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackId>"));

        String describeStacks = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        Matcher m = Pattern.compile("(sg-[0-9a-f]+)").matcher(describeStacks);
        assertTrue(m.find(), "stack outputs carried no group id: " + describeStacks);
        String groupId = m.group(1);
        assertNotNull(groupId);

        String describeGroups = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", EC2_AUTH)
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().asString();

        // The ingress rule, with its port range, its source, and its description.
        assertTrue(describeGroups.contains("203.0.113.0/24"),
                "declared ingress CIDR missing from the created group: " + describeGroups);
        assertTrue(describeGroups.contains("<fromPort>22</fromPort>"),
                "declared ingress fromPort missing: " + describeGroups);
        assertTrue(describeGroups.contains("<toPort>22</toPort>"),
                "declared ingress toPort missing: " + describeGroups);
        assertTrue(describeGroups.contains("ssh from the office"),
                "declared rule description missing: " + describeGroups);

        // The egress rule the template asked for.
        assertTrue(describeGroups.contains("0.0.0.0/0"),
                "declared egress CIDR missing from the created group: " + describeGroups);

        // And the tags, which an ownership filter reads to tell managed from foreign.
        assertTrue(describeGroups.contains("checkout"),
                "declared tag missing from the created group: " + describeGroups);
    }
}
