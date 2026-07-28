package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * End-to-end check that CloudFormation applies MapPublicIpOnLaunch to a subnet
 * (issue #2013): a public subnet declared true reports true via DescribeSubnets.
 */
@QuarkusTest
class CloudFormationSubnetMapPublicIpIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void mapPublicIpOnLaunchIsApplied() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "cfn-subnetmap-" + suffix;

        String template = """
                {
                  "Resources": {
                    "Vpc": {"Type": "AWS::EC2::VPC", "Properties": {"CidrBlock": "10.90.0.0/16"}},
                    "PublicSubnet": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.90.0.0/24", "MapPublicIpOnLaunch": true}
                    },
                    "PrivateSubnet": {
                      "Type": "AWS::EC2::Subnet",
                      "Properties": {"VpcId": {"Ref": "Vpc"}, "CidrBlock": "10.90.1.0/24", "MapPublicIpOnLaunch": false}
                    }
                  },
                  "Outputs": {
                    "PublicId": {"Value": {"Ref": "PublicSubnet"}},
                    "PrivateId": {"Value": {"Ref": "PrivateSubnet"}}
                  }
                }
                """;

        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);

        String describe = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>CREATE_COMPLETE</StackStatus>"))
            .extract().asString();

        String pubId = between(describe, "<OutputKey>PublicId</OutputKey>", "</member>");
        pubId = between(pubId, "<OutputValue>", "</OutputValue>");

        // The public subnet reports MapPublicIpOnLaunch true.
        given()
            .formParam("Action", "DescribeSubnets")
            .formParam("SubnetId.1", pubId)
            .header("Authorization", EC2_AUTH)
        .when().post("/").then().statusCode(200)
            .body(containsString("<mapPublicIpOnLaunch>true</mapPublicIpOnLaunch>"));
    }

    private static String between(String haystack, String open, String close) {
        int i = haystack.indexOf(open);
        int j = haystack.indexOf(close, i + open.length());
        return haystack.substring(i + open.length(), j);
    }
}
