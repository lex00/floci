package io.github.hectorvent.floci.services.ecs;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Network;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.SecurityGroupFirewallManager;
import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ecs.container.EcsCredentialsProxy;
import io.github.hectorvent.floci.services.ecs.model.AwsVpcConfiguration;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.KeyValuePair;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.NetworkConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.testing.TestImages;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A task under security-group enforcement fetches its role credentials from 169.254.170.2 and
 * calls STS with them, from inside the protected namespace, while the same security group still
 * drops a port it does not allow. The denied port is the credentials server's own port on the
 * Floci host, so the check also shows that the endpoint exception does not open a path around the
 * proxy.
 *
 * <p>Skipped without a rootful Linux Docker daemon, which security-group enforcement requires.
 */
@QuarkusTest
@TestProfile(EcsSecurityGroupTaskRoleDockerIntegrationTest.ProtectedTaskRoleProfile.class)
class EcsSecurityGroupTaskRoleDockerIntegrationTest {

    private static final Logger LOG = Logger.getLogger(EcsSecurityGroupTaskRoleDockerIntegrationTest.class);
    private static final String REGION = "us-east-1";
    private static final String SUBNET = "subnet-default-us-east-1-a";
    private static final String NETWORK = "floci-ecs-sg-task-role-it";
    private static final int CREDENTIALS_PORT = freePort();
    private static final long TIMEOUT_MILLIS = 90_000;

    /** STS GetCallerIdentity signed with the vended credentials, using only the Python stdlib. */
    private static final String TASK_SCRIPT = """
            import datetime, hashlib, hmac, json, os, re, socket, sys, time, urllib.parse, urllib.request
            uri = os.environ["AWS_CONTAINER_CREDENTIALS_RELATIVE_URI"]
            creds = json.loads(urllib.request.urlopen("http://169.254.170.2" + uri, timeout=10).read())
            print("CREDENTIALS " + creds["AccessKeyId"], flush=True)
            endpoint = urllib.parse.urlparse(os.environ["FLOCI_URL"])
            body = "Action=GetCallerIdentity&Version=2011-06-15"
            now = datetime.datetime.now(datetime.timezone.utc)
            amz_date, day = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
            region = os.environ.get("AWS_REGION", "us-east-1")
            headers = {"content-type": "application/x-www-form-urlencoded; charset=utf-8",
                       "host": endpoint.netloc, "x-amz-date": amz_date, "x-amz-security-token": creds["Token"]}
            signed = ";".join(sorted(headers))
            canonical = "\\n".join(["POST", "/", "", "".join(k + ":" + headers[k] + "\\n" for k in sorted(headers)),
                                    signed, hashlib.sha256(body.encode()).hexdigest()])
            scope = day + "/" + region + "/sts/aws4_request"
            to_sign = "\\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical.encode()).hexdigest()])
            key = ("AWS4" + creds["SecretAccessKey"]).encode()
            for part in (day, region, "sts", "aws4_request"):
                key = hmac.new(key, part.encode(), hashlib.sha256).digest()
            signature = hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()
            headers["authorization"] = ("AWS4-HMAC-SHA256 Credential=" + creds["AccessKeyId"] + "/" + scope
                                        + ", SignedHeaders=" + signed + ", Signature=" + signature)
            request = urllib.request.Request(endpoint.geturl().rstrip("/") + "/", data=body.encode(),
                                             headers=headers, method="POST")
            reply = urllib.request.urlopen(request, timeout=10).read().decode()
            print("IDENTITY " + re.search("<Arn>([^<]+)</Arn>", reply).group(1), flush=True)
            probe = socket.socket()
            probe.settimeout(3)
            try:
                probe.connect((endpoint.hostname, int(os.environ["DENIED_PORT"])))
                print("DENIED_PORT open", flush=True)
            except OSError as e:
                print("DENIED_PORT blocked " + type(e).__name__, flush=True)
            time.sleep(600)
            """;

