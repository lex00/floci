package io.github.hectorvent.floci.services.arcregionswitch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.arcregionswitch.model.RegionSwitchPlan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ARC Region switch, reached as JSON 1.0 ({@code X-Amz-Target: ArcRegionSwitch.*}) or as Smithy
 * RPC v2 CBOR ({@code /service/ArcRegionSwitch/operation/*}). Both paths share this handler.
 */
@ApplicationScoped
public class ArcRegionSwitchJsonHandler {

    /** Timestamp members in the responses this handler writes, for the CBOR encoder. */
    public static final Set<String> TIMESTAMP_FIELDS = Set.of("updatedAt", "startTime", "endTime", "timestamp",
            "lastEvaluationTime", "warningUpdatedTime");

    private final ArcRegionSwitchService service;
    private final ArcRegionSwitchExecutionService executions;
    private final ObjectMapper objectMapper;

    @Inject
    public ArcRegionSwitchJsonHandler(ArcRegionSwitchService service, ArcRegionSwitchExecutionService executions,
                                      ObjectMapper objectMapper) {
        this.service = service;
        this.executions = executions;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request) {
        return switch (action) {
            case "CreatePlan" -> createPlan(request);
            case "GetPlan", "GetPlanInRegion" -> planResponse(service.getPlan(text(request, "arn")));
            case "UpdatePlan" -> planResponse(service.updatePlan(text(request, "arn"), settings(request)));
            case "DeletePlan" -> deletePlan(request);
            case "ListPlans", "ListPlansInRegion" -> listPlans(request);
            case "StartPlanExecution" -> ok(executions.startPlanExecution(request));
            case "GetPlanExecution" -> getPlanExecution(request);
            case "ListPlanExecutions" -> page(request, "items", executions.listPlanExecutions(
                    text(request, "planArn"), text(request, "state")));
            case "ListPlanExecutionEvents" -> page(request, "items", executions.listPlanExecutionEvents(
                    text(request, "planArn"), text(request, "executionId"), text(request, "name")));
            case "CancelPlanExecution" -> {
                executions.cancelPlanExecution(text(request, "planArn"), text(request, "executionId"),
                        text(request, "comment"));
                yield ok(objectMapper.createObjectNode());
            }
            case "UpdatePlanExecution" -> {
                executions.updatePlanExecution(text(request, "planArn"), text(request, "executionId"),
                        text(request, "action"), text(request, "comment"));
                yield ok(objectMapper.createObjectNode());
            }
            case "UpdatePlanExecutionStep" -> {
                executions.updatePlanExecutionStep(request);
                yield ok(objectMapper.createObjectNode());
            }
            case "ApprovePlanExecutionStep" -> {
                executions.approvePlanExecutionStep(request);
                yield ok(objectMapper.createObjectNode());
            }
            case "GetPlanEvaluationStatus" -> ok(executions.getPlanEvaluationStatus(text(request, "planArn")));
            case "ListRoute53HealthChecks", "ListRoute53HealthChecksInRegion" -> page(request, "healthChecks",
                    executions.listRoute53HealthChecks(text(request, "arn"), text(request, "hostedZoneId"),
                            text(request, "recordName")));
            case "TagResource" -> tagResource(request);
            case "UntagResource" -> untagResource(request);
            case "ListTagsForResource" -> listTagsForResource(request);
            default -> Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: ArcRegionSwitch." + action)).build();
        };
    }

    private Response createPlan(JsonNode request) {
        RegionSwitchPlan plan = service.createPlan(text(request, "name"), strings(request.path("regions")),
                text(request, "recoveryApproach"), text(request, "primaryRegion"), settings(request),
                stringMap(request.path("tags")));
        return planResponse(plan);
    }

    private Response getPlanExecution(JsonNode request) {
        ObjectNode execution = executions.getPlanExecution(text(request, "planArn"), text(request, "executionId"));
        execution.set("plan", planNode(service.getPlan(text(request, "planArn"))));
        return ok(execution);
    }

    private Response page(JsonNode request, String member, List<ObjectNode> all) {
        int maxResults = request.path("maxResults").asInt(100);
        if (maxResults < 1 || maxResults > 100) {
            throw new AwsException("IllegalArgumentException", "maxResults must be between 1 and 100.", 400);
        }
        int start = 0;
        String nextToken = text(request, "nextToken");
        if (nextToken != null) {
            try {
                start = Math.max(0, Integer.parseInt(nextToken));
            } catch (NumberFormatException e) {
                throw new AwsException("IllegalArgumentException", "The nextToken is not valid.", 400);
            }
        }
        int end = Math.min(all.size(), start + maxResults);
        ObjectNode out = objectMapper.createObjectNode();
        out.putArray(member).addAll(all.subList(Math.min(start, end), end));
        if (end < all.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return ok(out);
    }

    private static Response ok(ObjectNode body) {
        return Response.ok(body).build();
    }

    private Response deletePlan(JsonNode request) {
        service.deletePlan(text(request, "arn"));
        executions.deletePlanExecutions(text(request, "arn"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listPlans(JsonNode request) {
        List<RegionSwitchPlan> all = service.listPlans();
        int maxResults = request.path("maxResults").asInt(100);
        if (maxResults < 1 || maxResults > 100) {
            throw new AwsException("IllegalArgumentException", "maxResults must be between 1 and 100.", 400);
        }
        int start = 0;
        String nextToken = text(request, "nextToken");
        if (nextToken != null) {
            try {
                start = Math.max(0, Integer.parseInt(nextToken));
            } catch (NumberFormatException e) {
                throw new AwsException("IllegalArgumentException", "The nextToken is not valid.", 400);
            }
        }
        int end = Math.min(all.size(), start + maxResults);
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray("plans");
        for (int i = start; i < end; i++) {
            RegionSwitchPlan plan = all.get(i);
            ObjectNode node = items.addObject();
            node.put("arn", plan.getArn());
            node.put("owner", plan.getOwner());
            node.put("name", plan.getName());
            plan.getRegions().forEach(node.putArray("regions")::add);
            node.put("recoveryApproach", plan.getRecoveryApproach());
            putIfPresent(node, "primaryRegion", plan.getPrimaryRegion());
            node.put("version", String.valueOf(plan.getVersion()));
            node.put("updatedAt", epochSeconds(plan.getUpdatedAtMillis()));
            putIfPresent(node, "description", plan.getDescription());
            node.put("executionRole", plan.getExecutionRole());
            if (plan.getRecoveryTimeObjectiveMinutes() != null) {
                node.put("recoveryTimeObjectiveMinutes", plan.getRecoveryTimeObjectiveMinutes());
            }
        }
        if (end < all.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return Response.ok(out).build();
    }

    private Response tagResource(JsonNode request) {
        service.tagResource(text(request, "arn"), stringMap(request.path("tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode request) {
        service.untagResource(text(request, "arn"), strings(request.path("resourceTagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listTagsForResource(JsonNode request) {
        ObjectNode out = objectMapper.createObjectNode();
        ObjectNode tags = out.putObject("resourceTags");
        service.getPlan(text(request, "arn")).getTags().forEach(tags::put);
        return Response.ok(out).build();
    }

    private Response planResponse(RegionSwitchPlan plan) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set("plan", planNode(plan));
        return Response.ok(out).build();
    }

    private ObjectNode planNode(RegionSwitchPlan plan) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", plan.getArn());
        putIfPresent(node, "description", plan.getDescription());
        node.set("workflows", plan.getWorkflows());
        node.put("executionRole", plan.getExecutionRole());
        if (plan.getRecoveryTimeObjectiveMinutes() != null) {
            node.put("recoveryTimeObjectiveMinutes", plan.getRecoveryTimeObjectiveMinutes());
        }
        putIfPresent(node, "associatedAlarms", plan.getAssociatedAlarms());
        putIfPresent(node, "triggers", plan.getTriggers());
        putIfPresent(node, "reportConfiguration", plan.getReportConfiguration());
        if (plan.getServiceQuotaChecksEnabled() != null) {
            node.put("serviceQuotaChecksEnabled", plan.getServiceQuotaChecksEnabled());
        }
        node.put("name", plan.getName());
        plan.getRegions().forEach(node.putArray("regions")::add);
        node.put("recoveryApproach", plan.getRecoveryApproach());
        putIfPresent(node, "primaryRegion", plan.getPrimaryRegion());
        node.put("owner", plan.getOwner());
        node.put("version", String.valueOf(plan.getVersion()));
        node.put("updatedAt", epochSeconds(plan.getUpdatedAtMillis()));
        return node;
    }

    private static ArcRegionSwitchService.PlanSettings settings(JsonNode request) {
        JsonNode rto = request.get("recoveryTimeObjectiveMinutes");
        JsonNode quotaChecks = request.get("serviceQuotaChecksEnabled");
        return new ArcRegionSwitchService.PlanSettings(text(request, "description"), request.get("workflows"),
                text(request, "executionRole"),
                rto == null || rto.isNull() ? null : rto.asInt(),
                present(request.get("associatedAlarms")), present(request.get("triggers")),
                present(request.get("reportConfiguration")),
                quotaChecks == null || quotaChecks.isNull() ? null : quotaChecks.asBoolean());
    }

    private static double epochSeconds(long millis) {
        return millis / 1000.0;
    }

    private static JsonNode present(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null) {
            node.set(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(item.asText()));
        }
        return out;
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> out.put(entry.getKey(), entry.getValue().asText()));
        }
        return out;
    }
}
