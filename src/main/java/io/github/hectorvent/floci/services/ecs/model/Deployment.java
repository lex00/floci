package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;

/**
 * A deployment of an ECS service, as reported in {@code DescribeServices}'
 * {@code services[].deployments}. Distinct from {@link ServiceDeployment}, which is the
 * separate {@code DescribeServiceDeployments} API shape and carries neither counts nor a
 * rollout state.
 */
@RegisterForReflection
public class Deployment {

    private String id;
    private String status;
    private String taskDefinition;
    private int desiredCount;
    private int pendingCount;
    private int runningCount;
    private int failedTasks;
    private String rolloutState;
    private String rolloutStateReason;
    private LaunchType launchType;
    private Instant createdAt;
    private Instant updatedAt;
    /** Raw passthrough of the service's {@code serviceConnectConfiguration}. AWS's own
     * {@code Service} shape has no top-level {@code serviceConnectConfiguration} member - it
     * lives only on {@code Deployment} (and on {@code ServiceRevision}). Both the real AWS SDKs
     * and Terraform's provider parse strictly against that shape, so echoing it back on the
     * Service object itself is invisible to them and reads as permanent drift. */
    private Map<String, Object> serviceConnectConfiguration;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTaskDefinition() { return taskDefinition; }
    public void setTaskDefinition(String taskDefinition) { this.taskDefinition = taskDefinition; }
    public int getDesiredCount() { return desiredCount; }
    public void setDesiredCount(int desiredCount) { this.desiredCount = desiredCount; }
    public int getPendingCount() { return pendingCount; }
    public void setPendingCount(int pendingCount) { this.pendingCount = pendingCount; }
    public int getRunningCount() { return runningCount; }
    public void setRunningCount(int runningCount) { this.runningCount = runningCount; }
    public int getFailedTasks() { return failedTasks; }
    public void setFailedTasks(int failedTasks) { this.failedTasks = failedTasks; }
    public String getRolloutState() { return rolloutState; }
    public void setRolloutState(String rolloutState) { this.rolloutState = rolloutState; }
    public String getRolloutStateReason() { return rolloutStateReason; }
    public void setRolloutStateReason(String rolloutStateReason) { this.rolloutStateReason = rolloutStateReason; }
    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Map<String, Object> getServiceConnectConfiguration() { return serviceConnectConfiguration; }
    public void setServiceConnectConfiguration(Map<String, Object> serviceConnectConfiguration) {
        this.serviceConnectConfiguration = serviceConnectConfiguration;
    }
}
