package io.github.hectorvent.floci.services.workspaces;

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

class WorkSpacesServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkSpacesService service = new WorkSpacesService(new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void ipGroupRulesAuthorizeRevokeAndReplace() throws Exception {
        String id = service.createIpGroup(json("""
                {"GroupName":"g1","UserRules":[{"ipRule":"10.0.0.0/16","ruleDesc":"office"}]}"""), REGION);
        assertTrue(id.matches("wsipg-[a-z0-9]{9}"));

        service.authorizeIpRules(id, json("[{\"ipRule\":\"10.1.0.0/16\"},{\"ipRule\":\"10.0.0.0/16\"}]"), REGION);
        assertEquals(2, service.describeIpGroups(null, REGION).getFirst().path("userRules").size());

        service.revokeIpRules(id, json("[\"10.0.0.0/16\"]"), REGION);
        service.updateRulesOfIpGroup(id, json("[{\"ipRule\":\"192.168.0.0/24\"}]"), REGION);
        ObjectNode group = service.describeIpGroups(mapper.createArrayNode().add(id), REGION).getFirst();
        assertEquals("192.168.0.0/24", group.path("userRules").get(0).path("ipRule").asText());

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createIpGroup(json("{\"GroupName\":\"g1\"}"), REGION));
        assertEquals("ResourceAlreadyExistsException", duplicate.getErrorCode());
    }

    @Test
    void workspacesArePendingOnCreateAndAvailableOnDescribe() throws Exception {
        List<List<ObjectNode>> outcome = service.createWorkspaces(json("""
                [{"DirectoryId":"d-1234567890","UserName":"alice","BundleId":"wsb-123456789",
                  "Tags":[{"Key":"team","Value":"euc"}]},
                 {"DirectoryId":"d-1234567890","UserName":"alice","BundleId":"wsb-123456789"}]"""), REGION);

        assertEquals(1, outcome.get(0).size());
        assertEquals(1, outcome.get(1).size());
        ObjectNode pending = outcome.get(0).getFirst();
        assertEquals("PENDING", pending.path("State").asText());
        String id = pending.path("WorkspaceId").asText();

        ObjectNode described = service.describeWorkspaces(json("{\"WorkspaceIds\":[\"" + id + "\"]}"), REGION)
                .getFirst();
        assertEquals("AVAILABLE", described.path("State").asText());
        assertEquals("euc", service.describeTags(id, REGION).getFirst().path("Value").asText());

        assertTrue(service.terminateWorkspaces(json("[{\"WorkspaceId\":\"" + id + "\"}]"), REGION).isEmpty());
        assertTrue(service.describeWorkspaces(json("{}"), REGION).isEmpty());
    }

    @Test
    void poolStartsStoppedAndFiltersByName() throws Exception {
        ObjectNode pool = service.createWorkspacesPool(json("""
                {"PoolName":"pool1","Description":"d","BundleId":"wsb-1","DirectoryId":"wsd-1",
                 "Capacity":{"DesiredUserSessions":3}}"""), REGION);
        assertEquals("STOPPED", pool.path("State").asText());
        assertEquals(3, pool.path("CapacityStatus").path("DesiredUserSessions").asInt());

        service.setPoolRunning(pool.path("PoolId").asText(), REGION, true);
        List<ObjectNode> found = service.describeWorkspacesPools(json("""
                {"Filters":[{"Name":"PoolName","Values":["pool1"],"Operator":"EQUALS"}]}"""), REGION);
        assertEquals("RUNNING", found.getFirst().path("State").asText());
        assertTrue(service.describeWorkspacesPools(json("""
                {"Filters":[{"Name":"PoolName","Values":["other"],"Operator":"EQUALS"}]}"""), REGION).isEmpty());
    }

    @Test
    void tagsOnUnknownResourceAreNotFound() {
        AwsException missing = assertThrows(AwsException.class, () -> service.describeTags("wsipg-unknown", REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void directoryRegistersWithDefaultsAndTakesModifications() throws Exception {
        ObjectNode registered = service.registerWorkspaceDirectory(json(
                "{\"DirectoryId\":\"d-1234567890\",\"SubnetIds\":[\"subnet-a\",\"subnet-b\"]}"), REGION);
        assertEquals("REGISTERED", registered.path("State").asText());

        service.modifyDirectoryProperty("d-1234567890", "SamlProperties", json("""
                {"Status":"ENABLED","UserAccessUrl":"https://idp.example.com"}"""), null, REGION);
        service.modifyDirectoryProperty("d-1234567890", "SamlProperties", null,
                json("[\"SAML_PROPERTIES_USER_ACCESS_URL\"]"), REGION);
        String groupId = service.createIpGroup(json("{\"GroupName\":\"dir-group\"}"), REGION);
        service.setIpGroups("d-1234567890", json("[\"" + groupId + "\"]"), REGION, true);

        ObjectNode directory = service.describeWorkspaceDirectories(json("{}"), REGION).getFirst();
        assertEquals("ENABLED", directory.path("SamlProperties").path("Status").asText());
        assertFalse(directory.path("SamlProperties").has("UserAccessUrl"));
        assertEquals(groupId, directory.path("ipGroupIds").get(0).asText());
        assertEquals("ALLOW", directory.path("WorkspaceAccessProperties").path("DeviceTypeWeb").asText());

        service.deregisterWorkspaceDirectory("d-1234567890", REGION);
        assertTrue(service.describeWorkspaceDirectories(json("{}"), REGION).isEmpty());
    }

    @Test
    void poolsDirectoryGetsGeneratedId() throws Exception {
        ObjectNode registered = service.registerWorkspaceDirectory(json("""
                {"WorkspaceType":"POOLS","UserIdentityType":"CUSTOMER_MANAGED","WorkspaceDirectoryName":"pool-dir"}"""),
                REGION);
        assertTrue(registered.path("DirectoryId").asText().startsWith("wsd-"));
        assertEquals(1, service.describeWorkspaceDirectories(json("""
                {"Filters":[{"Name":"WORKSPACE_TYPE","Values":["POOLS"]}]}"""), REGION).size());
    }

    @Test
    void customBundleLifecycle() throws Exception {
        ObjectNode bundle = service.createWorkspaceBundle(json("""
                {"BundleName":"mine","BundleDescription":"d","ImageId":"wsi-abc",
                 "ComputeType":{"Name":"STANDARD"},"UserStorage":{"Capacity":"50"}}"""), REGION);
        String id = bundle.path("BundleId").asText();
        assertTrue(id.startsWith("wsb-"));
        assertEquals("000000000000", bundle.path("Owner").asText());

        service.updateWorkspaceBundle(json("{\"BundleId\":\"" + id + "\",\"ImageId\":\"wsi-new\"}"), REGION);
        assertEquals("wsi-new", service.describeWorkspaceBundles(json("{}"), REGION).getFirst()
                .path("ImageId").asText());
        assertFalse(service.describeWorkspaceBundles(json("{\"Owner\":\"AMAZON\"}"), REGION).isEmpty());

        service.deleteWorkspaceBundle(id, REGION);
        AwsException missing = assertThrows(AwsException.class, () -> service.deleteWorkspaceBundle(id, REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }
}
