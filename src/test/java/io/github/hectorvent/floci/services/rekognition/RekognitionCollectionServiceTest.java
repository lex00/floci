package io.github.hectorvent.floci.services.rekognition;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RekognitionCollectionServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RekognitionCollectionService service = new RekognitionCollectionService(new InMemoryStorage<>(),
            new RegionResolver("us-east-1", "000000000000"), mapper);

    @Test
    void collectionsAreScopedToTheRegion() throws Exception {
        service.createCollection(mapper.readTree("{\"CollectionId\": \"c\"}"), "us-east-1");

        assertEquals(1, service.listCollections(mapper.createObjectNode(), "us-east-1").path("CollectionIds").size());
        assertEquals(0, service.listCollections(mapper.createObjectNode(), "eu-west-1").path("CollectionIds").size());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.describeCollection(mapper.readTree("{\"CollectionId\": \"c\"}"), "eu-west-1"))
                .getErrorCode());
    }

    @Test
    void listCollectionsPages() throws Exception {
        for (String id : new String[] {"a", "b", "c"}) {
            service.createCollection(mapper.readTree("{\"CollectionId\": \"" + id + "\"}"), "us-east-1");
        }
        String token = service.listCollections(mapper.readTree("{\"MaxResults\": 2}"), "us-east-1")
                .path("NextToken").asText();

        assertEquals("c", service.listCollections(
                mapper.readTree("{\"MaxResults\": 2, \"NextToken\": \"" + token + "\"}"), "us-east-1")
                .path("CollectionIds").get(0).asText());
    }
}
