package io.github.hectorvent.floci.services.arcregionswitch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.arcregionswitch.model.RegionSwitchPlan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Region switch plan executions. An execution runs the workflow for its target action and Region
 * at once: every step completes immediately, except that the run stops at a ManualApproval step
 * until ApprovePlanExecutionStep answers it. Executions are keyed by plan ARN and execution id.
 */
@ApplicationScoped
public class ArcRegionSwitchExecutionService implements Resettable {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final Set<String> FINISHED = Set.of("completed", "completedWithExceptions", "canceled", "failed",
            "planExecutionTimedOut");

    private final StorageBackend<String, ObjectNode> executions;
    private final ArcRegionSwitchService plans;

    @Inject
    public ArcRegionSwitchExecutionService(StorageFactory storageFactory, ArcRegionSwitchService plans) {
        this(storageFactory.create("arcregionswitch", "arc-region-switch-executions.json",
                new TypeReference<Map<String, ObjectNode>>() {}), plans);
    }

    ArcRegionSwitchExecutionService(StorageBackend<String, ObjectNode> executions, ArcRegionSwitchService plans) {
        this.executions = executions;
        this.plans = plans;
    }

    @Override
    public void clear() {
        executions.clear();
    }

    public synchronized ObjectNode startPlanExecution(JsonNode request) {
        RegionSwitchPlan plan = plans.getPlan(text(request, "planArn"));
        String targetRegion = text(request, "targetRegion");
        String action = text(request, "action");
        if (!"activate".equals(action) && !"deactivate".equals(action)) {
            throw illegalArgument("action must be activate or deactivate.");
        }
        if (targetRegion == null || !plan.getRegions().contains(targetRegion)) {
            throw illegalArgument("The target Region must be one of the plan's Regions " + plan.getRegions() + ".");
        }
        if (request.hasNonNull("latestVersion")
                && !request.get("latestVersion").asText().equals(String.valueOf(plan.getVersion()))) {
            throw illegalState("The plan version " + request.get("latestVersion").asText() + " is not the latest.");
        }
        boolean running = list(plan.getArn()).stream()
                .anyMatch(e -> !FINISHED.contains(e.path("executionState").asText()));
        if (running) {
            throw illegalState("The plan already has an execution in progress.");
        }
        JsonNode workflow = workflowFor(plan, action, targetRegion);
        String otherRegion = plan.getRegions().stream().filter(r -> !r.equals(targetRegion)).findFirst().orElse(null);
        String mode = request.path("mode").asText("graceful");
        long now = Instant.now().toEpochMilli();

        ObjectNode execution = JSON.objectNode();
        execution.put("planArn", plan.getArn());
        execution.put("executionId", UUID.randomUUID().toString());
        execution.put("version", String.valueOf(plan.getVersion()));
        execution.put("updatedAt", seconds(now));
        if (request.hasNonNull("comment")) {
            execution.put("comment", request.get("comment").asText());
        }
        execution.put("startTime", seconds(now));
        execution.put("mode", mode);
        execution.put("executionState", "inProgress");
        execution.put("executionAction", action);
        execution.put("executionRegion", targetRegion);
        ArrayNode states = execution.putArray("stepStates");
        ArrayNode blockTypes = execution.putArray("stepBlockTypes");
        if (workflow != null) {
            for (JsonNode step : workflow.path("steps")) {
                states.addObject().put("name", step.path("name").asText()).put("status", "notStarted")
                        .put("stepMode", mode);
                blockTypes.add(step.path("executionBlockType").asText());
            }
        }
        execution.putArray("events");
        event(execution, "executionStarted", null, null, now);
        advance(execution, now);
        execution.put("activateRegion", "activate".equals(action) ? targetRegion : otherRegion);
        execution.put("deactivateRegion", "activate".equals(action) ? otherRegion : targetRegion);
        save(execution);

        ObjectNode response = JSON.objectNode();
        response.put("executionId", execution.path("executionId").asText());
        response.put("plan", plan.getArn());
        response.put("planVersion", String.valueOf(plan.getVersion()));
        if (execution.hasNonNull("activateRegion")) {
            response.put("activateRegion", execution.path("activateRegion").asText());
        }
        if (execution.hasNonNull("deactivateRegion")) {
            response.put("deactivateRegion", execution.path("deactivateRegion").asText());
        }
        return response;
    }

