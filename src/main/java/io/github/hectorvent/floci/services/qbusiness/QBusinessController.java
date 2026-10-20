package io.github.hectorvent.floci.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.Pagination;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Q Business control plane (REST JSON, signing name {@code qbusiness}). AppConfig and EMR
 * Serverless also serve {@code /applications}, so {@link QBusinessRouteFilter} moves requests
 * signed for Q Business onto {@value QBusinessRouteFilter#INTERNAL_PREFIX}.
 */
@Path(QBusinessRouteFilter.INTERNAL_PREFIX + "/applications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QBusinessController {

    private static final String KINDS = "indices|retrievers|plugins|experiences|dataaccessors";

    private final QBusinessService service;
    private final QBusinessResourceService resources;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public QBusinessController(QBusinessService service, QBusinessResourceService resources,
                               RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.resources = resources;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response createApplication(@Context HttpHeaders headers, String body) {
        return Response.ok(service.createApplication(readTree(body), regionResolver.resolveRegion(headers))).build();
    }

    @GET
    @Path("/{applicationId}")
    public Response getApplication(@PathParam("applicationId") String applicationId, @Context HttpHeaders headers) {
        return Response.ok(service.getApplication(applicationId, regionResolver.resolveRegion(headers))).build();
    }

    @PUT
    @Path("/{applicationId}")
    public Response updateApplication(@PathParam("applicationId") String applicationId,
                                      @Context HttpHeaders headers, String body) {
        return Response.ok(service.updateApplication(applicationId, readTree(body),
                regionResolver.resolveRegion(headers))).build();
    }

    @DELETE
    @Path("/{applicationId}")
    public Response deleteApplication(@PathParam("applicationId") String applicationId,
                                      @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        String arn = service.getApplication(applicationId, region).path("applicationArn").asText();
        Response response = Response.ok(service.deleteApplication(applicationId, region)).build();
        resources.deleteApplicationResources(arn);
        return response;
    }

    @GET
    public Response listApplications(@QueryParam("maxResults") String maxResults,
                                     @QueryParam("nextToken") String nextToken,
                                     @Context HttpHeaders headers) {
        return Response.ok(service.listApplications(Pagination.parseMaxResults(maxResults, "ValidationException"),
                nextToken, regionResolver.resolveRegion(headers))).build();
    }

    // ──────────────────────────── Resources inside an application ────────────────────────────

    /** Create, list, get, update and delete for the resource types listed in {@code KINDS}. */
    @POST
    @Path("/{applicationId}/{kind: " + KINDS + "}")
    public Response createInApplication(@PathParam("applicationId") String applicationId,
                                        @PathParam("kind") String kind, @Context HttpHeaders headers, String body) {
        return inApplication("Create" + kindOf(kind).noun(), kind, applicationId, null, body, null, null, headers);
    }

    @GET
    @Path("/{applicationId}/{kind: " + KINDS + "}")
    public Response listInApplication(@PathParam("applicationId") String applicationId,
                                      @PathParam("kind") String kind, @QueryParam("maxResults") String maxResults,
                                      @QueryParam("nextToken") String nextToken, @Context HttpHeaders headers) {
        return inApplication(kindOf(kind).listAction(), kind, applicationId, null, null, maxResults, nextToken,
                headers);
    }

    @GET
    @Path("/{applicationId}/{kind: " + KINDS + "}/{id}")
    public Response getInApplication(@PathParam("applicationId") String applicationId, @PathParam("kind") String kind,
                                     @PathParam("id") String id, @Context HttpHeaders headers) {
        return inApplication("Get" + kindOf(kind).noun(), kind, applicationId, id, null, null, null, headers);
    }

    @PUT
    @Path("/{applicationId}/{kind: " + KINDS + "}/{id}")
    public Response updateInApplication(@PathParam("applicationId") String applicationId,
                                        @PathParam("kind") String kind, @PathParam("id") String id,
                                        @Context HttpHeaders headers, String body) {
        return inApplication("Update" + kindOf(kind).noun(), kind, applicationId, id, body, null, null, headers);
    }

    @DELETE
    @Path("/{applicationId}/{kind: " + KINDS + "}/{id}")
    public Response deleteInApplication(@PathParam("applicationId") String applicationId,
                                        @PathParam("kind") String kind, @PathParam("id") String id,
                                        @Context HttpHeaders headers) {
        return inApplication("Delete" + kindOf(kind).noun(), kind, applicationId, id, null, null, null, headers);
    }

    private Response inApplication(String action, String collection, String applicationId, String id, String body,
                                   String maxResults, String nextToken, HttpHeaders headers) {
        QBusinessResourceService.Kind kind = kindOf(collection);
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(switch (action) {
            case "CreateIndex", "CreateRetriever", "CreatePlugin", "CreateWebExperience", "CreateDataAccessor" ->
                    resources.create(kind, applicationId, null, readTree(body), region);
            case "ListIndices", "ListRetrievers", "ListPlugins", "ListWebExperiences", "ListDataAccessors" ->
                    resources.list(kind, applicationId, null,
                            Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken, region);
            case "GetIndex", "GetRetriever", "GetPlugin", "GetWebExperience", "GetDataAccessor" ->
                    resources.get(kind, applicationId, null, id, region);
            case "UpdateIndex", "UpdateRetriever", "UpdatePlugin", "UpdateWebExperience", "UpdateDataAccessor" ->
                    resources.update(kind, applicationId, null, id, readTree(body), region);
            case "DeleteIndex", "DeleteRetriever", "DeletePlugin", "DeleteWebExperience", "DeleteDataAccessor" ->
                    resources.delete(kind, applicationId, null, id, region);
            default -> throw new AwsException("UnknownOperationException", "Unknown operation: " + action, 400);
        }).build();
    }

    @POST
    @Path("/{applicationId}/indices/{indexId}/datasources")
    public Response createDataSource(@PathParam("applicationId") String applicationId,
                                     @PathParam("indexId") String indexId, @Context HttpHeaders headers, String body) {
        return Response.ok(resources.create(QBusinessResourceService.DATA_SOURCE, applicationId, indexId,
                readTree(body), regionResolver.resolveRegion(headers))).build();
    }

    @GET
    @Path("/{applicationId}/indices/{indexId}/datasources")
    public Response listDataSources(@PathParam("applicationId") String applicationId,
                                    @PathParam("indexId") String indexId, @QueryParam("maxResults") String maxResults,
                                    @QueryParam("nextToken") String nextToken, @Context HttpHeaders headers) {
        return Response.ok(resources.list(QBusinessResourceService.DATA_SOURCE, applicationId, indexId,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken,
                regionResolver.resolveRegion(headers))).build();
    }

    @GET
    @Path("/{applicationId}/indices/{indexId}/datasources/{dataSourceId}")
    public Response getDataSource(@PathParam("applicationId") String applicationId,
                                  @PathParam("indexId") String indexId, @PathParam("dataSourceId") String dataSourceId,
                                  @Context HttpHeaders headers) {
        return Response.ok(resources.get(QBusinessResourceService.DATA_SOURCE, applicationId, indexId, dataSourceId,
                regionResolver.resolveRegion(headers))).build();
    }

    @PUT
    @Path("/{applicationId}/indices/{indexId}/datasources/{dataSourceId}")
    public Response updateDataSource(@PathParam("applicationId") String applicationId,
                                     @PathParam("indexId") String indexId,
                                     @PathParam("dataSourceId") String dataSourceId, @Context HttpHeaders headers,
                                     String body) {
        return Response.ok(resources.update(QBusinessResourceService.DATA_SOURCE, applicationId, indexId,
                dataSourceId, readTree(body), regionResolver.resolveRegion(headers))).build();
    }

    @DELETE
    @Path("/{applicationId}/indices/{indexId}/datasources/{dataSourceId}")
    public Response deleteDataSource(@PathParam("applicationId") String applicationId,
                                     @PathParam("indexId") String indexId,
                                     @PathParam("dataSourceId") String dataSourceId, @Context HttpHeaders headers) {
        return Response.ok(resources.delete(QBusinessResourceService.DATA_SOURCE, applicationId, indexId,
                dataSourceId, regionResolver.resolveRegion(headers))).build();
    }

    @POST
    @Path("/{applicationId}/indices/{indexId}/datasources/{dataSourceId}/startsync")
    @Consumes(MediaType.WILDCARD)
    public Response startDataSourceSyncJob(@PathParam("applicationId") String applicationId,
                                           @PathParam("indexId") String indexId,
                                           @PathParam("dataSourceId") String dataSourceId,
                                           @Context HttpHeaders headers) {
        return Response.ok(resources.startSync(applicationId, indexId, dataSourceId,
                regionResolver.resolveRegion(headers))).build();
    }

    @POST
    @Path("/{applicationId}/indices/{indexId}/datasources/{dataSourceId}/stopsync")
    @Consumes(MediaType.WILDCARD)
    public Response stopDataSourceSyncJob(@PathParam("applicationId") String applicationId,
                                          @PathParam("indexId") String indexId,
                                          @PathParam("dataSourceId") String dataSourceId,
                                          @Context HttpHeaders headers) {
        return Response.ok(resources.stopSync(applicationId, indexId, dataSourceId,
                regionResolver.resolveRegion(headers))).build();
    }

    @GET
    @Path("/{applicationId}/indices/{indexId}/datasources/{dataSourceId}/syncjobs")
    public Response listDataSourceSyncJobs(@PathParam("applicationId") String applicationId,
                                           @PathParam("indexId") String indexId,
                                           @PathParam("dataSourceId") String dataSourceId,
                                           @QueryParam("syncStatus") String syncStatus,
                                           @QueryParam("maxResults") String maxResults,
                                           @QueryParam("nextToken") String nextToken, @Context HttpHeaders headers) {
        return Response.ok(resources.listSyncJobs(applicationId, indexId, dataSourceId, syncStatus,
                Pagination.parseMaxResults(maxResults, "ValidationException"), nextToken,
                regionResolver.resolveRegion(headers))).build();
    }

    @GET
    @Path("/{applicationId}/chatcontrols")
    public Response getChatControlsConfiguration(@PathParam("applicationId") String applicationId,
                                                 @Context HttpHeaders headers) {
        return Response.ok(resources.getChatControls(applicationId, regionResolver.resolveRegion(headers))).build();
    }

    @PATCH
    @Path("/{applicationId}/chatcontrols")
    public Response updateChatControlsConfiguration(@PathParam("applicationId") String applicationId,
                                                    @Context HttpHeaders headers, String body) {
        return Response.ok(resources.updateChatControls(applicationId, readTree(body),
                regionResolver.resolveRegion(headers))).build();
    }

    @DELETE
    @Path("/{applicationId}/chatcontrols")
    public Response deleteChatControlsConfiguration(@PathParam("applicationId") String applicationId,
                                                    @Context HttpHeaders headers) {
        return Response.ok(resources.deleteChatControls(applicationId, regionResolver.resolveRegion(headers))).build();
    }

    @POST
    @Path("/{applicationId}/policy")
    public Response associatePermission(@PathParam("applicationId") String applicationId,
                                        @Context HttpHeaders headers, String body) {
        return Response.ok(resources.associatePermission(applicationId, readTree(body),
                regionResolver.resolveRegion(headers))).build();
    }

    @GET
    @Path("/{applicationId}/policy")
    public Response getPolicy(@PathParam("applicationId") String applicationId, @Context HttpHeaders headers) {
        return Response.ok(resources.getPolicy(applicationId, regionResolver.resolveRegion(headers))).build();
    }

    @DELETE
    @Path("/{applicationId}/policy/{statementId}")
    public Response disassociatePermission(@PathParam("applicationId") String applicationId,
                                           @PathParam("statementId") String statementId,
                                           @Context HttpHeaders headers) {
        return Response.ok(resources.disassociatePermission(applicationId, statementId,
                regionResolver.resolveRegion(headers))).build();
    }

    private static QBusinessResourceService.Kind kindOf(String collection) {
        return QBusinessResourceService.APPLICATION_KINDS.get(collection);
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
