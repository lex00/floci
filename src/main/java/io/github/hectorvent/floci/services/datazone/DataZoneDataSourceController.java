package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
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
import jakarta.ws.rs.core.UriInfo;

import java.util.HashMap;
import java.util.Map;

import static io.github.hectorvent.floci.services.datazone.DataZonePages.body;
import static io.github.hectorvent.floci.services.datazone.DataZonePages.page;

/** DataZone data sources and their runs, connections and metadata generation runs. */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneDataSourceController {

    private final DataZoneDataSourceService service;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneDataSourceController(DataZoneDataSourceService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    // Data sources

    @POST
    @Path("/{domainIdentifier}/data-sources")
    public Response createDataSource(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     JsonNode request) {
        return Response.status(201).entity(service.createDataSource(region(headers), domainId, body(request)))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/data-sources/{identifier}")
    public Response getDataSource(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                  @PathParam("identifier") String identifier) {
        return Response.ok(service.getDataSource(region(headers), domainId, identifier)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/data-sources/{identifier}")
    public Response updateDataSource(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateDataSource(region(headers), domainId, identifier, body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/data-sources/{identifier}")
    public Response deleteDataSource(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("identifier") String identifier,
                                     @QueryParam("retainPermissionsOnRevokeFailure") Boolean retainPermissions) {
        return Response.status(202).entity(service.deleteDataSource(region(headers), domainId, identifier,
                retainPermissions)).build();
    }

    @GET
    @Path("/{domainIdentifier}/data-sources")
    public Response listDataSources(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                    @PathParam("domainIdentifier") String domainId,
                                    @QueryParam("maxResults") Integer maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listDataSources(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    // Data source runs

    @POST
    @Path("/{domainIdentifier}/data-sources/{dataSourceIdentifier}/runs")
    public Response startDataSourceRun(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                       @PathParam("dataSourceIdentifier") String dataSourceId) {
        return Response.ok(service.startDataSourceRun(region(headers), domainId, dataSourceId)).build();
    }

    @GET
    @Path("/{domainIdentifier}/data-source-runs/{identifier}")
    public Response getDataSourceRun(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("identifier") String identifier) {
        return Response.ok(service.getDataSourceRun(region(headers), domainId, identifier)).build();
    }

    @GET
    @Path("/{domainIdentifier}/data-sources/{dataSourceIdentifier}/runs")
    public Response listDataSourceRuns(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                       @PathParam("domainIdentifier") String domainId,
                                       @PathParam("dataSourceIdentifier") String dataSourceId,
                                       @QueryParam("maxResults") Integer maxResults,
                                       @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listDataSourceRuns(region(headers), domainId, dataSourceId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    @GET
    @Path("/{domainIdentifier}/data-source-runs/{identifier}/activities")
    public Response listDataSourceRunActivities(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                                @PathParam("domainIdentifier") String domainId,
                                                @PathParam("identifier") String identifier,
                                                @QueryParam("maxResults") Integer maxResults,
                                                @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listDataSourceRunActivities(region(headers), domainId, identifier,
                query(uriInfo)), maxResults, 50, nextToken, "items")).build();
    }

    // Connections

    @POST
    @Path("/{domainIdentifier}/connections")
    public Response createConnection(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     JsonNode request) {
        return Response.ok(service.createConnection(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/connections/{identifier}")
    public Response getConnection(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                  @PathParam("identifier") String identifier) {
        return Response.ok(service.getConnection(region(headers), domainId, identifier)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/connections/{identifier}")
    public Response updateConnection(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateConnection(region(headers), domainId, identifier, body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/connections/{identifier}")
    public Response deleteConnection(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("identifier") String identifier) {
        service.deleteConnection(region(headers), domainId, identifier);
        return Response.status(202).entity(Map.of("status", "DELETED")).build();
    }

    @GET
    @Path("/{domainIdentifier}/connections")
    public Response listConnections(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                    @PathParam("domainIdentifier") String domainId,
                                    @QueryParam("maxResults") Integer maxResults,
                                    @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listConnections(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    // Metadata generation runs

    @POST
    @Path("/{domainIdentifier}/metadata-generation-runs")
    public Response startMetadataGenerationRun(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId, JsonNode request) {
        return Response.ok(service.startMetadataGenerationRun(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/metadata-generation-runs/{identifier}")
    public Response getMetadataGenerationRun(@Context HttpHeaders headers,
                                             @PathParam("domainIdentifier") String domainId,
                                             @PathParam("identifier") String identifier,
                                             @QueryParam("type") String type) {
        return Response.ok(service.getMetadataGenerationRun(region(headers), domainId, identifier, type)).build();
    }

    @GET
    @Path("/{domainIdentifier}/metadata-generation-runs")
    public Response listMetadataGenerationRuns(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                               @PathParam("domainIdentifier") String domainId,
                                               @QueryParam("maxResults") Integer maxResults,
                                               @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listMetadataGenerationRuns(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    @POST
    @Path("/{domainIdentifier}/metadata-generation-runs/{identifier}/cancel")
    public Response cancelMetadataGenerationRun(@Context HttpHeaders headers,
                                                @PathParam("domainIdentifier") String domainId,
                                                @PathParam("identifier") String identifier) {
        service.cancelMetadataGenerationRun(region(headers), domainId, identifier);
        return Response.ok(Map.of()).build();
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
