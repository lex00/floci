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

/** DataZone subscription requests, subscriptions, subscription targets and subscription grants. */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneSubscriptionController {

    private final DataZoneSubscriptionService service;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneSubscriptionController(DataZoneSubscriptionService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    // Subscription requests

    @POST
    @Path("/{domainIdentifier}/subscription-requests")
    public Response createSubscriptionRequest(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId, JsonNode request) {
        return Response.ok(service.createSubscriptionRequest(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/subscription-requests/{identifier}")
    public Response getSubscriptionRequestDetails(@Context HttpHeaders headers,
                                                  @PathParam("domainIdentifier") String domainId,
                                                  @PathParam("identifier") String identifier) {
        return Response.ok(service.getSubscriptionRequestDetails(region(headers), domainId, identifier)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/subscription-requests/{identifier}")
    public Response updateSubscriptionRequest(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId,
                                              @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateSubscriptionRequest(region(headers), domainId, identifier, body(request)))
                .build();
    }

    @DELETE
    @Path("/{domainIdentifier}/subscription-requests/{identifier}")
    public Response deleteSubscriptionRequest(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId,
                                              @PathParam("identifier") String identifier) {
        service.deleteSubscriptionRequest(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @PUT
    @Path("/{domainIdentifier}/subscription-requests/{identifier}/accept")
    public Response acceptSubscriptionRequest(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId,
                                              @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.acceptSubscriptionRequest(region(headers), domainId, identifier, body(request)))
                .build();
    }

    @PUT
    @Path("/{domainIdentifier}/subscription-requests/{identifier}/reject")
    public Response rejectSubscriptionRequest(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId,
                                              @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.rejectSubscriptionRequest(region(headers), domainId, identifier, body(request)))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/subscription-requests")
    public Response listSubscriptionRequests(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                             @PathParam("domainIdentifier") String domainId,
                                             @QueryParam("maxResults") Integer maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listSubscriptionRequests(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    // Subscriptions

    @GET
    @Path("/{domainIdentifier}/subscriptions/{identifier}")
    public Response getSubscription(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                    @PathParam("identifier") String identifier) {
        return Response.ok(service.getSubscription(region(headers), domainId, identifier)).build();
    }

    @PUT
    @Path("/{domainIdentifier}/subscriptions/{identifier}/cancel")
    public Response cancelSubscription(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                       @PathParam("identifier") String identifier) {
        return Response.ok(service.cancelSubscription(region(headers), domainId, identifier)).build();
    }

    @PUT
    @Path("/{domainIdentifier}/subscriptions/{identifier}/revoke")
    public Response revokeSubscription(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                       @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.revokeSubscription(region(headers), domainId, identifier, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/subscriptions")
    public Response listSubscriptions(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                      @PathParam("domainIdentifier") String domainId,
                                      @QueryParam("maxResults") Integer maxResults,
                                      @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listSubscriptions(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    // Subscription targets

    @POST
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/subscription-targets")
    public Response createSubscriptionTarget(@Context HttpHeaders headers,
                                             @PathParam("domainIdentifier") String domainId,
                                             @PathParam("environmentIdentifier") String environmentId,
                                             JsonNode request) {
        return Response.ok(service.createSubscriptionTarget(region(headers), domainId, environmentId,
                body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/subscription-targets/{identifier}")
    public Response getSubscriptionTarget(@Context HttpHeaders headers,
                                          @PathParam("domainIdentifier") String domainId,
                                          @PathParam("environmentIdentifier") String environmentId,
                                          @PathParam("identifier") String identifier) {
        return Response.ok(service.getSubscriptionTarget(region(headers), domainId, environmentId, identifier))
                .build();
    }

    @PATCH
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/subscription-targets/{identifier}")
    public Response updateSubscriptionTarget(@Context HttpHeaders headers,
                                             @PathParam("domainIdentifier") String domainId,
                                             @PathParam("environmentIdentifier") String environmentId,
                                             @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateSubscriptionTarget(region(headers), domainId, environmentId, identifier,
                body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/subscription-targets/{identifier}")
    public Response deleteSubscriptionTarget(@Context HttpHeaders headers,
                                             @PathParam("domainIdentifier") String domainId,
                                             @PathParam("environmentIdentifier") String environmentId,
                                             @PathParam("identifier") String identifier) {
        service.deleteSubscriptionTarget(region(headers), domainId, environmentId, identifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/subscription-targets")
    public Response listSubscriptionTargets(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                            @PathParam("domainIdentifier") String domainId,
                                            @PathParam("environmentIdentifier") String environmentId,
                                            @QueryParam("maxResults") Integer maxResults,
                                            @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listSubscriptionTargets(region(headers), domainId, environmentId,
                query(uriInfo)), maxResults, 50, nextToken, "items")).build();
    }

    // Subscription grants

    @POST
    @Path("/{domainIdentifier}/subscription-grants")
    public Response createSubscriptionGrant(@Context HttpHeaders headers,
                                            @PathParam("domainIdentifier") String domainId, JsonNode request) {
        return Response.ok(service.createSubscriptionGrant(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/subscription-grants/{identifier}")
    public Response getSubscriptionGrant(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                         @PathParam("identifier") String identifier) {
        return Response.ok(service.getSubscriptionGrant(region(headers), domainId, identifier)).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/subscription-grants/{identifier}")
    public Response deleteSubscriptionGrant(@Context HttpHeaders headers,
                                            @PathParam("domainIdentifier") String domainId,
                                            @PathParam("identifier") String identifier) {
        return Response.ok(service.deleteSubscriptionGrant(region(headers), domainId, identifier)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/subscription-grants/{identifier}/status/{assetIdentifier}")
    public Response updateSubscriptionGrantStatus(@Context HttpHeaders headers,
                                                  @PathParam("domainIdentifier") String domainId,
                                                  @PathParam("identifier") String identifier,
                                                  @PathParam("assetIdentifier") String assetId, JsonNode request) {
        return Response.ok(service.updateSubscriptionGrantStatus(region(headers), domainId, identifier, assetId,
                body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/subscription-grants")
    public Response listSubscriptionGrants(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                           @PathParam("domainIdentifier") String domainId,
                                           @QueryParam("maxResults") Integer maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listSubscriptionGrants(region(headers), domainId, query(uriInfo)),
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
