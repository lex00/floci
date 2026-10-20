package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class Ec2ClientVpnIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String CERT = "arn:aws:acm:us-east-1:000000000000:certificate/probe";

    @Test
    void endpointLifecycleWithTargetNetworkAndAuthorizationRule() {
        String endpointId = given()
            .formParam("Action", "CreateClientVpnEndpoint")
            .formParam("ClientCidrBlock", "10.100.0.0/16")
            .formParam("ServerCertificateArn", CERT)
            .formParam("Authentication.1.Type", "certificate-authentication")
            .formParam("Authentication.1.MutualAuthentication.ClientRootCertificateChainArn", CERT)
            .formParam("ConnectionLogOptions.Enabled", "false")
            .formParam("Description", "probe")
            .formParam("TagSpecification.1.ResourceType", "client-vpn-endpoint")
            .formParam("TagSpecification.1.Tag.1.Key", "Name")
            .formParam("TagSpecification.1.Tag.1.Value", "app")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateClientVpnEndpointResponse.status.code", equalTo("pending-associate"))
            .body("CreateClientVpnEndpointResponse.dnsName", startsWith("*.cvpn-endpoint-"))
            .extract().path("CreateClientVpnEndpointResponse.clientVpnEndpointId");

        String item = "DescribeClientVpnEndpointsResponse.clientVpnEndpoint.item";
        given()
            .formParam("Action", "DescribeClientVpnEndpoints")
            .formParam("ClientVpnEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(item + ".clientCidrBlock", equalTo("10.100.0.0/16"))
            .body(item + ".vpnPort", equalTo("443"))
            .body(item + ".transportProtocol", equalTo("udp"))
            .body(item + ".authenticationOptions.item.type", equalTo("certificate-authentication"))
            .body(item + ".authenticationOptions.item.mutualAuthentication.clientRootCertificateChain",
                    equalTo(CERT))
            .body(item + ".connectionLogOptions.Enabled", equalTo("false"))
            .body(item + ".tagSet.item.value", equalTo("app"));

        given()
            .formParam("Action", "ModifyClientVpnEndpoint")
            .formParam("ClientVpnEndpointId", endpointId)
            .formParam("Description", "changed")
            .formParam("SplitTunnel", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        String vpcId = given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.60.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
        String subnetId = given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", "10.60.1.0/24")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");

        String associationId = given()
            .formParam("Action", "AssociateClientVpnTargetNetwork")
            .formParam("ClientVpnEndpointId", endpointId)
            .formParam("SubnetId", subnetId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AssociateClientVpnTargetNetworkResponse.status.code", equalTo("associated"))
            .extract().path("AssociateClientVpnTargetNetworkResponse.associationId");

        given()
            .formParam("Action", "DescribeClientVpnEndpoints")
            .formParam("ClientVpnEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(item + ".status.code", equalTo("available"))
            .body(item + ".description", equalTo("changed"))
            .body(item + ".splitTunnel", equalTo("true"))
            .body(item + ".vpcId", equalTo(vpcId));

        given()
            .formParam("Action", "AuthorizeClientVpnIngress")
            .formParam("ClientVpnEndpointId", endpointId)
            .formParam("TargetNetworkCidr", "10.60.0.0/16")
            .formParam("AuthorizeAllGroups", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("AuthorizeClientVpnIngressResponse.status.code", equalTo("active"));

        given()
            .formParam("Action", "DescribeClientVpnAuthorizationRules")
            .formParam("ClientVpnEndpointId", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeClientVpnAuthorizationRulesResponse.authorizationRule.item.destinationCidr",
                    equalTo("10.60.0.0/16"))
            .body("DescribeClientVpnAuthorizationRulesResponse.authorizationRule.item.accessAll",
                    equalTo("true"));

        given()
            .formParam("Action", "DeleteClientVpnEndpoint")
            .formParam("ClientVpnEndpointId", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400);

        given()
            .formParam("Action", "RevokeClientVpnIngress")
            .formParam("ClientVpnEndpointId", endpointId)
            .formParam("TargetNetworkCidr", "10.60.0.0/16")
            .formParam("RevokeAllGroups", "true")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DisassociateClientVpnTargetNetwork")
            .formParam("ClientVpnEndpointId", endpointId)
            .formParam("AssociationId", associationId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DeleteClientVpnEndpoint")
            .formParam("ClientVpnEndpointId", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeleteClientVpnEndpointResponse.status.code", equalTo("deleting"));

        given()
            .formParam("Action", "DescribeClientVpnEndpoints")
            .formParam("ClientVpnEndpointId.1", endpointId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidClientVpnEndpointId.NotFound</Code>"));
    }
}
