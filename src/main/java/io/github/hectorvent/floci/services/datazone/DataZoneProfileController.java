package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static io.github.hectorvent.floci.services.datazone.DataZonePages.body;
import static io.github.hectorvent.floci.services.datazone.DataZonePages.page;

/** DataZone group profiles, profile search, the portal login URL, data export configuration and notifications. */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneProfileController {

    private final DataZoneProfileService service;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneProfileController(DataZoneProfileService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    // Group profiles

    @POST
    @Path("/{domainIdentifier}/group-profiles")
    public Response createGroupProfile(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                       JsonNode request) {
        return Response.status(201).entity(service.createGroupProfile(region(headers), domainId, body(request)))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/group-profiles/{groupIdentifier}")
    public Response getGroupProfile(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                    @PathParam("groupIdentifier") String groupIdentifier) {
        return Response.ok(service.getGroupProfile(region(headers), domainId, groupIdentifier)).build();
    }

    @PUT
    @Path("/{domainIdentifier}/group-profiles/{groupIdentifier}")
    public Response updateGroupProfile(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                       @PathParam("groupIdentifier") String groupIdentifier, JsonNode request) {
        return Response.ok(service.updateGroupProfile(region(headers), domainId, groupIdentifier, body(request)))
                .build();
    }

    @POST
    @Path("/{domainIdentifier}/search-group-profiles")
    public Response searchGroupProfiles(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                        JsonNode request) {
        JsonNode input = body(request);
        return Response.ok(page(service.searchGroupProfiles(region(headers), domainId, input),
                maxResults(input), 50, DataZoneRecords.text(input, "nextToken"), "items")).build();
    }

    // User profiles

    @POST
    @Path("/{domainIdentifier}/search-user-profiles")
    public Response searchUserProfiles(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                       JsonNode request) {
        JsonNode input = body(request);
        return Response.ok(page(service.searchUserProfiles(region(headers), domainId, input),
                maxResults(input), 50, DataZoneRecords.text(input, "nextToken"), "items")).build();
    }

    @POST
    @Path("/{domainIdentifier}/get-portal-login-url")
    public Response getIamPortalLoginUrl(@Context HttpHeaders headers,
                                         @PathParam("domainIdentifier") String domainId) {
        return Response.ok(service.getIamPortalLoginUrl(region(headers), domainId)).build();
    }

    // Data export configuration

    @PUT
    @Path("/{domainIdentifier}/data-export-configuration")
    public Response putDataExportConfiguration(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId, JsonNode request) {
        service.putDataExportConfiguration(region(headers), domainId, body(request));
        return Response.ok(DataZoneRecords.JSON.objectNode()).build();
    }

    @GET
    @Path("/{domainIdentifier}/data-export-configuration")
    public Response getDataExportConfiguration(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId) {
        return Response.ok(service.getDataExportConfiguration(region(headers), domainId)).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/data-export-configuration")
    public Response deleteDataExportConfiguration(@Context HttpHeaders headers,
                                                  @PathParam("domainIdentifier") String domainId) {
        service.deleteDataExportConfiguration(region(headers), domainId);
        return Response.noContent().build();
    }

    // Notifications

    @GET
    @Path("/{domainIdentifier}/notifications")
    public Response listNotifications(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @QueryParam("type") String type, @QueryParam("taskStatus") String taskStatus,
                                      @QueryParam("maxResults") Integer maxResults,
                                      @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listNotifications(region(headers), domainId, type, taskStatus),
                maxResults, 50, nextToken, "notifications")).build();
    }

    private static Integer maxResults(JsonNode input) {
        JsonNode value = input.get("maxResults");
        return value == null || value.isNull() ? null : value.asInt();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
