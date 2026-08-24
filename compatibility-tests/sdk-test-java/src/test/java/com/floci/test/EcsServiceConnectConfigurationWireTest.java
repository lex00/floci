package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.AssignPublicIp;
import software.amazon.awssdk.services.ecs.model.AwsVpcConfiguration;
import software.amazon.awssdk.services.ecs.model.Compatibility;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.CreateServiceResponse;
import software.amazon.awssdk.services.ecs.model.Deployment;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.LaunchType;
import software.amazon.awssdk.services.ecs.model.NetworkConfiguration;
import software.amazon.awssdk.services.ecs.model.NetworkMode;
import software.amazon.awssdk.services.ecs.model.RegisterTaskDefinitionRequest;
import software.amazon.awssdk.services.ecs.model.Service;
import software.amazon.awssdk.services.ecs.model.ServiceConnectClientAlias;
import software.amazon.awssdk.services.ecs.model.ServiceConnectConfiguration;
import software.amazon.awssdk.services.ecs.model.ServiceConnectService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level regression coverage for lex00/floci#110: {@code serviceConnectConfiguration} must
 * round-trip through {@code CreateService}/{@code DescribeServices} in a way the REAL AWS SDK
 * can see.
 *
 * <p>Before this fix, floci echoed {@code serviceConnectConfiguration} back on the top-level
 * {@code Service} JSON object. That is technically present in the raw wire JSON - a hand-rolled
 * JSON-path assertion (as {@code EcsCreateFieldRoundtripIntegrationTest} used to have) reads it
 * fine - but it is NOT where AWS's own documented {@code Service} shape puts it. AWS's real API
 * model (confirmed against both botocore's and the AWS SDK for Java v2's generated ECS models)
 * has no top-level {@code serviceConnectConfiguration} member on {@code Service} at all; the
 * field lives only on each {@code deployments[]} entry (and on {@code ServiceRevision}). A
 * client that parses strictly against that shape - the AWS CLI, every AWS SDK, and Terraform's
 * AWS provider (generated from the same model) - silently drops an unmodeled field at the wrong
 * location. That is precisely why corpus-ecs-fargate saw a perpetual
 * {@code + service_connect_configuration {...}} diff on every plan even though floci's own raw
 * JSON response technically "had" the data: Terraform's provider never saw it.
 *
 * <p>This test uses {@code software.amazon.awssdk:ecs}, the real AWS Java SDK v2, so a
 * regression back to the top-level location fails here exactly the way it fails for Terraform -
 * {@code Service.serviceConnectConfiguration()} does not exist as a method on the real SDK's
 * {@code Service} POJO in the first place, so the only way to observe the value at all is via
 * {@code Service.deployments().get(0).serviceConnectConfiguration()}.
 */
@DisplayName("ECS Service Connect configuration wire fidelity (#110)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EcsServiceConnectConfigurationWireTest {

    private static EcsClient ecs;
    private static String suffix;
    private static String clusterName;
    private static String family;
    private static String serviceName;

    @BeforeAll
    static void setup() {
        ecs = TestFixtures.ecsClient();
        suffix = String.valueOf(System.currentTimeMillis() % 100000);
        clusterName = "scc-wire-cluster-" + suffix;
        family = "scc-wire-task-" + suffix;
        serviceName = "scc-wire-svc-" + suffix;

        ecs.createCluster(r -> r.clusterName(clusterName));
        ecs.registerTaskDefinition(RegisterTaskDefinitionRequest.builder()
                .family(family)
                .requiresCompatibilities(Compatibility.FARGATE)
                .networkMode(NetworkMode.AWSVPC)
                .cpu("256")
                .memory("512")
                .containerDefinitions(ContainerDefinition.builder()
                        .name("app")
                        .image("nginx:latest")
                        .essential(true)
                        .build())
                .build());
    }

    @AfterAll
    static void cleanup() {
        if (ecs != null) {
            try {
                ecs.deleteService(r -> r.cluster(clusterName).service(serviceName).force(true));
            } catch (Exception ignored) { }
            try {
                ecs.deleteCluster(r -> r.cluster(clusterName));
            } catch (Exception ignored) { }
            ecs.close();
        }
    }

    private static ServiceConnectConfiguration testServiceConnectConfiguration() {
        return ServiceConnectConfiguration.builder()
                .enabled(true)
                .namespace("wire-test-ns")
                .services(ServiceConnectService.builder()
                        .portName("web")
                        .discoveryName("web-svc")
                        .clientAliases(ServiceConnectClientAlias.builder()
                                .port(80)
                                .dnsName("web.internal")
                                .build())
                        .build())
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("CreateService: the real SDK parses serviceConnectConfiguration off deployments[0], not off Service itself")
    void createServiceSurfacesServiceConnectConfigurationOnDeployment() {
        CreateServiceResponse resp = ecs.createService(r -> r
                .cluster(clusterName)
                .serviceName(serviceName)
                .taskDefinition(family)
                .desiredCount(1)
                .launchType(LaunchType.FARGATE)
                .networkConfiguration(NetworkConfiguration.builder()
                        .awsvpcConfiguration(AwsVpcConfiguration.builder()
                                .subnets("subnet-abc123")
                                .assignPublicIp(AssignPublicIp.ENABLED)
                                .build())
                        .build())
                .serviceConnectConfiguration(testServiceConnectConfiguration()));

        Service service = resp.service();
        assertThat(service).isNotNull();
        assertThat(service.deployments()).isNotEmpty();

        Deployment primary = service.deployments().get(0);
        ServiceConnectConfiguration scc = primary.serviceConnectConfiguration();
        assertThat(scc).as("serviceConnectConfiguration must be visible via the real SDK's "
                + "Deployment shape, matching AWS's documented Service shape").isNotNull();
        assertThat(scc.enabled()).isTrue();
        assertThat(scc.namespace()).isEqualTo("wire-test-ns");
        assertThat(scc.services()).hasSize(1);
        assertThat(scc.services().get(0).portName()).isEqualTo("web");
        assertThat(scc.services().get(0).discoveryName()).isEqualTo("web-svc");
        assertThat(scc.services().get(0).clientAliases()).hasSize(1);
        assertThat(scc.services().get(0).clientAliases().get(0).port()).isEqualTo(80);
        assertThat(scc.services().get(0).clientAliases().get(0).dnsName()).isEqualTo("web.internal");
    }

    @Test
    @Order(2)
    @DisplayName("DescribeServices: serviceConnectConfiguration survives a subsequent describe, on deployments[0]")
    void describeServicesSurfacesServiceConnectConfigurationOnDeployment() {
        Service service = ecs.describeServices(DescribeServicesRequest.builder()
                        .cluster(clusterName)
                        .services(serviceName)
                        .build())
                .services().get(0);

        assertThat(service.deployments()).isNotEmpty();
        ServiceConnectConfiguration scc = service.deployments().get(0).serviceConnectConfiguration();
        assertThat(scc).as("DescribeServices must keep reporting serviceConnectConfiguration on "
                + "the primary deployment, not drop it after CreateService returns it once")
                .isNotNull();
        assertThat(scc.enabled()).isTrue();
        assertThat(scc.namespace()).isEqualTo("wire-test-ns");
        assertThat(scc.services().get(0).discoveryName()).isEqualTo("web-svc");
        assertThat(scc.services().get(0).clientAliases().get(0).dnsName()).isEqualTo("web.internal");
    }
}
