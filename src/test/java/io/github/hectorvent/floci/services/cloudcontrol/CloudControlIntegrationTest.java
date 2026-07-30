package io.github.hectorvent.floci.services.cloudcontrol;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.parsing.Parser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.http.ContentType.TEXT;
import static io.restassured.config.EncoderConfig.encoderConfig;
import static io.restassured.config.RestAssuredConfig.config;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class CloudControlIntegrationTest {

    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";
    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                    + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @BeforeAll
    static void registerAwsJsonParser() {
        RestAssured.registerParser("application/x-amz-json-1.1", Parser.JSON);
        RestAssured.registerParser("application/x-amz-json-1.0", Parser.JSON);
    }

    @Test
    void listResourcesReturnsCreatedS3Ec2AndIamResources() {
        String bucket = "cloudcontrol-test-bucket";
        given().when().put("/" + bucket).then().statusCode(200);

        String vpcId = given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", "10.42.0.0/16")
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateVpcResponse.vpc.vpcId");

        String subnetId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", vpcId)
                .formParam("CidrBlock", "10.42.1.0/24")
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateSubnetResponse.subnet.subnetId");

        String groupId = given()
                .formParam("Action", "CreateSecurityGroup")
                .formParam("GroupName", "cloudcontrol-sg")
                .formParam("GroupDescription", "cloudcontrol sg")
                .formParam("VpcId", vpcId)
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("CreateSecurityGroupResponse.groupId");

        given()
                .formParam("Action", "CreateUser")
                .formParam("UserName", "cloudcontrol-user")
                .header("Authorization", IAM_AUTH)
                .when().post("/")
                .then().statusCode(200);

        given()
                .formParam("Action", "CreateRole")
                .formParam("RoleName", "CloudControlRole")
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .header("Authorization", IAM_AUTH)
                .when().post("/")
                .then().statusCode(200);

        assertListed("AWS::S3::Bucket", bucket, "BucketName");
        assertListed("AWS::EC2::VPC", vpcId, "CidrBlock");
        assertListed("AWS::EC2::Subnet", subnetId, "VpcId");
        assertListed("AWS::EC2::SecurityGroup", groupId, "GroupName");
        assertListed("AWS::EC2::SecurityGroup", groupId, "GroupName", "application/x-amz-json-1.0");
        assertListed("AWS::IAM::User", "cloudcontrol-user", "UserName");
        assertListed("AWS::IAM::Role", "CloudControlRole", "RoleName");
    }

    @Test
    void createResourceProvisionsViaCloudControlAndTokenRoundTrips() throws InterruptedException {
        String ct = "application/x-amz-json-1.0";
        // CreateResource AWS::EC2::VPC — Cloud Control is how Formae drives AWS. It is async:
        // the call returns IN_PROGRESS + a request token immediately.
        String token = given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
                .contentType(ct)
                .header("X-Amz-Target", "CloudApiService.CreateResource")
                .body("{\"TypeName\":\"AWS::EC2::VPC\",\"DesiredState\":\"{\\\"CidrBlock\\\":\\\"10.77.0.0/16\\\"}\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("ProgressEvent.RequestToken");

        // Poll GetResourceRequestStatus until the async provision reaches SUCCESS.
        String identifier = null;
        for (int i = 0; i < 20 && identifier == null; i++) {
            var pe = given()
                    .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
                    .contentType(ct)
                    .header("X-Amz-Target", "CloudApiService.GetResourceRequestStatus")
                    .body("{\"RequestToken\":\"" + token + "\"}")
                    .when().post("/")
                    .then().statusCode(200)
                    .extract();
            if ("SUCCESS".equals(pe.path("ProgressEvent.OperationStatus"))) {
                identifier = pe.path("ProgressEvent.Identifier");
            } else {
                Thread.sleep(100);
            }
        }
        assertThat(identifier, containsString("vpc-"));

        // The created VPC is now visible on the read side.
        assertListed("AWS::EC2::VPC", identifier, "VpcId", ct);
    }

    @Test
    void getResourceReadsBackATypeTheReadSideDoesNotList() throws InterruptedException {
        String ct = "application/x-amz-json-1.0";
        // AWS::EC2::InternetGateway is provisionable but not one of the listed types, so before
        // the create-time record existed this GetResource returned ResourceNotFoundException.
        String token = given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
                .contentType(ct)
                .header("X-Amz-Target", "CloudApiService.CreateResource")
                .body("{\"TypeName\":\"AWS::EC2::InternetGateway\",\"DesiredState\":\"{}\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("ProgressEvent.RequestToken");

        String identifier = awaitIdentifier(token, ct);
        assertThat(identifier, containsString("igw-"));

        String body = given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
                .contentType(ct)
                .header("X-Amz-Target", "CloudApiService.GetResource")
                .body("{\"TypeName\":\"AWS::EC2::InternetGateway\",\"Identifier\":\"" + identifier + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().asString();

        assertThat(body, containsString(identifier));
    }

    @Test
    void deleteResourceReportsFailureWhenItWouldSilentlyNoOp() {
        String ct = "application/x-amz-json-1.0";
        // An inline policy's delete needs the principals recorded at create time. Cloud Control
        // never created this one, so the delete would do nothing — it must not report SUCCESS.
        given()
                .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
                .contentType(ct)
                .header("X-Amz-Target", "CloudApiService.DeleteResource")
                .body("{\"TypeName\":\"AWS::IAM::Policy\",\"Identifier\":\"never-created\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("ProgressEvent.OperationStatus", org.hamcrest.Matchers.equalTo("FAILED"));
    }

    private String awaitIdentifier(String token, String ct) throws InterruptedException {
        for (int i = 0; i < 20; i++) {
            var pe = given()
                    .config(config().encoderConfig(encoderConfig().encodeContentTypeAs(ct, TEXT)))
                    .contentType(ct)
                    .header("X-Amz-Target", "CloudApiService.GetResourceRequestStatus")
                    .body("{\"RequestToken\":\"" + token + "\"}")
                    .when().post("/")
                    .then().statusCode(200)
                    .extract();
            if ("SUCCESS".equals(pe.path("ProgressEvent.OperationStatus"))) {
                return pe.path("ProgressEvent.Identifier");
            }
            Thread.sleep(100);
        }
        return null;
    }

    @Test
    void listResourcesReportsAnInstancesRuntimeModel() {
        // Cloud Control reports a resource's current model, not an echo of the desired state, so
        // an instance has to carry what only the running resource knows — its addresses and the
        // subnet it landed in. A caller that provisions through Cloud Control and reads back has
        // no other route to them.
        String instanceId = given()
                .formParam("Action", "RunInstances")
                .formParam("ImageId", "ami-0abcdef1234567890")
                .formParam("InstanceType", "t3.micro")
                .formParam("MinCount", "1")
                .formParam("MaxCount", "1")
                .header("Authorization", EC2_AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract().path("RunInstancesResponse.instancesSet.item.instanceId");

        assertListed("AWS::EC2::Instance", instanceId, "InstanceType");
    }

    private void assertListed(String typeName, String identifier, String propertyName) {
        assertListed(typeName, identifier, propertyName, "application/x-amz-json-1.1");
    }

    private void assertListed(String typeName, String identifier, String propertyName, String contentType) {
        String body = given()
                .config(config().encoderConfig(
                        encoderConfig().encodeContentTypeAs(contentType, TEXT)))
                .contentType(contentType)
                .header("X-Amz-Target", "CloudApiService.ListResources")
                .body("{\"TypeName\":\"" + typeName + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().asString();

        assertThat(body, containsString("\"TypeName\":\"" + typeName + "\""));
        assertThat(body, containsString("\"Identifier\":\"" + identifier + "\""));
        assertThat(body, containsString(propertyName));
    }
}
