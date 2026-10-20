package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.datazone.DataZoneClient;
import software.amazon.awssdk.services.datazone.model.CreateDomainResponse;
import software.amazon.awssdk.services.datazone.model.CreateGlossaryResponse;
import software.amazon.awssdk.services.datazone.model.CreateGlossaryTermResponse;
import software.amazon.awssdk.services.datazone.model.CreateProjectResponse;
import software.amazon.awssdk.services.datazone.model.DomainStatus;
import software.amazon.awssdk.services.datazone.model.GetGlossaryTermResponse;
import software.amazon.awssdk.services.datazone.model.GlossaryTermStatus;
import software.amazon.awssdk.services.datazone.model.ResourceNotFoundException;

import static org.junit.jupiter.api.Assertions.*;

class DataZoneTest {
    @Test
    void glossaryTermLifecycle() {
        try (DataZoneClient client = TestFixtures.dataZoneClient()) {
            CreateDomainResponse domain = client.createDomain(r -> r.name("sdk-datazone-domain")
                    .domainExecutionRole("arn:aws:iam::000000000000:role/service-role/AmazonDataZoneDomainExecution"));
            assertEquals(DomainStatus.AVAILABLE, domain.status());
            String domainId = domain.id();

            CreateProjectResponse project = client.createProject(r -> r.domainIdentifier(domainId).name("sdk-project"));
            CreateGlossaryResponse glossary = client.createGlossary(r -> r.domainIdentifier(domainId)
                    .name("sdk-glossary").owningProjectIdentifier(project.id()));
            CreateGlossaryTermResponse term = client.createGlossaryTerm(r -> r.domainIdentifier(domainId)
                    .glossaryIdentifier(glossary.id()).name("sdk-term"));

            GetGlossaryTermResponse fetched = client.getGlossaryTerm(r -> r.domainIdentifier(domainId)
                    .identifier(term.id()));
            assertEquals("sdk-term", fetched.name());
            assertEquals(GlossaryTermStatus.ENABLED, fetched.status());
            assertNotNull(fetched.createdAt());

            client.updateGlossaryTerm(r -> r.domainIdentifier(domainId).identifier(term.id())
                    .status(GlossaryTermStatus.DISABLED));
            client.deleteGlossaryTerm(r -> r.domainIdentifier(domainId).identifier(term.id()));
            assertThrows(ResourceNotFoundException.class,
                    () -> client.getGlossaryTerm(r -> r.domainIdentifier(domainId).identifier(term.id())));

            client.deleteDomain(r -> r.identifier(domainId));
            assertThrows(ResourceNotFoundException.class, () -> client.getDomain(r -> r.identifier(domainId)));
        }
    }
}
