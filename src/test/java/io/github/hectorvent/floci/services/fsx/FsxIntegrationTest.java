package io.github.hectorvent.floci.services.fsx;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class FsxIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/fsx/aws4_request";
    private static final String EC2_AUTH = "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/ec2/aws4_request";
    private static final String TARGET = "AWSSimbaAPIService_v20180301.";

    private static String subnetId;
    private static String vpcId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createNetwork() {
        if (subnetId != null) {
            return;
        }
        vpcId = given().header("Authorization", EC2_AUTH)
                .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.77.0.0/16")
            .when().post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        subnetId = given().header("Authorization", EC2_AUTH)
                .formParam("Action", "CreateSubnet").formParam("VpcId", vpcId)
                .formParam("CidrBlock", "10.77.1.0/24")
            .when().post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    private static RequestSpecification call(String action, String body) {
        return given()
                .header("Authorization", AUTH)
                .header("X-Amz-Target", TARGET + action)
                .contentType("application/x-amz-json-1.1")
                .body(body);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "LUSTRE|LustreConfiguration|{\"DeploymentType\":\"PERSISTENT_2\",\"PerUnitStorageThroughput\":125}",
        "WINDOWS|WindowsConfiguration|{\"ThroughputCapacity\":32,\"DeploymentType\":\"SINGLE_AZ_2\"}",
        "ONTAP|OntapConfiguration|{\"DeploymentType\":\"SINGLE_AZ_1\",\"ThroughputCapacity\":128}",
        "OPENZFS|OpenZFSConfiguration|{\"DeploymentType\":\"SINGLE_AZ_1\",\"ThroughputCapacity\":64}"
    })
    void createDescribeAndDeleteEveryType(String type, String member, String configuration) {
        String id = call("CreateFileSystem", """
                {"FileSystemType":"%s","StorageCapacity":1200,"SubnetIds":["%s"],"%s":%s,
                 "Tags":[{"Key":"Name","Value":"probe"}]}""".formatted(type, subnetId, member, configuration))
        .when().post("/").then().statusCode(200)
            .body("FileSystem.FileSystemType", equalTo(type))
            .body("FileSystem.Lifecycle", equalTo("AVAILABLE"))
            .body("FileSystem.VpcId", equalTo(vpcId))
            .body("FileSystem.CreationTime", notNullValue())
            .body("FileSystem." + member + ".DeploymentType", notNullValue())
            .extract().path("FileSystem.FileSystemId");

        call("DescribeFileSystems", "{\"FileSystemIds\":[\"" + id + "\"]}")
        .when().post("/").then().statusCode(200)
            .body("FileSystems[0].FileSystemId", equalTo(id))
            .body("FileSystems[0].DNSName", startsWith(id + ".fsx.us-east-1."))
            .body("FileSystems[0].ResourceARN", endsWith(":file-system/" + id))
            .body("FileSystems[0].Tags[0].Value", equalTo("probe"));

        call("DeleteFileSystem", "{\"FileSystemId\":\"" + id + "\"}")
        .when().post("/").then().statusCode(200)
            .body("FileSystemId", equalTo(id))
            .body("Lifecycle", equalTo("DELETING"));

        call("DescribeFileSystems", "{\"FileSystemIds\":[\"" + id + "\"]}")
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("FileSystemNotFound"));
    }

    @Test
    void updateTagsAndPagination() {
        String body = """
                {"FileSystemType":"LUSTRE","StorageCapacity":1200,"SubnetIds":["%s"],
                 "LustreConfiguration":{"ImportPath":"s3://probe-fsx-bucket"}}""".formatted(subnetId);
        String arn = call("CreateFileSystem", body).when().post("/").then().statusCode(200)
            .body("FileSystem.LustreConfiguration.DataRepositoryConfiguration.ImportPath",
                    equalTo("s3://probe-fsx-bucket"))
            .extract().path("FileSystem.ResourceARN");
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        call("CreateFileSystem", body).when().post("/").then().statusCode(200);

        call("UpdateFileSystem", """
                {"FileSystemId":"%s","StorageCapacity":2400,
                 "LustreConfiguration":{"AutomaticBackupRetentionDays":3}}""".formatted(id))
        .when().post("/").then().statusCode(200)
            .body("FileSystem.StorageCapacity", equalTo(2400))
            .body("FileSystem.LustreConfiguration.AutomaticBackupRetentionDays", equalTo(3));

        call("TagResource", "{\"ResourceARN\":\"" + arn + "\",\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]}")
        .when().post("/").then().statusCode(200);
        call("ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}")
        .when().post("/").then().statusCode(200)
            .body("Tags.Key", contains("env"));
        call("UntagResource", "{\"ResourceARN\":\"" + arn + "\",\"TagKeys\":[\"env\"]}")
        .when().post("/").then().statusCode(200);

        String token = call("DescribeFileSystems", "{\"MaxResults\":1}")
        .when().post("/").then().statusCode(200)
            .body("FileSystems.size()", equalTo(1))
            .extract().path("NextToken");
        call("DescribeFileSystems", "{\"MaxResults\":100,\"NextToken\":\"" + token + "\"}")
        .when().post("/").then().statusCode(200)
            .body("FileSystems.FileSystemType", hasItem("LUSTRE"));
    }

    @Test
    void invalidImportPathIsRejected() {
        call("CreateFileSystem", """
                {"FileSystemType":"LUSTRE","StorageCapacity":1200,"SubnetIds":["%s"],
                 "LustreConfiguration":{"ImportPath":"probe-fsx-bucket"}}""".formatted(subnetId))
        .when().post("/").then().statusCode(400)
            .body("__type", equalTo("InvalidImportPath"));
    }
}
