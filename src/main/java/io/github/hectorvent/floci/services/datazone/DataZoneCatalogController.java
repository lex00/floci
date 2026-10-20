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

import static io.github.hectorvent.floci.services.datazone.DataZonePages.body;
import static io.github.hectorvent.floci.services.datazone.DataZonePages.page;

/** DataZone assets, asset filters, data products, listings and catalog search. */
@Path("/v2/domains")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DataZoneCatalogController {

    private final DataZoneCatalogService service;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneCatalogController(DataZoneCatalogService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    // Assets

    @POST
    @Path("/{domainIdentifier}/assets")
    public Response createAsset(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                JsonNode request) {
        return Response.status(201).entity(service.createAsset(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/assets/{identifier}")
    public Response getAsset(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                             @PathParam("identifier") String identifier, @QueryParam("revision") String revision) {
        return Response.ok(service.getAsset(region(headers), domainId, identifier, revision)).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/assets/{identifier}")
    public Response deleteAsset(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                @PathParam("identifier") String identifier) {
        service.deleteAsset(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @POST
    @Path("/{domainIdentifier}/assets/{identifier}/revisions")
    public Response createAssetRevision(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                        @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.createAssetRevision(region(headers), domainId, identifier, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/assets/{identifier}/revisions")
    public Response listAssetRevisions(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                       @PathParam("identifier") String identifier,
                                       @QueryParam("maxResults") Integer maxResults,
                                       @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listAssetRevisions(region(headers), domainId, identifier),
                maxResults, 50, nextToken, "items")).build();
    }

    @PUT
    @Path("/{domainIdentifier}/assets/{identifier}/accept-predictions")
    public Response acceptPredictions(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("identifier") String identifier,
                                      @QueryParam("revision") String revision, JsonNode request) {
        return Response.ok(service.acceptPredictions(region(headers), domainId, identifier, revision)).build();
    }

    @PUT
    @Path("/{domainIdentifier}/assets/{identifier}/reject-predictions")
    public Response rejectPredictions(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("identifier") String identifier,
                                      @QueryParam("revision") String revision, JsonNode request) {
        return Response.ok(service.rejectPredictions(region(headers), domainId, identifier, revision)).build();
    }

    // Asset filters

    @POST
    @Path("/{domainIdentifier}/assets/{assetIdentifier}/filters")
    public Response createAssetFilter(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("assetIdentifier") String assetId, JsonNode request) {
        return Response.status(201).entity(service.createAssetFilter(region(headers), domainId, assetId,
                body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/assets/{assetIdentifier}/filters/{identifier}")
    public Response getAssetFilter(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("assetIdentifier") String assetId,
                                   @PathParam("identifier") String identifier) {
        return Response.ok(service.getAssetFilter(region(headers), domainId, assetId, identifier)).build();
    }

    @PATCH
    @Path("/{domainIdentifier}/assets/{assetIdentifier}/filters/{identifier}")
    public Response updateAssetFilter(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("assetIdentifier") String assetId,
                                      @PathParam("identifier") String identifier, JsonNode request) {
        return Response.ok(service.updateAssetFilter(region(headers), domainId, assetId, identifier,
                body(request))).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/assets/{assetIdentifier}/filters/{identifier}")
    public Response deleteAssetFilter(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("assetIdentifier") String assetId,
                                      @PathParam("identifier") String identifier) {
        service.deleteAssetFilter(region(headers), domainId, assetId, identifier);
        return Response.noContent().build();
    }

    @GET
    @Path("/{domainIdentifier}/assets/{assetIdentifier}/filters")
    public Response listAssetFilters(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                     @PathParam("assetIdentifier") String assetId,
                                     @QueryParam("status") String status,
                                     @QueryParam("maxResults") Integer maxResults,
                                     @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listAssetFilters(region(headers), domainId, assetId, status),
                maxResults, 50, nextToken, "items")).build();
    }

    // Data products

    @POST
    @Path("/{domainIdentifier}/data-products")
    public Response createDataProduct(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      JsonNode request) {
        return Response.status(201).entity(service.createDataProduct(region(headers), domainId, body(request)))
                .build();
    }

    @GET
    @Path("/{domainIdentifier}/data-products/{identifier}")
    public Response getDataProduct(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   @PathParam("identifier") String identifier,
                                   @QueryParam("revision") String revision) {
        return Response.ok(service.getDataProduct(region(headers), domainId, identifier, revision)).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/data-products/{identifier}")
    public Response deleteDataProduct(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                      @PathParam("identifier") String identifier) {
        service.deleteDataProduct(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @POST
    @Path("/{domainIdentifier}/data-products/{identifier}/revisions")
    public Response createDataProductRevision(@Context HttpHeaders headers,
                                              @PathParam("domainIdentifier") String domainId,
                                              @PathParam("identifier") String identifier, JsonNode request) {
        return Response.status(201).entity(service.createDataProductRevision(region(headers), domainId, identifier,
                body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/data-products/{identifier}/revisions")
    public Response listDataProductRevisions(@Context HttpHeaders headers,
                                             @PathParam("domainIdentifier") String domainId,
                                             @PathParam("identifier") String identifier,
                                             @QueryParam("maxResults") Integer maxResults,
                                             @QueryParam("nextToken") String nextToken) {
        return Response.ok(page(service.listDataProductRevisions(region(headers), domainId, identifier),
                maxResults, 50, nextToken, "items")).build();
    }

    // Listings

    @POST
    @Path("/{domainIdentifier}/listings/change-set")
    public Response createListingChangeSet(@Context HttpHeaders headers,
                                           @PathParam("domainIdentifier") String domainId, JsonNode request) {
        return Response.ok(service.createListingChangeSet(region(headers), domainId, body(request))).build();
    }

    @GET
    @Path("/{domainIdentifier}/listings/{identifier}")
    public Response getListing(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                               @PathParam("identifier") String identifier,
                               @QueryParam("listingRevision") String listingRevision) {
        return Response.ok(service.getListing(region(headers), domainId, identifier, listingRevision)).build();
    }

    @DELETE
    @Path("/{domainIdentifier}/listings/{identifier}")
    public Response deleteListing(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                  @PathParam("identifier") String identifier) {
        service.deleteListing(region(headers), domainId, identifier);
        return Response.noContent().build();
    }

    @POST
    @Path("/{domainIdentifier}/listings/search")
    public Response searchListings(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                   JsonNode request) {
        return Response.ok(service.searchListings(region(headers), domainId, body(request))).build();
    }

    // Search

    @POST
    @Path("/{domainIdentifier}/search")
    public Response search(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                           JsonNode request) {
        return Response.ok(service.search(region(headers), domainId, body(request))).build();
    }

    @POST
    @Path("/{domainIdentifier}/types-search")
    public Response searchTypes(@Context HttpHeaders headers, @PathParam("domainIdentifier") String domainId,
                                JsonNode request) {
        return Response.ok(service.searchTypes(region(headers), domainId, body(request))).build();
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }
}
