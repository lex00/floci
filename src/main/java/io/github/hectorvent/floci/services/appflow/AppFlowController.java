package io.github.hectorvent.floci.services.appflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.appflow.model.ConnectorProfile;
import io.github.hectorvent.floci.services.appflow.model.Flow;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Amazon AppFlow (restJson1). Every operation is a POST to a single-segment path such as
 * {@code /create-connector-profile}, which JAX-RS matches ahead of the S3 {@code /{bucket}} route.
 * Tagging goes through the shared {@code /tags/{resourceArn}} controller.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppFlowController {

    private static final int DEFAULT_MAX_RESULTS = 20;

    private final AppFlowService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AppFlowController(AppFlowService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/create-connector-profile")
    public Response createConnectorProfile(@Context HttpHeaders headers, String body) {
        ConnectorProfile profile = service.createConnectorProfile(parse(body), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("connectorProfileArn", profile.getConnectorProfileArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/describe-connector-profiles")
    public Response describeConnectorProfiles(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        List<String> names = new ArrayList<>();
        for (JsonNode name : request.path("connectorProfileNames")) {
            names.add(name.asText());
        }
        List<ConnectorProfile> matches = service.describeConnectorProfiles(regionResolver.resolveRegion(headers),
                names, text(request, "connectorType"), text(request, "connectorLabel"));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("connectorProfileDetails");
        Page<ConnectorProfile> page = page(matches, request);
        page.items().forEach(profile -> details.add(profileNode(profile)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/update-connector-profile")
    public Response updateConnectorProfile(@Context HttpHeaders headers, String body) {
        ConnectorProfile profile = service.updateConnectorProfile(parse(body), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("connectorProfileArn", profile.getConnectorProfileArn());
        return Response.ok(response).build();
    }

    @POST
    @Path("/delete-connector-profile")
    public Response deleteConnectorProfile(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.deleteConnectorProfile(text(request, "connectorProfileName"),
                request.path("forceDelete").asBoolean(false), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/create-flow")
    public Response createFlow(@Context HttpHeaders headers, String body) {
        Flow flow = service.createFlow(parse(body), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", flow.getFlowArn());
        response.put("flowStatus", flow.getFlowStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/describe-flow")
    public Response describeFlow(@Context HttpHeaders headers, String body) {
        Flow flow = service.describeFlow(text(parse(body), "flowName"), regionResolver.resolveRegion(headers));
        return Response.ok(flowNode(flow)).build();
    }

    @POST
    @Path("/list-flows")
    public Response listFlows(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("flows");
        Page<Flow> page = page(service.listFlows(regionResolver.resolveRegion(headers)), request);
        page.items().forEach(flow -> list.add(flowSummaryNode(flow)));
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/update-flow")
    public Response updateFlow(@Context HttpHeaders headers, String body) {
        Flow flow = service.updateFlow(parse(body), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowStatus", flow.getFlowStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/delete-flow")
    public Response deleteFlow(@Context HttpHeaders headers, String body) {
        service.deleteFlow(text(parse(body), "flowName"), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/start-flow")
    public Response startFlow(@Context HttpHeaders headers, String body) {
        AppFlowService.FlowRun run = service.startFlow(text(parse(body), "flowName"),
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", run.flow().getFlowArn());
        response.put("flowStatus", run.flow().getFlowStatus());
        if (run.executionId() != null) {
            response.put("executionId", run.executionId());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/stop-flow")
    public Response stopFlow(@Context HttpHeaders headers, String body) {
        Flow flow = service.stopFlow(text(parse(body), "flowName"), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("flowArn", flow.getFlowArn());
        response.put("flowStatus", flow.getFlowStatus());
        return Response.ok(response).build();
    }

    @POST
    @Path("/describe-flow-execution-records")
    public Response describeFlowExecutionRecords(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        List<JsonNode> records = service.describeFlowExecutionRecords(text(request, "flowName"),
                regionResolver.resolveRegion(headers));
        Page<JsonNode> page = page(records, request);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("flowExecutions").addAll(page.items());
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/cancel-flow-executions")
    public Response cancelFlowExecutions(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        List<String> invalid = service.cancelFlowExecutions(text(request, "flowName"), request.get("executionIds"),
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("invalidExecutions");
        invalid.forEach(list::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/register-connector")
    public Response registerConnector(@Context HttpHeaders headers, String body) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("connectorArn", service.registerConnector(parse(body), regionResolver.resolveRegion(headers)));
        return Response.ok(response).build();
    }

    @POST
    @Path("/update-connector-registration")
    public Response updateConnectorRegistration(@Context HttpHeaders headers, String body) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("connectorArn", service.updateConnectorRegistration(parse(body),
                regionResolver.resolveRegion(headers)));
        return Response.ok(response).build();
    }

    @POST
    @Path("/unregister-connector")
    public Response unregisterConnector(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.unregisterConnector(text(request, "connectorLabel"), request.path("forceDelete").asBoolean(false),
                regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/describe-connector")
    public Response describeConnector(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("connectorConfiguration", service.describeConnector(text(request, "connectorType"),
                text(request, "connectorLabel"), regionResolver.resolveRegion(headers)));
        return Response.ok(response).build();
    }

    @POST
    @Path("/describe-connectors")
    public Response describeConnectors(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        String region = regionResolver.resolveRegion(headers);
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode configurations = response.putObject("connectorConfigurations");
        service.describeConnectors(request.get("connectorTypes")).forEach(configurations::set);
        response.putArray("connectors").addAll(service.listConnectors(region));
        return Response.ok(response).build();
    }

    @POST
    @Path("/list-connectors")
    public Response listConnectors(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        Page<ObjectNode> page = page(service.listConnectors(regionResolver.resolveRegion(headers)), request);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("connectors").addAll(page.items());
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    /** No SaaS application is reached, so a connector lists no entities. */
    @POST
    @Path("/list-connector-entities")
    public Response listConnectorEntities(@Context HttpHeaders headers, String body) {
        service.resetConnectorMetadataCache(parse(body), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.putObject("connectorEntityMap");
        return Response.ok(response).build();
    }

    @POST
    @Path("/describe-connector-entity")
    public Response describeConnectorEntity(@Context HttpHeaders headers, String body) {
        JsonNode request = parse(body);
        service.resetConnectorMetadataCache(request, regionResolver.resolveRegion(headers));
        throw new AwsException("ResourceNotFoundException",
                "Entity " + text(request, "connectorEntityName") + " does not exist.", 404);
    }

    @POST
    @Path("/reset-connector-metadata-cache")
    public Response resetConnectorMetadataCache(@Context HttpHeaders headers, String body) {
        service.resetConnectorMetadataCache(parse(body), regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private ObjectNode profileNode(ConnectorProfile profile) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("connectorProfileArn", profile.getConnectorProfileArn());
        node.put("connectorProfileName", profile.getConnectorProfileName());
        node.put("connectorType", profile.getConnectorType());
        putIfPresent(node, "connectorLabel", profile.getConnectorLabel());
        node.put("connectionMode", profile.getConnectionMode());
        putIfPresent(node, "credentialsArn", profile.getCredentialsArn());
        if (profile.getConnectorProfileProperties() != null) {
            node.set("connectorProfileProperties", profile.getConnectorProfileProperties());
        }
        node.put("createdAt", epochSeconds(profile.getCreatedAt()));
        node.put("lastUpdatedAt", epochSeconds(profile.getLastUpdatedAt()));
        return node;
    }

    private ObjectNode flowNode(Flow flow) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("flowArn", flow.getFlowArn());
        putIfPresent(node, "description", flow.getDescription());
        node.put("flowName", flow.getFlowName());
        putIfPresent(node, "kmsArn", flow.getKmsArn());
        node.put("flowStatus", flow.getFlowStatus());
        node.set("sourceFlowConfig", flow.getSourceFlowConfig());
        node.set("destinationFlowConfigList", flow.getDestinationFlowConfigList());
        putLastRun(node, flow);
        node.set("triggerConfig", flow.getTriggerConfig());
        node.set("tasks", flow.getTasks());
        node.put("createdAt", epochSeconds(flow.getCreatedAt()));
        node.put("lastUpdatedAt", epochSeconds(flow.getLastUpdatedAt()));
        putIfPresent(node, "createdBy", flow.getCreatedBy());
        putIfPresent(node, "lastUpdatedBy", flow.getLastUpdatedBy());
        node.set("tags", tags(flow.getTags()));
        if (flow.getMetadataCatalogConfig() != null) {
            node.set("metadataCatalogConfig", flow.getMetadataCatalogConfig());
        }
        node.put("schemaVersion", flow.getSchemaVersion());
        return node;
    }

    private ObjectNode flowSummaryNode(Flow flow) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("flowArn", flow.getFlowArn());
        putIfPresent(node, "description", flow.getDescription());
        node.put("flowName", flow.getFlowName());
        node.put("flowStatus", flow.getFlowStatus());
        node.put("sourceConnectorType", flow.getSourceFlowConfig().path("connectorType").asText());
        JsonNode firstDestination = flow.getDestinationFlowConfigList().path(0);
        if (firstDestination.has("connectorType")) {
            node.put("destinationConnectorType", firstDestination.path("connectorType").asText());
        }
        node.put("triggerType", flow.getTriggerConfig().path("triggerType").asText());
        node.put("createdAt", epochSeconds(flow.getCreatedAt()));
        node.put("lastUpdatedAt", epochSeconds(flow.getLastUpdatedAt()));
        putIfPresent(node, "createdBy", flow.getCreatedBy());
        putIfPresent(node, "lastUpdatedBy", flow.getLastUpdatedBy());
        node.set("tags", tags(flow.getTags()));
        putLastRun(node, flow);
        return node;
    }

    private void putLastRun(ObjectNode node, Flow flow) {
        if (flow.getLastRunStatus() == null) {
            return;
        }
        ObjectNode details = node.putObject("lastRunExecutionDetails");
        details.put("mostRecentExecutionTime", epochSeconds(flow.getLastRunAt()));
        details.put("mostRecentExecutionStatus", flow.getLastRunStatus());
    }

    private ObjectNode tags(Map<String, String> tags) {
        ObjectNode node = objectMapper.createObjectNode();
        if (tags != null) {
            tags.forEach(node::put);
        }
        return node;
    }

    private static <T> Page<T> page(List<T> items, JsonNode request) {
        int maxResults = DEFAULT_MAX_RESULTS;
        if (request.has("maxResults") && !request.get("maxResults").isNull()) {
            maxResults = request.get("maxResults").asInt();
            if (maxResults < 1 || maxResults > 100) {
                throw new AwsException("ValidationException", "1 validation error detected: Value '"
                        + maxResults + "' at 'maxResults' failed to satisfy constraint: "
                        + "Member must have value between 1 and 100", 400);
            }
        }
        int start = 0;
        String token = text(request, "nextToken");
        if (token != null) {
            try {
                start = Integer.parseInt(token);
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationException", "Invalid nextToken.", 400);
            }
            if (start < 0 || start > items.size()) {
                throw new AwsException("ValidationException", "Invalid nextToken.", 400);
            }
        }
        int end = Math.min(items.size(), start + maxResults);
        String next = end < items.size() ? Integer.toString(end) : null;
        return new Page<>(items.subList(start, end), next);
    }

    private record Page<T>(List<T> items, String nextToken) {}

    private static double epochSeconds(long millis) {
        return millis / 1000.0;
    }

    private static void putIfPresent(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static String text(JsonNode request, String member) {
        JsonNode value = request.get(member);
        return value == null || value.isNull() ? null : value.asText();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode request = objectMapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("ValidationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON.", 400);
        }
    }
}
