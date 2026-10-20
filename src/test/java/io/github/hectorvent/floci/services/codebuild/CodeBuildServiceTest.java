package io.github.hectorvent.floci.services.codebuild;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codebuild.model.Build;
import io.github.hectorvent.floci.services.codebuild.model.Fleet;
import io.github.hectorvent.floci.services.codebuild.model.Project;
import io.github.hectorvent.floci.services.codebuild.model.ProjectArtifacts;
import io.github.hectorvent.floci.services.codebuild.model.ProjectEnvironment;
import io.github.hectorvent.floci.services.codebuild.model.ProjectSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CodeBuildServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    @Test
    void retryBuildRetainsOriginalBuildspecOverride() {
        CodeBuildRunner runner = mock(CodeBuildRunner.class);
        CodeBuildService service = new CodeBuildService(runner, mock(EmulatorConfig.class), null);
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");

        service.createProject(REGION, ACCOUNT, "retry-project", null, source, null, null,
                artifacts, null, new ProjectEnvironment(), "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);

        String buildspec = "version: 0.2\nphases:\n  build:\n    commands:\n      - echo retry\n";
        Build original = service.startBuild(REGION, ACCOUNT, "retry-project", buildspec,
                null, null, null, null, null, null);
        Build retried = service.retryBuild(REGION, ACCOUNT, original.getId());

        assertNotEquals(original.getId(), retried.getId());
        ArgumentCaptor<String> buildspecOverrides = ArgumentCaptor.forClass(String.class);
        verify(runner, times(2)).startBuild(eq(REGION), any(Build.class), any(Project.class),
                buildspecOverrides.capture());
        assertEquals(List.of(buildspec, buildspec), buildspecOverrides.getAllValues());
    }

    @Test
    void fleetLifecycleResolvesByNameAndArn() {
        CodeBuildService service = new CodeBuildService(mock(CodeBuildRunner.class), mock(EmulatorConfig.class), null);
        Fleet request = new Fleet();
        request.setName("build-fleet");
        request.setBaseCapacity(1);
        request.setEnvironmentType("LINUX_CONTAINER");
        request.setComputeType("BUILD_GENERAL1_SMALL");

        Fleet created = service.createFleet(REGION, ACCOUNT, request);

        assertTrue(created.getArn().startsWith("arn:aws:codebuild:us-east-1:000000000000:fleet/build-fleet:"));
        assertEquals("ACTIVE", created.getStatus().get("statusCode"));
        assertEquals("QUEUE", created.getOverflowBehavior());
        assertEquals(List.of(created.getArn()), service.listFleets(REGION, null, null));
        assertEquals(1, service.batchGetFleets(REGION, List.of("build-fleet")).size());

        Fleet changes = new Fleet();
        changes.setBaseCapacity(3);
        assertEquals(3, service.updateFleet(REGION, created.getArn(), changes).getBaseCapacity());

        AwsException duplicate = assertThrows(AwsException.class, () -> service.createFleet(REGION, ACCOUNT, request));
        assertEquals("ResourceAlreadyExistsException", duplicate.getErrorCode());

        service.deleteFleet(REGION, created.getArn());
        assertTrue(service.listFleets(REGION, null, null).isEmpty());
    }

    @Test
    void buildOnAFleetRunsOnTheFleetsSlots() {
        CodeBuildRunner runner = mock(CodeBuildRunner.class);
        CodeBuildService service = new CodeBuildService(runner, mock(EmulatorConfig.class), null);
        Fleet request = new Fleet();
        request.setName("slots");
        request.setBaseCapacity(2);
        request.setEnvironmentType("LINUX_CONTAINER");
        request.setComputeType("BUILD_GENERAL1_SMALL");
        Fleet fleet = service.createFleet(REGION, ACCOUNT, request);
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        ProjectEnvironment environment = new ProjectEnvironment();
        environment.setType("LINUX_CONTAINER");
        environment.setImage("aws/codebuild/standard:7.0");
        environment.setFleet(Map.of("fleetArn", "slots"));

        Project project = service.createProject(REGION, ACCOUNT, "fleet-project", null, source, null, null,
                artifacts, null, environment, "arn:aws:iam::000000000000:role/codebuild",
                null, null, null, null, null, null, null);
        assertEquals(fleet.getArn(), project.getEnvironment().getFleet().get("fleetArn"));
        assertEquals("BUILD_GENERAL1_SMALL", project.getEnvironment().getComputeType());

        Build build = service.startBuild(REGION, ACCOUNT, "fleet-project", null, null, null, null, null, null, null);
        assertEquals(fleet.getArn(), build.getEnvironment().getFleet().get("fleetArn"));
        ArgumentCaptor<CodeBuildRunner.FleetLease> lease = ArgumentCaptor.forClass(CodeBuildRunner.FleetLease.class);
        verify(runner).startBuild(eq(REGION), any(Build.class), any(Project.class), any(), lease.capture());
        assertEquals(fleet.getArn(), lease.getValue().fleetArn());
        assertEquals(2, lease.getValue().capacity().getAsInt());
        assertEquals(false, lease.getValue().onDemand());
    }

    @Test
    void fleetMustExistAndRunTheProjectsEnvironmentType() {
        CodeBuildService service = new CodeBuildService(mock(CodeBuildRunner.class), mock(EmulatorConfig.class), null);
        Fleet request = new Fleet();
        request.setName("arm");
        request.setBaseCapacity(1);
        request.setEnvironmentType("ARM_CONTAINER");
        request.setComputeType("BUILD_GENERAL1_SMALL");
        service.createFleet(REGION, ACCOUNT, request);
        ProjectSource source = new ProjectSource();
        source.setType("NO_SOURCE");
        ProjectArtifacts artifacts = new ProjectArtifacts();
        artifacts.setType("NO_ARTIFACTS");
        ProjectEnvironment linux = new ProjectEnvironment();
        linux.setType("LINUX_CONTAINER");
        linux.setFleet(Map.of("fleetArn", "arm"));
        ProjectEnvironment missing = new ProjectEnvironment();
        missing.setFleet(Map.of("fleetArn", "nope"));

        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> service.createProject(REGION,
                ACCOUNT, "mismatch", null, source, null, null, artifacts, null, linux,
                "arn:aws:iam::000000000000:role/codebuild", null, null, null, null, null, null, null))
                .getErrorCode());
        assertEquals("InvalidInputException", assertThrows(AwsException.class, () -> service.createProject(REGION,
                ACCOUNT, "missing", null, source, null, null, artifacts, null, missing,
                "arn:aws:iam::000000000000:role/codebuild", null, null, null, null, null, null, null))
                .getErrorCode());
    }
}
