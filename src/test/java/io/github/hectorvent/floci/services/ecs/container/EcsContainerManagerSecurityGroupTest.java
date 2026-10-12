package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the FireLens launch-loop rewrite dropping the awsvpc
 * security-group namespace join. prepareNetwork still creates the ENI;
 * this asserts the per-container loop actually joins it.
 */
class EcsContainerManagerSecurityGroupTest {

    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private DockerClient dockerClient;
    private ContainerDetector containerDetector;
    private RegionResolver regionResolver;
    private Ec2Service ec2Service;
    private SecurityGroupFirewallManager firewallManager;
    private EmulatorConfig config;
    private ContainerBuilder containerBuilder;
    private EcsTaskRoleCredentials taskRoleCredentials;
    private EcsCredentialsProxy credentialsProxy;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.resolveImageForLaunch(any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("docker-id", Map.of()));
        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);

        containerDetector = mock(ContainerDetector.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        ec2Service = mock(Ec2Service.class);
        firewallManager = mock(SecurityGroupFirewallManager.class);
        taskRoleCredentials = mock(EcsTaskRoleCredentials.class);
        credentialsProxy = mock(EcsCredentialsProxy.class);

        manager = new EcsContainerManager(
                containerBuilder, lifecycleManager, mock(ContainerLogStreamer.class),
                containerDetector, config, regionResolver, awsEnv,
                mock(SsmService.class), mock(SecretsManagerService.class), mock(S3Service.class),
                ecrRegistryManager, mock(HostVolumePolicy.class),
                ec2Service, firewallManager,
                taskRoleCredentials, credentialsProxy,
                new EcsTaskLinkLocalAddresses());
    }

    @Test
    void awsvpcTaskWithFirewallJoinsHelperNamespaceAndSkipsHostPorts() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "10.0.0.5"));

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);

        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any()))
                .thenReturn(List.of(sg));

        EcsTaskHandle handle = manager.startTask(awsvpcTask(), awsvpcTaskDef(List.of(new PortMapping(80, 80, "tcp"))),
                List.of(), "us-east-1");

        verify(builder).withNetworkMode("container:helper-id");
        verify(builder).withLabels(Map.of("io.floci.security-group.workload", "true"));
        verify(builder, never()).withPortBinding(anyInt(), anyInt());
        verify(builder, never()).withDynamicPort(anyInt());
        verify(builder, never()).withExposedPort(anyInt());
        // Port bindings come off the helper, whose namespace holds them; the workload container
        // shares that namespace and publishes none, and is inspected only for its launch clock.
        verify(dockerClient).inspectContainerCmd("helper-id");
        verify(dockerClient, times(1)).inspectContainerCmd("docker-id");
        verify(firewallManager).register(any(), eq("helper-id"), any());
        assertEquals("eni-1", handle.getNetworkInterfaceId());
        assertEquals("us-east-1", handle.getRegion());
    }

    @Test
    void protectedTaskGetsItsRoleCredentialsThroughTheHelpersLinkLocalAddress() {
        String relativeUri = "/v2/credentials/0b5f1a6e-8c21-4c2e-9a4c-2f0d2b6c7a10";
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);
        when(containerBuilder.resolveDockerNetwork(any())).thenReturn(Optional.of("floci-ecs-net"));
        when(taskRoleCredentials.issue(any(), any(), any(), any())).thenReturn(Optional.of(relativeUri));
        stubProtectedNetwork();

        TaskDefinition taskDef = awsvpcTaskDef(List.of());
        taskDef.setTaskRoleArn("arn:aws:iam::000000000000:role/ecsTaskRole");
        manager.startTask(awsvpcTask(), taskDef, List.of(), "us-east-1");

        verify(credentialsProxy).ensureProxyOn("floci-ecs-net");
        // The task's one address goes on the helper, whose namespace every container shares.
        verify(firewallManager).createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(),
                eq("169.254.170.1"));
        verify(builder, never()).withLinkLocalIp(anyString());
        verify(builder).withNetworkMode("container:helper-id");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> env = ArgumentCaptor.forClass(List.class);
        verify(builder).withEnv(env.capture());
        assertTrue(env.getValue().contains("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI=" + relativeUri),
                env.getValue().toString());
    }

    @Test
    void protectedTaskRevokesItsCredentialsWhenTheNamespaceCannotBeBuilt() {
        when(config.services().ecs().taskRoleCredentials().enabled()).thenReturn(true);
        when(containerBuilder.resolveDockerNetwork(any())).thenReturn(Optional.of("floci-ecs-net"));
        when(taskRoleCredentials.issue(any(), any(), any(), any()))
                .thenReturn(Optional.of("/v2/credentials/0b5f1a6e-8c21-4c2e-9a4c-2f0d2b6c7a10"));
        stubProtectedNetwork();
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("rootless Docker"));

        TaskDefinition taskDef = awsvpcTaskDef(List.of());
        taskDef.setTaskRoleArn("arn:aws:iam::000000000000:role/ecsTaskRole");
        assertThrows(IllegalStateException.class,
                () -> manager.startTask(awsvpcTask(), taskDef, List.of(), "us-east-1"));

        verify(taskRoleCredentials).revoke("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
    }

    private void stubProtectedNetwork() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "10.0.0.5"));
        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any()))
                .thenReturn(List.of(sg));
    }

    @Test
    void disabledFirewallDoesNotJoinNamespace() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(false);

        manager.startTask(awsvpcTask(), awsvpcTaskDef(List.of(new PortMapping(80, 80, "tcp"))),
                List.of(), "us-east-1");

        verify(builder, never()).withNetworkMode(anyString());
        verify(builder, never()).withLabels(Map.of("io.floci.security-group.workload", "true"));
        verify(ec2Service, never()).createNetworkInterface(any(), any(), any(), any(), any(), any(), any());
        verify(builder).withDynamicPort(80);
    }

    @Test
    void stopTaskUnregistersEniAndReleasingTheTaskNetworkDeletesIt() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "10.0.0.5"));

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any()))
                .thenReturn(List.of(sg));

        DockerClient stopClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient, dockerClient, stopClient);

        EcsTask task = awsvpcTask();
        EcsTaskHandle handle = manager.startTask(task, awsvpcTaskDef(List.of()),
                List.of(), "us-east-1");
        manager.stopTaskAndCollectExitCodes(handle);

        // Stopping the containers tears down their firewall registration, but the ENI belongs to
        // the task: it lives until the task itself is released.
        verify(firewallManager, atLeastOnce()).unregister("eni-1");
        verify(ec2Service, never()).deleteNetworkInterface(any(), any());

        manager.releaseTaskNetwork(task, "us-east-1");
        verify(ec2Service).deleteNetworkInterface("us-east-1", "eni-1");
    }

    @Test
    void firelensRouterAndAppBothJoinTheHelperNamespace() {
        when(containerDetector.isRunningInContainer()).thenReturn(false);
        when(firewallManager.enabled()).thenReturn(true);
        when(firewallManager.createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any()))
                .thenReturn(new SecurityGroupFirewallManager.Namespace("helper-id", "10.0.0.5"));

        NetworkInterface eni = new NetworkInterface();
        eni.setNetworkInterfaceId("eni-1");
        eni.setVpcId("vpc-1");
        eni.setPrivateIpAddress("10.0.0.10");
        eni.setGroups(List.of(new GroupIdentifier("sg-1", "default")));
        when(ec2Service.createNetworkInterface(any(), eq("subnet-1"), any(), any(), any(), any(), any()))
                .thenReturn(eni);
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-1");
        when(ec2Service.describeSecurityGroups(any(), eq(List.of("sg-1")), any(), any()))
                .thenReturn(List.of(sg));

        when(lifecycleManager.create(any())).thenReturn("router-id");
        when(lifecycleManager.startCreated(anyString(), any()))
                .thenReturn(new ContainerInfo("router-id", Map.of()));
        InspectVolumeCmd inspectVolumeCmd = mock(InspectVolumeCmd.class);
        InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
        when(dockerClient.inspectVolumeCmd(anyString())).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenReturn(volume);
        when(volume.getMountpoint()).thenReturn("/var/lib/docker/volumes/floci-ecs-firelens-abc123/_data");
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("router-id")).thenReturn(copyCmd);

        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("amazon/aws-for-fluent-bit:stable");
        router.setFirelensConfiguration(new io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration(
                "fluentbit", Map.of()));
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new io.github.hectorvent.floci.services.ecs.model.LogConfiguration(
                "awsfirelens", Map.of("Name", "cloudwatch"), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("firelens-family");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app, router));

        manager.startTask(awsvpcTask(), taskDef, List.of(), "us-east-1");

        verify(builder, times(2)).withNetworkMode("container:helper-id");
        verify(builder, times(2)).withLabels(Map.of("io.floci.security-group.workload", "true"));

        verify(firewallManager).createNamespace(eq("ecs"), eq("abc123"), any(), any(), any(), any(), any(), any());
        verify(lifecycleManager).create(any());
        verify(lifecycleManager).startCreated(eq("router-id"), any());
        verify(lifecycleManager).createAndStart(any());
    }

    private static EcsTask awsvpcTask() {
        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of("subnet-1"));
        awsvpc.setSecurityGroups(List.of("sg-1"));
        NetworkConfiguration network = new NetworkConfiguration();
        network.setAwsvpcConfiguration(awsvpc);
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setNetworkConfiguration(network);
        return task;
    }

    private static TaskDefinition awsvpcTaskDef(List<PortMapping> ports) {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setPortMappings(ports);
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("test-family");
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app));
        return taskDef;
    }
}