    public ObjectNode getPlanExecution(String planArn, String executionId) {
        ObjectNode execution = require(planArn, executionId).deepCopy();
        execution.remove(List.of("events", "stepBlockTypes", "activateRegion", "deactivateRegion"));
        return execution;
    }

    public List<ObjectNode> listPlanExecutions(String planArn, String state) {
        plans.getPlan(planArn);
        return list(planArn).stream()
                .filter(e -> state == null || state.equals(e.path("executionState").asText()))
                .sorted(Comparator.comparingDouble((ObjectNode e) -> e.path("startTime").asDouble()).reversed())
                .map(e -> {
                    ObjectNode summary = e.deepCopy();
                    summary.remove(List.of("events", "stepBlockTypes", "stepStates", "activateRegion",
                            "deactivateRegion"));
                    return summary;
                })
                .toList();
    }

    public List<ObjectNode> listPlanExecutionEvents(String planArn, String executionId, String stepName) {
        List<ObjectNode> events = new ArrayList<>();
        for (JsonNode event : require(planArn, executionId).path("events")) {
            if (stepName == null || stepName.equals(event.path("stepName").asText())) {
                events.add(((ObjectNode) event).deepCopy());
            }
        }
        return events;
    }

    public synchronized void cancelPlanExecution(String planArn, String executionId, String comment) {
        ObjectNode execution = require(planArn, executionId);
        if (FINISHED.contains(execution.path("executionState").asText())) {
            return;
        }
        long now = Instant.now().toEpochMilli();
        for (JsonNode state : execution.path("stepStates")) {
            String status = state.path("status").asText();
            if ("notStarted".equals(status) || "pendingApproval".equals(status) || "running".equals(status)) {
                ((ObjectNode) state).put("status", "canceled");
            }
        }
        if (comment != null) {
            execution.put("comment", comment);
        }
        event(execution, "executionCanceled", null, null, now);
        finish(execution, "canceled", now);
        save(execution);
    }

    public synchronized void updatePlanExecution(String planArn, String executionId, String action, String comment) {
        ObjectNode execution = require(planArn, executionId);
        String state = execution.path("executionState").asText();
        if (FINISHED.contains(state)) {
            throw illegalState("The execution " + executionId + " has finished.");
        }
        long now = Instant.now().toEpochMilli();
        switch (action == null ? "" : action) {
            case "switchToGraceful", "switchToUngraceful" -> {
                String mode = "switchToGraceful".equals(action) ? "graceful" : "ungraceful";
                execution.put("mode", mode);
                execution.path("stepStates").forEach(s -> ((ObjectNode) s).put("stepMode", mode));
                event(execution, "switchToGraceful".equals(action) ? "executionBehaviorChangedToGraceful"
                        : "executionBehaviorChangedToUngraceful", null, null, now);
            }
            case "pause" -> {
                execution.put("executionState", "pausedByOperator");
                event(execution, "executionPaused", null, null, now);
            }
            case "resume" -> {
                if (!"pausedByOperator".equals(state)) {
                    throw illegalState("The execution " + executionId + " is not paused.");
                }
                execution.put("executionState", "inProgress");
                advance(execution, now);
            }
            default -> throw illegalArgument("action must be switchToGraceful, switchToUngraceful, pause or resume.");
        }
        if (comment != null) {
            execution.put("comment", comment);
        }
        execution.put("updatedAt", seconds(now));
        save(execution);
    }

