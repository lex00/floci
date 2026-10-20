package io.github.hectorvent.floci.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KendraServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final KendraService service = new KendraService(new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver("us-east-1", "000000000000"), mapper);

    private String create(String name) throws Exception {
        return service.createIndex(mapper.readTree("""
                {"Name": "%s", "RoleArn": "arn:aws:iam::000000000000:role/r"}
                """.formatted(name)), "us-east-1").get("Id").asText();
    }

    @Test
    void createDefaultsToEnterpriseEditionAndUpdateChangesTheName() throws Exception {
        String id = create("idx");

        assertEquals("ENTERPRISE_EDITION",
                service.describeIndex(mapper.readTree("{\"Id\": \"" + id + "\"}"), "us-east-1").get("Edition").asText());
        service.updateIndex(mapper.readTree("{\"Id\": \"" + id + "\", \"Name\": \"renamed\"}"), "us-east-1");
        assertEquals("renamed",
                service.describeIndex(mapper.readTree("{\"Id\": \"" + id + "\"}"), "us-east-1").get("Name").asText());
    }

    @Test
    void indexesAreScopedToTheRegion() throws Exception {
        String id = create("idx");

        assertEquals(0, service.listIndices(mapper.createObjectNode(), "eu-west-1")
                .get("IndexConfigurationSummaryItems").size());
        assertThrows(AwsException.class,
                () -> service.describeIndex(mapper.readTree("{\"Id\": \"" + id + "\"}"), "eu-west-1"));
    }

    @Test
    void tagsOnAMissingIndexAreUnavailable() throws Exception {
        AwsException missing = assertThrows(AwsException.class, () -> service.listTags(
                mapper.readTree("{\"ResourceARN\": \"arn:aws:kendra:us-east-1:000000000000:index/x\"}"), "us-east-1"));
        assertEquals("ResourceUnavailableException", missing.getErrorCode());
    }

    @Test
    void dataSourceIsActiveAndRecordsSyncJobs() throws Exception {
        String indexId = create("idx");
        String id = service.createDataSource(mapper.readTree("""
                {"IndexId": "%s", "Name": "custom", "Type": "CUSTOM"}
                """.formatted(indexId)), "us-east-1").get("Id").asText();
        String request = "{\"IndexId\": \"" + indexId + "\", \"Id\": \"" + id + "\"}";

        assertEquals("ACTIVE", service.describeDataSource(mapper.readTree(request), "us-east-1").get("Status").asText());
        service.startDataSourceSyncJob(mapper.readTree(request), "us-east-1");
        assertEquals("SUCCEEDED", service.listDataSourceSyncJobs(mapper.readTree(request), "us-east-1")
                .get("History").get(0).get("Status").asText());

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createDataSource(mapper.readTree("""
                {"IndexId": "%s", "Name": "custom", "Type": "CUSTOM"}
                """.formatted(indexId)), "us-east-1"));
        assertEquals("ResourceAlreadyExistException", duplicate.getErrorCode());
        AwsException noRole = assertThrows(AwsException.class, () -> service.createDataSource(mapper.readTree("""
                {"IndexId": "%s", "Name": "s3", "Type": "S3"}
                """.formatted(indexId)), "us-east-1"));
        assertEquals("ValidationException", noRole.getErrorCode());
    }

    @Test
    void queryRanksDocumentsByTermFrequency() throws Exception {
        String indexId = create("idx");
        String once = Base64.getEncoder().encodeToString("Floci runs one emulator.".getBytes());
        String twice = Base64.getEncoder().encodeToString("The emulator speaks every emulator protocol.".getBytes());
        service.batchPutDocument(mapper.readTree("""
                {"IndexId": "%s", "Documents": [
                  {"Id": "a", "Title": "A", "Blob": "%s", "ContentType": "PLAIN_TEXT",
                   "Attributes": [{"Key": "team", "Value": {"StringValue": "core"}}]},
                  {"Id": "b", "Title": "B", "Blob": "%s", "ContentType": "PLAIN_TEXT"},
                  {"Id": "c", "Title": "C", "Blob": "%s", "ContentType": "PLAIN_TEXT"}]}
                """.formatted(indexId, once, twice, Base64.getEncoder().encodeToString("unrelated".getBytes()))),
                "us-east-1");

        JsonNode result = service.query(mapper.readTree("""
                {"IndexId": "%s", "QueryText": "emulator"}
                """.formatted(indexId)), "us-east-1");
        assertEquals(2, result.get("TotalNumberOfResults").asInt());
        assertEquals("b", result.get("ResultItems").get(0).get("DocumentId").asText());

        JsonNode filtered = service.query(mapper.readTree("""
                {"IndexId": "%s", "QueryText": "emulator",
                 "AttributeFilter": {"EqualsTo": {"Key": "team", "Value": {"StringValue": "core"}}}}
                """.formatted(indexId)), "us-east-1");
        assertEquals(1, filtered.get("TotalNumberOfResults").asInt());
        assertEquals(3, service.describeIndex(mapper.readTree("{\"Id\": \"" + indexId + "\"}"), "us-east-1")
                .get("IndexStatistics").get("TextDocumentStatistics").get("IndexedTextDocumentsCount").asInt());

        service.batchDeleteDocument(mapper.readTree("""
                {"IndexId": "%s", "DocumentIdList": ["b"]}
                """.formatted(indexId)), "us-east-1");
        assertEquals("a", service.retrieve(mapper.readTree("""
                {"IndexId": "%s", "QueryText": "emulator"}
                """.formatted(indexId)), "us-east-1").get("ResultItems").get(0).get("DocumentId").asText());
    }
}
