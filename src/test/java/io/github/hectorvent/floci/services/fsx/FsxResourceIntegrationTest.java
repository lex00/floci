package io.github.hectorvent.floci.services.fsx;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class FsxResourceIntegrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/fsx/aws4_request";
    private static final String EC2_AUTH = "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/ec2/aws4_request";
    private static final String TARGET = "AWSSimbaAPIService_v20180301.";

    private static String subnetId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void createNetwork() {
        if (subnetId != null) {
            return;
        }
        String vpcId = given().header("Authorization", EC2_AUTH)
                .formParam("Action", "CreateVpc").formParam("CidrBlock", "10.78.0.0/16")
            .when().post("/").then().statusCode(200).extract().path("CreateVpcResponse.vpc.vpcId");
        subnetId = given().header("Authorization", EC2_AUTH)
                .formParam("Action", "CreateSubnet").formParam("VpcId", vpcId)
                .formParam("CidrBlock", "10.78.1.0/24")
            .when().post("/").then().statusCode(200).extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    private static RequestSpecification call(String action, String body) {
        return given()
                .header("Authorization", AUTH)
                .header("X-Amz-Target", TARGET + action)
                .contentType("application/x-amz-json-1.1")
                .body(body);
    }

    private String fileSystem(String type, String member, String configuration) {
        return call("CreateFileSystem", """
                {"FileSystemType":"%s","StorageCapacity":1200,"SubnetIds":["%s"],"%s":%s}"""
                .formatted(type, subnetId, member, configuration))
        .when().post("/").then().statusCode(200).extract().path("FileSystem.FileSystemId");
    }

    @Test
    void ontapStorageVirtualMachineAndVolumeLifecycle() {
        String fsId = fileSystem("ONTAP", "OntapConfiguration", "{\"DeploymentType\":\"SINGLE_AZ_1\",\"ThroughputCapacity\":128}");
        String svmId = call("CreateStorageVirtualMachine", """
                {"FileSystemId":"%s","Name":"svm1","Tags":[{"Key":"team","Value":"a"}]}""".formatted(fsId))
        .when().post("/").then().statusCode(200)
            .body("StorageVirtualMachine.Lifecycle", equalTo("CREATED"))
            .body("StorageVirtualMachine.FileSystemId", equalTo(fsId))
            .body("StorageVirtualMachine.Endpoints.Nfs.DNSName", notNullValue())
            .body("StorageVirtualMachine.ResourceARN", startsWith("arn:aws:fsx:us-east-1:000000000000:storage-virtual-machine/" + fsId + "/svm-"))
            .extract().path("StorageVirtualMachine.StorageVirtualMachineId");

        call("DescribeVolumes", "{\"Filters\":[{\"Name\":\"storage-virtual-machine-id\",\"Values\":[\"" + svmId + "\"]}]}")
        .when().post("/").then().statusCode(200)
            .body("Volumes", hasSize(1))
            .body("Volumes[0].Name", equalTo("svm1_root"))
            .body("Volumes[0].OntapConfiguration.StorageVirtualMachineRoot", equalTo(true));

        String volumeId = call("CreateVolume", """
                {"VolumeType":"ONTAP","Name":"vol1","OntapConfiguration":{"StorageVirtualMachineId":"%s",
                 "SizeInMegabytes":1024,"JunctionPath":"/vol1"}}""".formatted(svmId))
        .when().post("/").then().statusCode(200)
            .body("Volume.Lifecycle", equalTo("CREATED"))
            .body("Volume.FileSystemId", equalTo(fsId))
            .body("Volume.OntapConfiguration.SizeInMegabytes", equalTo(1024))
            .body("Volume.OntapConfiguration.StorageVirtualMachineRoot", equalTo(false))
            .extract().path("Volume.VolumeId");

        call("UpdateVolume", "{\"VolumeId\":\"" + volumeId + "\",\"OntapConfiguration\":{\"SizeInMegabytes\":2048}}")
        .when().post("/").then().statusCode(200).body("Volume.OntapConfiguration.SizeInMegabytes", equalTo(2048));

        call("DeleteStorageVirtualMachine", "{\"StorageVirtualMachineId\":\"" + svmId + "\"}")
        .when().post("/").then().statusCode(400).body("__type", equalTo("BadRequest"));

        String finalBackup = call("DeleteVolume", "{\"VolumeId\":\"" + volumeId + "\"}")
        .when().post("/").then().statusCode(200)
            .body("Lifecycle", equalTo("DELETING"))
            .extract().path("OntapResponse.FinalBackupId");
        call("DescribeBackups", "{\"BackupIds\":[\"" + finalBackup + "\"]}")
        .when().post("/").then().statusCode(200)
            .body("Backups[0].ResourceType", equalTo("VOLUME"))
            .body("Backups[0].Volume.VolumeId", equalTo(volumeId));

        call("DescribeVolumes", "{\"VolumeIds\":[\"" + volumeId + "\"]}")
        .when().post("/").then().statusCode(400).body("__type", equalTo("VolumeNotFound"));
        call("DeleteStorageVirtualMachine", "{\"StorageVirtualMachineId\":\"" + svmId + "\"}")
        .when().post("/").then().statusCode(200).body("Lifecycle", equalTo("DELETING"));
        call("DescribeStorageVirtualMachines", "{\"StorageVirtualMachineIds\":[\"" + svmId + "\"]}")
        .when().post("/").then().statusCode(400).body("__type", equalTo("StorageVirtualMachineNotFound"));
    }

    @Test
    void openZfsChildVolumesHangOffTheRootVolume() {
        String fsId = fileSystem("OPENZFS", "OpenZFSConfiguration", "{\"DeploymentType\":\"SINGLE_AZ_1\",\"ThroughputCapacity\":64}");
        String rootId = call("DescribeFileSystems", "{\"FileSystemIds\":[\"" + fsId + "\"]}")
        .when().post("/").then().statusCode(200)
            .extract().path("FileSystems[0].OpenZFSConfiguration.RootVolumeId");
        call("DescribeVolumes", "{\"VolumeIds\":[\"" + rootId + "\"]}")
        .when().post("/").then().statusCode(200)
            .body("Volumes[0].OpenZFSConfiguration.VolumePath", equalTo("/fsx"));

        String childId = call("CreateVolume", """
                {"VolumeType":"OPENZFS","Name":"data","OpenZFSConfiguration":{"ParentVolumeId":"%s"},
                 "Tags":[{"Key":"env","Value":"test"}]}""".formatted(rootId))
        .when().post("/").then().statusCode(200)
            .body("Volume.Lifecycle", equalTo("AVAILABLE"))
            .body("Volume.OpenZFSConfiguration.VolumePath", equalTo("/fsx/data"))
            .body("Volume.OpenZFSConfiguration.ParentVolumeId", equalTo(rootId))
            .extract().path("Volume.VolumeId");
        String childArn = call("DescribeVolumes", "{\"VolumeIds\":[\"" + childId + "\"]}")
        .when().post("/").then().statusCode(200).extract().path("Volumes[0].ResourceARN");

        call("TagResource", "{\"ResourceARN\":\"" + childArn + "\",\"Tags\":[{\"Key\":\"owner\",\"Value\":\"me\"}]}")
        .when().post("/").then().statusCode(200);
        call("ListTagsForResource", "{\"ResourceARN\":\"" + childArn + "\"}")
        .when().post("/").then().statusCode(200).body("Tags.Key", hasItem("owner"));

        call("DeleteVolume", "{\"VolumeId\":\"" + rootId + "\"}")
        .when().post("/").then().statusCode(400).body("__type", equalTo("BadRequest"));
        call("DeleteFileSystem", "{\"FileSystemId\":\"" + fsId + "\"}").when().post("/").then().statusCode(200);
        call("DescribeVolumes", "{\"Filters\":[{\"Name\":\"file-system-id\",\"Values\":[\"" + fsId + "\"]}]}")
        .when().post("/").then().statusCode(200).body("Volumes", hasSize(0));
    }

    @Test
    void fileSystemBackupsAreCopiedAndDeleted() {
        String fsId = fileSystem("WINDOWS", "WindowsConfiguration", "{\"ThroughputCapacity\":32}");
        String backupId = call("CreateBackup", "{\"FileSystemId\":\"" + fsId + "\",\"Tags\":[{\"Key\":\"k\",\"Value\":\"v\"}]}")
        .when().post("/").then().statusCode(200)
            .body("Backup.Lifecycle", equalTo("AVAILABLE"))
            .body("Backup.Type", equalTo("USER_INITIATED"))
            .body("Backup.ResourceType", equalTo("FILE_SYSTEM"))
            .body("Backup.FileSystem.FileSystemId", equalTo(fsId))
            .extract().path("Backup.BackupId");

        String copyId = call("CopyBackup", "{\"SourceBackupId\":\"" + backupId + "\",\"CopyTags\":true}")
        .when().post("/").then().statusCode(200)
            .body("Backup.SourceBackupId", equalTo(backupId))
            .body("Backup.Tags.Key", contains("k"))
            .extract().path("Backup.BackupId");

        call("DescribeBackups", "{\"Filters\":[{\"Name\":\"file-system-id\",\"Values\":[\"" + fsId + "\"]}]}")
        .when().post("/").then().statusCode(200).body("Backups.BackupId", hasItem(copyId));

        call("DeleteBackup", "{\"BackupId\":\"" + backupId + "\"}")
        .when().post("/").then().statusCode(200).body("Lifecycle", equalTo("DELETED"));
        call("DescribeBackups", "{\"BackupIds\":[\"" + backupId + "\"]}")
        .when().post("/").then().statusCode(400).body("__type", equalTo("BackupNotFound"));

        String scratch = fileSystem("LUSTRE", "LustreConfiguration", "{\"DeploymentType\":\"SCRATCH_2\"}");
        call("CreateBackup", "{\"FileSystemId\":\"" + scratch + "\"}")
        .when().post("/").then().statusCode(400).body("__type", equalTo("BadRequest"));
    }

    @Test
    void lustreDataRepositoryAssociationLifecycle() {
        String fsId = fileSystem("LUSTRE", "LustreConfiguration", "{\"DeploymentType\":\"PERSISTENT_2\",\"PerUnitStorageThroughput\":125}");
        String draId = call("CreateDataRepositoryAssociation", """
                {"FileSystemId":"%s","FileSystemPath":"/ns1","DataRepositoryPath":"s3://bucket/prefix",
                 "S3":{"AutoImportPolicy":{"Events":["NEW"]}}}""".formatted(fsId))
        .when().post("/").then().statusCode(200)
            .body("Association.Lifecycle", equalTo("AVAILABLE"))
            .body("Association.ImportedFileChunkSize", equalTo(1024))
            .body("Association.ResourceARN", startsWith("arn:aws:fsx:us-east-1:000000000000:association/" + fsId + "/dra-"))
            .extract().path("Association.AssociationId");

        call("CreateDataRepositoryAssociation", """
                {"FileSystemId":"%s","FileSystemPath":"/ns1","DataRepositoryPath":"s3://other"}""".formatted(fsId))
        .when().post("/").then().statusCode(400).body("__type", equalTo("BadRequest"));

        call("UpdateDataRepositoryAssociation", "{\"AssociationId\":\"" + draId + "\",\"ImportedFileChunkSize\":2048}")
        .when().post("/").then().statusCode(200).body("Association.ImportedFileChunkSize", equalTo(2048));
        call("DescribeDataRepositoryAssociations", "{\"Filters\":[{\"Name\":\"file-system-id\",\"Values\":[\"" + fsId + "\"]}]}")
        .when().post("/").then().statusCode(200).body("Associations.AssociationId", contains(draId));

        call("DeleteDataRepositoryAssociation", "{\"AssociationId\":\"" + draId + "\",\"DeleteDataInFileSystem\":true}")
        .when().post("/").then().statusCode(200)
            .body("Lifecycle", equalTo("DELETING"))
            .body("DeleteDataInFileSystem", equalTo(true));
        call("DescribeDataRepositoryAssociations", "{}")
        .when().post("/").then().statusCode(200).body("Associations.AssociationId", not(hasItem(draId)));
    }
}
