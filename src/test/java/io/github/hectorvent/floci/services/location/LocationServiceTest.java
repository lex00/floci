package io.github.hectorvent.floci.services.location;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocationServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LocationService service = new LocationService(new InMemoryStorage<>(),
            new RegionResolver("us-east-1", "000000000000"), mapper);

    @Test
    void sameNameIsAllowedAcrossTypesAndRegions() throws Exception {
        service.create(LocationService.TRACKER, mapper.readTree("{\"TrackerName\": \"x\"}"), "us-east-1");
        service.create(LocationService.GEOFENCE_COLLECTION, mapper.readTree("{\"CollectionName\": \"x\"}"), "us-east-1");
        service.create(LocationService.TRACKER, mapper.readTree("{\"TrackerName\": \"x\"}"), "eu-west-1");

        assertEquals(1, service.list(LocationService.TRACKER, mapper.createObjectNode(), "us-east-1")
                .get("Entries").size());
    }

    @Test
    void createValidatesNameAndPricingPlan() throws Exception {
        assertThrows(AwsException.class, () -> service.create(LocationService.TRACKER,
                mapper.readTree("{\"TrackerName\": \"bad name\"}"), "us-east-1"));
        assertThrows(AwsException.class, () -> service.create(LocationService.TRACKER,
                mapper.readTree("{\"TrackerName\": \"t\", \"PricingPlan\": \"Free\"}"), "us-east-1"));
    }

    @Test
    void tagsAreAddressedByArn() throws Exception {
        String arn = service.create(LocationService.MAP,
                mapper.readTree("{\"MapName\": \"m\", \"Configuration\": {\"Style\": \"VectorEsriStreets\"}}"),
                "us-east-1").get("MapArn").asText();

        service.tagResource(arn, Map.of("a", "1", "b", "2"));
        service.untagResource(arn, List.of("a"));

        assertEquals(Map.of("b", "2"), service.listTags(arn));
        assertEquals("Grab", LocationService.mapDataSource("VectorGrabStandardLight"));
    }
}
