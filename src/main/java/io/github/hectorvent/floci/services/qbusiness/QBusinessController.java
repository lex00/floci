package io.github.hectorvent.floci.services.qbusiness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
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

/**
 * Amazon Q Business control plane (REST JSON, signing name {@code qbusiness}). AppConfig and EMR
 * Serverless also serve {@code /applications}, so {@link QBusinessRouteFilter} moves requests
 * signed for Q Business onto {@value QBusinessRouteFilter#INTERNAL_PREFIX}.
 */
@Path(QBusinessRouteFilter.INTERNAL_PREFIX + "/applications")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class QBusinessController {

    private final QBusinessService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public QBusinessController(QBusinessService service, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
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
        return Response.ok(service.deleteApplication(applicationId, regionResolver.resolveRegion(headers))).build();
    }

    @GET
    public Response listApplications(@QueryParam("maxResults") String maxResults,
                                     @QueryParam("nextToken") String nextToken,
                                     @Context HttpHeaders headers) {
        return Response.ok(service.listApplications(Pagination.parseMaxResults(maxResults, "ValidationException"),
                nextToken, regionResolver.resolveRegion(headers))).build();
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
