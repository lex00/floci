package io.github.hectorvent.floci.services.kendra;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KendraServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final KendraService service = new KendraService(new InMemoryStorage<>(),
            new RegionResolver("us-east-1", "000000000000"), mapper);

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
}
