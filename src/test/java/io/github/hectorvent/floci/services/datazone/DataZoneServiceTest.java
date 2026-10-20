package io.github.hectorvent.floci.services.datazone;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import io.github.hectorvent.floci.services.datazone.model.DataZoneGlossary;
import io.github.hectorvent.floci.services.datazone.model.DataZoneGlossaryTerm;
import io.github.hectorvent.floci.services.datazone.model.DataZoneProject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataZoneServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/service-role/AmazonDataZoneDomainExecution";

    private final DataZoneService service = new DataZoneService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

    @Test
    void createDomainIsAvailableWithArnAndPortal() {
        DataZoneDomain domain = service.createDomain(REGION, "analytics", null, ROLE, null, null, null, null,
                Map.of("team", "data"));

        assertTrue(domain.getId().matches("dzd_[a-z0-9]{14}"));
        assertEquals("AVAILABLE", domain.getStatus());
        assertEquals("arn:aws:datazone:us-east-1:000000000000:domain/" + domain.getId(), domain.getArn());
        assertEquals("https://" + domain.getId() + ".datazone.us-east-1.on.aws", domain.getPortalUrl());
        assertEquals(Map.of("team", "data"), service.listTags(REGION, domain.getArn()));
    }

    @Test
    void duplicateDomainNameConflicts() {
        service.createDomain(REGION, "analytics", null, ROLE, null, null, null, null, null);

        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "analytics", null, ROLE, null, null, null, null, null));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void malformedRoleIsAValidationError() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createDomain(REGION, "analytics", null, "not-a-role", null, null, null, null, null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void glossaryNeedsAnExistingProject() {
        DataZoneDomain domain = service.createDomain(REGION, "analytics", null, ROLE, null, null, null, null, null);

        AwsException e = assertThrows(AwsException.class, () -> service.createGlossary(REGION, domain.getId(),
                "business", "missingproject", null, null, null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void termNeedsAnExistingGlossary() {
        DataZoneDomain domain = service.createDomain(REGION, "analytics", null, ROLE, null, null, null, null, null);

        AwsException e = assertThrows(AwsException.class, () -> service.createGlossaryTerm(REGION, domain.getId(),
                "missingglossary", "revenue", null, null, null, null));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void enabledTermAndGlossaryRefuseDeletionUntilDisabled() {
        DataZoneDomain domain = service.createDomain(REGION, "analytics", null, ROLE, null, null, null, null, null);
        DataZoneProject project = service.createProject(REGION, domain.getId(), "governance", null, null, null);
        DataZoneGlossary glossary = service.createGlossary(REGION, domain.getId(), "business",
                project.getId(), null, null, null);
        DataZoneGlossaryTerm term = service.createGlossaryTerm(REGION, domain.getId(), glossary.getId(),
                "revenue", null, "Money in", null, null);
        assertEquals("ENABLED", term.getStatus());

        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.deleteGlossaryTerm(REGION, domain.getId(), term.getId())).getErrorCode());
        service.updateGlossary(REGION, domain.getId(), glossary.getId(), null, null, "DISABLED");
        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.deleteGlossary(REGION, domain.getId(), glossary.getId())).getErrorCode());

        service.updateGlossaryTerm(REGION, domain.getId(), term.getId(), null, null, "DISABLED",
                null, null, null);
        service.deleteGlossaryTerm(REGION, domain.getId(), term.getId());
        service.deleteGlossary(REGION, domain.getId(), glossary.getId());

        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getGlossaryTerm(REGION, domain.getId(), term.getId())).getErrorCode());
    }

    @Test
    void deleteDomainRemovesItsChildren() {
        DataZoneDomain domain = service.createDomain(REGION, "analytics", null, ROLE, null, null, null, null, null);
        DataZoneProject project = service.createProject(REGION, domain.getId(), "governance", null, null, null);
        service.createGlossary(REGION, domain.getId(), "business", project.getId(), null, null, null);

        service.deleteDomain(REGION, domain.getId());

        assertEquals(List.of(), service.listDomains(REGION, null));
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getDomain(REGION, domain.getId())).getErrorCode());
    }

    @Test
    void projectOwningGlossariesNeedsSkipDeletionCheck() {
        DataZoneDomain domain = service.createDomain(REGION, "analytics", null, ROLE, null, null, null, null, null);
        DataZoneProject project = service.createProject(REGION, domain.getId(), "governance", null, null, null);
        service.createGlossary(REGION, domain.getId(), "business", project.getId(), null, null, null);

        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.deleteProject(REGION, domain.getId(), project.getId(), false)).getErrorCode());
        service.deleteProject(REGION, domain.getId(), project.getId(), true);
        assertEquals(List.of(), service.listProjects(REGION, domain.getId(), null));
    }
}
