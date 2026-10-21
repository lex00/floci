package io.github.hectorvent.floci.services.datazone;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.datazone.DataZoneClient;
import software.amazon.awssdk.services.datazone.model.AccountInfo;
import software.amazon.awssdk.services.datazone.model.AccountSource;
import software.amazon.awssdk.services.datazone.model.ActionParameters;
import software.amazon.awssdk.services.datazone.model.AssetTypesForRule;
import software.amazon.awssdk.services.datazone.model.AwsConsoleLinkParameters;
import software.amazon.awssdk.services.datazone.model.CloudFormationProperties;
import software.amazon.awssdk.services.datazone.model.ConflictException;
import software.amazon.awssdk.services.datazone.model.CreateEnvironmentBlueprintResponse;
import software.amazon.awssdk.services.datazone.model.CreateProjectProfileResponse;
import software.amazon.awssdk.services.datazone.model.CreateRuleResponse;
import software.amazon.awssdk.services.datazone.model.DeploymentMode;
import software.amazon.awssdk.services.datazone.model.DomainUnitTarget;
import software.amazon.awssdk.services.datazone.model.EnvironmentConfiguration;
import software.amazon.awssdk.services.datazone.model.GetEnvironmentCredentialsResponse;
import software.amazon.awssdk.services.datazone.model.GetRuleResponse;
import software.amazon.awssdk.services.datazone.model.GlossaryTermEnforcementDetail;
import software.amazon.awssdk.services.datazone.model.MetadataFormEnforcementDetail;
import software.amazon.awssdk.services.datazone.model.MetadataFormReference;
import software.amazon.awssdk.services.datazone.model.ProvisioningProperties;
import software.amazon.awssdk.services.datazone.model.ResolutionStrategy;
import software.amazon.awssdk.services.datazone.model.ResourceNotFoundException;
import software.amazon.awssdk.services.datazone.model.RuleAction;
import software.amazon.awssdk.services.datazone.model.RuleDetail;
import software.amazon.awssdk.services.datazone.model.RuleScope;
import software.amazon.awssdk.services.datazone.model.RuleScopeSelectionMode;
import software.amazon.awssdk.services.datazone.model.RuleTarget;
import software.amazon.awssdk.services.datazone.model.RuleTargetType;
import software.amazon.awssdk.services.datazone.model.RuleType;
import software.amazon.awssdk.services.datazone.model.Status;
import software.amazon.awssdk.services.datazone.model.ValidationException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DataZoneAdministrationSdkIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void projectProfilesAndAccountPools() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("admin-" + suffix())).id();
            String rootUnitId = dz.getDomain(r -> r.identifier(domainId)).rootDomainUnitId();

            CreateProjectProfileResponse profile = dz.createProjectProfile(r -> r.domainIdentifier(domainId)
                    .name("analytics").description("Analytics projects")
                    .environmentConfigurations(EnvironmentConfiguration.builder().name("lake")
                            .environmentBlueprintId("bp-1").deploymentMode(DeploymentMode.ON_CREATE).build()));
            assertEquals(Status.ENABLED, profile.status());
            assertEquals(rootUnitId, profile.domainUnitId());
            assertNotNull(profile.createdAt());
            assertNotNull(profile.environmentConfigurations().getFirst().id());
            assertThrows(ConflictException.class, () -> dz.createProjectProfile(r -> r.domainIdentifier(domainId)
                    .name("analytics")));
            dz.createProjectProfile(r -> r.domainIdentifier(domainId).name("archive"));

            assertEquals(Status.DISABLED, dz.updateProjectProfile(r -> r.domainIdentifier(domainId)
                    .identifier(profile.id()).status(Status.DISABLED)).status());
            assertEquals("Analytics projects", dz.getProjectProfile(r -> r.domainIdentifier(domainId)
                    .identifier(profile.id())).description());
            assertEquals(List.of("archive", "analytics"), dz.listProjectProfiles(r -> r.domainIdentifier(domainId)
                    .sortBy("NAME").sortOrder("DESCENDING")).items().stream().map(p -> p.name()).toList());
            dz.deleteProjectProfile(r -> r.domainIdentifier(domainId).identifier(profile.id()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getProjectProfile(r -> r
                    .domainIdentifier(domainId).identifier(profile.id())));

            String poolId = dz.createAccountPool(r -> r.domainIdentifier(domainId).name("sandbox")
                    .resolutionStrategy(ResolutionStrategy.MANUAL)
                    .accountSource(AccountSource.fromAccounts(List.of(AccountInfo.builder().awsAccountId("111122223333")
                            .supportedRegions("us-east-1", "us-west-2").awsAccountName("sandbox-1").build())))).id();
            assertEquals("sandbox", dz.getAccountPool(r -> r.domainIdentifier(domainId).identifier(poolId)).name());
            assertThrows(ConflictException.class, () -> dz.createAccountPool(r -> r.domainIdentifier(domainId)
                    .name("sandbox").resolutionStrategy(ResolutionStrategy.MANUAL)
                    .accountSource(AccountSource.fromAccounts(List.of()))));
            dz.updateAccountPool(r -> r.domainIdentifier(domainId).identifier(poolId)
                    .accountSource(AccountSource.fromAccounts(List.of(
                            AccountInfo.builder().awsAccountId("111122223333").supportedRegions("us-east-1").build(),
                            AccountInfo.builder().awsAccountId("444455556666").supportedRegions("eu-west-1").build()))));
            assertEquals(List.of("111122223333", "444455556666"), dz.listAccountsInAccountPool(r -> r
                    .domainIdentifier(domainId).identifier(poolId)).items().stream()
                    .map(a -> a.awsAccountId()).toList());
            assertEquals(1, dz.listAccountPools(r -> r.domainIdentifier(domainId)).items().size());
            dz.deleteAccountPool(r -> r.domainIdentifier(domainId).identifier(poolId));
            assertThrows(ResourceNotFoundException.class, () -> dz.getAccountPool(r -> r.domainIdentifier(domainId)
                    .identifier(poolId)));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void rulesAreRevisedAndCascadeToChildDomainUnits() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("rules-" + suffix())).id();
            String rootUnitId = dz.getDomain(r -> r.identifier(domainId)).rootDomainUnitId();
            String childUnitId = dz.createDomainUnit(r -> r.domainIdentifier(domainId).name("finance")
                    .parentDomainUnitIdentifier(rootUnitId)).id();

            CreateRuleResponse created = dz.createRule(r -> r.domainIdentifier(domainId).name("require-terms")
                    .target(RuleTarget.fromDomainUnitTarget(DomainUnitTarget.builder().domainUnitId(rootUnitId)
                            .includeChildDomainUnits(true).build()))
                    .action(RuleAction.CREATE_LISTING_CHANGE_SET)
                    .scope(RuleScope.builder().assetType(AssetTypesForRule.builder()
                            .selectionMode(RuleScopeSelectionMode.SPECIFIC)
                            .specificAssetTypes("amazon.datazone.GlueTableAssetType").build()).build())
                    .detail(RuleDetail.fromGlossaryTermEnforcementDetail(GlossaryTermEnforcementDetail.builder()
                            .requiredGlossaryTermIds("term-1").build())));
            assertEquals(RuleType.GLOSSARY_TERM_ENFORCEMENT, created.ruleType());
            assertEquals(RuleTargetType.DOMAIN_UNIT, created.targetType());
            assertNotNull(created.createdAt());

            GetRuleResponse first = dz.getRule(r -> r.domainIdentifier(domainId).identifier(created.identifier()));
            assertEquals("1", first.revision());
            assertEquals("2", dz.updateRule(r -> r.domainIdentifier(domainId).identifier(created.identifier())
                    .description("Glue tables need a term")).revision());
            assertEquals("Glue tables need a term", dz.getRule(r -> r.domainIdentifier(domainId)
                    .identifier(created.identifier())).description());
            assertEquals(null, dz.getRule(r -> r.domainIdentifier(domainId).identifier(created.identifier())
                    .revision("1")).description());
            assertThrows(ValidationException.class, () -> dz.updateRule(r -> r.domainIdentifier(domainId)
                    .identifier(created.identifier())
                    .detail(RuleDetail.fromMetadataFormEnforcementDetail(MetadataFormEnforcementDetail.builder()
                            .requiredMetadataForms(MetadataFormReference.builder().typeIdentifier("Form")
                                    .typeRevision("1").build()).build()))));

            assertEquals(1, dz.listRules(r -> r.domainIdentifier(domainId).targetType(RuleTargetType.DOMAIN_UNIT)
                    .targetIdentifier(rootUnitId).assetTypes("amazon.datazone.GlueTableAssetType")).items().size());
            assertTrue(dz.listRules(r -> r.domainIdentifier(domainId).targetType(RuleTargetType.DOMAIN_UNIT)
                    .targetIdentifier(rootUnitId).assetTypes("OtherType")).items().isEmpty());
            assertTrue(dz.listRules(r -> r.domainIdentifier(domainId).targetType(RuleTargetType.DOMAIN_UNIT)
                    .targetIdentifier(childUnitId)).items().isEmpty());
            assertEquals(created.identifier(), dz.listRules(r -> r.domainIdentifier(domainId)
                    .targetType(RuleTargetType.DOMAIN_UNIT).targetIdentifier(childUnitId).includeCascaded(true))
                    .items().getFirst().identifier());

            dz.deleteRule(r -> r.domainIdentifier(domainId).identifier(created.identifier()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getRule(r -> r.domainIdentifier(domainId)
                    .identifier(created.identifier())));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void customBlueprintsAndEnvironmentActionsRolesAndCredentials() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("blueprints-" + suffix())).id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("builders")).id();

            CreateEnvironmentBlueprintResponse blueprint = dz.createEnvironmentBlueprint(r -> r
                    .domainIdentifier(domainId).name("CustomLake")
                    .provisioningProperties(ProvisioningProperties.fromCloudFormation(CloudFormationProperties
                            .builder().templateUrl("https://example.com/lake.template.json").build())));
            assertEquals("Custom", blueprint.provider());
            assertThrows(ConflictException.class, () -> dz.createEnvironmentBlueprint(r -> r
                    .domainIdentifier(domainId).name("DefaultDataLake")
                    .provisioningProperties(ProvisioningProperties.fromCloudFormation(CloudFormationProperties
                            .builder().templateUrl("https://example.com/x.json").build()))));
            assertEquals(List.of("CustomLake"), dz.listEnvironmentBlueprints(r -> r.domainIdentifier(domainId)
                    .managed(false)).items().stream().map(b -> b.name()).toList());
            assertEquals(3, dz.listEnvironmentBlueprints(r -> r.domainIdentifier(domainId)).items().size());
            assertEquals("Lake v2", dz.updateEnvironmentBlueprint(r -> r.domainIdentifier(domainId)
                    .identifier(blueprint.id()).description("Lake v2")).description());
            assertEquals("Lake v2", dz.getEnvironmentBlueprint(r -> r.domainIdentifier(domainId)
                    .identifier(blueprint.id())).description());

            dz.putEnvironmentBlueprintConfiguration(r -> r.domainIdentifier(domainId)
                    .environmentBlueprintIdentifier(blueprint.id()).enabledRegions("us-east-1"));
            String profileId = dz.createEnvironmentProfile(r -> r.domainIdentifier(domainId).name("custom")
                    .environmentBlueprintIdentifier(blueprint.id()).projectIdentifier(projectId)).id();
            String environmentId = dz.createEnvironment(r -> r.domainIdentifier(domainId).name("dev")
                    .projectIdentifier(projectId).environmentProfileIdentifier(profileId)).id();
            assertThrows(ConflictException.class, () -> dz.deleteEnvironmentBlueprint(r -> r
                    .domainIdentifier(domainId).identifier(blueprint.id())));

            String actionId = dz.createEnvironmentAction(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).name("console")
                    .parameters(ActionParameters.fromAwsConsoleLink(AwsConsoleLinkParameters.builder()
                            .uri("https://console.aws.amazon.com/athena").build()))).id();
            assertEquals("console", dz.getEnvironmentAction(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).identifier(actionId)).name());
            assertEquals("Athena", dz.updateEnvironmentAction(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).identifier(actionId).description("Athena")).description());
            assertEquals(1, dz.listEnvironmentActions(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId)).items().size());
            dz.deleteEnvironmentAction(r -> r.domainIdentifier(domainId).environmentIdentifier(environmentId)
                    .identifier(actionId));
            assertThrows(ResourceNotFoundException.class, () -> dz.getEnvironmentAction(r -> r
                    .domainIdentifier(domainId).environmentIdentifier(environmentId).identifier(actionId)));

            String roleArn = "arn:aws:iam::000000000000:role/service-role/datazone-env";
            dz.associateEnvironmentRole(r -> r.domainIdentifier(domainId).environmentIdentifier(environmentId)
                    .environmentRoleArn(roleArn));
            dz.disassociateEnvironmentRole(r -> r.domainIdentifier(domainId).environmentIdentifier(environmentId)
                    .environmentRoleArn(roleArn));
            assertThrows(ResourceNotFoundException.class, () -> dz.disassociateEnvironmentRole(r -> r
                    .domainIdentifier(domainId).environmentIdentifier(environmentId).environmentRoleArn(roleArn)));

            GetEnvironmentCredentialsResponse credentials = dz.getEnvironmentCredentials(r -> r
                    .domainIdentifier(domainId).environmentIdentifier(environmentId));
            assertNotNull(credentials.accessKeyId());
            assertNotNull(credentials.sessionToken());
            assertTrue(credentials.expiration().isAfter(Instant.now()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getEnvironmentCredentials(r -> r
                    .domainIdentifier(domainId).environmentIdentifier("missing")));

            dz.deleteEnvironment(r -> r.domainIdentifier(domainId).identifier(environmentId));
            dz.deleteEnvironmentProfile(r -> r.domainIdentifier(domainId).identifier(profileId));
            dz.deleteEnvironmentBlueprint(r -> r.domainIdentifier(domainId).identifier(blueprint.id()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getEnvironmentBlueprint(r -> r
                    .domainIdentifier(domainId).identifier(blueprint.id())));
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
