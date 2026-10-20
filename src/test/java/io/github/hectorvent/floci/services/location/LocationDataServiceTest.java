package io.github.hectorvent.floci.services.location;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocationDataServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final LocationService resources = new LocationService(new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"), mapper);
    private final LocationDataService service = new LocationDataService(new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), resources, mapper);

    @Test
    void geofenceNeedsExactlyOneShapeAndACollection() throws Exception {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, () -> service.putGeofence(
                "missing", "g", mapper.readTree("{\"Geometry\": {\"Circle\": {\"Center\": [0, 0], \"Radius\": 1}}}"),
                REGION)).getErrorCode());
        resources.create(LocationService.GEOFENCE_COLLECTION, mapper.readTree("{\"CollectionName\": \"c\"}"), REGION);
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.putGeofence("c", "g",
                mapper.readTree("{\"Geometry\": {}}"), REGION)).getErrorCode());
        service.putGeofence("c", "g", mapper.readTree("{\"Geometry\": {\"Circle\": {\"Center\": [0, 0], \"Radius\": 1}}}"),
                REGION);
        assertEquals(1, service.geofenceCount("c", REGION));
        service.batchDeleteGeofence("c", mapper.readTree("{\"GeofenceIds\": [\"g\"]}"), REGION);
        assertEquals(0, service.geofenceCount("c", REGION));
    }

    @Test
    void listDevicePositionsFiltersByPolygon() throws Exception {
        resources.create(LocationService.TRACKER, mapper.readTree("{\"TrackerName\": \"t\"}"), REGION);
        service.batchUpdateDevicePosition("t", mapper.readTree("""
                {"Updates": [{"DeviceId": "in", "SampleTime": "2026-01-01T00:00:00Z", "Position": [0.5, 0.5]},
                             {"DeviceId": "out", "SampleTime": "2026-01-01T00:00:00Z", "Position": [5, 5]}]}"""), REGION);

        JsonNode entries = service.listDevicePositions("t", mapper.readTree("""
                {"FilterGeometry": {"Polygon": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}}"""), REGION).get("Entries");
        assertEquals(1, entries.size());
        assertEquals("in", entries.get(0).get("DeviceId").asText());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getDevicePosition("t", "nobody", REGION)).getErrorCode());
    }

    @Test
    void routeDistanceFollowsTheGreatCircle() throws Exception {
        resources.create(LocationService.ROUTE_CALCULATOR,
                mapper.readTree("{\"CalculatorName\": \"r\", \"DataSource\": \"Here\"}"), REGION);
        JsonNode route = service.calculateRoute("r", mapper.readTree("""
                {"DeparturePosition": [0, 0], "DestinationPosition": [1, 0], "DistanceUnit": "Kilometers"}"""), REGION);

        assertEquals(111.2, route.get("Summary").get("Distance").asDouble(), 0.1);
        assertEquals(route.get("Summary").get("Distance").asDouble() / 50 * 3600,
                route.get("Summary").get("DurationSeconds").asDouble(), 0.001);
        JsonNode matrix = service.calculateRouteMatrix("r", mapper.readTree("""
                {"DeparturePositions": [[0, 0], [0, 1]], "DestinationPositions": [[1, 0]]}"""), REGION);
        assertEquals(2, matrix.get("Summary").get("RouteCount").asInt());
    }
}
