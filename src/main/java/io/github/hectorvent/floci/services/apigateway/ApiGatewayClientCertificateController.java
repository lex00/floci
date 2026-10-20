package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.apigateway.model.ClientCertificate;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The API Gateway client certificate operations under {@code /clientcertificates}. */
@Path("/clientcertificates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class ApiGatewayClientCertificateController {

    private static final TypeReference<List<Map<String, String>>> PATCH_OPERATIONS = new TypeReference<>() { };
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 500;

    private final ApiGatewayClientCertificateService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ApiGatewayClientCertificateController(ApiGatewayClientCertificateService service,
                                                 RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response generate(@Context HttpHeaders headers, String body) {
        JsonNode request = readBody(body);
        Map<String, String> tags = new LinkedHashMap<>();
        request.path("tags").properties().forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
        String description = request.hasNonNull("description") ? request.path("description").asText() : null;
        ClientCertificate certificate = service.generate(regionResolver.resolveRegion(headers), description, tags);
        return Response.status(201).entity(toNode(certificate).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    public Response list(@Context HttpHeaders headers, @QueryParam("position") String position,
                         @QueryParam("limit") Integer limit) {
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw new AwsException("BadRequestException", "limit must be between 1 and " + MAX_LIMIT, 400);
        }
        List<ClientCertificate> all = service.list(regionResolver.resolveRegion(headers));
        int start = 0;
        if (position != null) {
            while (start < all.size() && all.get(start).getClientCertificateId().compareTo(position) < 0) {
                start++;
            }
        }
        int end = Math.min(all.size(), start + pageSize);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode items = root.putArray("item");
        all.subList(start, end).forEach(c -> items.add(toNode(c)));
        if (end < all.size()) {
            root.put("position", all.get(end).getClientCertificateId());
        }
        return Response.ok(root.toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @GET
    @Path("/{clientCertificateId}")
    public Response get(@Context HttpHeaders headers, @PathParam("clientCertificateId") String id) {
        ClientCertificate certificate = service.get(regionResolver.resolveRegion(headers), id);
        return Response.ok(toNode(certificate).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @PATCH
    @Path("/{clientCertificateId}")
    public Response update(@Context HttpHeaders headers, @PathParam("clientCertificateId") String id, String body) {
        List<Map<String, String>> operations;
        try {
            operations = objectMapper.convertValue(readBody(body).path("patchOperations"), PATCH_OPERATIONS);
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException", "Invalid patchOperations: " + e.getMessage(), 400);
        }
        ClientCertificate certificate = service.update(regionResolver.resolveRegion(headers), id, operations);
        return Response.ok(toNode(certificate).toString()).type(MediaType.APPLICATION_JSON).build();
    }

    @DELETE
    @Path("/{clientCertificateId}")
    public Response delete(@Context HttpHeaders headers, @PathParam("clientCertificateId") String id) {
        service.delete(regionResolver.resolveRegion(headers), id);
        return Response.accepted().build();
    }

    private JsonNode readBody(String body) {
        if (body == null || body.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(body);
        } catch (IOException e) {
            throw new AwsException("BadRequestException", "Invalid request body: " + e.getMessage(), 400);
        }
    }

    private ObjectNode toNode(ClientCertificate certificate) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("clientCertificateId", certificate.getClientCertificateId());
        if (certificate.getDescription() != null) {
            node.put("description", certificate.getDescription());
        }
        node.put("pemEncodedCertificate", certificate.getPemEncodedCertificate());
        node.put("createdDate", certificate.getCreatedDate());
        node.put("expirationDate", certificate.getExpirationDate());
        ObjectNode tags = node.putObject("tags");
        certificate.getTags().forEach(tags::put);
        return node;
    }
}
