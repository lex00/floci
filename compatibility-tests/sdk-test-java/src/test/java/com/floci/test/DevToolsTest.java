package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.codebuild.CodeBuildClient;
import software.amazon.awssdk.services.codebuild.model.ComputeType;
import software.amazon.awssdk.services.codebuild.model.EnvironmentType;
import software.amazon.awssdk.services.codebuild.model.Fleet;
import software.amazon.awssdk.services.codecommit.CodeCommitClient;
import software.amazon.awssdk.services.codecommit.model.RepositoryDoesNotExistException;
import software.amazon.awssdk.services.codecommit.model.RepositoryMetadata;
import software.amazon.awssdk.services.codeconnections.CodeConnectionsClient;
import software.amazon.awssdk.services.codeconnections.model.Connection;
import software.amazon.awssdk.services.codeconnections.model.ConnectionStatus;
import software.amazon.awssdk.services.codeconnections.model.ProviderType;
import software.amazon.awssdk.services.ecrpublic.EcrPublicClient;
import software.amazon.awssdk.services.ecrpublic.model.Repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CodeBuild fleets, CodeCommit, CodeConnections and ECR Public through the AWS SDK. */
class DevToolsTest {

    @Test
    void codeBuildFleetRoundTrips() {
        try (CodeBuildClient codebuild = TestFixtures.codeBuildClient()) {
            Fleet fleet = codebuild.createFleet(r -> r.name("sdk-compat-fleet").baseCapacity(1)
                    .environmentType(EnvironmentType.LINUX_CONTAINER)
                    .computeType(ComputeType.BUILD_GENERAL1_SMALL)).fleet();
            try {
                assertEquals("ACTIVE", fleet.status().statusCodeAsString());
                assertNotNull(fleet.created());
                assertEquals(1, codebuild.batchGetFleets(r -> r.names(fleet.arn())).fleets().size());
            } finally {
                codebuild.deleteFleet(r -> r.arn(fleet.arn()));
            }
        }
    }

    @Test
    void codeCommitRepositoryRoundTrips() {
        try (CodeCommitClient codecommit = TestFixtures.codeCommitClient()) {
            RepositoryMetadata created = codecommit.createRepository(r -> r.repositoryName("sdk-compat-repo")
                    .repositoryDescription("compat")).repositoryMetadata();
            try {
                assertNotNull(created.creationDate());
                assertTrue(created.cloneUrlHttp().endsWith("/v1/repos/sdk-compat-repo"));
                assertEquals("compat", codecommit.getRepository(r -> r.repositoryName("sdk-compat-repo"))
                        .repositoryMetadata().repositoryDescription());
            } finally {
                codecommit.deleteRepository(r -> r.repositoryName("sdk-compat-repo"));
            }
            assertThrows(RepositoryDoesNotExistException.class,
                    () -> codecommit.getRepository(r -> r.repositoryName("sdk-compat-repo")));
        }
    }

    @Test
    void codeConnectionsConnectionRoundTrips() {
        try (CodeConnectionsClient connections = TestFixtures.codeConnectionsClient()) {
            String arn = connections.createConnection(r -> r.connectionName("sdk-compat")
                    .providerType(ProviderType.GIT_HUB)).connectionArn();
            try {
                Connection connection = connections.getConnection(r -> r.connectionArn(arn)).connection();
                assertEquals(ConnectionStatus.PENDING, connection.connectionStatus());
            } finally {
                connections.deleteConnection(r -> r.connectionArn(arn));
            }
        }
    }

    @Test
    void ecrPublicRepositoryRoundTrips() {
        try (EcrPublicClient ecrPublic = TestFixtures.ecrPublicClient()) {
            Repository repository = ecrPublic.createRepository(r -> r.repositoryName("sdk-compat-public")
                    .catalogData(c -> c.description("compat"))).repository();
            try {
                assertNotNull(repository.createdAt());
                assertEquals("compat", ecrPublic.getRepositoryCatalogData(r -> r.repositoryName("sdk-compat-public"))
                        .catalogData().description());
            } finally {
                ecrPublic.deleteRepository(r -> r.repositoryName("sdk-compat-public"));
            }
        }
    }
}
