package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
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
import java.util.List;
import java.util.Map;

import static io.github.hectorvent.floci.services.datazone.DataZonePages.body;
import static io.github.hectorvent.floci.services.datazone.DataZonePages.page;

/**
 * DataZone project profiles, rules, account pools, custom environment blueprints, and environment
 * actions, roles and credentials.
 */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneAdministrationController {

    private final DataZoneAdministrationService service;
    private final DataZoneEnvironmentService environments;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneAdministrationController(DataZoneAdministrationService service,
                                            DataZoneEnvironmentService environments, RegionResolver regionResolver) {
        this.service = service;
        this.environments = environments;
        this.regionResolver = regionResolver;
    }

    // Project profiles

    @POST
    @Path("/{domainIdentifier}/project-profiles")
    public Response createProjectProfile(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                         JsonNode request) {
        return Response.status(201).entity(service.createProjectProfile(region(headers), domainId, body(request)))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/project-profiles/{identifier}")
    public Response getProjectProfile(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("identifier") String identifier) {
        return Response.ok(service.getProjectProfile(region(headers), domainId, identifier)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/project-profiles/{identifier}")
    public Response updateProjectProfile(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                         @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateProjectProfile(region(headers), domainId, identifier, body(request)))
                .build();
    }

    @DELETE
    @Path("/{domainIdentifier}/project-profiles/{identifier}")
    public Response deleteProjectProfile(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                         @PathParam("identifier") String identifier) {
        service.deleteProjectProfile(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/project-profiles")
    public Response listProjectProfiles(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                        @PathParam("domainIdentifier") String domainId,
                                        @QueryParam("maxResults") Integer maxResults,
                                        @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listProjectProfiles(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    // Rules

    @POST
    @Path("/{domainIdentifier}/rules")
    public Response createRule(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                               JsonNode request) {
        return Response.status(201).entity(service.createRule(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/rules/{identifier}")
    public Response getRule(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                            @PathParam("identifier") String identifier, @QueryParam("revision") String revision) {
        return Response.ok(service.getRule(region(headers), domainId, identifier, revision)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/rules/{identifier}")
    public Response updateRule(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                               @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateRule(region(headers), domainId, identifier, body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/rules/{identifier}")
    public Response deleteRule(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                               @PathParam("identifier") String identifier) {
        service.deleteRule(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/list-rules/{targetType}/{targetIdentifier}")
    public Response listRules(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                              @PathParam("domainIdentifier") String domainId,
                              @PathParam("targetType") String targetType,
                              @PathParam("targetIdentifier") String targetIdentifier,
                              @QueryParam("projectIds") List<String> projectIds,
                              @QueryParam("assetTypes") List<String> assetTypes,
                              @QueryParam("maxResults") Integer maxResults,
                              @QueryParam("nextToken") String nextToken) {
        if (maxResults != null && maxResults < 25) {
            throw SmithyValidationErrors.validationException("Value '" + maxResults
                    + "' at 'maxResults' failed to satisfy constraint: Member must have value between 25 and 50");
        }
        return Response.ok(page(service.listRules(region(headers), domainId, targetType, targetIdentifier,
                query(uriInfo), projectIds, assetTypes), maxResults, 50, nextToken, "items")).build();
    }

    // Account pools

    @POST
    @Path("/{domainIdentifier}/account-pools")
    public Response createAccountPool(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      JsonNode request) {
        return Response.status(201).entity(service.createAccountPool(region(headers), domainId, body(request)))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/account-pools/{identifier}")
    public Response getAccountPool(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("identifier") String identifier) {
        return Response.ok(service.getAccountPool(region(headers), domainId, identifier)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/account-pools/{identifier}")
    public Response updateAccountPool(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateAccountPool(region(headers), domainId, identifier, body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/account-pools/{identifier}")
    public Response deleteAccountPool(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("identifier") String identifier) {
        service.deleteAccountPool(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/account-pools")
    public Response listAccountPools(@Context HttpHeaders headers, @Context UriInfo uriInfo,
                                     @PathParam("domainIdentifier") String domainId,
                                     @QueryParam("maxResults") Integer maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listAccountPools(region(headers), domainId, query(uriInfo)),
                maxResults, 50, nextToken, "items")).build();
    }

    @GET
    @Path("/{domainIdentifier}/account-pools/{identifier}/accounts")
    public Response listAccountsInAccountPool(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId,
                                              @PathParam("identifier") String identifier,
                                              @QueryParam("maxResults") Integer maxResults,
                                              @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listAccountsInAccountPool(region(headers), domainId, identifier),
                maxResults, 50, nextToken, "items")).build();
    }

    // Custom environment blueprints

    @POST
    @Path("/{domainIdentifier}/environment-blueprints")
    public Response createEnvironmentBlueprint(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId, JsonNode request) {
        return Response.status(201).entity(environments.createEnvironmentBlueprint(region(headers), domainId,
                body(request))).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/environment-blueprints/{identifier}")
    public Response updateEnvironmentBlueprint(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId,
                                               @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(environments.updateEnvironmentBlueprint(region(headers), domainId, identifier,
                body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/environment-blueprints/{identifier}")
    public Response deleteEnvironmentBlueprint(@Context HttpHeaders headers,
                                               @PathParam("domainIdentifier") String domainId,
                                               @PathParam("identifier") String identifier) {
        environments.deleteEnvironmentBlueprint(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    // Environment actions

    @POST
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/actions")
    public Response createEnvironmentAction(@Context HttpHeaders headers,
                                            @PathParam("domainIdentifier") String domainId,
                                            @PathParam("environmentIdentifier") String environmentId,
                                            JsonNode request) {
        return Response.status(201).entity(service.createEnvironmentAction(region(headers), domainId, environmentId,
                body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/actions/{identifier}")
    public Response getEnvironmentAction(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                         @PathParam("environmentIdentifier") String environmentId,
                                         @PathParam("identifier") String identifier) {
        return Response.ok(service.getEnvironmentAction(region(headers), domainId, environmentId, identifier))
                .build();
    }

    @PATCH
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/actions/{identifier}")
    public Response updateEnvironmentAction(@Context HttpHeaders headers,
                                            @PathParam("domainIdentifier") String domainId,
                                            @PathParam("environmentIdentifier") String environmentId,
                                            @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateEnvironmentAction(region(headers), domainId, environmentId, identifier,
                body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/actions/{identifier}")
    public Response deleteEnvironmentAction(@Context HttpHeaders headers,
                                            @PathParam("domainIdentifier") String domainId,
                                            @PathParam("environmentIdentifier") String environmentId,
                                            @PathParam("identifier") String identifier) {
        service.deleteEnvironmentAction(region(headers), domainId, environmentId, identifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/actions")
    public Response listEnvironmentActions(@Context HttpHeaders headers,
                                           @PathParam("domainIdentifier") String domainId,
                                           @PathParam("environmentIdentifier") String environmentId,
                                           @QueryParam("maxResults") Integer maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listEnvironmentActions(region(headers), domainId, environmentId),
                maxResults, 50, nextToken, "items")).build();
    }

    // Environment roles and credentials

    @PUT
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/roles/{environmentRoleArn: .+}")
    public Response associateEnvironmentRole(@Context HttpHeaders headers,
                                             @PathParam("domainIdentifier") String domainId,
                                             @PathParam("environmentIdentifier") String environmentId,
                                             @PathParam("environmentRoleArn") String roleArn) {
        service.associateEnvironmentRole(region(headers), domainId, environmentId, roleArn);
        return Response.ok(DataZoneRecords.JSON.objectNode()).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/roles/{environmentRoleArn: .+}")
    public Response disassociateEnvironmentRole(@Context HttpHeaders headers,
                                                @PathParam("domainIdentifier") String domainId,
                                                @PathParam("environmentIdentifier") String environmentId,
                                                @PathParam("environmentRoleArn") String roleArn) {
        service.disassociateEnvironmentRole(region(headers), domainId, environmentId, roleArn);
        return Response.ok(DataZoneRecords.JSON.objectNode()).build();
    }

    @GET
    @Path("/{domainIdentifier}/environments/{environmentIdentifier}/credentials")
    public Response getEnvironmentCredentials(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId,
                                              @PathParam("environmentIdentifier") String environmentId) {
        return Response.ok(service.getEnvironmentCredentials(region(headers), domainId, environmentId)).build();
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