    public synchronized void updatePlanExecutionStep(JsonNode request) {
        ObjectNode execution = require(text(request, "planArn"), text(request, "executionId"));
        ObjectNode state = stepState(execution, text(request, "stepName"));
        long now = Instant.now().toEpochMilli();
        String action = text(request, "actionToTake");
        if ("skip".equals(action)) {
            if (!"notStarted".equals(state.path("status").asText())
                    && !"pendingApproval".equals(state.path("status").asText())) {
                throw illegalArgument("Only a step that has not finished can be skipped.");
            }
            state.put("status", "skipped");
            event(execution, "stepSkipped", state.path("name").asText(), null, now);
            if ("pendingManualApproval".equals(execution.path("executionState").asText())) {
                execution.put("executionState", "inProgress");
                advance(execution, now);
            }
        } else if ("switchToUngraceful".equals(action)) {
            state.put("stepMode", "ungraceful");
            event(execution, "stepExecutionBehaviorChangedToUngraceful", state.path("name").asText(), null, now);
        } else {
            throw illegalArgument("actionToTake must be switchToUngraceful or skip.");
        }
        execution.put("updatedAt", seconds(now));
        save(execution);
    }

    public synchronized void approvePlanExecutionStep(JsonNode request) {
        ObjectNode execution = require(text(request, "planArn"), text(request, "executionId"));
        ObjectNode state = stepState(execution, text(request, "stepName"));
        if (!"pendingApproval".equals(state.path("status").asText())) {
            throw illegalArgument("The step " + state.path("name").asText() + " is not waiting for approval.");
        }
        long now = Instant.now().toEpochMilli();
        if ("approve".equals(text(request, "approval"))) {
            state.put("status", "completed");
            state.put("endTime", seconds(now));
            event(execution, "stepSucceeded", state.path("name").asText(), "ManualApproval", now);
            execution.put("executionState", "inProgress");
            advance(execution, now);
        } else if ("decline".equals(text(request, "approval"))) {
            state.put("status", "failed");
            state.put("endTime", seconds(now));
            event(execution, "stepFailed", state.path("name").asText(), "ManualApproval", now);
            event(execution, "executionFailed", null, null, now);
            finish(execution, "failed", now);
        } else {
            throw illegalArgument("approval must be approve or decline.");
        }
        if (request.hasNonNull("comment")) {
            execution.put("comment", request.get("comment").asText());
        }
        save(execution);
    }

    /** Plans are checked when stored, so an evaluation always passes. */
    public ObjectNode getPlanEvaluationStatus(String planArn) {
        RegionSwitchPlan plan = plans.getPlan(planArn);
        ObjectNode status = JSON.objectNode();
        status.put("planArn", plan.getArn());
        status.put("lastEvaluationTime", seconds(plan.getUpdatedAtMillis()));
        status.put("lastEvaluatedVersion", String.valueOf(plan.getVersion()));
        status.put("region", plan.getPrimaryRegion() != null ? plan.getPrimaryRegion() : plan.getRegions().getFirst());
        status.put("evaluationState", "passed");
        status.putArray("warnings");
        return status;
    }

    /** The Route 53 health checks the plan's Route53HealthCheck steps name, one per Region. */
    public List<ObjectNode> listRoute53HealthChecks(String planArn, String hostedZoneId, String recordName) {
        RegionSwitchPlan plan = plans.getPlan(planArn);
        List<ObjectNode> checks = new ArrayList<>();
        if (plan.getWorkflows() == null) {
            return checks;
        }
        for (JsonNode workflow : plan.getWorkflows()) {
            for (JsonNode step : workflow.path("steps")) {
                if (!"Route53HealthCheck".equals(step.path("executionBlockType").asText())) {
                    continue;
                }
                JsonNode config = step.path("executionBlockConfiguration").path("route53HealthCheckConfig");
                String zone = config.path("hostedZoneId").asText();
                String record = config.path("recordName").asText();
                if ((hostedZoneId != null && !hostedZoneId.equals(zone))
                        || (recordName != null && !recordName.equals(record))) {
                    continue;
                }
                for (String region : plan.getRegions()) {
                    boolean seen = checks.stream().anyMatch(c -> zone.equals(c.path("hostedZoneId").asText())
                            && record.equals(c.path("recordName").asText()) && region.equals(c.path("region").asText()));
                    if (!seen) {
                        checks.add(JSON.objectNode().put("hostedZoneId", zone).put("recordName", record)
                                .put("status", "healthy").put("region", region));
                    }
                }
            }
        }
        return checks;
    }

