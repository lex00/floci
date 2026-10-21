package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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

import java.util.HashMap;
import java.util.Map;

import static io.github.hectorvent.floci.services.datazone.DataZonePages.body;
import static io.github.hectorvent.floci.services.datazone.DataZonePages.page;

/** DataZone notebooks, notebook runs, exports, imports and syncs, and job runs. */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneNotebookController {

    private final DataZoneNotebookService service;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneNotebookController(DataZoneNotebookService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    // Notebooks

    @POST
    @Path("/{domainIdentifier}/notebooks")
    public Response createNotebook(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   JsonNode request) {
        return Response.status(201).entity(service.createNotebook(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/notebooks/{identifier}")
    public Response getNotebook(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                @PathParam("identifier") String identifier) {
        return Response.ok(service.getNotebook(region(headers), domainId, identifier)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/notebooks/{identifier}")
    public Response updateNotebook(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateNotebook(region(headers), domainId, identifier, body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/notebooks/{identifier}")
    public Response deleteNotebook(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("identifier") String identifier) {
        service.deleteNotebook(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/notebooks")
    public Response listNotebooks(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                  @PathParam("domainIdentifier") String domainId,
                                  @QueryParam("maxResults") Integer maxResults,
                                  @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listNotebooks(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    // Notebook runs

    @POST
    @Path("/{domainIdentifier}/notebook-runs")
    public Response startNotebookRun(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     JsonNode request) {
        return Response.ok(service.startNotebookRun(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/notebook-runs/{identifier}")
    public Response getNotebookRun(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("identifier") String identifier) {
        return Response.ok(service.getNotebookRun(region(headers), domainId, identifier)).build();
    }

    @PUT
    @Path("/{domainIdentifier}/notebook-runs/{identifier}/stop")
    public Response stopNotebookRun(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                    @PathParam("identifier") String identifier) {
        return Response.ok(service.stopNotebookRun(region(headers), domainId, identifier)).build();
    }

    @GET
    @Path("/{domainIdentifier}/notebook-runs")
    public Response listNotebookRuns(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                     @PathParam("domainIdentifier") String domainId,
                                     @QueryParam("maxResults") Integer maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listNotebookRuns(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    // Notebook exports, imports and syncs

    @POST
    @Path("/{domainIdentifier}/notebook-exports")
    public Response startNotebookExport(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                        JsonNode request) {
        return Response.ok(service.startNotebookExport(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/notebook-exports/{identifier}")
    public Response getNotebookExport(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("identifier") String identifier) {
        return Response.ok(service.getNotebookExport(region(headers), domainId, identifier)).build();
    }

    @POST
    @Path("/{domainIdentifier}/notebook-imports")
    public Response startNotebookImport(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                        JsonNode request) {
        return Response.ok(service.startNotebookImport(region(headers), domainId, body(request))).build();
    }

    @POST
    @Path("/{domainIdentifier}/notebook-syncs")
    public Response startNotebookSync(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      JsonNode request) {
        return Response.ok(service.startNotebookSync(region(headers), domainId, body(request))).build();
    }

    // Job runs

    @GET
    @Path("/{domainIdentifier}/jobRuns/{identifier}")
    public Response getJobRun(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                              @PathParam("identifier") String identifier) {
        return Response.ok(service.getJobRun(region(headers), domainId, identifier)).build();
    }

    @GET
    @Path("/{domainIdentifier}/jobs/{jobIdentifier}/runs")
    public Response listJobRuns(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                @PathParam("domainIdentifier") String domainId,
                                @PathParam("jobIdentifier") String jobIdentifier,
                                @QueryParam("maxResults") Integer maxResults,
                                @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listJobRuns(region(headers), domainId, jobIdentifier, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
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
