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
import software.amazon.awssdk.services.datazone.model.CreateNotebookResponse;
import software.amazon.awssdk.services.datazone.model.EncryptionConfiguration;
import software.amazon.awssdk.services.datazone.model.FileFormat;
import software.amazon.awssdk.services.datazone.model.GetDataExportConfigurationResponse;
import software.amazon.awssdk.services.datazone.model.GetIamPortalLoginUrlResponse;
import software.amazon.awssdk.services.datazone.model.GetNotebookExportResponse;
import software.amazon.awssdk.services.datazone.model.GetNotebookRunResponse;
import software.amazon.awssdk.services.datazone.model.GitMetadata;
import software.amazon.awssdk.services.datazone.model.GroupProfileStatus;
import software.amazon.awssdk.services.datazone.model.GroupSearchType;
import software.amazon.awssdk.services.datazone.model.NotebookExportStatus;
import software.amazon.awssdk.services.datazone.model.NotebookRunStatus;
import software.amazon.awssdk.services.datazone.model.NotebookStatus;
import software.amazon.awssdk.services.datazone.model.NotebookType;
import software.amazon.awssdk.services.datazone.model.NotificationType;
import software.amazon.awssdk.services.datazone.model.ResourceNotFoundException;
import software.amazon.awssdk.services.datazone.model.SortOrder;
import software.amazon.awssdk.services.datazone.model.SourceLocation;
import software.amazon.awssdk.services.datazone.model.StartNotebookImportResponse;
import software.amazon.awssdk.services.datazone.model.StartNotebookSyncResponse;
import software.amazon.awssdk.services.datazone.model.UserSearchType;
import software.amazon.awssdk.services.datazone.model.UserType;
import software.amazon.awssdk.services.datazone.model.ValidationException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DataZoneNotebookSdkIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void profilesExportConfigurationAndNotifications() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("profiles-" + suffix())).id();

            String groupId = dz.createGroupProfile(r -> r.domainIdentifier(domainId).groupIdentifier("analysts"))
                    .id();
            assertEquals(GroupProfileStatus.ASSIGNED, dz.getGroupProfile(r -> r.domainIdentifier(domainId)
                    .groupIdentifier("analysts")).status());
            assertThrows(ConflictException.class, () -> dz.createGroupProfile(r -> r.domainIdentifier(domainId)
                    .groupIdentifier("analysts")));
            dz.createGroupProfile(r -> r.domainIdentifier(domainId).groupIdentifier("etl-sessions")
                    .rolePrincipalArn("arn:aws:iam::000000000000:role/etl"));
            assertEquals(GroupProfileStatus.NOT_ASSIGNED, dz.updateGroupProfile(r -> r.domainIdentifier(domainId)
                    .groupIdentifier(groupId).status(GroupProfileStatus.NOT_ASSIGNED)).status());
            assertEquals(List.of("analysts"), dz.searchGroupProfiles(r -> r.domainIdentifier(domainId)
                    .groupType(GroupSearchType.DATAZONE_SSO_GROUP).searchText("ANAL")).items().stream()
                    .map(g -> g.groupName()).toList());
            assertEquals("arn:aws:iam::000000000000:role/etl", dz.searchGroupProfiles(r -> r
                    .domainIdentifier(domainId).groupType(GroupSearchType.IAM_ROLE_SESSION_GROUP)).items()
                    .getFirst().rolePrincipalArn());

            dz.createUserProfile(r -> r.domainIdentifier(domainId).userIdentifier("jane.doe")
                    .userType(UserType.SSO_USER));
            dz.createUserProfile(r -> r.domainIdentifier(domainId)
                    .userIdentifier("arn:aws:iam::000000000000:role/analyst").userType(UserType.IAM_ROLE));
            assertEquals("jane.doe", dz.searchUserProfiles(r -> r.domainIdentifier(domainId)
                    .userType(UserSearchType.DATAZONE_SSO_USER).searchText("jane")).items().getFirst()
                    .details().sso().username());
            assertEquals(1, dz.searchUserProfiles(r -> r.domainIdentifier(domainId)
                    .userType(UserSearchType.DATAZONE_IAM_USER).searchText("analyst")).items().size());
            assertEquals(2, dz.searchUserProfiles(r -> r.domainIdentifier(domainId)
                    .userType(UserSearchType.DATAZONE_USER)).items().size());
            assertThrows(ValidationException.class, () -> dz.searchUserProfiles(r -> r.domainIdentifier(domainId)));

            GetIamPortalLoginUrlResponse login = dz.getIamPortalLoginUrl(r -> r.domainIdentifier(domainId));
            assertTrue(login.authCodeUrl().startsWith("https://" + domainId + ".datazone."));
            assertEquals(login.userProfileId(), dz.getIamPortalLoginUrl(r -> r.domainIdentifier(domainId))
                    .userProfileId());

            assertThrows(ResourceNotFoundException.class, () -> dz.getDataExportConfiguration(r -> r
                    .domainIdentifier(domainId)));
            dz.putDataExportConfiguration(r -> r.domainIdentifier(domainId).enableExport(true)
                    .encryptionConfiguration(EncryptionConfiguration.builder().sseAlgorithm("AES256").build()));
            GetDataExportConfigurationResponse export = dz.getDataExportConfiguration(r -> r
                    .domainIdentifier(domainId));
            assertTrue(export.isExportEnabled());
            assertEquals("AES256", export.encryptionConfiguration().sseAlgorithm());
            assertNotNull(export.createdAt());
            dz.deleteDataExportConfiguration(r -> r.domainIdentifier(domainId));
            assertThrows(ResourceNotFoundException.class, () -> dz.deleteDataExportConfiguration(r -> r
                    .domainIdentifier(domainId)));

            assertTrue(dz.listNotifications(r -> r.domainIdentifier(domainId).type(NotificationType.TASK))
                    .notifications().isEmpty());
            assertThrows(ValidationException.class, () -> dz.listNotifications(r -> r.domainIdentifier(domainId)));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void notebooksRunExportImportAndSync() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("notebooks-" + suffix())).id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("science")).id();

            CreateNotebookResponse notebook = dz.createNotebook(r -> r.domainIdentifier(domainId)
                    .owningProjectIdentifier(projectId).name("churn").type(NotebookType.DATA)
                    .parameters(Map.of("month", "10")));
            assertEquals(NotebookStatus.ACTIVE, notebook.status());
            assertEquals(projectId, notebook.owningProjectId());
            assertNotNull(notebook.createdAt());
            assertThrows(ResourceNotFoundException.class, () -> dz.createNotebook(r -> r.domainIdentifier(domainId)
                    .owningProjectIdentifier("missing").name("x")));
            assertEquals("Churn model", dz.updateNotebook(r -> r.domainIdentifier(domainId)
                    .identifier(notebook.id()).description("Churn model")).description());
            assertEquals(List.of(notebook.id()), dz.listNotebooks(r -> r.domainIdentifier(domainId)
                    .owningProjectIdentifier(projectId)).items().stream().map(n -> n.id()).toList());

            String runId = dz.startNotebookRun(r -> r.domainIdentifier(domainId)
                    .owningProjectIdentifier(projectId).notebookIdentifier(notebook.id())).id();
            GetNotebookRunResponse run = dz.getNotebookRun(r -> r.domainIdentifier(domainId).identifier(runId));
            assertEquals(NotebookRunStatus.SUCCEEDED, run.status());
            assertEquals("10", run.parameters().get("month"));
            assertNotNull(run.completedAt());
            assertThrows(ConflictException.class, () -> dz.stopNotebookRun(r -> r.domainIdentifier(domainId)
                    .identifier(runId)));
            assertEquals(1, dz.listNotebookRuns(r -> r.domainIdentifier(domainId).notebookIdentifier(notebook.id())
                    .sortOrder(SortOrder.ASCENDING)).items().size());

            String exportId = dz.startNotebookExport(r -> r.domainIdentifier(domainId)
                    .owningProjectIdentifier(projectId).notebookIdentifier(notebook.id())
                    .fileFormat(FileFormat.IPYNB)).id();
            GetNotebookExportResponse export = dz.getNotebookExport(r -> r.domainIdentifier(domainId)
                    .identifier(exportId));
            assertEquals(NotebookExportStatus.SUCCEEDED, export.status());
            assertTrue(export.outputLocation().s3().uri().endsWith(exportId + ".ipynb"));

            StartNotebookImportResponse imported = dz.startNotebookImport(r -> r.domainIdentifier(domainId)
                    .owningProjectIdentifier(projectId).name("imported")
                    .sourceLocation(SourceLocation.fromS3("s3://bucket/imported.ipynb")));
            assertEquals("imported", dz.getNotebook(r -> r.domainIdentifier(domainId)
                    .identifier(imported.notebookId())).name());

            StartNotebookSyncResponse synced = dz.startNotebookSync(r -> r.domainIdentifier(domainId)
                    .owningProjectIdentifier(projectId).notebookId(notebook.id())
                    .gitMetadata(GitMetadata.builder().repository("analytics").branch("main").build()));
            assertEquals(NotebookStatus.ACTIVE, synced.status());
            assertEquals("main", dz.getNotebook(r -> r.domainIdentifier(domainId).identifier(notebook.id()))
                    .gitMetadata().branch());

            assertThrows(ResourceNotFoundException.class, () -> dz.getJobRun(r -> r.domainIdentifier(domainId)
                    .identifier("run-1")));
            assertTrue(dz.listJobRuns(r -> r.domainIdentifier(domainId).jobIdentifier("job-1")).items().isEmpty());

            dz.deleteNotebook(r -> r.domainIdentifier(domainId).identifier(notebook.id()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getNotebook(r -> r.domainIdentifier(domainId)
                    .identifier(notebook.id())));
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
