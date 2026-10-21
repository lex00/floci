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
import software.amazon.awssdk.services.datazone.model.ConnectionPropertiesInput;
import software.amazon.awssdk.services.datazone.model.ConnectionPropertiesPatch;
import software.amazon.awssdk.services.datazone.model.ConnectionScope;
import software.amazon.awssdk.services.datazone.model.ConnectionType;
import software.amazon.awssdk.services.datazone.model.CreateConnectionResponse;
import software.amazon.awssdk.services.datazone.model.CreateDataSourceResponse;
import software.amazon.awssdk.services.datazone.model.DataSourceConfigurationInput;
import software.amazon.awssdk.services.datazone.model.DataSourceRunStatus;
import software.amazon.awssdk.services.datazone.model.DataSourceStatus;
import software.amazon.awssdk.services.datazone.model.EnableSetting;
import software.amazon.awssdk.services.datazone.model.FormInput;
import software.amazon.awssdk.services.datazone.model.GetDataSourceResponse;
import software.amazon.awssdk.services.datazone.model.GetMetadataGenerationRunResponse;
import software.amazon.awssdk.services.datazone.model.MetadataGenerationRunStatus;
import software.amazon.awssdk.services.datazone.model.MetadataGenerationRunType;
import software.amazon.awssdk.services.datazone.model.MetadataGenerationTargetType;
import software.amazon.awssdk.services.datazone.model.RedshiftCredentials;
import software.amazon.awssdk.services.datazone.model.RedshiftPropertiesInput;
import software.amazon.awssdk.services.datazone.model.ResourceNotFoundException;
import software.amazon.awssdk.services.datazone.model.S3PropertiesInput;
import software.amazon.awssdk.services.datazone.model.S3PropertiesPatch;
import software.amazon.awssdk.services.datazone.model.StartDataSourceRunResponse;
import software.amazon.awssdk.services.datazone.model.StartMetadataGenerationRunResponse;
import software.amazon.awssdk.services.datazone.model.ValidationException;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DataZoneDataSourceSdkIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void dataSourceRunsFinishAtOnceAndImportNothing() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("sources-" + suffix())).id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("producers")).id();
            String environmentId = environment(dz, domainId, projectId);

            CreateDataSourceResponse created = dz.createDataSource(r -> r.domainIdentifier(domainId).name("sales")
                    .type("GLUE").projectIdentifier(projectId).environmentIdentifier(environmentId)
                    .configuration(DataSourceConfigurationInput.fromGlueRunConfiguration(g -> g
                            .dataAccessRole("arn:aws:iam::000000000000:role/access")))
                    .assetFormsInput(FormInput.builder().formName("Owner").typeIdentifier("OwnerForm")
                            .content("{}").build()));
            assertEquals(DataSourceStatus.READY, created.status());
            assertEquals(EnableSetting.ENABLED, created.enableSetting());
            assertEquals(environmentId, created.environmentId());
            assertEquals("000000000000", created.configuration().glueRunConfiguration().accountId());
            assertEquals("us-east-1", created.configuration().glueRunConfiguration().region());
            assertEquals("OwnerForm", created.assetFormsOutput().getFirst().typeName());
            assertThrows(ConflictException.class, () -> dz.createDataSource(r -> r.domainIdentifier(domainId)
                    .name("sales").type("GLUE").projectIdentifier(projectId)));
            assertThrows(ResourceNotFoundException.class, () -> dz.createDataSource(r -> r.domainIdentifier(domainId)
                    .name("other").type("GLUE").projectIdentifier("missing")));

            assertEquals(EnableSetting.DISABLED, dz.updateDataSource(r -> r.domainIdentifier(domainId)
                    .identifier(created.id()).enableSetting(EnableSetting.DISABLED).description("Sales tables"))
                    .enableSetting());
            assertEquals(List.of(created.id()), dz.listDataSources(r -> r.domainIdentifier(domainId)
                    .projectIdentifier(projectId).type("GLUE")).items().stream().map(s -> s.dataSourceId()).toList());
            assertTrue(dz.listDataSources(r -> r.domainIdentifier(domainId).projectIdentifier(projectId)
                    .name("missing")).items().isEmpty());
            assertThrows(ValidationException.class, () -> dz.listDataSources(r -> r.domainIdentifier(domainId)));

            StartDataSourceRunResponse run = dz.startDataSourceRun(r -> r.domainIdentifier(domainId)
                    .dataSourceIdentifier(created.id()));
            assertEquals(DataSourceRunStatus.SUCCESS, run.status());
            assertEquals(0, run.runStatisticsForAssets().added());
            assertNotNull(run.stoppedAt());
            assertEquals(created.id(), dz.getDataSourceRun(r -> r.domainIdentifier(domainId).identifier(run.id()))
                    .dataSourceId());
            assertEquals(1, dz.listDataSourceRuns(r -> r.domainIdentifier(domainId)
                    .dataSourceIdentifier(created.id())).items().size());
            assertTrue(dz.listDataSourceRunActivities(r -> r.domainIdentifier(domainId).identifier(run.id()))
                    .items().isEmpty());
            GetDataSourceResponse afterRun = dz.getDataSource(r -> r.domainIdentifier(domainId)
                    .identifier(created.id()));
            assertEquals(DataSourceRunStatus.SUCCESS, afterRun.lastRunStatus());
            assertEquals(0, afterRun.lastRunAssetCount());

            assertEquals(DataSourceStatus.DELETING, dz.deleteDataSource(r -> r.domainIdentifier(domainId)
                    .identifier(created.id())).status());
            assertThrows(ResourceNotFoundException.class, () -> dz.getDataSource(r -> r.domainIdentifier(domainId)
                    .identifier(created.id())));
            assertThrows(ResourceNotFoundException.class, () -> dz.getDataSourceRun(r -> r.domainIdentifier(domainId)
                    .identifier(run.id())));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void connectionsDeriveTheirTypeAndEndpoint() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("connections-" + suffix())).id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("analysts")).id();
            String environmentId = environment(dz, domainId, projectId);

            CreateConnectionResponse s3 = dz.createConnection(r -> r.domainIdentifier(domainId).name("raw")
                    .environmentIdentifier(environmentId)
                    .props(ConnectionPropertiesInput.fromS3Properties(S3PropertiesInput.builder()
                            .s3Uri("s3://raw-bucket/").build())));
            assertEquals(ConnectionType.S3, s3.type());
            assertEquals(ConnectionScope.PROJECT, s3.scope());
            assertEquals(projectId, s3.projectId());
            assertEquals("s3://raw-bucket/", s3.props().s3Properties().s3Uri());
            assertEquals("000000000000", s3.physicalEndpoints().getFirst().awsLocation().awsAccountId());
            assertThrows(ConflictException.class, () -> dz.createConnection(r -> r.domainIdentifier(domainId)
                    .name("raw").environmentIdentifier(environmentId)));

            CreateConnectionResponse redshift = dz.createConnection(r -> r.domainIdentifier(domainId)
                    .name("warehouse").props(ConnectionPropertiesInput.fromRedshiftProperties(
                            RedshiftPropertiesInput.builder().host("wh.example.com").port(5439).databaseName("dev")
                                    .credentials(RedshiftCredentials.builder()
                                            .secretArn("arn:aws:secretsmanager:us-east-1:000000000000:secret:wh")
                                            .build())
                                    .build())));
            assertEquals(ConnectionType.REDSHIFT, redshift.type());
            assertEquals(ConnectionScope.DOMAIN, redshift.scope());
            assertNull(redshift.projectId());
            assertEquals("jdbc:redshift://wh.example.com:5439/dev", redshift.props().redshiftProperties().jdbcUrl());
            assertEquals(5439, redshift.physicalEndpoints().getFirst().port());

            assertEquals("s3://curated-bucket/", dz.updateConnection(r -> r.domainIdentifier(domainId)
                    .identifier(s3.connectionId()).description("Curated")
                    .props(ConnectionPropertiesPatch.fromS3Properties(S3PropertiesPatch.builder()
                            .s3Uri("s3://curated-bucket/").build())))
                    .props().s3Properties().s3Uri());
            assertEquals("Curated", dz.getConnection(r -> r.domainIdentifier(domainId)
                    .identifier(s3.connectionId())).description());
            assertEquals(List.of("warehouse", "raw"), dz.listConnections(r -> r.domainIdentifier(domainId)
                    .sortOrder("DESCENDING")).items().stream().map(c -> c.name()).toList());
            assertEquals(1, dz.listConnections(r -> r.domainIdentifier(domainId).type(ConnectionType.S3))
                    .items().size());

            String sourceId = dz.createDataSource(r -> r.domainIdentifier(domainId).name("from-connection")
                    .type("REDSHIFT").projectIdentifier(projectId).connectionIdentifier(redshift.connectionId()))
                    .id();
            assertThrows(ConflictException.class, () -> dz.deleteConnection(r -> r.domainIdentifier(domainId)
                    .identifier(redshift.connectionId())));
            dz.deleteDataSource(r -> r.domainIdentifier(domainId).identifier(sourceId));
            dz.deleteConnection(r -> r.domainIdentifier(domainId).identifier(redshift.connectionId()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getConnection(r -> r.domainIdentifier(domainId)
                    .identifier(redshift.connectionId())));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void metadataGenerationRunsSucceedAtOnce() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("metadata-" + suffix())).id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("producers")).id();
            String assetId = dz.createAsset(r -> r.domainIdentifier(domainId).name("orders")
                    .typeIdentifier("amazon.datazone.GlueTableAssetType").owningProjectIdentifier(projectId)).id();

            StartMetadataGenerationRunResponse started = dz.startMetadataGenerationRun(r -> r
                    .domainIdentifier(domainId).owningProjectIdentifier(projectId)
                    .types(MetadataGenerationRunType.BUSINESS_DESCRIPTIONS, MetadataGenerationRunType.BUSINESS_NAMES)
                    .target(t -> t.type(MetadataGenerationTargetType.ASSET).identifier(assetId)));
            assertEquals(MetadataGenerationRunStatus.SUCCEEDED, started.status());
            assertEquals(projectId, started.owningProjectId());

            GetMetadataGenerationRunResponse run = dz.getMetadataGenerationRun(r -> r.domainIdentifier(domainId)
                    .identifier(started.id()));
            assertEquals(assetId, run.target().identifier());
            assertEquals("1", run.target().revision());
            assertEquals(2, run.typeStats().size());
            assertEquals(1, dz.getMetadataGenerationRun(r -> r.domainIdentifier(domainId).identifier(started.id())
                    .type(MetadataGenerationRunType.BUSINESS_NAMES)).typeStats().size());

            assertEquals(1, dz.listMetadataGenerationRuns(r -> r.domainIdentifier(domainId)
                    .targetIdentifier(assetId).type(MetadataGenerationRunType.BUSINESS_NAMES)).items().size());
            assertTrue(dz.listMetadataGenerationRuns(r -> r.domainIdentifier(domainId)
                    .status(MetadataGenerationRunStatus.CANCELED)).items().isEmpty());
            assertThrows(ConflictException.class, () -> dz.cancelMetadataGenerationRun(r -> r
                    .domainIdentifier(domainId).identifier(started.id())));
            assertThrows(ResourceNotFoundException.class, () -> dz.startMetadataGenerationRun(r -> r
                    .domainIdentifier(domainId).owningProjectIdentifier(projectId)
                    .types(MetadataGenerationRunType.BUSINESS_NAMES)
                    .target(t -> t.type(MetadataGenerationTargetType.ASSET).identifier("missing"))));
            assertThrows(ValidationException.class, () -> dz.startMetadataGenerationRun(r -> r
                    .domainIdentifier(domainId).owningProjectIdentifier(projectId)
                    .target(t -> t.type(MetadataGenerationTargetType.ASSET).identifier(assetId))));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    private static String environment(DataZoneClient dz, String domainId, String projectId) {
        String blueprintId = dz.listEnvironmentBlueprints(r -> r.domainIdentifier(domainId).managed(true)
                .name("DefaultDataLake")).items().getFirst().id();
        dz.putEnvironmentBlueprintConfiguration(r -> r.domainIdentifier(domainId)
                .environmentBlueprintIdentifier(blueprintId).enabledRegions("us-east-1")
                .provisioningRoleArn("arn:aws:iam::000000000000:role/provisioning"));
        String profileId = dz.createEnvironmentProfile(r -> r.domainIdentifier(domainId).name("lake")
                .environmentBlueprintIdentifier(blueprintId).projectIdentifier(projectId)
                .awsAccountId("000000000000").awsAccountRegion("us-east-1")).id();
        return dz.createEnvironment(r -> r.domainIdentifier(domainId).name("env")
                .projectIdentifier(projectId).environmentProfileIdentifier(profileId)).id();
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
