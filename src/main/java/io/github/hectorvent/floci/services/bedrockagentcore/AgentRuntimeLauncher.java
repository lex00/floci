package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;

/** Starts and stops the process that serves one AgentCore runtime session. */
interface AgentRuntimeLauncher {

    /** Starts the version's artifact, listening on {@code launch.containerPort()}. Blocks on Docker and S3. */
    RunningRuntime start(Launch launch);

    void stop(RunningRuntime runtime);

    /** What a session's container runs, and where its output goes. */
    record Launch(String accountId, String region, String runtimeId, String runtimeName, String endpointName,
                  AgentRuntimeVersion version, int containerPort) {
    }

    /** A started runtime and the address Floci reaches its HTTP server at. */
    record RunningRuntime(String containerId, String host, int port, AutoCloseable cleanup) {
    }
}
