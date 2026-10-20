package io.github.hectorvent.floci.services.appflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.appflow.model.ConnectorProfile;
import io.github.hectorvent.floci.services.appflow.model.Flow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppFlowServiceTest {

    private static final String REGION = "us-east-1";

    private final ObjectMapper mapper = new ObjectMapper();
    private AppFlowService service;

    @BeforeEach
    void setUp() {
        AccountAwareStorageBackend<ConnectorProfile> profiles = AccountAwareStorageBackend.inMemory("000000000000");
        AccountAwareStorageBackend<Flow> flows = AccountAwareStorageBackend.inMemory("000000000000");
        service = new AppFlowService(profiles, flows, AccountAwareStorageBackend.inMemory("000000000000"),
                AccountAwareStorageBackend.inMemory("000000000000"));
    }

    @Test
    void createConnectorProfileKeepsPropertiesAndDropsCredentials() throws Exception {
        ConnectorProfile profile = service.createConnectorProfile(profileRequest("sales"), REGION);

        assertEquals("arn:aws:appflow:us-east-1:000000000000:connectorprofile/sales",
                profile.getConnectorProfileArn());
        assertEquals("https://example.my.salesforce.com", profile.getConnectorProfileProperties()
                .path("Salesforce").path("instanceUrl").asText());
        assertTrue(profile.getCredentialsArn().startsWith(
                "arn:aws:secretsmanager:us-east-1:000000000000:secret:appflow!000000000000-sales-"));
        assertFalse(mapper.writeValueAsString(profile).contains("secret-token"));
    }

    @Test
    void createConnectorProfileRejectsDuplicatesAndBadEnums() throws Exception {
        service.createConnectorProfile(profileRequest("sales"), REGION);

        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.createConnectorProfile(profileRequest("sales"), REGION));
        assertEquals("ConflictException", duplicate.getErrorCode());

        JsonNode badMode = mapper.readTree("""
                {"connectorProfileName":"x","connectorType":"Salesforce","connectionMode":"Hybrid",
                 "connectorProfileConfig":{"connectorProfileProperties":{}}}""");
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createConnectorProfile(badMode, REGION)).getErrorCode());
    }

    @Test
    void describeFiltersByNameAndType() throws Exception {
        service.createConnectorProfile(profileRequest("sales"), REGION);
        service.createConnectorProfile(mapper.readTree("""
                {"connectorProfileName":"chat","connectorType":"Slack","connectionMode":"Public",
                 "connectorProfileConfig":{"connectorProfileProperties":{"Slack":{"instanceUrl":"https://x.slack.com"}}}}"""),
                REGION);

        assertEquals(2, service.describeConnectorProfiles(REGION, List.of(), null, null).size());
        assertEquals("chat", service.describeConnectorProfiles(REGION, List.of(), "Slack", null)
                .getFirst().getConnectorProfileName());
        assertEquals(1, service.describeConnectorProfiles(REGION, List.of("sales"), null, null).size());
        assertTrue(service.describeConnectorProfiles("eu-west-1", List.of(), null, null).isEmpty());
    }

    @Test
    void deleteConnectorProfileInUseNeedsForce() throws Exception {
        service.createConnectorProfile(profileRequest("sales"), REGION);
        service.createFlow(flowRequest("sync", "OnDemand", "sales"), REGION);

        AwsException inUse = assertThrows(AwsException.class,
                () -> service.deleteConnectorProfile("sales", false, REGION));
        assertEquals("ConflictException", inUse.getErrorCode());

        service.deleteConnectorProfile("sales", true, REGION);
        assertTrue(service.describeConnectorProfiles(REGION, List.of("sales"), null, null).isEmpty());
    }

    @Test
    void createFlowRequiresNamedProfilesToExist() throws Exception {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.createFlow(flowRequest("sync", "OnDemand", "absent"), REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void scheduledFlowMovesFromDraftToActiveToSuspended() throws Exception {
        service.createConnectorProfile(profileRequest("sales"), REGION);
        Flow flow = service.createFlow(flowRequest("nightly", "Scheduled", "sales"), REGION);
        assertEquals("Draft", flow.getFlowStatus());

        AppFlowService.FlowRun run = service.startFlow("nightly", REGION);
        assertEquals("Active", run.flow().getFlowStatus());
        assertNull(run.executionId());

        assertEquals("Suspended", service.stopFlow("nightly", REGION).getFlowStatus());
    }

    @Test
    void onDemandFlowRecordsARun() throws Exception {
        service.createConnectorProfile(profileRequest("sales"), REGION);
        Flow flow = service.createFlow(flowRequest("sync", "OnDemand", "sales"), REGION);
        assertEquals("Active", flow.getFlowStatus());

        AppFlowService.FlowRun run = service.startFlow("sync", REGION);
        assertNotNull(run.executionId());
        assertEquals("Successful", service.describeFlow("sync", REGION).getLastRunStatus());
        assertEquals("UnsupportedOperationException", assertThrows(AwsException.class,
                () -> service.stopFlow("sync", REGION)).getErrorCode());
    }

    @Test
    void updateFlowBumpsSchemaVersionWhenTasksChange() throws Exception {
        service.createConnectorProfile(profileRequest("sales"), REGION);
        service.createFlow(flowRequest("sync", "OnDemand", "sales"), REGION);

        JsonNode update = flowRequest("sync", "OnDemand", "sales");
        ((ObjectNode) update.get("tasks").get(0))
                .put("destinationField", "Renamed");
        Flow updated = service.updateFlow(update, REGION);

        assertEquals(2, updated.getSchemaVersion());
    }

    @Test
    void tagsApplyToFlowsByArn() throws Exception {
        service.createConnectorProfile(profileRequest("sales"), REGION);
        Flow flow = service.createFlow(flowRequest("sync", "OnDemand", "sales"), REGION);
        String arn = flow.getFlowArn();

        service.tagResource(REGION, arn, Map.of("team", "data"));
        service.untagResource(REGION, arn, List.of("env"));
        assertEquals(Map.of("team", "data"), service.listTags(REGION, arn));

        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listTags(REGION, arn + "-missing")).getErrorCode());
    }

    private JsonNode profileRequest(String name) throws Exception {
        return mapper.readTree("""
                {"connectorProfileName":"%s","connectorType":"Salesforce","connectionMode":"Public",
                 "connectorProfileConfig":{
                   "connectorProfileProperties":{"Salesforce":{"instanceUrl":"https://example.my.salesforce.com"}},
                   "connectorProfileCredentials":{"Salesforce":{"accessToken":"secret-token"}}}}""".formatted(name));
    }

    private JsonNode flowRequest(String name, String triggerType, String profile) throws Exception {
        return mapper.readTree("""
                {"flowName":"%s","triggerConfig":{"triggerType":"%s"},
                 "sourceFlowConfig":{"connectorType":"Salesforce","connectorProfileName":"%s",
                   "sourceConnectorProperties":{"Salesforce":{"object":"Account"}}},
                 "destinationFlowConfigList":[{"connectorType":"S3",
                   "destinationConnectorProperties":{"S3":{"bucketName":"out"}}}],
                 "tasks":[{"taskType":"Map","sourceFields":["Id"],"destinationField":"Id"}],
                 "tags":{"env":"test"}}""".formatted(name, triggerType, profile));
    }

    @Test
    void customConnectorRegistersAndBlocksUnregisterWhileUsed() throws Exception {
        String arn = service.registerConnector(mapper.readTree("""
                {"connectorLabel": "crm", "connectorProvisioningType": "LAMBDA",
                 "connectorProvisioningConfig": {"lambda": {"lambdaArn": "arn:aws:lambda:us-east-1:000000000000:function:crm"}}}
                """), REGION);
        assertEquals("arn:aws:appflow:us-east-1:000000000000:connector/crm", arn);
        assertEquals("crm", service.describeConnector("CustomConnector", "crm", REGION)
                .path("connectorLabel").asText());
        assertEquals(1, service.listConnectors(REGION).size());

        service.createConnectorProfile(mapper.readTree("""
                {"connectorProfileName": "crm-profile", "connectorType": "CustomConnector", "connectorLabel": "crm",
                 "connectionMode": "Public", "connectorProfileConfig": {"connectorProfileProperties": {}}}
                """), REGION);
        AwsException conflict = assertThrows(AwsException.class, () -> service.unregisterConnector("crm", false, REGION));
        assertEquals("ConflictException", conflict.getErrorCode());
        service.unregisterConnector("crm", true, REGION);
        assertTrue(service.listConnectors(REGION).isEmpty());
    }

    @Test
    void builtInConnectorConfigurationReportsDirections() {
        assertTrue(service.describeConnector("S3", null, REGION).path("canUseAsDestination").asBoolean());
        assertTrue(service.describeConnectors(null).containsKey("Salesforce"));
    }
}