    /**
     * Real containers, task-role credentials on, and a user-defined network for the proxy.
     * Security-group enforcement is already on in the test configuration.
     */
    public static final class ProtectedTaskRoleProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ecs.mock", "false",
                    "floci.services.ecs.docker-network", NETWORK,
                    "floci.services.ecs.task-role-credentials.enabled", "true",
                    "floci.services.ecs.task-role-credentials.port", String.valueOf(CREDENTIALS_PORT));
        }
    }

    @Inject
    EcsService ecsService;

    @Inject
    Ec2Service ec2Service;

    @Inject
    SecurityGroupFirewallManager firewallManager;

    @Inject
    EcsCredentialsProxy credentialsProxy;

    @Inject
    DockerClient dockerClient;

    private String networkId;

    @BeforeEach
    void requireRootfulLinuxDocker() {
        Assumptions.assumeTrue(firewallManager.enabled());
        try {
            Assumptions.assumeTrue("linux".equalsIgnoreCase(dockerClient.infoCmd().exec().getOsType()));
        } catch (RuntimeException e) {
            Assumptions.abort("Docker daemon is unavailable");
        }
        List<Network> existing = dockerClient.listNetworksCmd().withNameFilter(NETWORK).exec().stream()
                .filter(network -> NETWORK.equals(network.getName())).toList();
        networkId = existing.isEmpty()
                ? dockerClient.createNetworkCmd().withName(NETWORK).exec().getId()
                : existing.getFirst().getId();
    }

    @AfterEach
    void removeTheProxyAndItsNetwork() {
        credentialsProxy.stopManagedContainers();
        if (networkId != null) {
            try {
                dockerClient.removeNetworkCmd(networkId).exec();
            } catch (RuntimeException e) {
                LOG.warnv("Could not remove test network {0}: {1}", NETWORK, e.getMessage());
            }
        }
    }

    @Test
    void protectedTaskRunsAsItsRoleAndItsGroupStillDropsOtherPorts() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String roleName = "ecs-protected-task-role-" + suffix;
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole").formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":"
                        + "[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"},"
                        + "\"Action\":\"sts:AssumeRole\"}]}")
                .post("/").then().statusCode(200);
        String roleArn = "arn:aws:iam::000000000000:role/" + roleName;

        String vpcId = ec2Service.describeSubnets(REGION, List.of(SUBNET), Map.of()).getFirst().getVpcId();
        SecurityGroup group = ec2Service.createSecurityGroup(REGION, "ecs-protected-" + suffix,
                "egress to the Floci API only", vpcId);
        ec2Service.revokeSecurityGroupEgress(REGION, group.getGroupId(), List.of(permission("-1", null)));
        ec2Service.authorizeSecurityGroupEgress(REGION, group.getGroupId(),
                List.of(permission("tcp", RestAssured.port)));

        String cluster = "ecs-protected-" + suffix;
        ecsService.createCluster(cluster, REGION);
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage(TestImages.PYTHON_ALPINE);
        app.setEntryPoint(List.of("python3", "-u", "-c"));
        app.setCommand(List.of(TASK_SCRIPT));
        // The test listener is on a random port, unlike the base URL that AWS_ENDPOINT_URL is built from.
        app.setEnvironment(List.of(
                new KeyValuePair("FLOCI_URL", "http://host.docker.internal:" + RestAssured.port),
                new KeyValuePair("DENIED_PORT", String.valueOf(CREDENTIALS_PORT))));
        TaskDefinition taskDef = ecsService.registerTaskDefinition("ecs-protected-" + suffix, List.of(app),
                NetworkMode.awsvpc, null, null, roleArn, null, null, REGION);

        AwsVpcConfiguration awsvpc = new AwsVpcConfiguration();
        awsvpc.setSubnets(List.of(SUBNET));
        awsvpc.setSecurityGroups(List.of(group.getGroupId()));
        NetworkConfiguration networkConfiguration = new NetworkConfiguration();
        networkConfiguration.setAwsvpcConfiguration(awsvpc);

        String taskArn = ecsService.runTask(cluster, taskDef.getTaskDefinitionArn(), 1, LaunchType.FARGATE,
                null, null, List.of(), networkConfiguration, REGION).getFirst().getTaskArn();
        try {
            String logs = awaitLogs(cluster, taskArn, "DENIED_PORT ");
            assertTrue(logs.contains("CREDENTIALS ASIA"), logs);
            assertTrue(logs.contains("IDENTITY arn:aws:sts::000000000000:assumed-role/" + roleName + "/"), logs);
            assertTrue(logs.contains("DENIED_PORT blocked"), logs);
        } finally {
            ecsService.stopTask(cluster, taskArn, "test teardown", REGION);
        }
    }

    /** Waits for the task's container to print a line starting with {@code marker}. */
    private String awaitLogs(String cluster, String taskArn, String marker) {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        String logs = "";
        while (System.currentTimeMillis() < deadline) {
            EcsTask task = ecsService.describeTasks(cluster, List.of(taskArn), REGION).getFirst();
            if ("STOPPED".equals(task.getLastStatus())) {
                fail("task stopped: " + task.getStoppedReason() + "\n" + logs);
            }
            String dockerId = task.getContainers().isEmpty() ? null : task.getContainers().getFirst().getDockerId();
            if (dockerId != null) {
                logs = logs(dockerId);
                if (logs.contains(marker)) {
                    return logs;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        fail("no " + marker.trim() + " line within " + TIMEOUT_MILLIS + " ms\n" + logs);
        return logs;
    }

    private String logs(String containerId) {
        StringBuilder out = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            out.append("(could not read logs: ").append(e.getMessage()).append(')');
        }
        return out.toString();
    }

    private static IpPermission permission(String protocol, Integer port) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol(protocol);
        if (port != null) {
            permission.setFromPort(port);
            permission.setToPort(port);
        }
        IpRange anywhere = new IpRange();
        anywhere.setCidrIp("0.0.0.0/0");
        permission.getIpRanges().add(anywhere);
        return permission;
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
