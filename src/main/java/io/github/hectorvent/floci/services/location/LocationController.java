package io.github.hectorvent.floci.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Amazon Location Service control plane (REST JSON, signing name {@code geo}). The SDKs prefix
 * each operation's host ({@code cp.geofencing.}, {@code cp.maps.} and so on), and the paths are
 * unique, so any host that reaches Floci routes here.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LocationController {

    private static final String COLLECTIONS = "/geofencing/v0/collections";
    private static final String TRACKERS = "/tracking/v0/trackers";
    private static final String MAPS = "/maps/v0/maps";
    private static final String INDEXES = "/places/v0/indexes";
    private static final String CALCULATORS = "/routes/v0/calculators";

    private final LocationService service;
    private final LocationDataService data;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LocationController(LocationService service, LocationDataService data, RegionResolver regionResolver,
                              ObjectMapper objectMapper) {
        this.service = service;
        this.data = data;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // Geofence collections

    @POST
    @Path(COLLECTIONS)
    public Response createGeofenceCollection(@Context HttpHeaders headers, String body) {
        return ok(service.create(LocationService.GEOFENCE_COLLECTION, readTree(body), region(headers)));
    }

    @GET
    @Path(COLLECTIONS + "/{name}")
    public Response describeGeofenceCollection(@PathParam("name") String name, @Context HttpHeaders headers) {
        String region = region(headers);
        ObjectNode collection = service.describe(LocationService.GEOFENCE_COLLECTION, name, region);
        collection.put("GeofenceCount", data.geofenceCount(name, region));
        return ok(collection);
    }

    @PATCH
    @Path(COLLECTIONS + "/{name}")
    public Response updateGeofenceCollection(@PathParam("name") String name, @Context HttpHeaders headers,
                                             String body) {
        return ok(service.update(LocationService.GEOFENCE_COLLECTION, name, readTree(body), region(headers)));
    }

    @DELETE
    @Path(COLLECTIONS + "/{name}")
    public Response deleteGeofenceCollection(@PathParam("name") String name, @Context HttpHeaders headers) {
        String region = region(headers);
        ObjectNode response = service.delete(LocationService.GEOFENCE_COLLECTION, name, region);
        data.deleteCollectionData(name, region);
        return ok(response);
    }

    @POST
    @Path("/geofencing/v0/list-collections")
    public Response listGeofenceCollections(@Context HttpHeaders headers, String body) {
        return ok(service.list(LocationService.GEOFENCE_COLLECTION, readTree(body), region(headers)));
    }

    // Geofences

    @PUT
    @Path(COLLECTIONS + "/{name}/geofences/{geofenceId}")
    public Response putGeofence(@PathParam("name") String name, @PathParam("geofenceId") String geofenceId,
                                @Context HttpHeaders headers, String body) {
        return ok(data.putGeofence(name, geofenceId, readTree(body), region(headers)));
    }

    @GET
    @Path(COLLECTIONS + "/{name}/geofences/{geofenceId}")
    public Response getGeofence(@PathParam("name") String name, @PathParam("geofenceId") String geofenceId,
                                @Context HttpHeaders headers) {
        return ok(data.getGeofence(name, geofenceId, region(headers)));
    }

    @POST
    @Path(COLLECTIONS + "/{name}/put-geofences")
    public Response batchPutGeofence(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(data.batchPutGeofence(name, readTree(body), region(headers)));
    }

    @POST
    @Path(COLLECTIONS + "/{name}/list-geofences")
    public Response listGeofences(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(data.listGeofences(name, readTree(body), region(headers)));
    }

    @POST
    @Path(COLLECTIONS + "/{name}/delete-geofences")
    public Response batchDeleteGeofence(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(data.batchDeleteGeofence(name, readTree(body), region(headers)));
    }

    @POST
    @Path(COLLECTIONS + "/{name}/positions")
    public Response batchEvaluateGeofences(@PathParam("name") String name, @Context HttpHeaders headers,
                                           String body) {
        return ok(data.batchEvaluateGeofences(name, readTree(body), region(headers)));
    }

    // Trackers

    @POST
    @Path(TRACKERS)
    public Response createTracker(@Context HttpHeaders headers, String body) {
        return ok(service.create(LocationService.TRACKER, readTree(body), region(headers)));
    }

    @GET
    @Path(TRACKERS + "/{name}")
    public Response describeTracker(@PathParam("name") String name, @Context HttpHeaders headers) {
        return ok(service.describe(LocationService.TRACKER, name, region(headers)));
    }

    @PATCH
    @Path(TRACKERS + "/{name}")
    public Response updateTracker(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(service.update(LocationService.TRACKER, name, readTree(body), region(headers)));
    }

    @DELETE
    @Path(TRACKERS + "/{name}")
    public Response deleteTracker(@PathParam("name") String name, @Context HttpHeaders headers) {
        String region = region(headers);
        ObjectNode response = service.delete(LocationService.TRACKER, name, region);
        data.deleteTrackerData(name, region);
        return ok(response);
    }

    @POST
    @Path("/tracking/v0/list-trackers")
    public Response listTrackers(@Context HttpHeaders headers, String body) {
        return ok(service.list(LocationService.TRACKER, readTree(body), region(headers)));
    }

    // Device positions and tracker consumers

    @POST
    @Path(TRACKERS + "/{name}/positions")
    public Response batchUpdateDevicePosition(@PathParam("name") String name, @Context HttpHeaders headers,
                                              String body) {
        return ok(data.batchUpdateDevicePosition(name, readTree(body), region(headers)));
    }

    @GET
    @Path(TRACKERS + "/{name}/devices/{deviceId}/positions/latest")
    public Response getDevicePosition(@PathParam("name") String name, @PathParam("deviceId") String deviceId,
                                      @Context HttpHeaders headers) {
        return ok(data.getDevicePosition(name, deviceId, region(headers)));
    }

    @POST
    @Path(TRACKERS + "/{name}/devices/{deviceId}/list-positions")
    public Response getDevicePositionHistory(@PathParam("name") String name, @PathParam("deviceId") String deviceId,
                                             @Context HttpHeaders headers, String body) {
        return ok(data.getDevicePositionHistory(name, deviceId, readTree(body), region(headers)));
    }

    @POST
    @Path(TRACKERS + "/{name}/get-positions")
    public Response batchGetDevicePosition(@PathParam("name") String name, @Context HttpHeaders headers,
                                           String body) {
        return ok(data.batchGetDevicePosition(name, readTree(body), region(headers)));
    }

    @POST
    @Path(TRACKERS + "/{name}/list-positions")
    public Response listDevicePositions(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(data.listDevicePositions(name, readTree(body), region(headers)));
    }

    @POST
    @Path(TRACKERS + "/{name}/delete-positions")
    public Response batchDeleteDevicePositionHistory(@PathParam("name") String name, @Context HttpHeaders headers,
                                                     String body) {
        return ok(data.batchDeleteDevicePositionHistory(name, readTree(body), region(headers)));
    }

    @POST
    @Path(TRACKERS + "/{name}/consumers")
    public Response associateTrackerConsumer(@PathParam("name") String name, @Context HttpHeaders headers,
                                             String body) {
        return ok(data.associateTrackerConsumer(name, readTree(body), region(headers)));
    }

    @POST
    @Path(TRACKERS + "/{name}/list-consumers")
    public Response listTrackerConsumers(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(data.listTrackerConsumers(name, readTree(body), region(headers)));
    }

    @DELETE
    @Path(TRACKERS + "/{name}/consumers/{consumerArn: .+}")
    public Response disassociateTrackerConsumer(@PathParam("name") String name,
                                                @PathParam("consumerArn") String consumerArn,
                                                @Context HttpHeaders headers) {
        return ok(data.disassociateTrackerConsumer(name, consumerArn, region(headers)));
    }

    // Maps

    @POST
    @Path(MAPS)
    public Response createMap(@Context HttpHeaders headers, String body) {
        return ok(service.create(LocationService.MAP, readTree(body), region(headers)));
    }

    @GET
    @Path(MAPS + "/{name}")
    public Response describeMap(@PathParam("name") String name, @Context HttpHeaders headers) {
        return ok(service.describe(LocationService.MAP, name, region(headers)));
    }

    @PATCH
    @Path(MAPS + "/{name}")
    public Response updateMap(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(service.update(LocationService.MAP, name, readTree(body), region(headers)));
    }

    @DELETE
    @Path(MAPS + "/{name}")
    public Response deleteMap(@PathParam("name") String name, @Context HttpHeaders headers) {
        return ok(service.delete(LocationService.MAP, name, region(headers)));
    }

    @POST
    @Path("/maps/v0/list-maps")
    public Response listMaps(@Context HttpHeaders headers, String body) {
        return ok(service.list(LocationService.MAP, readTree(body), region(headers)));
    }

    // Place indexes

    @POST
    @Path(INDEXES)
    public Response createPlaceIndex(@Context HttpHeaders headers, String body) {
        return ok(service.create(LocationService.PLACE_INDEX, readTree(body), region(headers)));
    }

    @GET
    @Path(INDEXES + "/{name}")
    public Response describePlaceIndex(@PathParam("name") String name, @Context HttpHeaders headers) {
        return ok(service.describe(LocationService.PLACE_INDEX, name, region(headers)));
    }

    @PATCH
    @Path(INDEXES + "/{name}")
    public Response updatePlaceIndex(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(service.update(LocationService.PLACE_INDEX, name, readTree(body), region(headers)));
    }

    @DELETE
    @Path(INDEXES + "/{name}")
    public Response deletePlaceIndex(@PathParam("name") String name, @Context HttpHeaders headers) {
        return ok(service.delete(LocationService.PLACE_INDEX, name, region(headers)));
    }

    @POST
    @Path("/places/v0/list-indexes")
    public Response listPlaceIndexes(@Context HttpHeaders headers, String body) {
        return ok(service.list(LocationService.PLACE_INDEX, readTree(body), region(headers)));
    }

    // Route calculators

    @POST
    @Path(CALCULATORS)
    public Response createRouteCalculator(@Context HttpHeaders headers, String body) {
        return ok(service.create(LocationService.ROUTE_CALCULATOR, readTree(body), region(headers)));
    }

    @GET
    @Path(CALCULATORS + "/{name}")
    public Response describeRouteCalculator(@PathParam("name") String name, @Context HttpHeaders headers) {
        return ok(service.describe(LocationService.ROUTE_CALCULATOR, name, region(headers)));
    }

    @PATCH
    @Path(CALCULATORS + "/{name}")
    public Response updateRouteCalculator(@PathParam("name") String name, @Context HttpHeaders headers,
                                          String body) {
        return ok(service.update(LocationService.ROUTE_CALCULATOR, name, readTree(body), region(headers)));
    }

    @DELETE
    @Path(CALCULATORS + "/{name}")
    public Response deleteRouteCalculator(@PathParam("name") String name, @Context HttpHeaders headers) {
        return ok(service.delete(LocationService.ROUTE_CALCULATOR, name, region(headers)));
    }

    @POST
    @Path("/routes/v0/list-calculators")
    public Response listRouteCalculators(@Context HttpHeaders headers, String body) {
        return ok(service.list(LocationService.ROUTE_CALCULATOR, readTree(body), region(headers)));
    }

    // Routes

    @POST
    @Path(CALCULATORS + "/{name}/calculate/route")
    public Response calculateRoute(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(data.calculateRoute(name, readTree(body), region(headers)));
    }

    @POST
    @Path(CALCULATORS + "/{name}/calculate/route-matrix")
    public Response calculateRouteMatrix(@PathParam("name") String name, @Context HttpHeaders headers, String body) {
        return ok(data.calculateRouteMatrix(name, readTree(body), region(headers)));
    }

    private String region(HttpHeaders headers) {
        return regionResolver.resolveRegion(headers);
    }

    private static Response ok(Object entity) {
        return Response.ok(entity).build();
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
