package io.github.hectorvent.floci.services.location;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Amazon Location Service data inside the control-plane resources: geofences in a collection,
 * device positions in a tracker, a tracker's consumers, and routes from a route calculator.
 * Routes follow the great circle between positions at a fixed speed per travel mode, since no
 * road network is loaded.
 */
@ApplicationScoped
public class LocationDataService {

    private static final double EARTH_RADIUS_KM = 6371.0088;
    private static final double KM_PER_MILE = 1.609344;
    private static final Map<String, Double> SPEED_KMH = Map.of(
            "Car", 50.0, "Truck", 40.0, "Walking", 5.0, "Bicycle", 15.0, "Motorcycle", 50.0);
    private static final int MAX_PAGE = 100;
    private static final int BATCH_MAX = 10;

    private final StorageBackend<String, ObjectNode> geofences;
    private final StorageBackend<String, ObjectNode> devices;
    private final StorageBackend<String, ObjectNode> consumers;
    private final LocationService resources;
    private final ObjectMapper objectMapper;

    @Inject
    public LocationDataService(StorageFactory storageFactory, LocationService resources, ObjectMapper objectMapper) {
        this(storageFactory.create("location", "location-geofences.json", new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("location", "location-devices.json", new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("location", "location-consumers.json", new TypeReference<Map<String, ObjectNode>>() {}),
                resources, objectMapper);
    }

    LocationDataService(StorageBackend<String, ObjectNode> geofences, StorageBackend<String, ObjectNode> devices,
                        StorageBackend<String, ObjectNode> consumers, LocationService resources,
                        ObjectMapper objectMapper) {
        this.geofences = geofences;
        this.devices = devices;
        this.consumers = consumers;
        this.resources = resources;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Geofences ────────────────────────────

    public synchronized ObjectNode putGeofence(String collection, String geofenceId, JsonNode request, String region) {
        resources.describe(LocationService.GEOFENCE_COLLECTION, collection, region);
        ObjectNode stored = storeGeofence(collection, geofenceId, request, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("GeofenceId", geofenceId);
        response.put("CreateTime", stored.path("CreateTime").asText());
        response.put("UpdateTime", stored.path("UpdateTime").asText());
        return response;
    }

    public synchronized ObjectNode batchPutGeofence(String collection, JsonNode request, String region) {
        resources.describe(LocationService.GEOFENCE_COLLECTION, collection, region);
        JsonNode entries = request.path("Entries");
        requireBatch(entries, "Entries");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode successes = response.putArray("Successes");
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode entry : entries) {
            String geofenceId = entry.path("GeofenceId").asText("");
            try {
                ObjectNode stored = storeGeofence(collection, geofenceId, entry, region);
                successes.addObject()
                        .put("GeofenceId", geofenceId)
                        .put("CreateTime", stored.path("CreateTime").asText())
                        .put("UpdateTime", stored.path("UpdateTime").asText());
            } catch (AwsException e) {
                errors.addObject().put("GeofenceId", geofenceId).set("Error", itemError("ValidationError", e));
            }
        }
        return response;
    }

    public ObjectNode getGeofence(String collection, String geofenceId, String region) {
        resources.describe(LocationService.GEOFENCE_COLLECTION, collection, region);
        return geofences.get(geofenceKey(region, collection, geofenceId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Geofence " + geofenceId + " not found.", 404))
                .deepCopy();
    }

    public ObjectNode listGeofences(String collection, JsonNode request, String region) {
        resources.describe(LocationService.GEOFENCE_COLLECTION, collection, region);
        List<ObjectNode> all = geofences.scan(k -> k.startsWith(collectionPrefix(region, collection)));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(all, g -> g.path("GeofenceId").asText(), maxResults,
                text(request, "NextToken"), MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Entries").addAll(page.items());
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode batchDeleteGeofence(String collection, JsonNode request, String region) {
        resources.describe(LocationService.GEOFENCE_COLLECTION, collection, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode id : request.path("GeofenceIds")) {
            String key = geofenceKey(region, collection, id.asText());
            if (geofences.get(key).isEmpty()) {
                errors.addObject().put("GeofenceId", id.asText()).set("Error", objectMapper.createObjectNode()
                        .put("Code", "ResourceNotFoundError").put("Message", "Geofence " + id.asText() + " not found."));
                continue;
            }
            geofences.delete(key);
        }
        return response;
    }

    /** Validates each update; geofence events would go to EventBridge, which Floci does not emit here. */
    public ObjectNode batchEvaluateGeofences(String collection, JsonNode request, String region) {
        resources.describe(LocationService.GEOFENCE_COLLECTION, collection, region);
        JsonNode updates = request.path("DevicePositionUpdates");
        if (!updates.isArray() || updates.isEmpty()) {
            throw validation("DevicePositionUpdates must hold at least one update.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        for (JsonNode update : updates) {
            try {
                validateUpdate(update);
            } catch (AwsException e) {
                errors.addObject().put("DeviceId", update.path("DeviceId").asText(""))
                        .put("SampleTime", update.path("SampleTime").asText(""))
                        .set("Error", itemError("ValidationError", e));
            }
        }
        return response;
    }

    public int geofenceCount(String collection, String region) {
        return geofences.scan(k -> k.startsWith(collectionPrefix(region, collection))).size();
    }

    public synchronized void deleteCollectionData(String collection, String region) {
        String prefix = collectionPrefix(region, collection);
        for (String key : List.copyOf(geofences.keys())) {
            if (key.startsWith(prefix)) {
                geofences.delete(key);
            }
        }
    }

    private ObjectNode storeGeofence(String collection, String geofenceId, JsonNode request, String region) {
        if (geofenceId == null || geofenceId.isBlank() || geofenceId.length() > 100) {
            throw validation("GeofenceId must be 1 to 100 characters.");
        }
        JsonNode geometry = request.path("Geometry");
        int shapes = 0;
        for (String member : List.of("Polygon", "Circle", "MultiPolygon", "Geobuf")) {
            if (geometry.hasNonNull(member)) {
                shapes++;
            }
        }
        if (shapes != 1) {
            throw validation("Geometry must hold exactly one of Polygon, Circle, MultiPolygon or Geobuf.");
        }
        if (geometry.has("Circle")) {
            requirePosition(geometry.path("Circle").path("Center"), "Circle.Center");
            if (geometry.path("Circle").path("Radius").asDouble(0) <= 0) {
                throw validation("Circle.Radius must be positive.");
            }
        }
        if (geometry.has("Polygon")) {
            requirePolygon(geometry.get("Polygon"));
        }
        String key = geofenceKey(region, collection, geofenceId);
        String now = timestamp();
        String created = geofences.get(key).map(g -> g.path("CreateTime").asText()).orElse(now);
        ObjectNode geofence = objectMapper.createObjectNode();
        geofence.put("GeofenceId", geofenceId);
        geofence.set("Geometry", geometry.deepCopy());
        geofence.put("Status", "ACTIVE");
        geofence.put("CreateTime", created);
        geofence.put("UpdateTime", now);
        if (request.get("GeofenceProperties") instanceof ObjectNode properties) {
            geofence.set("GeofenceProperties", properties.deepCopy());
        }
        geofences.put(key, geofence);
        return geofence;
    }

    // ──────────────────────────── Device positions ────────────────────────────

    public synchronized ObjectNode batchUpdateDevicePosition(String tracker, JsonNode request, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        JsonNode updates = request.path("Updates");
        requireBatch(updates, "Updates");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        String received = timestamp();
        for (JsonNode update : updates) {
            String deviceId = update.path("DeviceId").asText("");
            try {
                validateUpdate(update);
            } catch (AwsException e) {
                errors.addObject().put("DeviceId", deviceId).put("SampleTime", update.path("SampleTime").asText(""))
                        .set("Error", itemError("ValidationError", e));
                continue;
            }
            String key = deviceKey(region, tracker, deviceId);
            ObjectNode device = devices.get(key).orElseGet(() -> {
                ObjectNode created = objectMapper.createObjectNode();
                created.put("DeviceId", deviceId);
                created.putArray("History");
                return created;
            });
            ObjectNode position = objectMapper.createObjectNode();
            position.put("DeviceId", deviceId);
            position.put("SampleTime", normalizeTime(update.path("SampleTime").asText()));
            position.put("ReceivedTime", received);
            position.set("Position", update.get("Position").deepCopy());
            if (update.get("Accuracy") instanceof ObjectNode accuracy) {
                position.set("Accuracy", accuracy.deepCopy());
            }
            if (update.get("PositionProperties") instanceof ObjectNode properties) {
                position.set("PositionProperties", properties.deepCopy());
            }
            ((ArrayNode) device.get("History")).add(position);
            devices.put(key, device);
        }
        return response;
    }

    public ObjectNode getDevicePosition(String tracker, String deviceId, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        ObjectNode latest = latest(region, tracker, deviceId);
        if (latest == null) {
            throw new AwsException("ResourceNotFoundException", "Device " + deviceId + " has no position.", 404);
        }
        return latest;
    }

    public ObjectNode getDevicePositionHistory(String tracker, String deviceId, JsonNode request, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        ObjectNode device = devices.get(deviceKey(region, tracker, deviceId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Device " + deviceId + " has no position.", 404));
        Instant start = request.hasNonNull("StartTimeInclusive")
                ? parseTime(request.get("StartTimeInclusive").asText(), "StartTimeInclusive") : Instant.MIN;
        Instant end = request.hasNonNull("EndTimeExclusive")
                ? parseTime(request.get("EndTimeExclusive").asText(), "EndTimeExclusive") : Instant.MAX;
        List<ObjectNode> positions = new ArrayList<>();
        for (JsonNode position : device.path("History")) {
            Instant sample = Instant.parse(position.path("SampleTime").asText());
            if (!sample.isBefore(start) && sample.isBefore(end)) {
                positions.add(((ObjectNode) position).deepCopy());
            }
        }
        positions.sort(Comparator.comparing(p -> p.path("SampleTime").asText()));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(positions,
                p -> p.path("SampleTime").asText() + "|" + p.path("ReceivedTime").asText(), maxResults,
                text(request, "NextToken"), MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("DevicePositions").addAll(page.items());
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public ObjectNode batchGetDevicePosition(String tracker, JsonNode request, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode errors = response.putArray("Errors");
        ArrayNode positions = response.putArray("DevicePositions");
        for (JsonNode id : request.path("DeviceIds")) {
            ObjectNode latest = latest(region, tracker, id.asText());
            if (latest == null) {
                errors.addObject().put("DeviceId", id.asText()).set("Error", objectMapper.createObjectNode()
                        .put("Code", "ResourceNotFoundError").put("Message", "Device " + id.asText() + " not found."));
            } else {
                positions.add(latest);
            }
        }
        return response;
    }

    public ObjectNode listDevicePositions(String tracker, JsonNode request, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        JsonNode polygon = request.path("FilterGeometry").path("Polygon");
        List<ObjectNode> entries = new ArrayList<>();
        for (ObjectNode device : devices.scan(k -> k.startsWith(trackerPrefix(region, tracker)))) {
            ObjectNode latest = latest(region, tracker, device.path("DeviceId").asText());
            if (latest == null || (polygon.isArray() && !polygon.isEmpty()
                    && !insidePolygon(latest.path("Position"), polygon))) {
                continue;
            }
            latest.remove("ReceivedTime");
            entries.add(latest);
        }
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(entries, e -> e.path("DeviceId").asText(), maxResults,
                text(request, "NextToken"), MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Entries").addAll(page.items());
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode batchDeleteDevicePositionHistory(String tracker, JsonNode request, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        for (JsonNode id : request.path("DeviceIds")) {
            devices.delete(deviceKey(region, tracker, id.asText()));
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Errors");
        return response;
    }

    public synchronized void deleteTrackerData(String tracker, String region) {
        String prefix = trackerPrefix(region, tracker);
        for (String key : List.copyOf(devices.keys())) {
            if (key.startsWith(prefix)) {
                devices.delete(key);
            }
        }
        consumers.delete(trackerPrefix(region, tracker));
    }

    private ObjectNode latest(String region, String tracker, String deviceId) {
        ObjectNode device = devices.get(deviceKey(region, tracker, deviceId)).orElse(null);
        if (device == null) {
            return null;
        }
        ObjectNode latest = null;
        for (JsonNode position : device.path("History")) {
            if (latest == null || position.path("SampleTime").asText().compareTo(latest.path("SampleTime").asText()) >= 0) {
                latest = (ObjectNode) position;
            }
        }
        return latest == null ? null : latest.deepCopy();
    }

    private void validateUpdate(JsonNode update) {
        if (update.path("DeviceId").asText("").isBlank()) {
            throw validation("DeviceId is required.");
        }
        parseTime(update.path("SampleTime").asText(""), "SampleTime");
        requirePosition(update.path("Position"), "Position");
    }

    // ──────────────────────────── Tracker consumers ────────────────────────────

    public synchronized ObjectNode associateTrackerConsumer(String tracker, JsonNode request, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        String consumerArn = text(request, "ConsumerArn");
        if (consumerArn == null || !consumerArn.contains(":geofence-collection/")) {
            throw validation("ConsumerArn must be a geofence collection ARN.");
        }
        ObjectNode record = consumers.get(trackerPrefix(region, tracker)).orElse(objectMapper.createObjectNode());
        ArrayNode arns = record.get("ConsumerArns") instanceof ArrayNode existing ? existing : record.putArray("ConsumerArns");
        for (JsonNode arn : arns) {
            if (consumerArn.equals(arn.asText())) {
                throw new AwsException("ConflictException", "The consumer is already associated.", 409);
            }
        }
        arns.add(consumerArn);
        consumers.put(trackerPrefix(region, tracker), record);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode disassociateTrackerConsumer(String tracker, String consumerArn, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        ObjectNode record = consumers.get(trackerPrefix(region, tracker)).orElse(objectMapper.createObjectNode());
        ArrayNode kept = objectMapper.createArrayNode();
        boolean found = false;
        for (JsonNode arn : record.path("ConsumerArns")) {
            if (consumerArn.equals(arn.asText())) {
                found = true;
            } else {
                kept.add(arn);
            }
        }
        if (!found) {
            throw new AwsException("ResourceNotFoundException", "The consumer is not associated.", 404);
        }
        record.set("ConsumerArns", kept);
        consumers.put(trackerPrefix(region, tracker), record);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTrackerConsumers(String tracker, JsonNode request, String region) {
        resources.describe(LocationService.TRACKER, tracker, region);
        List<String> arns = new ArrayList<>();
        consumers.get(trackerPrefix(region, tracker))
                .ifPresent(record -> record.path("ConsumerArns").forEach(arn -> arns.add(arn.asText())));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<String> page = Pagination.paginate(arns, arn -> arn, maxResults, text(request, "NextToken"),
                MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray("ConsumerArns");
        page.items().forEach(list::add);
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    // ──────────────────────────── Routes ────────────────────────────

    public ObjectNode calculateRoute(String calculator, JsonNode request, String region) {
        ObjectNode calculatorResource = resources.describe(LocationService.ROUTE_CALCULATOR, calculator, region);
        List<JsonNode> stops = new ArrayList<>();
        stops.add(requirePosition(request.path("DeparturePosition"), "DeparturePosition"));
        for (JsonNode waypoint : request.path("WaypointPositions")) {
            stops.add(requirePosition(waypoint, "WaypointPositions"));
        }
        stops.add(requirePosition(request.path("DestinationPosition"), "DestinationPosition"));
        String unit = request.path("DistanceUnit").asText("Kilometers");
        double speed = speedKmh(request);
        boolean geometry = request.path("IncludeLegGeometry").asBoolean(false);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode legs = response.putArray("Legs");
        double totalDistance = 0;
        double totalSeconds = 0;
        double minLon = Double.MAX_VALUE;
        double minLat = Double.MAX_VALUE;
        double maxLon = -Double.MAX_VALUE;
        double maxLat = -Double.MAX_VALUE;
        for (JsonNode stop : stops) {
            minLon = Math.min(minLon, stop.get(0).asDouble());
            maxLon = Math.max(maxLon, stop.get(0).asDouble());
            minLat = Math.min(minLat, stop.get(1).asDouble());
            maxLat = Math.max(maxLat, stop.get(1).asDouble());
        }
        for (int i = 0; i + 1 < stops.size(); i++) {
            JsonNode from = stops.get(i);
            JsonNode to = stops.get(i + 1);
            double km = haversineKm(from, to);
            double distance = inUnit(km, unit);
            double seconds = km / speed * 3600;
            totalDistance += distance;
            totalSeconds += seconds;
            ObjectNode leg = legs.addObject();
            leg.set("StartPosition", from.deepCopy());
            leg.set("EndPosition", to.deepCopy());
            leg.put("Distance", distance);
            leg.put("DurationSeconds", seconds);
            if (geometry) {
                leg.putObject("Geometry").putArray("LineString").add(from.deepCopy()).add(to.deepCopy());
            }
            ObjectNode step = leg.putArray("Steps").addObject();
            step.set("StartPosition", from.deepCopy());
            step.set("EndPosition", to.deepCopy());
            step.put("Distance", distance);
            step.put("DurationSeconds", seconds);
            if (geometry) {
                step.put("GeometryOffset", 0);
            }
        }
        ObjectNode summary = response.putObject("Summary");
        summary.putArray("RouteBBox").add(minLon).add(minLat).add(maxLon).add(maxLat);
        summary.put("DataSource", calculatorResource.path("DataSource").asText());
        summary.put("Distance", totalDistance);
        summary.put("DurationSeconds", totalSeconds);
        summary.put("DistanceUnit", unit);
        return response;
    }

    public ObjectNode calculateRouteMatrix(String calculator, JsonNode request, String region) {
        ObjectNode calculatorResource = resources.describe(LocationService.ROUTE_CALCULATOR, calculator, region);
        JsonNode departures = request.path("DeparturePositions");
        JsonNode destinations = request.path("DestinationPositions");
        if (!departures.isArray() || departures.isEmpty() || !destinations.isArray() || destinations.isEmpty()) {
            throw validation("DeparturePositions and DestinationPositions must each hold a position.");
        }
        String unit = request.path("DistanceUnit").asText("Kilometers");
        double speed = speedKmh(request);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode matrix = response.putArray("RouteMatrix");
        for (JsonNode departure : departures) {
            requirePosition(departure, "DeparturePositions");
            ArrayNode row = matrix.addArray();
            for (JsonNode destination : destinations) {
                requirePosition(destination, "DestinationPositions");
                double km = haversineKm(departure, destination);
                row.addObject().put("Distance", inUnit(km, unit)).put("DurationSeconds", km / speed * 3600);
            }
        }
        response.set("SnappedDeparturePositions", departures.deepCopy());
        response.set("SnappedDestinationPositions", destinations.deepCopy());
        ObjectNode summary = response.putObject("Summary");
        summary.put("DataSource", calculatorResource.path("DataSource").asText());
        summary.put("RouteCount", departures.size() * destinations.size());
        summary.put("ErrorCount", 0);
        summary.put("DistanceUnit", unit);
        return response;
    }

    private static double speedKmh(JsonNode request) {
        String mode = request.path("TravelMode").asText("Car");
        Double speed = SPEED_KMH.get(mode);
        if (speed == null) {
            throw validation("TravelMode must be one of " + SPEED_KMH.keySet() + ".");
        }
        return speed;
    }

    static double haversineKm(JsonNode from, JsonNode to) {
        double lat1 = Math.toRadians(from.get(1).asDouble());
        double lat2 = Math.toRadians(to.get(1).asDouble());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(to.get(0).asDouble() - from.get(0).asDouble());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    private static double inUnit(double km, String unit) {
        return "Miles".equals(unit) ? km / KM_PER_MILE : km;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static JsonNode requirePosition(JsonNode position, String member) {
        if (!position.isArray() || position.size() != 2 || !position.get(0).isNumber() || !position.get(1).isNumber()) {
            throw validation(member + " must be a [longitude, latitude] pair.");
        }
        double lon = position.get(0).asDouble();
        double lat = position.get(1).asDouble();
        if (lon < -180 || lon > 180 || lat < -90 || lat > 90) {
            throw validation(member + " is out of range.");
        }
        return position;
    }

    private static void requirePolygon(JsonNode polygon) {
        if (!polygon.isArray() || polygon.isEmpty()) {
            throw validation("Polygon must hold at least one linear ring.");
        }
        for (JsonNode ring : polygon) {
            if (!ring.isArray() || ring.size() < 4) {
                throw validation("Each linear ring must hold at least four positions.");
            }
            for (JsonNode position : ring) {
                requirePosition(position, "Polygon");
            }
            if (!ring.get(0).equals(ring.get(ring.size() - 1))) {
                throw validation("Each linear ring must end where it starts.");
            }
        }
    }

    /** Ray casting against the polygon's outer ring. */
    private static boolean insidePolygon(JsonNode point, JsonNode polygon) {
        double x = point.get(0).asDouble();
        double y = point.get(1).asDouble();
        JsonNode ring = polygon.get(0);
        boolean inside = false;
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            double xi = ring.get(i).get(0).asDouble();
            double yi = ring.get(i).get(1).asDouble();
            double xj = ring.get(j).get(0).asDouble();
            double yj = ring.get(j).get(1).asDouble();
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static void requireBatch(JsonNode entries, String member) {
        if (!entries.isArray() || entries.isEmpty() || entries.size() > BATCH_MAX) {
            throw validation(member + " must hold 1 to " + BATCH_MAX + " entries.");
        }
    }

    private ObjectNode itemError(String code, AwsException e) {
        return objectMapper.createObjectNode().put("Code", code).put("Message", e.getMessage());
    }

    private static Instant parseTime(String value, String member) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw validation(member + " must be an ISO 8601 timestamp.");
        }
    }

    private static String normalizeTime(String value) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.parse(value));
    }

    private static String timestamp() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private static String collectionPrefix(String region, String collection) {
        return region + "::" + collection + "::";
    }

    private static String geofenceKey(String region, String collection, String geofenceId) {
        return collectionPrefix(region, collection) + geofenceId;
    }

    private static String trackerPrefix(String region, String tracker) {
        return region + "::" + tracker + "::";
    }

    private static String deviceKey(String region, String tracker, String deviceId) {
        return trackerPrefix(region, tracker) + deviceId;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
