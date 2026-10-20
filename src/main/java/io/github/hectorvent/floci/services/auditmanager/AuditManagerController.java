package io.github.hectorvent.floci.services.auditmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.auditmanager.model.Control;
import io.github.hectorvent.floci.services.auditmanager.model.Framework;
import io.github.hectorvent.floci.services.auditmanager.model.FrameworkControlSet;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AWS Audit Manager REST-JSON controller for assessment frameworks and controls.
 *
 * <p>Tagging goes through the shared {@code /tags/{resourceArn}} path, which
 * {@link AuditManagerService} serves as a {@code TagHandler}.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuditManagerController {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AuditManagerService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public AuditManagerController(AuditManagerService service, RegionResolver regionResolver,
                                  ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────── Assessment frameworks ────────────────────────

    @POST
    @Path("/assessmentFrameworks")
    public Response createAssessmentFramework(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        Framework framework = service.createAssessmentFramework(text(request, "name"),
                text(request, "description"), text(request, "complianceType"),
                parseControlSets(request.get("controlSets")), parseTags(request.get("tags")), region);
        return Response.ok(wrap("framework", frameworkNode(framework, region))).build();
    }

    @GET
    @Path("/assessmentFrameworks/{frameworkId}")
    public Response getAssessmentFramework(@PathParam("frameworkId") String frameworkId,
                                           @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        Framework framework = service.getAssessmentFramework(frameworkId, region);
        return Response.ok(wrap("framework", frameworkNode(framework, region))).build();
    }

    @PUT
    @Path("/assessmentFrameworks/{frameworkId}")
    public Response updateAssessmentFramework(@PathParam("frameworkId") String frameworkId,
                                              @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        Framework framework = service.updateAssessmentFramework(frameworkId, text(request, "name"),
                text(request, "description"), text(request, "complianceType"),
                parseControlSets(request.get("controlSets")), region);
        return Response.ok(wrap("framework", frameworkNode(framework, region))).build();
    }

    @DELETE
    @Path("/assessmentFrameworks/{frameworkId}")
    public Response deleteAssessmentFramework(@PathParam("frameworkId") String frameworkId,
                                              @Context HttpHeaders headers) {
        service.deleteAssessmentFramework(frameworkId, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/assessmentFrameworks")
    public Response listAssessmentFrameworks(@QueryParam("frameworkType") String frameworkType,
                                             @QueryParam("maxResults") String maxResults,
                                             @QueryParam("nextToken") String nextToken,
                                             @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<Framework> page = service.listAssessmentFrameworks(frameworkType,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("frameworkMetadataList");
        for (Framework framework : page.items()) {
            ObjectNode node = list.addObject();
            node.put("arn", framework.getArn());
            node.put("id", framework.getId());
            node.put("type", "Custom");
            node.put("name", framework.getName());
            putIfPresent(node, "description", framework.getDescription());
            putIfPresent(node, "complianceType", framework.getComplianceType());
            node.put("controlsCount", framework.getControlSets().stream()
                    .mapToInt(set -> set.getControlIds().size()).sum());
            node.put("controlSetsCount", framework.getControlSets().size());
            node.put("createdAt", epochSeconds(framework.getCreatedAt()));
            node.put("lastUpdatedAt", epochSeconds(framework.getLastUpdatedAt()));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    // ──────────────────────── Controls ────────────────────────

    @POST
    @Path("/controls")
    public Response createControl(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        Control control = service.createControl(text(request, "name"), text(request, "description"),
                text(request, "testingInformation"), text(request, "actionPlanTitle"),
                text(request, "actionPlanInstructions"), parseMappingSources(request.get("controlMappingSources")),
                parseTags(request.get("tags")), region);
        return Response.ok(wrap("control", controlNode(control))).build();
    }

    @GET
    @Path("/controls/{controlId}")
    public Response getControl(@PathParam("controlId") String controlId, @Context HttpHeaders headers) {
        Control control = service.getControl(controlId, regionResolver.resolveRegion(headers));
        return Response.ok(wrap("control", controlNode(control))).build();
    }

    @PUT
    @Path("/controls/{controlId}")
    public Response updateControl(@PathParam("controlId") String controlId, @Context HttpHeaders headers,
                                  String body) {
        String region = regionResolver.resolveRegion(headers);
        JsonNode request = readTree(body);
        Control control = service.updateControl(controlId, text(request, "name"), text(request, "description"),
                text(request, "testingInformation"), text(request, "actionPlanTitle"),
                text(request, "actionPlanInstructions"), parseMappingSources(request.get("controlMappingSources")),
                region);
        return Response.ok(wrap("control", controlNode(control))).build();
    }

    @DELETE
    @Path("/controls/{controlId}")
    public Response deleteControl(@PathParam("controlId") String controlId, @Context HttpHeaders headers) {
        service.deleteControl(controlId, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/controls")
    public Response listControls(@QueryParam("controlType") String controlType,
                                 @QueryParam("maxResults") String maxResults,
                                 @QueryParam("nextToken") String nextToken,
                                 @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        PaginatedResult<Control> page = service.listControls(controlType,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("controlMetadataList");
        for (Control control : page.items()) {
            ObjectNode node = list.addObject();
            node.put("arn", control.getArn());
            node.put("id", control.getId());
            node.put("name", control.getName());
            putIfPresent(node, "controlSources", controlSources(control));
            node.put("createdAt", epochSeconds(control.getCreatedAt()));
            node.put("lastUpdatedAt", epochSeconds(control.getLastUpdatedAt()));
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    // ──────────────────────── Rendering ────────────────────────

    private ObjectNode frameworkNode(Framework framework, String region) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", framework.getArn());
        node.put("id", framework.getId());
        node.put("name", framework.getName());
        node.put("type", "Custom");
        putIfPresent(node, "complianceType", framework.getComplianceType());
        putIfPresent(node, "description", framework.getDescription());
        Set<String> sources = new LinkedHashSet<>();
        ArrayNode sets = node.putArray("controlSets");
        for (FrameworkControlSet set : framework.getControlSets()) {
            ObjectNode setNode = sets.addObject();
            setNode.put("id", set.getId());
            setNode.put("name", set.getName());
            ArrayNode controls = setNode.putArray("controls");
            for (String controlId : set.getControlIds()) {
                Optional<Control> control = service.findControl(controlId, region);
                if (control.isPresent()) {
                    controls.add(controlNode(control.get()));
                    String controlSources = controlSources(control.get());
                    if (controlSources != null) {
                        sources.add(controlSources);
                    }
                } else {
                    controls.addObject().put("id", controlId);
                }
            }
        }
        if (!sources.isEmpty()) {
            node.put("controlSources", String.join(", ", sources));
        }
        node.put("createdAt", epochSeconds(framework.getCreatedAt()));
        node.put("lastUpdatedAt", epochSeconds(framework.getLastUpdatedAt()));
        node.set("tags", objectMapper.valueToTree(framework.getTags() != null ? framework.getTags() : Map.of()));
        return node;
    }

    private ObjectNode controlNode(Control control) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", control.getArn());
        node.put("id", control.getId());
        node.put("type", "Custom");
        node.put("name", control.getName());
        putIfPresent(node, "description", control.getDescription());
        putIfPresent(node, "testingInformation", control.getTestingInformation());
        putIfPresent(node, "actionPlanTitle", control.getActionPlanTitle());
        putIfPresent(node, "actionPlanInstructions", control.getActionPlanInstructions());
        putIfPresent(node, "controlSources", controlSources(control));
        node.set("controlMappingSources", objectMapper.valueToTree(control.getControlMappingSources()));
        node.put("createdAt", epochSeconds(control.getCreatedAt()));
        node.put("lastUpdatedAt", epochSeconds(control.getLastUpdatedAt()));
        node.set("tags", objectMapper.valueToTree(control.getTags() != null ? control.getTags() : Map.of()));
        node.put("state", "ACTIVE");
        return node;
    }

    /** The distinct mapping source types of a control, comma separated as AWS reports them. */
    private String controlSources(Control control) {
        Set<String> types = new LinkedHashSet<>();
        for (Map<String, Object> source : control.getControlMappingSources()) {
            if (source.get("sourceType") instanceof String type && !type.isBlank()) {
                types.add(type);
            }
        }
        return types.isEmpty() ? null : String.join(", ", types);
    }

    private ObjectNode wrap(String field, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(field, value);
        return response;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static long epochSeconds(Instant instant) {
        return instant != null ? instant.getEpochSecond() : 0L;
    }

    // ──────────────────────── Parsing ────────────────────────

    private List<FrameworkControlSet> parseControlSets(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<FrameworkControlSet> sets = new ArrayList<>();
        for (JsonNode setNode : node) {
            JsonNode controlsNode = setNode.get("controls");
            List<String> controlIds = null;
            if (controlsNode != null && controlsNode.isArray()) {
                controlIds = new ArrayList<>();
                for (JsonNode control : controlsNode) {
                    controlIds.add(text(control, "id"));
                }
            }
            sets.add(new FrameworkControlSet(text(setNode, "id"), text(setNode, "name"), controlIds));
        }
        return sets;
    }

    private List<Map<String, Object>> parseMappingSources(JsonNode node) {
        if (node == null || !node.isArray()) {
            return null;
        }
        List<Map<String, Object>> sources = new ArrayList<>();
        for (JsonNode source : node) {
            if (source.isObject()) {
                sources.add(objectMapper.convertValue(source, MAP_TYPE));
            }
        }
        return sources;
    }

    private Map<String, String> parseTags(JsonNode tagsNode) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        }
        return tags;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
