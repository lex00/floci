package io.github.hectorvent.floci.services.fsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.fsx.model.FsxFileSystem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FsxServiceTest {

    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private FsxService service;

    @BeforeEach
    void setUp() {
        Ec2Service ec2 = mock(Ec2Service.class);
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-1");
        subnet.setVpcId("vpc-1");
        when(ec2.describeSubnets(anyString(), anyList(), anyMap())).thenReturn(List.of());
        when(ec2.describeSubnets(eq(REGION), eq(List.of("subnet-1")), anyMap())).thenReturn(List.of(subnet));
        service = new FsxService(new InMemoryStorage<>(), ec2, new RegionResolver(REGION, "000000000000"), mapper);
    }

    @Test
    void lustreFileSystemGetsDefaultsAndDataRepository() {
        FsxFileSystem fs = service.createFileSystem(json("""
                {"FileSystemType":"LUSTRE","StorageCapacity":1200,"SubnetIds":["subnet-1"],
                 "LustreConfiguration":{"ImportPath":"s3://bucket/prefix"}}"""), REGION);

        assertTrue(fs.getFileSystemId().startsWith("fs-"));
        assertEquals("vpc-1", fs.getVpcId());
        assertEquals("AVAILABLE", fs.getLifecycle());
        assertEquals("SCRATCH_1", fs.getConfiguration().path("DeploymentType").asText());
        assertEquals("fsx", fs.getConfiguration().path("MountName").asText());
        assertEquals("s3://bucket/prefix",
                fs.getConfiguration().path("DataRepositoryConfiguration").path("ImportPath").asText());
        assertFalse(fs.getConfiguration().has("ImportPath"));
        assertTrue(fs.getResourceArn().endsWith(":file-system/" + fs.getFileSystemId()));
        assertTrue(fs.getDnsName().startsWith(fs.getFileSystemId() + ".fsx.us-east-1."));
    }

    @Test
    void lustreImportPathMustBeS3() {
        AwsException e = assertThrows(AwsException.class, () -> service.createFileSystem(json("""
                {"FileSystemType":"LUSTRE","SubnetIds":["subnet-1"],
                 "LustreConfiguration":{"ImportPath":"probe-bucket"}}"""), REGION));
        assertEquals("InvalidImportPath", e.getErrorCode());
    }

    @Test
    void typeSpecificRequirementsAreEnforced() {
        assertEquals("MissingFileSystemConfiguration", assertThrows(AwsException.class, () ->
                service.createFileSystem(json("{\"FileSystemType\":\"WINDOWS\",\"SubnetIds\":[\"subnet-1\"]}"),
                        REGION)).getErrorCode());
        assertEquals("BadRequest", assertThrows(AwsException.class, () -> service.createFileSystem(json("""
                {"FileSystemType":"OPENZFS","SubnetIds":["subnet-1"],
                 "OpenZFSConfiguration":{"DeploymentType":"SINGLE_AZ_1"}}"""), REGION)).getErrorCode());
        assertEquals("IncompatibleParameterError", assertThrows(AwsException.class, () ->
                service.createFileSystem(json("""
                        {"FileSystemType":"LUSTRE","SubnetIds":["subnet-1"],
                         "WindowsConfiguration":{"ThroughputCapacity":8}}"""), REGION)).getErrorCode());
        assertEquals("InvalidNetworkSettings", assertThrows(AwsException.class, () ->
                service.createFileSystem(json("{\"FileSystemType\":\"LUSTRE\",\"SubnetIds\":[\"subnet-x\"]}"),
                        REGION)).getErrorCode());
    }

    @Test
    void updateMergesConfigurationAndDeleteRemoves() {
        FsxFileSystem fs = service.createFileSystem(json("""
                {"FileSystemType":"ONTAP","StorageCapacity":1024,"SubnetIds":["subnet-1"],
                 "OntapConfiguration":{"DeploymentType":"SINGLE_AZ_1","ThroughputCapacity":128,
                   "FsxAdminPassword":"secret","RouteTableIds":["rtb-1"]}}"""), REGION);
        assertFalse(fs.getConfiguration().has("FsxAdminPassword"));
        assertTrue(fs.getConfiguration().path("Endpoints").path("Management").has("DNSName"));

        FsxFileSystem updated = service.updateFileSystem(json("""
                {"FileSystemId":"%s","StorageCapacity":2048,
                 "OntapConfiguration":{"ThroughputCapacity":256,"AddRouteTableIds":["rtb-2"],
                   "RemoveRouteTableIds":["rtb-1"]}}""".formatted(fs.getFileSystemId())), REGION);
        assertEquals(2048, updated.getStorageCapacity());
        assertEquals(256, updated.getConfiguration().path("ThroughputCapacity").asInt());
        assertEquals("rtb-2", updated.getConfiguration().path("RouteTableIds").get(0).asText());

        assertEquals("DELETING", service.deleteFileSystem(fs.getFileSystemId(), REGION).getLifecycle());
        assertEquals("FileSystemNotFound", assertThrows(AwsException.class,
                () -> service.getFileSystem(fs.getFileSystemId(), REGION)).getErrorCode());
    }

    @Test
    void tagsFollowTheResourceArn() {
        FsxFileSystem fs = service.createFileSystem(json("""
                {"FileSystemType":"OPENZFS","SubnetIds":["subnet-1"],"Tags":[{"Key":"a","Value":"1"}],
                 "OpenZFSConfiguration":{"DeploymentType":"SINGLE_AZ_1","ThroughputCapacity":64}}"""), REGION);
        assertTrue(fs.getConfiguration().path("RootVolumeId").asText().startsWith("fsvol-"));

        service.tagResource(fs.getResourceArn(), Map.of("b", "2"), REGION);
        service.untagResource(fs.getResourceArn(), List.of("a"), REGION);

        assertEquals(Map.of("b", "2"), service.listTags(fs.getResourceArn(), REGION));
        assertEquals("ResourceNotFound", assertThrows(AwsException.class,
                () -> service.listTags("arn:aws:fsx:us-east-1:000000000000:file-system/fs-missing", REGION))
                .getErrorCode());
    }

    private JsonNode json(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
