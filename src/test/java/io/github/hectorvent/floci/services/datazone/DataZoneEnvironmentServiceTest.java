package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataZoneEnvironmentServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final DataZoneService domains = new DataZoneService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver);
    private final DataZoneEnvironmentService service =
            new DataZoneEnvironmentService(new InMemoryStorage<>(), domains, regionResolver);

    @Test
    void managedBlueprintIdsAreStablePerDomain() {
        String domainId = domains.createDomain(REGION, "d", null, null, null, null, null, null, null).getId();
        String id = service.listEnvironmentBlueprints(REGION, domainId, "DefaultDataWarehouse", null).getFirst()
                .path("id").asText();

        assertEquals(DataZoneEnvironmentService.blueprintId(domainId, "DefaultDataWarehouse"), id);
        assertEquals("DefaultDataWarehouse", service.getEnvironmentBlueprint(REGION, domainId, id).path("name").asText());
        assertTrue(service.listEnvironmentBlueprints(REGION, domainId, null, false).isEmpty());
    }

    @Test
    void environmentNeedsAProfileOrBlueprintAndAProject() throws Exception {
        String domainId = domains.createDomain(REGION, "d2", null, null, null, null, null, null, null).getId();
        String projectId = domains.createProject(REGION, domainId, "p", null, null, null).getId();

        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.createEnvironment(REGION,
                domainId, mapper.readTree("{\"name\": \"e\", \"projectIdentifier\": \"" + projectId + "\"}")))
                .getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> service.createEnvironment(
                REGION, domainId, mapper.readTree("{\"name\": \"e\", \"projectIdentifier\": \"missing\", "
                        + "\"environmentBlueprintIdentifier\": \"x\"}"))).getErrorCode());
        String environmentId = service.createEnvironment(REGION, domainId, mapper.readTree(
                "{\"name\": \"e\", \"projectIdentifier\": \"" + projectId + "\", \"environmentBlueprintIdentifier\": \""
                        + DataZoneEnvironmentService.blueprintId(domainId, "DefaultDataLake") + "\"}"))
                .path("id").asText();
        assertTrue(service.projectHasEnvironments(REGION, domainId, projectId));

        service.deleteDomainResources(REGION, domainId);
        assertThrows(AwsException.class, () -> service.getEnvironment(REGION, domainId, environmentId));
    }
}
