package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class Ec2SecurityGroupVpcAssociationIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void associateDescribeAndDisassociate() {
        String vpcA = createVpc("10.40.0.0/16");
        String vpcB = createVpc("10.41.0.0/16");
        String groupId = given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", "multi-vpc-" + System.nanoTime())
            .formParam("GroupDescription", "multi vpc")
            .formParam("VpcId", vpcA)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");

        given()
            .formParam("Action", "AssociateSecurityGroupVpc")
            .formParam("GroupId", groupId)
            .formParam("VpcId", vpcB)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateSecurityGroupVpcResponse.state", equalTo("associated"));

        given()
            .formParam("Action", "DescribeSecurityGroupVpcAssociations")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeSecurityGroupVpcAssociationsResponse.securityGroupVpcAssociationSet.item.vpcId",
                    equalTo(vpcB))
            .body("DescribeSecurityGroupVpcAssociationsResponse.securityGroupVpcAssociationSet.item.state",
                    equalTo("associated"));

        given()
            .formParam("Action", "AssociateSecurityGroupVpc")
            .formParam("GroupId", groupId)
            .formParam("VpcId", vpcA)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));

        given()
            .formParam("Action", "DisassociateSecurityGroupVpc")
            .formParam("GroupId", groupId)
            .formParam("VpcId", vpcB)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DisassociateSecurityGroupVpcResponse.state", equalTo("disassociating"));

        given()
            .formParam("Action", "DescribeSecurityGroupVpcAssociations")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(vpcB)));
    }

    private String createVpc(String cidr) {
        return given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", cidr)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }
}
