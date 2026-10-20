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
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
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
}