    public synchronized void deletePlanExecutions(String planArn) {
        for (String key : List.copyOf(executions.keys())) {
            if (key.startsWith(planArn + "|")) {
                executions.delete(key);
            }
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /** Runs the remaining steps until one waits for approval or none are left. */
    private void advance(ObjectNode execution, long now) {
        ArrayNode states = (ArrayNode) execution.get("stepStates");
        JsonNode blockTypes = execution.path("stepBlockTypes");
        for (int i = 0; i < states.size(); i++) {
            ObjectNode state = (ObjectNode) states.get(i);
            if (!"notStarted".equals(state.path("status").asText())) {
                continue;
            }
            String name = state.path("name").asText();
            String blockType = blockTypes.path(i).asText();
            state.put("startTime", seconds(now));
            event(execution, "stepStarted", name, blockType, now);
            if ("ManualApproval".equals(blockType)) {
                state.put("status", "pendingApproval");
                event(execution, "stepPendingApproval", name, blockType, now);
                execution.put("executionState", "pendingManualApproval");
                execution.put("updatedAt", seconds(now));
                return;
            }
            state.put("status", "completed");
            state.put("endTime", seconds(now));
            event(execution, "stepSucceeded", name, blockType, now);
        }
        event(execution, "executionSucceeded", null, null, now);
        finish(execution, "completed", now);
    }

    private static void finish(ObjectNode execution, String state, long now) {
        execution.put("executionState", state);
        execution.put("endTime", seconds(now));
        execution.put("updatedAt", seconds(now));
        long started = (long) (execution.path("startTime").asDouble() * 1000);
        execution.put("actualRecoveryTime", Duration.ofMillis(Math.max(0, now - started)).toString());
    }

    private static void event(ObjectNode execution, String type, String stepName, String blockType, long now) {
        ArrayNode events = (ArrayNode) execution.get("events");
        ObjectNode event = JSON.objectNode();
        event.put("timestamp", seconds(now));
        event.put("type", type);
        if (stepName != null) {
            event.put("stepName", stepName);
        }
        if (blockType != null && !blockType.isEmpty()) {
            event.put("executionBlockType", blockType);
        }
        event.put("eventId", UUID.randomUUID().toString());
        if (!events.isEmpty()) {
            event.put("previousEventId", events.get(events.size() - 1).path("eventId").asText());
        }
        events.add(event);
    }

    private static JsonNode workflowFor(RegionSwitchPlan plan, String action, String region) {
        if (plan.getWorkflows() == null) {
            return null;
        }
        JsonNode fallback = null;
        for (JsonNode workflow : plan.getWorkflows()) {
            if (!action.equals(workflow.path("workflowTargetAction").asText())) {
                continue;
            }
            if (region.equals(workflow.path("workflowTargetRegion").asText())) {
                return workflow;
            }
            if (!workflow.hasNonNull("workflowTargetRegion") && fallback == null) {
                fallback = workflow;
            }
        }
        return fallback;
    }

    private static ObjectNode stepState(ObjectNode execution, String stepName) {
        for (JsonNode state : execution.path("stepStates")) {
            if (state.path("name").asText().equals(stepName)) {
                return (ObjectNode) state;
            }
        }
        throw new AwsException("ResourceNotFoundException", "The step " + stepName + " was not found.", 404);
    }

    private ObjectNode require(String planArn, String executionId) {
        plans.getPlan(planArn);
        return executions.get(planArn + "|" + executionId).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "The execution " + executionId + " was not found.", 404));
    }

    private List<ObjectNode> list(String planArn) {
        return executions.scan(k -> k.startsWith(planArn + "|"));
    }

    private void save(ObjectNode execution) {
        executions.put(execution.path("planArn").asText() + "|" + execution.path("executionId").asText(), execution);
    }

    private static double seconds(long millis) {
        return millis / 1000.0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException illegalArgument(String message) {
        return new AwsException("IllegalArgumentException", message, 400);
    }

    private static AwsException illegalState(String message) {
        return new AwsException("IllegalStateException", message, 400);
    }
}
