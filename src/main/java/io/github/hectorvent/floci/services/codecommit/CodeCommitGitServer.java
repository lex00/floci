package io.github.hectorvent.floci.services.codecommit;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.ContainerTeardown;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.ContainerStorageHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The Git side of CodeCommit: bare repositories in a stock Git image, served over Git's smart HTTP
 * protocol. Each request runs {@code git upload-pack} or {@code git receive-pack} with
 * {@code --stateless-rpc} in the container, which is what a smart HTTP server runs behind each
 * request. The container starts with the first Git request and keeps every repository under
 * {@code /repos/<repositoryId>.git} on a named volume.
 */
@ApplicationScoped
public class CodeCommitGitServer implements ContainerTeardown {

    private static final Logger LOG = Logger.getLogger(CodeCommitGitServer.class);
    private static final String CONTAINER_NAME = "floci-codecommit-git";
    private static final String VOLUME_NAME = "floci-codecommit-repos";
    private static final String REPOS = "/repos";
    private static final Pattern REPOSITORY_ID = Pattern.compile("[0-9a-f-]{36}");
    private static final long GIT_TIMEOUT_SECONDS = 300;

    /** The Git services a client may ask for, by their smart HTTP names. */
    public static final List<String> SERVICES = List.of("git-upload-pack", "git-receive-pack");

    private final ContainerBuilder containerBuilder;
    private final ContainerLifecycleManager lifecycleManager;
    private final EmulatorConfig config;

    private volatile String containerId;

    @Inject
    public CodeCommitGitServer(ContainerBuilder containerBuilder, ContainerLifecycleManager lifecycleManager,
                               EmulatorConfig config) {
        this.containerBuilder = containerBuilder;
        this.lifecycleManager = lifecycleManager;
        this.config = config;
    }

    /** What a Git command wrote to standard output, and how it exited. */
    public record GitResult(byte[] output, long exitCode, String error) {}

    /**
     * The reference advertisement for {@code GET info/refs?service=<service>}: the service line the
     * smart HTTP protocol puts first, then the command's own advertisement.
     */
    public byte[] advertise(String repositoryId, String service, String gitProtocol) {
        GitResult result = run(repositoryId, service, gitProtocol, null, true);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (gitProtocol == null || !gitProtocol.contains("version=2")) {
            byte[] line = ("# service=" + service + "\n").getBytes(StandardCharsets.UTF_8);
            body.writeBytes(String.format("%04x", line.length + 4).getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(line);
            body.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
        }
        body.writeBytes(result.output());
        return body.toByteArray();
    }

    /** Runs one stateless RPC of the service with the client's request body. */
    public byte[] rpc(String repositoryId, String service, String gitProtocol, InputStream request) {
        return run(repositoryId, service, gitProtocol, request, false).output();
    }

    /** Removes a repository's Git data, when the server has started. */
    public void delete(String repositoryId) {
        if (containerId == null || !REPOSITORY_ID.matcher(repositoryId).matches()) {
            return;
        }
        try {
            exec(List.of("rm", "-rf", path(repositoryId)), List.of(), null);
        } catch (RuntimeException e) {
            LOG.warnv("Could not remove the Git data of CodeCommit repository {0}: {1}", repositoryId, e.getMessage());
        }
    }

    private GitResult run(String repositoryId, String service, String gitProtocol, InputStream request,
                          boolean advertise) {
        if (!SERVICES.contains(service)) {
            throw new AwsException("InvalidRequest", "Unsupported Git service " + service, 403);
        }
        if (!REPOSITORY_ID.matcher(repositoryId).matches()) {
            throw new IllegalArgumentException("Invalid repository id " + repositoryId);
        }
        ensureStarted();
        String path = path(repositoryId);
        GitResult init = exec(List.of("sh", "-c", "[ -d '" + path + "' ] || git init --quiet --bare "
                + "--initial-branch=main '" + path + "' && git -C '" + path
                + "' config http.receivepack true"), List.of(), null);
        if (init.exitCode() != 0) {
            throw new IllegalStateException("git init failed: " + init.error());
        }
        List<String> command = new ArrayList<>(List.of("git", service.substring("git-".length()), "--stateless-rpc"));
        if (advertise) {
            command.add("--advertise-refs");
        }
        command.add(path);
        List<String> env = gitProtocol != null && !gitProtocol.isBlank()
                ? List.of("GIT_PROTOCOL=" + gitProtocol) : List.of();
        GitResult result = exec(command, env, request);
        if (result.exitCode() != 0) {
            LOG.warnv("{0} for CodeCommit repository {1} exited {2}: {3}", service, repositoryId,
                    result.exitCode(), result.error());
        }
        return result;
    }

    private GitResult exec(List<String> command, List<String> env, InputStream stdin) {
        DockerClient docker = lifecycleManager.getDockerClient();
        String execId = docker.execCreateCmd(containerId)
                .withCmd(command.toArray(new String[0]))
                .withEnv(env)
                .withAttachStdin(stdin != null)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec()
                .getId();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        try (ResultCallback.Adapter<Frame> callback = docker.execStartCmd(execId)
                .withStdIn(stdin)
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Frame frame) {
                        if (frame.getStreamType() == StreamType.STDERR) {
                            stderr.writeBytes(frame.getPayload());
                        } else {
                            stdout.writeBytes(frame.getPayload());
                        }
                    }
                })) {
            if (!callback.awaitCompletion(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Git command timed out: " + command);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted running " + command, e);
        } catch (IOException e) {
            LOG.debugv("Closing the Git exec stream failed: {0}", e.getMessage());
        }
        Long exitCode = docker.inspectExecCmd(execId).exec().getExitCodeLong();
        return new GitResult(stdout.toByteArray(), exitCode != null ? exitCode : -1,
                stderr.toString(StandardCharsets.UTF_8));
    }

    private synchronized void ensureStarted() {
        if (containerId != null && lifecycleManager.isContainerRunning(containerId)) {
            return;
        }
        String image = config.services().codecommit().gitImage();
        String name = ContainerStorageHelper.dockerName(config, CONTAINER_NAME);
        String volume = ContainerStorageHelper.dockerName(config, VOLUME_NAME);
        lifecycleManager.removeIfExists(name);
        lifecycleManager.ensureVolume(volume);
        ContainerSpec spec = containerBuilder.newContainer(image)
                .withName(name)
                .withEntrypoint(List.of("sh", "-c", "trap 'exit 0' TERM; while :; do sleep 3600 & wait $!; done"))
                .withNamedVolume(volume, REPOS)
                .withLogRotation()
                .build();
        ContainerInfo info = lifecycleManager.createAndStart(spec);
        containerId = info.containerId();
        LOG.infov("Started the CodeCommit Git server {0} from {1}", name, image);
    }

    @Override
    public synchronized void stopManagedContainers() {
        if (containerId != null) {
            lifecycleManager.stopAndRemove(containerId, null);
            containerId = null;
        }
    }

    private static String path(String repositoryId) {
        return REPOS + "/" + repositoryId + ".git";
    }
}
