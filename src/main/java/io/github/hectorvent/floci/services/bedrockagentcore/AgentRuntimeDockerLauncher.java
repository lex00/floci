package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.iam.model.SessionCreds;
import io.github.hectorvent.floci.services.lambda.launcher.LambdaExecutionRoleCredentials;
import io.github.hectorvent.floci.services.lambda.zip.ZipExtractor;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.jboss.logging.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Runs an AgentCore runtime version in Docker, the way Lambda runs a function: the container gets
 * the execution role's session credentials and the runtime's environment variables, and its output
 * goes to the runtime endpoint's CloudWatch Logs group.
 *
 * <p>A {@code containerConfiguration} artifact runs its image as built. A {@code codeConfiguration}
 * artifact's S3 zip is unpacked into {@code /var/task} of a Python image and its entry point runs
 * there.
 */
@ApplicationScoped
public class AgentRuntimeDockerLauncher implements AgentRuntimeLauncher {

    static final String TASK_DIR = "/var/task";
    private static final Logger LOG = Logger.getLogger(AgentRuntimeDockerLauncher.class);
    private static final DateTimeFormatter LOG_STREAM_DATE =
            DateTimeFormatter.ofPattern("yyyy/MM/dd").withZone(ZoneOffset.UTC);

    private final EmulatorConfig config;
    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final ContainerLogStreamer logStreamer;
    private final EcrRegistryManager ecrRegistryManager;
    private final S3Service s3Service;
    private final ZipExtractor zipExtractor;
    private final LaunchedContainerAwsEnv awsEnv;
    private final LambdaExecutionRoleCredentials executionRoleCredentials;

    @Inject
    public AgentRuntimeDockerLauncher(EmulatorConfig config, ContainerBuilder containerBuilder,
                                      ContainerLifecycleManager lifecycleManager, ContainerLogStreamer logStreamer,
                                      EcrRegistryManager ecrRegistryManager, S3Service s3Service,
                                      ZipExtractor zipExtractor, LaunchedContainerAwsEnv awsEnv,
                                      LambdaExecutionRoleCredentials executionRoleCredentials) {
        this.config = config;
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.logStreamer = logStreamer;
        this.ecrRegistryManager = ecrRegistryManager;
        this.s3Service = s3Service;
        this.zipExtractor = zipExtractor;
        this.awsEnv = awsEnv;
        this.executionRoleCredentials = executionRoleCredentials;
    }

    /** The log group AgentCore writes a runtime endpoint's output to. */
    static String logGroup(String runtimeId, String endpointName) {
        return "/aws/bedrock-agentcore/runtimes/" + runtimeId + "-" + endpointName;
    }

    @Override
    public RunningRuntime start(Launch launch) {
        AgentRuntimeVersion version = launch.version();
        JsonNode artifact = version.getAgentRuntimeArtifact();
        JsonNode container = artifact == null ? null : artifact.path("containerConfiguration");
        JsonNode code = artifact == null ? null : artifact.path("codeConfiguration");
        boolean fromCode = container == null || !container.path("containerUri").isTextual();
        if (fromCode && (code == null || !code.path("code").path("s3").isObject())) {
            throw new AwsException("ValidationException",
                    "The agent runtime artifact has neither a containerUri nor an S3 code location", 400);
        }
        String accountId = launch.accountId();
        String image = fromCode
                ? codeImage(code.path("runtime").asText("PYTHON_3_12"))
                : ecrRegistryManager.rewriteImageUri(container.path("containerUri").asText());
        Optional<SessionCreds> credentials = executionRoleCredentials.forRole(
                version.getRoleArn(), accountId, launch.runtimeName());
        String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        ContainerBuilder.Builder builder = containerBuilder.newContainer(image)
                .withName(ContainerStorageHelper.prefixedDockerName(config, "floci-agentcore",
                        launch.runtimeId() + "-" + shortId))
                .withEnv(environment(launch, credentials, fromCode))
                .withLoopbackPortBinding(launch.containerPort(), 0)
                .withInit()
                .withDockerNetwork(config.services().lambda().dockerNetwork())
                .withHostDockerInternalOnLinux()
                .withEmbeddedDns()
                .withLogRotation()
                .withLabels(ContainerStorageHelper.resourceIdentityLabels(
                        "bedrock-agentcore", launch.runtimeId(), accountId, launch.region()));
        if (fromCode) {
            builder.withWorkingDir(TASK_DIR).withCmd(codeCommand(code.path("entryPoint")));
        }
        ContainerSpec spec = builder.build();
        String containerId;
        try {
            containerId = lifecycleManager.create(spec);
        } catch (RuntimeException e) {
            forget(accountId, credentials);
            throw e;
        }
        Closeable logs = null;
        try {
            if (fromCode) {
                copyCode(containerId, launch, code.path("code").path("s3"));
            }
            String logStream = LOG_STREAM_DATE.format(Instant.now()) + "/[runtime-logs]" + UUID.randomUUID();
            logs = logStreamer.attachForAccount(accountId, containerId,
                    logGroup(launch.runtimeId(), launch.endpointName()), logStream, launch.region(),
                    "agentcore:" + launch.runtimeId());
            ContainerInfo info = lifecycleManager.startCreated(containerId, spec);
            EndpointInfo endpoint = info.getEndpoint(launch.containerPort());
            LOG.infov("Started AgentCore runtime {0} version {1} in container {2} at {3}:{4}",
                    launch.runtimeId(), version.getVersion(), containerId, endpoint.host(), endpoint.port());
            Closeable logHandle = logs;
            return new RunningRuntime(containerId, endpoint.host(), endpoint.port(), () -> {
                lifecycleManager.stopAndRemove(containerId, logHandle, 1);
                forget(accountId, credentials);
            });
        } catch (RuntimeException e) {
            lifecycleManager.stopAndRemove(containerId, logs, 1);
            forget(accountId, credentials);
            throw e;
        }
    }

