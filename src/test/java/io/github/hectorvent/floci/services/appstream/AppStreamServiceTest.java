package io.github.hectorvent.floci.services.appstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppStreamServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppStreamService service = new AppStreamService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"));

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void createStackFillsDefaultUserSettingsAndRejectsDuplicates() throws Exception {
        ObjectNode stack = service.createStack(json("{\"Name\":\"s1\",\"Tags\":{\"team\":\"euc\"}}"), REGION);

        assertEquals("arn:aws:appstream:us-east-1:000000000000:stack/s1", stack.path("Arn").asText());
        assertEquals(7, stack.path("UserSettings").size());
        assertFalse(stack.path("ApplicationSettings").path("Enabled").asBoolean());
        assertEquals("euc", service.listTagsForResource(stack.path("Arn").asText()).path("team").asText());
        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createStack(json("{\"Name\":\"s1\"}"), REGION));
        assertEquals("ResourceAlreadyExistsException", duplicate.getErrorCode());
    }

    @Test
    void updateStackClearsNamedAttributes() throws Exception {
        service.createStack(json("{\"Name\":\"s2\",\"RedirectURL\":\"https://example.com\"}"), REGION);

        ObjectNode updated = service.updateStack(
                json("{\"Name\":\"s2\",\"Description\":\"d\",\"AttributesToDelete\":[\"REDIRECT_URL\"]}"), REGION);

        assertEquals("d", updated.path("Description").asText());
        assertFalse(updated.has("RedirectURL"));
    }

    @Test
    void describeStacksByMissingNameIsNotFound() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeStacks(mapper.createArrayNode().add("nope"), REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void fleetStartsStopsAndMustBeStoppedToDelete() throws Exception {
        ObjectNode fleet = service.createFleet(json("""
                {"Name":"f1","InstanceType":"stream.standard.small","ImageName":"img",
                 "ComputeCapacity":{"DesiredInstances":2}}"""), REGION);
        assertEquals("STOPPED", fleet.path("State").asText());

        service.startFleet("f1", REGION);
        ObjectNode running = service.describeFleets(null, REGION).getFirst();
        assertEquals("RUNNING", running.path("State").asText());
        assertEquals(2, running.path("ComputeCapacityStatus").path("Running").asInt());
        assertThrows(AwsException.class, () -> service.deleteFleet("f1", REGION));

        service.stopFleet("f1", REGION);
        service.deleteFleet("f1", REGION);
        assertTrue(service.describeFleets(null, REGION).isEmpty());
    }

    @Test
    void fleetStackAssociationBlocksDeletes() throws Exception {
        service.createStack(json("{\"Name\":\"s3\"}"), REGION);
        service.createFleet(json("{\"Name\":\"f3\",\"InstanceType\":\"stream.standard.small\"}"), REGION);
        service.associateFleet("f3", "s3", REGION);

        assertEquals(List.of("s3"), service.listAssociatedStacks("f3", REGION));
        assertEquals(List.of("f3"), service.listAssociatedFleets("s3", REGION));
        assertThrows(AwsException.class, () -> service.deleteStack("s3", REGION));

        service.disassociateFleet("f3", "s3", REGION);
        service.deleteStack("s3", REGION);
    }

    @Test
    void usersCreateDisableAndDelete() throws Exception {
        JsonNode request = json("{\"UserName\":\"a@example.com\",\"AuthenticationType\":\"USERPOOL\"}");
        service.createUser(request, REGION);
        service.setUserEnabled(request, REGION, false);

        List<ObjectNode> users = service.describeUsers(json("{\"AuthenticationType\":\"USERPOOL\"}"), REGION);
        assertEquals(1, users.size());
        assertFalse(users.getFirst().path("Enabled").asBoolean());

        service.deleteUser(request, REGION);
        AwsException missing = assertThrows(AwsException.class, () -> service.deleteUser(request, REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void imageBuilderStartsRunningAndStops() throws Exception {
        ObjectNode builder = service.createImageBuilder(json("""
                {"Name":"b1","InstanceType":"stream.standard.medium","ImageName":"AppStream-WinServer2022",
                 "Tags":{"team":"euc"}}"""), REGION);

        assertEquals("RUNNING", builder.path("State").asText());
        assertEquals("WINDOWS_SERVER_2022", builder.path("Platform").asText());
        assertEquals("arn:aws:appstream:us-east-1::image/AppStream-WinServer2022", builder.path("ImageArn").asText());
        assertEquals("euc", service.listTagsForResource(builder.path("Arn").asText()).path("team").asText());
        assertEquals("STOPPED", service.setImageBuilderState("b1", REGION, false).path("State").asText());
        assertEquals("DELETING", service.deleteImageBuilder("b1", REGION).path("State").asText());
        assertTrue(service.describeImageBuilders(null, REGION).isEmpty());
    }

    @Test
    void updatedImageIsPrivateAndShareable() throws Exception {
        ObjectNode result = service.createUpdatedImage(json(
                "{\"existingImageName\":\"AppStream-AmazonLinux2\",\"newImageName\":\"mine\"}"), REGION);
        assertEquals("PRIVATE", result.path("image").path("Visibility").asText());
        assertEquals("AMAZON_LINUX2", result.path("image").path("Platform").asText());

        service.updateImagePermissions(json("""
                {"Name":"mine","SharedAccountId":"111111111111","ImagePermissions":{"allowFleet":true}}"""), REGION);
        ObjectNode permissions = service.describeImagePermissions(json("{\"Name\":\"mine\"}"), REGION);
        assertEquals("111111111111",
                permissions.path("SharedImagePermissionsList").get(0).path("sharedAccountId").asText());

        List<ObjectNode> privateImages = service.describeImages(json("{\"Type\":\"PRIVATE\"}"), REGION);
        assertEquals(1, privateImages.size());
        assertEquals("TRUE", privateImages.getFirst().path("ImageSharedWithOthers").asText());
        assertFalse(privateImages.getFirst().has("SharedImagePermissions"));

        AwsException publicDelete = assertThrows(AwsException.class,
                () -> service.deleteImage("AppStream-AmazonLinux2", REGION));
        assertEquals("OperationNotPermittedException", publicDelete.getErrorCode());
        service.deleteImage("mine", REGION);
        assertTrue(service.describeImages(json("{\"Type\":\"PRIVATE\"}"), REGION).isEmpty());
    }

    @Test
    void applicationNeedsAppBlockAndBlocksItsDelete() throws Exception {
        String icon = "\"IconS3Location\":{\"S3Bucket\":\"b\",\"S3Key\":\"icon.png\"}";
        AwsException missingBlock = assertThrows(AwsException.class, () -> service.createApplication(json(
                "{\"Name\":\"a1\",\"LaunchPath\":\"C:\\\\app.exe\",\"AppBlockArn\":\"arn:nope\","
                        + icon + "}"), REGION));
        assertEquals("ResourceNotFoundException", missingBlock.getErrorCode());

        ObjectNode block = service.createAppBlock(json(
                "{\"Name\":\"blk\",\"SourceS3Location\":{\"S3Bucket\":\"b\",\"S3Key\":\"vhd\"}}"), REGION);
        assertEquals("ACTIVE", block.path("State").asText());
        ObjectNode app = service.createApplication(json("{\"Name\":\"a1\",\"LaunchPath\":\"app\","
                + "\"LaunchParameters\":\"-x\",\"AppBlockArn\":\"" + block.path("Arn").asText() + "\"," + icon
                + "}"), REGION);
        assertTrue(app.path("Enabled").asBoolean());

        ObjectNode updated = service.updateApplication(
                json("{\"Name\":\"a1\",\"AttributesToDelete\":[\"LAUNCH_PARAMETERS\"]}"), REGION);
        assertFalse(updated.has("LaunchParameters"));
        AwsException inUse = assertThrows(AwsException.class, () -> service.deleteAppBlock("blk", REGION));
        assertEquals("ResourceInUseException", inUse.getErrorCode());

        service.createFleet(json("{\"Name\":\"ef\",\"InstanceType\":\"stream.standard.small\"}"), REGION);
        service.associateApplicationFleet("ef", app.path("Arn").asText(), REGION);
        assertEquals(1, service.describeApplicationFleetAssociations(json("{\"FleetName\":\"ef\"}"), REGION).size());
        assertThrows(AwsException.class, () -> service.deleteApplication("a1", REGION));
        service.disassociateApplicationFleet("ef", app.path("Arn").asText(), REGION);
        service.deleteApplication("a1", REGION);
        service.deleteAppBlock("blk", REGION);
    }

    @Test
    void entitlementTracksEntitledApplications() throws Exception {
        service.createStack(json("{\"Name\":\"es\"}"), REGION);
        String request = "{\"Name\":\"e1\",\"StackName\":\"es\",\"AppVisibility\":\"ALL\","
                + "\"Attributes\":[{\"Name\":\"roles\",\"Value\":\"admin\"}]}";
        service.createEntitlement(json(request), REGION);
        AwsException duplicate = assertThrows(AwsException.class, () -> service.createEntitlement(json(request), REGION));
        assertEquals("EntitlementAlreadyExistsException", duplicate.getErrorCode());

        service.setEntitledApplication(json(
                "{\"StackName\":\"es\",\"EntitlementName\":\"e1\",\"ApplicationIdentifier\":\"a1\"}"), REGION, true);
        assertEquals("a1", service.listEntitledApplications(
                json("{\"StackName\":\"es\",\"EntitlementName\":\"e1\"}"), REGION)
                .getFirst().path("ApplicationIdentifier").asText());

        ObjectNode updated = service.updateEntitlement(
                json("{\"Name\":\"e1\",\"StackName\":\"es\",\"AppVisibility\":\"ASSOCIATED\"}"), REGION);
        assertEquals("ASSOCIATED", updated.path("AppVisibility").asText());
        assertFalse(updated.has("EntitledApplications"));

        service.deleteEntitlement("e1", "es", REGION);
        AwsException missing = assertThrows(AwsException.class,
                () -> service.describeEntitlements(json("{\"StackName\":\"es\",\"Name\":\"e1\"}"), REGION));
        assertEquals("EntitlementNotFoundException", missing.getErrorCode());
    }
}
