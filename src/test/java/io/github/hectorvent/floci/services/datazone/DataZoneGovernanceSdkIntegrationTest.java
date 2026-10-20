package io.github.hectorvent.floci.services.datazone;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.datazone.DataZoneClient;
import software.amazon.awssdk.services.datazone.model.ConflictException;
import software.amazon.awssdk.services.datazone.model.CreateDomainResponse;
import software.amazon.awssdk.services.datazone.model.CreateDomainUnitResponse;
import software.amazon.awssdk.services.datazone.model.CreateGlossaryPolicyGrantDetail;
import software.amazon.awssdk.services.datazone.model.DataZoneEntityType;
import software.amazon.awssdk.services.datazone.model.GetDomainUnitResponse;
import software.amazon.awssdk.services.datazone.model.ManagedPolicyType;
import software.amazon.awssdk.services.datazone.model.Member;
import software.amazon.awssdk.services.datazone.model.OwnerProperties;
import software.amazon.awssdk.services.datazone.model.OwnerUserProperties;
import software.amazon.awssdk.services.datazone.model.PolicyGrantDetail;
import software.amazon.awssdk.services.datazone.model.PolicyGrantMember;
import software.amazon.awssdk.services.datazone.model.PolicyGrantPrincipal;
import software.amazon.awssdk.services.datazone.model.ProjectDesignation;
import software.amazon.awssdk.services.datazone.model.ProjectMember;
import software.amazon.awssdk.services.datazone.model.ProjectPolicyGrantPrincipal;
import software.amazon.awssdk.services.datazone.model.ResourceNotFoundException;
import software.amazon.awssdk.services.datazone.model.TargetEntityType;
import software.amazon.awssdk.services.datazone.model.UserDesignation;
import software.amazon.awssdk.services.datazone.model.ValidationException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DataZoneGovernanceSdkIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void domainUnitsHangOffTheRootUnitAndKeepTheirOwners() {
        try (DataZoneClient dz = client()) {
            CreateDomainResponse domain = dz.createDomain(r -> r.name("units-" + suffix()));
            String domainId = domain.id();
            String rootId = domain.rootDomainUnitId();

            GetDomainUnitResponse root = dz.getDomainUnit(r -> r.domainIdentifier(domainId).identifier(rootId));
            assertEquals(domain.name(), root.name());

            CreateDomainUnitResponse sales = dz.createDomainUnit(r -> r.domainIdentifier(domainId)
                    .name("sales").parentDomainUnitIdentifier(rootId).description("Sales data"));
            assertEquals(List.of(rootId), sales.ancestorDomainUnitIds());
            assertEquals(rootId, sales.parentDomainUnitId());
            assertNotNull(sales.createdAt());
            assertThrows(ConflictException.class, () -> dz.createDomainUnit(r -> r.domainIdentifier(domainId)
                    .name("sales").parentDomainUnitIdentifier(rootId)));
            CreateDomainUnitResponse emea = dz.createDomainUnit(r -> r.domainIdentifier(domainId)
                    .name("emea").parentDomainUnitIdentifier(sales.id()));
            assertEquals(List.of(rootId, sales.id()), emea.ancestorDomainUnitIds());

            assertEquals(List.of("sales"), dz.listDomainUnitsForParent(r -> r.domainIdentifier(domainId)
                    .parentDomainUnitIdentifier(rootId)).items().stream().map(u -> u.name()).toList());
            assertEquals("Revenue", dz.updateDomainUnit(r -> r.domainIdentifier(domainId).identifier(sales.id())
                    .description("Revenue")).description());

            OwnerProperties owner = OwnerProperties.fromUser(OwnerUserProperties.builder()
                    .userIdentifier("arn:aws:iam::000000000000:role/steward").build());
            dz.addEntityOwner(r -> r.domainIdentifier(domainId).entityType(DataZoneEntityType.DOMAIN_UNIT)
                    .entityIdentifier(sales.id()).owner(owner));
            assertThrows(ConflictException.class, () -> dz.addEntityOwner(r -> r.domainIdentifier(domainId)
                    .entityType(DataZoneEntityType.DOMAIN_UNIT).entityIdentifier(sales.id()).owner(owner)));
            assertEquals("arn:aws:iam::000000000000:role/steward", dz.listEntityOwners(r -> r
                    .domainIdentifier(domainId).entityType(DataZoneEntityType.DOMAIN_UNIT)
                    .entityIdentifier(sales.id())).owners().getFirst().user().userId());
            assertEquals(1, dz.getDomainUnit(r -> r.domainIdentifier(domainId).identifier(sales.id()))
                    .owners().size());
            dz.removeEntityOwner(r -> r.domainIdentifier(domainId).entityType(DataZoneEntityType.DOMAIN_UNIT)
                    .entityIdentifier(sales.id()).owner(owner));
            assertThrows(ResourceNotFoundException.class, () -> dz.removeEntityOwner(r -> r
                    .domainIdentifier(domainId).entityType(DataZoneEntityType.DOMAIN_UNIT)
                    .entityIdentifier(sales.id()).owner(owner)));

            dz.updateRootDomainUnitOwner(r -> r.domainIdentifier(domainId).currentOwner("admin")
                    .newOwner("arn:aws:iam::000000000000:role/admin"));
            assertEquals("arn:aws:iam::000000000000:role/admin", dz.getDomainUnit(r -> r.domainIdentifier(domainId)
                    .identifier(rootId)).owners().getFirst().user().userId());

            assertThrows(ConflictException.class, () -> dz.deleteDomainUnit(r -> r.domainIdentifier(domainId)
                    .identifier(sales.id())));
            dz.deleteDomainUnit(r -> r.domainIdentifier(domainId).identifier(emea.id()));
            dz.deleteDomainUnit(r -> r.domainIdentifier(domainId).identifier(sales.id()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getDomainUnit(r -> r.domainIdentifier(domainId)
                    .identifier(sales.id())));
            assertThrows(ConflictException.class, () -> dz.deleteDomainUnit(r -> r.domainIdentifier(domainId)
                    .identifier(rootId)));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void projectMembershipsAndPolicyGrants() {
        try (DataZoneClient dz = client()) {
            CreateDomainResponse domain = dz.createDomain(r -> r.name("grants-" + suffix()));
            String domainId = domain.id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("analysts")).id();
            String profileId = dz.createUserProfile(r -> r.domainIdentifier(domainId)
                    .userIdentifier("arn:aws:iam::000000000000:role/analyst")).id();

            dz.createProjectMembership(r -> r.domainIdentifier(domainId).projectIdentifier(projectId)
                    .member(Member.fromUserIdentifier("arn:aws:iam::000000000000:role/analyst"))
                    .designation(UserDesignation.PROJECT_CONTRIBUTOR));
            dz.createProjectMembership(r -> r.domainIdentifier(domainId).projectIdentifier(projectId)
                    .member(Member.fromGroupIdentifier("data-team")).designation(UserDesignation.PROJECT_OWNER));
            List<ProjectMember> members = dz.listProjectMemberships(r -> r.domainIdentifier(domainId)
                    .projectIdentifier(projectId)).members();
            assertEquals(2, members.size());
            assertTrue(members.stream().anyMatch(m -> m.memberDetails().user() != null
                    && profileId.equals(m.memberDetails().user().userId())
                    && m.designation() == UserDesignation.PROJECT_CONTRIBUTOR));
            dz.deleteProjectMembership(r -> r.domainIdentifier(domainId).projectIdentifier(projectId)
                    .member(Member.fromGroupIdentifier("data-team")));
            assertThrows(ResourceNotFoundException.class, () -> dz.deleteProjectMembership(r -> r
                    .domainIdentifier(domainId).projectIdentifier(projectId)
                    .member(Member.fromGroupIdentifier("data-team"))));
            assertEquals(1, dz.listProjectMemberships(r -> r.domainIdentifier(domainId)
                    .projectIdentifier(projectId)).members().size());

            PolicyGrantPrincipal principal = PolicyGrantPrincipal.fromProject(ProjectPolicyGrantPrincipal.builder()
                    .projectDesignation(ProjectDesignation.OWNER).projectIdentifier(projectId).build());
            String grantId = dz.addPolicyGrant(r -> r.domainIdentifier(domainId)
                    .entityType(TargetEntityType.DOMAIN_UNIT).entityIdentifier(domain.rootDomainUnitId())
                    .policyType(ManagedPolicyType.CREATE_GLOSSARY).principal(principal)
                    .detail(PolicyGrantDetail.fromCreateGlossary(CreateGlossaryPolicyGrantDetail.builder()
                            .includeChildDomainUnits(true).build()))).grantId();
            assertEquals(10, grantId.length());
            assertThrows(ValidationException.class, () -> dz.addPolicyGrant(r -> r.domainIdentifier(domainId)
                    .entityType(TargetEntityType.DOMAIN_UNIT).entityIdentifier(domain.rootDomainUnitId())
                    .policyType(ManagedPolicyType.CREATE_PROJECT).principal(principal)
                    .detail(PolicyGrantDetail.fromCreateGlossary(CreateGlossaryPolicyGrantDetail.builder().build()))));

            List<PolicyGrantMember> grants = dz.listPolicyGrants(r -> r.domainIdentifier(domainId)
                    .entityType(TargetEntityType.DOMAIN_UNIT).entityIdentifier(domain.rootDomainUnitId())
                    .policyType(ManagedPolicyType.CREATE_GLOSSARY)).grantList();
            assertEquals(1, grants.size());
            assertEquals(grantId, grants.getFirst().grantId());
            assertEquals(projectId, grants.getFirst().principal().project().projectIdentifier());
            assertEquals(Boolean.TRUE, grants.getFirst().detail().createGlossary().includeChildDomainUnits());
            assertTrue(dz.listPolicyGrants(r -> r.domainIdentifier(domainId)
                    .entityType(TargetEntityType.DOMAIN_UNIT).entityIdentifier(domain.rootDomainUnitId())
                    .policyType(ManagedPolicyType.CREATE_PROJECT)).grantList().isEmpty());

            dz.removePolicyGrant(r -> r.domainIdentifier(domainId).entityType(TargetEntityType.DOMAIN_UNIT)
                    .entityIdentifier(domain.rootDomainUnitId()).policyType(ManagedPolicyType.CREATE_GLOSSARY)
                    .principal(principal));
            assertTrue(dz.listPolicyGrants(r -> r.domainIdentifier(domainId)
                    .entityType(TargetEntityType.DOMAIN_UNIT).entityIdentifier(domain.rootDomainUnitId())
                    .policyType(ManagedPolicyType.CREATE_GLOSSARY)).grantList().isEmpty());
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private DataZoneClient client() {
        return DataZoneClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(Apache5HttpClient.builder()).build();
    }
}