    @Override
    public void stop(RunningRuntime runtime) {
        try {
            runtime.cleanup().close();
        } catch (Exception e) {
            LOG.warnv(e, "Could not stop the AgentCore runtime container {0}", runtime.containerId());
        }
    }

    private void forget(String accountId, Optional<SessionCreds> credentials) {
        credentials.ifPresent(creds -> executionRoleCredentials.unregister(accountId, creds.accessKeyId()));
    }

    private List<String> environment(Launch launch, Optional<SessionCreds> credentials, boolean fromCode) {
        List<String> env = new ArrayList<>(awsEnv.sdkBaselineEnv(launch.region(), Optional.empty(), credentials,
                launch.accountId()));
        if (fromCode) {
            env.add("PYTHONPATH=" + TASK_DIR);
            env.add("PYTHONUNBUFFERED=1");
        }
        Map<String, String> userEnv = launch.version().getEnvironmentVariables();
        if (userEnv != null) {
            userEnv.forEach((name, value) -> env.add(name + "=" + value));
        }
        return env;
    }

    /** {@code PYTHON_3_12} runs in the configured image with {@code {version}} set to {@code 3.12}. */
    String codeImage(String managedRuntime) {
        String version = managedRuntime.replaceFirst("^PYTHON_", "").replace('_', '.');
        return config.services().bedrockAgentCore().codeRuntimeImage().replace("{version}", version);
    }

    /** A script entry point runs under {@code python}; any other first element is a command of its own. */
    static List<String> codeCommand(JsonNode entryPoint) {
        List<String> command = new ArrayList<>();
        entryPoint.forEach(part -> command.add(part.asText()));
        if (command.isEmpty()) {
            throw new AwsException("ValidationException", "codeConfiguration.entryPoint is empty", 400);
        }
        if (command.getFirst().endsWith(".py")) {
            command.addFirst("python");
        }
        return command;
    }

    /** The code goes in by {@code docker cp}, so it works when Floci itself runs in a container. */
    private void copyCode(String containerId, Launch launch, JsonNode s3) {
        String bucket = s3.path("bucket").asText();
        String key = s3.path("prefix").asText();
        String versionId = s3.path("versionId").isTextual() ? s3.path("versionId").asText() : null;
        byte[] zip = RequestScopes.callAs(launch.accountId(), launch.region(),
                () -> s3Service.getObject(bucket, key, versionId)).getData();
        Path dir;
        try {
            dir = Files.createTempDirectory("floci-agentcore-");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not make room for the code of " + launch.runtimeId(), e);
        }
        try {
            zipExtractor.extractTo(zip, dir, config.services().lambda().zipMaxEntries());
            RetryingTarCopier.copyStreamed(lifecycleManager.getDockerClient(), containerId, TASK_DIR,
                    "agentcore-" + launch.runtimeId(), out -> tar(dir, out));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not unpack the code of " + launch.runtimeId(), e);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void tar(Path dir, OutputStream out) throws IOException {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(out); Stream<Path> walk = Files.walk(dir)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
            List<Path> paths = walk.filter(path -> !path.equals(dir)).sorted().toList();
            for (Path path : paths) {
                boolean directory = Files.isDirectory(path);
                String name = dir.relativize(path).toString().replace('\\', '/');
                TarArchiveEntry entry = new TarArchiveEntry(path.toFile(), directory ? name + "/" : name);
                entry.setMode(directory || Files.isExecutable(path) ? 0755 : 0644);
                tar.putArchiveEntry(entry);
                if (!directory) {
                    Files.copy(path, tar);
                }
                tar.closeArchiveEntry();
            }
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            LOG.debugv(e, "Could not remove the unpacked code at {0}", dir);
        }
    }
}
