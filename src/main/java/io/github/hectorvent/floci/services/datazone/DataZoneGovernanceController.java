package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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

import static io.github.hectorvent.floci.services.datazone.DataZonePages.body;
import static io.github.hectorvent.floci.services.datazone.DataZonePages.page;

/** DataZone domain units, entity owners, project memberships and policy grants. */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneGovernanceController {

    private final DataZoneGovernanceService service;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneGovernanceController(DataZoneGovernanceService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    // Domain units

    @POST
    @Path("/{domainIdentifier}/domain-units")
    public Response createDomainUnit(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     JsonNode request) {
        return Response.status(201).entity(service.createDomainUnit(region(headers), domainId, body(request)))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/domain-units/{identifier}")
    public Response getDomainUnit(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                  @PathParam("identifier") String identifier) {
        return Response.ok(service.getDomainUnit(region(headers), domainId, identifier)).build();
    }

    @PUT
    @Path("/{domainIdentifier}/domain-units/{identifier}")
    public Response updateDomainUnit(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateDomainUnit(region(headers), domainId, identifier, body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/domain-units/{identifier}")
    public Response deleteDomainUnit(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("identifier") String identifier) {
        service.deleteDomainUnit(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/domain-units")
    public Response listDomainUnitsForParent(@Context HttpHeaders headers,
                                             @PathParam("domainIdentifier") String domainId,
                                             @QueryParam("parentDomainUnitIdentifier") String parent,
                                             @QueryParam("maxResults") Integer maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listDomainUnitsForParent(region(headers), domainId, parent),
                maxResults, 25, nextToken, "items")).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/root-domain-unit-owner")
    public Response updateRootDomainUnitOwner(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId, JsonNode request) {
        service.updateRootDomainUnitOwner(region(headers), domainId, body(request));
        return Response.noContent().build();
    }

    // Entity owners

    @POST
    @Path("/{domainIdentifier}/entities/{entityType}/{entityIdentifier}/addOwner")
    public Response addEntityOwner(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("entityType") String entityType,
                                   @PathParam("entityIdentifier") String entityIdentifier, JsonNode request) {
        service.addEntityOwner(region(headers), domainId, entityType, entityIdentifier, body(request));
        return Response.status(201).entity(JsonNodeFactory.instance.objectNode()).build();
    }

    @POST
    @Path("/{domainIdentifier}/entities/{entityType}/{entityIdentifier}/removeOwner")
    public Response removeEntityOwner(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("entityType") String entityType,
                                      @PathParam("entityIdentifier") String entityIdentifier, JsonNode request) {
        service.removeEntityOwner(region(headers), domainId, entityType, entityIdentifier, body(request));
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/entities/{entityType}/{entityIdentifier}/owners")
    public Response listEntityOwners(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("entityType") String entityType,
                                     @PathParam("entityIdentifier") String entityIdentifier,
                                     @QueryParam("maxResults") Integer maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listEntityOwners(region(headers), domainId, entityType, entityIdentifier),
                maxResults, 25, nextToken, "owners")).build();
    }

    // Project memberships

    @POST
    @Path("/{domainIdentifier}/projects/{projectIdentifier}/createMembership")
    public Response createProjectMembership(@Context HttpHeaders headers,
                                            @PathParam("domainIdentifier") String domainId,
                                            @PathParam("projectIdentifier") String projectId, JsonNode request) {
        service.createProjectMembership(region(headers), domainId, projectId, body(request));
        return Response.status(201).entity(JsonNodeFactory.instance.objectNode()).build();
    }

    @POST
    @Path("/{domainIdentifier}/projects/{projectIdentifier}/deleteMembership")
    public Response deleteProjectMembership(@Context HttpHeaders headers,
                                            @PathParam("domainIdentifier") String domainId,
                                            @PathParam("projectIdentifier") String projectId, JsonNode request) {
        service.deleteProjectMembership(region(headers), domainId, projectId, body(request));
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/projects/{projectIdentifier}/memberships")
    public Response listProjectMemberships(@Context HttpHeaders headers,
                                           @PathParam("domainIdentifier") String domainId,
                                           @PathParam("projectIdentifier") String projectId,
                                           @QueryParam("sortBy") String sortBy,
                                           @QueryParam("sortOrder") String sortOrder,
                                           @QueryParam("maxResults") Integer maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listProjectMemberships(region(headers), domainId, projectId, sortBy,
                sortOrder), maxResults, 50, nextToken, "members")).build();
    }

    // Policy grants

    @POST
    @Path("/{domainIdentifier}/policies/managed/{entityType}/{entityIdentifier}/addGrant")
    public Response addPolicyGrant(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("entityType") String entityType,
                                   @PathParam("entityIdentifier") String entityIdentifier, JsonNode request) {
        String grantId = service.addPolicyGrant(region(headers), domainId, entityType, entityIdentifier,
                body(request));
        return Response.status(201).entity(JsonNodeFactory.instance.objectNode().put("grantId", grantId)).build();
    }

    @POST
    @Path("/{domainIdentifier}/policies/managed/{entityType}/{entityIdentifier}/removeGrant")
    public Response removePolicyGrant(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("entityType") String entityType,
                                      @PathParam("entityIdentifier") String entityIdentifier, JsonNode request) {
        service.removePolicyGrant(region(headers), domainId, entityType, entityIdentifier, body(request));
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/policies/managed/{entityType}/{entityIdentifier}/grants")
    public Response listPolicyGrants(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("entityType") String entityType,
                                     @PathParam("entityIdentifier") String entityIdentifier,
                                     @QueryParam("policyType") String policyType,
                                     @QueryParam("maxResults") Integer maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listPolicyGrants(region(headers), domainId, entityType, entityIdentifier,
                policyType), maxResults, 25, nextToken, "grantList")).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
