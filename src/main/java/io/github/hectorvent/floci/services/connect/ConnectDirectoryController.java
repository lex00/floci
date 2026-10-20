package io.github.hectorvent.floci.services.connect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Amazon Connect phone number and user hierarchy structure operations, which AWS serves outside
 * the {@code /instance} path that {@link ConnectController} owns.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConnectDirectoryController {

    private final ConnectService connectService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ConnectDirectoryController(ConnectService connectService, RegionResolver regionResolver,
                                      ObjectMapper objectMapper) {
        this.connectService = connectService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/phone-number/search-available")
    public Response searchAvailablePhoneNumbers(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        JsonNode maxResults = request.get("MaxResults");
        List<ObjectNode> numbers = connectService.searchAvailablePhoneNumbers(
                textOrNull(request, "TargetArn"),
                textOrNull(request, "InstanceId"),
                textOrNull(request, "PhoneNumberCountryCode"),
                textOrNull(request, "PhoneNumberType"),
                textOrNull(request, "PhoneNumberPrefix"),
                maxResults == null || maxResults.isNull() ? null : maxResults.asInt(),
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("AvailableNumbersList");
        numbers.forEach(list::add);
        return Response.ok(response).build();
    }

    @POST
    @Path("/phone-number/claim")
    public Response claimPhoneNumber(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        Map<String, String> tags = new LinkedHashMap<>();
        JsonNode tagsNode = request.get("Tags");
        if (tagsNode != null && tagsNode.isObject()) {
            tagsNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        ObjectNode number = connectService.claimPhoneNumber(
                textOrNull(request, "TargetArn"),
                textOrNull(request, "InstanceId"),
                textOrNull(request, "PhoneNumber"),
                textOrNull(request, "PhoneNumberDescription"),
                tags,
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("PhoneNumberId", number.path("PhoneNumberId").asText());
        response.put("PhoneNumberArn", number.path("PhoneNumberArn").asText());
        return Response.ok(response).build();
    }

    @GET
    @Path("/phone-number/{phoneNumberId}")
    public Response describePhoneNumber(@PathParam("phoneNumberId") String phoneNumberId,
                                        @Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ClaimedPhoneNumberSummary",
                connectService.describePhoneNumber(phoneNumberId, regionResolver.resolveRegion(headers)));
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/phone-number/{phoneNumberId}")
    public Response releasePhoneNumber(@PathParam("phoneNumberId") String phoneNumberId,
                                       @Context HttpHeaders headers) {
        connectService.releasePhoneNumber(phoneNumberId, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/user-hierarchy-structure/{instanceId}")
    public Response updateUserHierarchyStructure(@PathParam("instanceId") String instanceId,
                                                 @Context HttpHeaders headers, String body) {
        connectService.updateUserHierarchyStructure(instanceId, readTree(body).get("HierarchyStructure"),
                regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/user-hierarchy-structure/{instanceId}")
    public Response describeUserHierarchyStructure(@PathParam("instanceId") String instanceId,
                                                   @Context HttpHeaders headers) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("HierarchyStructure",
                connectService.describeUserHierarchyStructure(instanceId, regionResolver.resolveRegion(headers)));
        return Response.ok(response).build();
    }

    private JsonNode readTree(String body) {
        try {
            JsonNode node = objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
