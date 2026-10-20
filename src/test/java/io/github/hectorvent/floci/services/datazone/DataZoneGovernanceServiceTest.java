package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataZoneGovernanceServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final DataZoneService domains = new DataZoneService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver);
    private final DataZoneEnvironmentService environments =
            new DataZoneEnvironmentService(new InMemoryStorage<>(), domains, regionResolver);
    private final DataZoneGovernanceService service =
            new DataZoneGovernanceService(new InMemoryStorage<>(), domains, environments, regionResolver);

    @Test
    void policyTypesMapToTheirDetailMembers() {
        assertEquals("createDomainUnit", DataZoneGovernanceService.detailMember("CREATE_DOMAIN_UNIT"));
        assertEquals("createEnvironmentFromBlueprint",
                DataZoneGovernanceService.detailMember("CREATE_ENVIRONMENT_FROM_BLUEPRINT"));
    }

    @Test
    void domainUnitWithAProjectStays() throws Exception {
        DataZoneDomain domain = domains.createDomain(REGION, "d", null, null, null, null, null, null, null);
        String unitId = service.createDomainUnit(REGION, domain.getId(), mapper.readTree(
                "{\"name\": \"u\", \"parentDomainUnitIdentifier\": \"" + domain.getRootDomainUnitId() + "\"}"))
                .path("id").asText();
        domains.createProject(REGION, domain.getId(), "p", null, unitId, null);

        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.deleteDomainUnit(REGION, domain.getId(), unitId)).getErrorCode());

        service.deleteDomainResources(REGION, domain.getId());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getDomainUnit(REGION, domain.getId(), unitId)).getErrorCode());
    }

    @Test
    void membershipNeedsAKnownDesignationAndProject() throws Exception {
        DataZoneDomain domain = domains.createDomain(REGION, "d2", null, null, null, null, null, null, null);
        String projectId = domains.createProject(REGION, domain.getId(), "p", null, null, null).getId();

        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.createProjectMembership(
                REGION, domain.getId(), projectId, mapper.readTree(
                        "{\"member\": {\"groupIdentifier\": \"g\"}, \"designation\": \"ADMIN\"}"))).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.createProjectMembership(REGION, domain.getId(), "missing", mapper.readTree(
                        "{\"member\": {\"groupIdentifier\": \"g\"}, \"designation\": \"PROJECT_OWNER\"}")))
                .getErrorCode());

        service.createProjectMembership(REGION, domain.getId(), projectId, mapper.readTree(
                "{\"member\": {\"groupIdentifier\": \"g\"}, \"designation\": \"PROJECT_OWNER\"}"));
        service.deleteProjectResources(REGION, domain.getId(), projectId);
        assertTrue(service.listProjectMemberships(REGION, domain.getId(), projectId, null, null).isEmpty());
    }
}
