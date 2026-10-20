package io.github.hectorvent.floci.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QBusinessResourceServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final QBusinessService applications = new QBusinessService(new InMemoryStorage<>(), regionResolver, mapper);
    private final QBusinessResourceService service = new QBusinessResourceService(new InMemoryStorage<>(),
            new InMemoryStorage<>(), applications, regionResolver, mapper);

    private String application() throws Exception {
        return applications.createApplication(mapper.readTree("{\"displayName\": \"app\"}"), REGION)
                .get("applicationId").asText();
    }

    @Test
    void indexAndDataSourceLifecycle() throws Exception {
        String app = application();
        JsonNode index = service.create(QBusinessResourceService.INDEX, app, null,
                mapper.readTree("{\"displayName\": \"idx\", \"tags\": [{\"key\": \"team\", \"value\": \"it\"}]}"), REGION);
        String indexId = index.get("indexId").asText();
        assertEquals("arn:aws:qbusiness:us-east-1:000000000000:application/" + app + "/index/" + indexId,
                index.get("indexArn").asText());
        assertEquals(Map.of("team", "it"), service.listTags(index.get("indexArn").asText()));
        assertEquals("ACTIVE", service.get(QBusinessResourceService.INDEX, app, null, indexId, REGION)
                .get("status").asText());

        JsonNode dataSource = service.create(QBusinessResourceService.DATA_SOURCE, app, indexId, mapper.readTree("""
                {"displayName": "docs", "configuration": {"type": "S3", "connectionConfiguration": {}}}"""), REGION);
        String dataSourceId = dataSource.get("dataSourceId").asText();
        assertEquals("S3", service.get(QBusinessResourceService.DATA_SOURCE, app, indexId, dataSourceId, REGION)
                .get("type").asText());
        service.startSync(app, indexId, dataSourceId, REGION);
        assertEquals("SUCCEEDED", service.listSyncJobs(app, indexId, dataSourceId, null, null, null, REGION)
                .get("history").get(0).get("status").asText());

        service.delete(QBusinessResourceService.INDEX, app, null, indexId, REGION);
        AwsException gone = assertThrows(AwsException.class, () -> service.get(QBusinessResourceService.DATA_SOURCE,
                app, indexId, dataSourceId, REGION));
        assertEquals("ResourceNotFoundException", gone.getErrorCode());
    }

    @Test
    void createNeedsRequiredMembersAndAnApplication() throws Exception {
        String app = application();
        assertThrows(AwsException.class, () -> service.create(QBusinessResourceService.RETRIEVER, app, null,
                mapper.readTree("{\"displayName\": \"r\"}"), REGION));
        AwsException missing = assertThrows(AwsException.class, () -> service.create(QBusinessResourceService.INDEX,
                "nope", null, mapper.readTree("{\"displayName\": \"i\"}"), REGION));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }

    @Test
    void chatControlsAndPolicy() throws Exception {
        String app = application();
        assertEquals("ENTERPRISE_CONTENT_ONLY", service.getChatControls(app, REGION).get("responseScope").asText());
        service.updateChatControls(app, mapper.readTree("""
                {"responseScope": "EXTENDED_KNOWLEDGE_ENABLED",
                 "blockedPhrasesConfigurationUpdate": {"blockedPhrasesToCreateOrUpdate": ["secret"]},
                 "topicConfigurationsToCreateOrUpdate": [{"name": "hr", "rules": []}]}"""), REGION);
        JsonNode controls = service.getChatControls(app, REGION);
        assertEquals("secret", controls.get("blockedPhrases").get("blockedPhrases").get(0).asText());
        assertEquals("hr", controls.get("topicConfigurations").get(0).get("name").asText());

        service.associatePermission(app, mapper.readTree("""
                {"statementId": "s1", "actions": ["qbusiness:SearchRelevantContent"],
                 "principal": "arn:aws:iam::111111111111:role/r"}"""), REGION);
        assertTrue(service.getPolicy(app, REGION).get("policy").asText().contains("\"Sid\":\"s1\""));
        assertThrows(AwsException.class, () -> service.associatePermission(app, mapper.readTree("""
                {"statementId": "s1", "actions": ["qbusiness:SearchRelevantContent"],
                 "principal": "arn:aws:iam::111111111111:role/r"}"""), REGION));
        service.disassociatePermission(app, "s1", REGION);
        assertThrows(AwsException.class, () -> service.disassociatePermission(app, "s1", REGION));
    }
}
