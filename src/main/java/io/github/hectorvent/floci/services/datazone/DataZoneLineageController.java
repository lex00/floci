package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.datazone.DataZonePages.body;
import static io.github.hectorvent.floci.services.datazone.DataZonePages.page;

/** DataZone lineage events, lineage nodes, graph queries and the metadata kept on assets and listings. */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneLineageController {

    private static final String ENTITY = "/{domainIdentifier}/entities/{entityType}/{entityIdentifier}";

    private final DataZoneLineageService lineage;
    private final DataZoneEntityMetadataService metadata;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneLineageController(DataZoneLineageService lineage, DataZoneEntityMetadataService metadata,
                                     RegionResolver regionResolver) {
        this.lineage = lineage;
        this.metadata = metadata;
        this.regionResolver = regionResolver;
    }

    // Lineage events

    @POST
    @Path("/{domainIdentifier}/lineage/events")
    @Consumes(MediaType.WILDCARD)
    public Response postLineageEvent(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @HeaderParam("Client-Token") String clientToken, byte[] event) {
        return Response.status(201).entity(lineage.postLineageEvent(region(headers), domainId, event, clientToken))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/lineage/events/{identifier}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response getLineageEvent(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                    @PathParam("identifier") String identifier) {
        ObjectNode event = lineage.getLineageEvent(region(headers), domainId, identifier);
        return Response.ok(event.path("event").asText().getBytes(StandardCharsets.UTF_8))
                .header("Domain-Id", event.path("domainId").asText())
                .header("Id", event.path("id").asText())
                .header("Created-By", event.path("createdBy").asText())
                .header("Processing-Status", event.path("processingStatus").asText())
                .header("Event-Time", httpDate(event.path("eventTime").decimalValue()))
                .header("Created-At", httpDate(event.path("createdAt").decimalValue()))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/lineage/events")
    public Response listLineageEvents(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                      @PathParam("domainIdentifier") String domainId,
                                      @QueryParam("maxResults") Integer maxResults,
                                      @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(lineage.listLineageEvents(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/lineage/events/{identifier}")
    public Response deleteLineageEvent(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                       @PathParam("identifier") String identifier) {
        return Response.ok(lineage.deleteLineageEvent(region(headers), domainId, identifier)).build();
    }

    // Lineage nodes and graph

    @GET
    @Path("/{domainIdentifier}/lineage/nodes/{identifier}")
    public Response getLineageNode(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("identifier") String identifier,
                                   @QueryParam("timestamp") String timestamp) {
        return Response.ok(lineage.getLineageNode(region(headers), domainId, identifier, timestamp)).build();
    }

    @GET
    @Path("/{domainIdentifier}/lineage/nodes/{identifier}/history")
    public Response listLineageNodeHistory(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                           @PathParam("domainIdentifier") String domainId,
                                           @PathParam("identifier") String identifier,
                                           @QueryParam("maxResults") Integer maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(lineage.listLineageNodeHistory(region(headers), domainId, identifier,
                query(uriInfo)), maxResults, 50, nextToken, "nodes")).build();
    }

    @POST
    @Path("/{domainIdentifier}/graph/query")
    public Response queryGraph(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                               @QueryParam("maxResults") Integer maxResults,
                               @QueryParam("nextToken") String nextToken, JsonNode request) {
        return Response.ok(page(lineage.queryGraph(region(headers), domainId, body(request)), maxResults, 50,
                nextToken, "items")).build();
    }

    // Time series data points

    @POST
    @Path(ENTITY + "/time-series-data-points")
    public Response postTimeSeriesDataPoints(@Context HttpHeaders headers,
                                             @PathParam("domainIdentifier") String domainId,
                                             @PathParam("entityType") String entityType,
                                             @PathParam("entityIdentifier") String entityId, JsonNode request) {
        return Response.status(201).entity(metadata.postTimeSeriesDataPoints(region(headers), domainId, entityType,
                entityId, body(request))).build();
    }

    @GET
    @Path(ENTITY + "/time-series-data-points/{identifier}")
    public Response getTimeSeriesDataPoint(@Context HttpHeaders headers,
                                           @PathParam("domainIdentifier") String domainId,
                                           @PathParam("entityType") String entityType,
                                           @PathParam("entityIdentifier") String entityId,
                                           @PathParam("identifier") String identifier,
                                           @QueryParam("formName") String formName) {
        return Response.ok(metadata.getTimeSeriesDataPoint(region(headers), domainId, entityType, entityId,
                identifier, formName)).build();
    }

    @GET
    @Path(ENTITY + "/time-series-data-points")
    public Response listTimeSeriesDataPoints(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                             @PathParam("domainIdentifier") String domainId,
                                             @PathParam("entityType") String entityType,
                                             @PathParam("entityIdentifier") String entityId,
                                             @QueryParam("maxResults") Integer maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(metadata.listTimeSeriesDataPoints(region(headers), domainId, entityType, entityId,
                query(uriInfo)), maxResults, 50, nextToken, "items")).build();
    }

    @DELETE
    @Path(ENTITY + "/time-series-data-points")
    public Response deleteTimeSeriesDataPoints(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId,
                                               @PathParam("entityType") String entityType,
                                               @PathParam("entityIdentifier") String entityId,
                                               @QueryParam("formName") String formName) {
        metadata.deleteTimeSeriesDataPoints(region(headers), domainId, entityType, entityId, formName);
        return Response.noContent().build();
    }

    // Attribute metadata

    @GET
    @Path(ENTITY + "/attributes-metadata")
    public Response batchGetAttributesMetadata(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId,
                                               @PathParam("entityType") String entityType,
                                               @PathParam("entityIdentifier") String entityId,
                                               @QueryParam("attributeIdentifier") List<String> attributeIds) {
        return Response.ok(metadata.batchGetAttributesMetadata(region(headers), domainId, entityType, entityId,
                attributeIds)).build();
    }

    @PUT
    @Path(ENTITY + "/attributes-metadata")
    public Response batchPutAttributesMetadata(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId,
                                               @PathParam("entityType") String entityType,
                                               @PathParam("entityIdentifier") String entityId, JsonNode request) {
        return Response.ok(metadata.batchPutAttributesMetadata(region(headers), domainId, entityType, entityId,
                body(request))).build();
    }

    // Governed terms

    @PATCH
    @Path(ENTITY + "/associate-governed-terms")
    public Response associateGovernedTerms(@Context HttpHeaders headers,
                                           @PathParam("domainIdentifier") String domainId,
                                           @PathParam("entityType") String entityType,
                                           @PathParam("entityIdentifier") String entityId, JsonNode request) {
        metadata.associateGovernedTerms(region(headers), domainId, entityType, entityId, body(request));
        return Response.ok(DataZoneRecords.JSON.objectNode()).build();
    }

    @PATCH
    @Path(ENTITY + "/disassociate-governed-terms")
    public Response disassociateGovernedTerms(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId,
                                              @PathParam("entityType") String entityType,
                                              @PathParam("entityIdentifier") String entityId, JsonNode request) {
        metadata.disassociateGovernedTerms(region(headers), domainId, entityType, entityId, body(request));
        return Response.ok(DataZoneRecords.JSON.objectNode()).build();
    }

    private static String httpDate(BigDecimal seconds) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.ofEpochMilli(seconds.movePointRight(3).longValue()).atOffset(ZoneOffset.UTC));
    }

    private static Map<String, String> query(UriInfo uriInfo) {
        Map<String, String> out = new HashMap<>();
        uriInfo.getQueryParameters().forEach((name, values) -> {
            if (!values.isEmpty()) {
                out.put(name, values.getFirst());
            }
        });
        return out;
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
