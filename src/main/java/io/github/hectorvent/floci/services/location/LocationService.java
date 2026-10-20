package io.github.hectorvent.floci.services.location;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Amazon Location Service resources: geofence collections, trackers, maps, place indexes and
 * route calculators. They share one lifecycle (create, describe, update, delete, list and tags),
 * so one table of {@link ResourceType}s drives them. Each stored record is the resource's
 * {@code Describe*} response, keyed by region, type and name.
 */
@ApplicationScoped
public class LocationService {

    public static final ResourceType GEOFENCE_COLLECTION = new ResourceType("geofence-collection",
            "CollectionName", "CollectionArn",
            List.of("PricingPlan", "PricingPlanDataSource", "KmsKeyId"),
            List.of("PricingPlan", "PricingPlanDataSource"), Set.of());
    public static final ResourceType TRACKER = new ResourceType("tracker", "TrackerName", "TrackerArn",
            List.of("PricingPlan", "PricingPlanDataSource", "KmsKeyId", "PositionFiltering", "EventBridgeEnabled",
                    "KmsKeyEnableGeospatialQueries"),
            List.of("PricingPlan", "PricingPlanDataSource", "PositionFiltering", "EventBridgeEnabled",
                    "KmsKeyEnableGeospatialQueries"),
            Set.of());
    public static final ResourceType MAP = new ResourceType("map", "MapName", "MapArn",
            List.of("PricingPlan", "Configuration"), List.of("PricingPlan"), Set.of("Configuration"));
    public static final ResourceType PLACE_INDEX = new ResourceType("place-index", "IndexName", "IndexArn",
            List.of("PricingPlan", "DataSource", "DataSourceConfiguration"),
            List.of("PricingPlan", "DataSourceConfiguration"), Set.of("DataSource"));
    public static final ResourceType ROUTE_CALCULATOR = new ResourceType("route-calculator", "CalculatorName",
            "CalculatorArn", List.of("PricingPlan", "DataSource"), List.of("PricingPlan"), Set.of("DataSource"));

    private static final List<ResourceType> TYPES =
            List.of(GEOFENCE_COLLECTION, TRACKER, MAP, PLACE_INDEX, ROUTE_CALCULATOR);
    private static final Pattern RESOURCE_NAME = Pattern.compile("[-._\\w]{1,100}");
    private static final Set<String> PRICING_PLANS =
            Set.of("RequestBasedUsage", "MobileAssetTracking", "MobileAssetManagement");
    private static final int DESCRIPTION_MAX = 1000;
    private static final int MAX_PAGE = 100;
    private static final int TAGS_MAX = 50;

    private final StorageBackend<String, ObjectNode> resources;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LocationService(StorageFactory storageFactory, RegionResolver regionResolver,
                           ObjectMapper objectMapper) {
        this(storageFactory.create("location", "location-resources.json",
                new TypeReference<Map<String, ObjectNode>>() {}), regionResolver, objectMapper);
    }

    LocationService(StorageBackend<String, ObjectNode> resources, RegionResolver regionResolver,
                    ObjectMapper objectMapper) {
        this.resources = resources;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode create(ResourceType type, JsonNode request, String region) {
        String name = text(request, type.nameField());
        if (name == null || !RESOURCE_NAME.matcher(name).matches()) {
            throw validation(type.nameField() + " must be 1 to 100 letters, digits, hyphens, periods "
                    + "or underscores.");
        }
        for (String required : type.requiredFields()) {
            if (!request.hasNonNull(required)) {
                throw validation(required + " is required.");
            }
        }
        validateCommon(request);
        String key = key(region, type, name);
        if (resources.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "A " + type.resource() + " named " + name + " already exists.", 409);
        }
        String now = timestamp();
        ObjectNode resource = objectMapper.createObjectNode();
        resource.put(type.nameField(), name);
        resource.put(type.arnField(), regionResolver.buildArn("geo", region, type.resource() + "/" + name));
        resource.put("Description", request.path("Description").asText(""));
        for (String field : type.createFields()) {
            if (request.hasNonNull(field)) {
                resource.set(field, request.get(field));
            }
        }
        if (type == MAP) {
            resource.put("DataSource", mapDataSource(request.path("Configuration").path("Style").asText("")));
        }
        if (type == PLACE_INDEX && !resource.has("DataSourceConfiguration")) {
            resource.putObject("DataSourceConfiguration").put("IntendedUse", "SingleUse");
        }
        resource.set("Tags", tagMap(request.get("Tags")));
        resource.put("CreateTime", now);
        resource.put("UpdateTime", now);
        resources.put(key, resource);

        ObjectNode response = objectMapper.createObjectNode();
        response.put(type.nameField(), name);
        response.put(type.arnField(), resource.path(type.arnField()).asText());
        response.put("CreateTime", now);
        return response;
    }

    public ObjectNode describe(ResourceType type, String name, String region) {
        ObjectNode resource = find(type, name, region).deepCopy();
        if (type == GEOFENCE_COLLECTION) {
            resource.put("GeofenceCount", 0);
        }
        return resource;
    }

    public synchronized ObjectNode update(ResourceType type, String name, JsonNode request, String region) {
        ObjectNode resource = find(type, name, region);
        validateCommon(request);
        if (request.hasNonNull("Description")) {
            resource.put("Description", request.get("Description").asText());
        }
        for (String field : type.updateFields()) {
            if (request.hasNonNull(field)) {
                resource.set(field, request.get(field));
            }
        }
        if (type == MAP && request.path("ConfigurationUpdate").isObject()) {
            ObjectNode configuration = (ObjectNode) resource.get("Configuration");
            request.get("ConfigurationUpdate").properties()
                    .forEach(entry -> configuration.set(entry.getKey(), entry.getValue()));
        }
        String now = timestamp();
        resource.put("UpdateTime", now);
        resources.put(key(region, type, name), resource);

        ObjectNode response = objectMapper.createObjectNode();
        response.put(type.nameField(), name);
        response.put(type.arnField(), resource.path(type.arnField()).asText());
        response.put("UpdateTime", now);
        return response;
    }

    public synchronized ObjectNode delete(ResourceType type, String name, String region) {
        find(type, name, region);
        resources.delete(key(region, type, name));
        return objectMapper.createObjectNode();
    }

    public ObjectNode list(ResourceType type, JsonNode request, String region) {
        String prefix = region + "::" + type.resource() + "::";
        List<ObjectNode> all = resources.scan(key -> key.startsWith(prefix));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(all,
                resource -> resource.path(type.nameField()).asText(), maxResults, text(request, "NextToken"),
                MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode entries = response.putArray("Entries");
        for (ObjectNode resource : page.items()) {
            ObjectNode entry = entries.addObject();
            for (String field : List.of(type.nameField(), "Description", "DataSource", "PricingPlan",
                    "PricingPlanDataSource", "CreateTime", "UpdateTime")) {
                if (resource.has(field)) {
                    entry.set(field, resource.get(field));
                }
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public Map<String, String> listTags(String arn) {
        Map<String, String> tags = new HashMap<>();
        findByArn(arn).path("Tags").properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        return tags;
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        ObjectNode resource = findByArn(arn);
        ObjectNode current = (ObjectNode) resource.get("Tags");
        tags.forEach(current::put);
        if (current.size() > TAGS_MAX) {
            throw validation("A resource can have at most " + TAGS_MAX + " tags.");
        }
        resources.put(keyOfArn(arn), resource);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        ObjectNode resource = findByArn(arn);
        ObjectNode current = (ObjectNode) resource.get("Tags");
        tagKeys.forEach(current::remove);
        resources.put(keyOfArn(arn), resource);
    }

    private ObjectNode findByArn(String arn) {
        return resources.get(keyOfArn(arn)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Resource " + arn + " not found.", 404));
    }

    /** The storage key an ARN names, {@code arn:<partition>:geo:<region>:<account>:<type>/<name>}. */
    private static String keyOfArn(String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":", 6);
        if (parts.length < 6 || parts[5].indexOf('/') < 0) {
            throw validation("ResourceArn is not a Location resource ARN.");
        }
        String resourceType = parts[5].substring(0, parts[5].indexOf('/'));
        String name = parts[5].substring(parts[5].indexOf('/') + 1);
        for (ResourceType type : TYPES) {
            if (type.resource().equals(resourceType)) {
                return key(parts[3], type, name);
            }
        }
        throw validation("ResourceArn is not a Location resource ARN.");
    }

    private ObjectNode find(ResourceType type, String name, String region) {
        return resources.get(key(region, type, name)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", type.resource() + " " + name + " not found.", 404));
    }

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = objectMapper.createObjectNode();
        if (tags != null && tags.isObject()) {
            if (tags.size() > TAGS_MAX) {
                throw validation("A resource can have at most " + TAGS_MAX + " tags.");
            }
            tags.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }

    private static void validateCommon(JsonNode request) {
        String plan = text(request, "PricingPlan");
        if (plan != null && !PRICING_PLANS.contains(plan)) {
            throw validation("PricingPlan must be one of " + PRICING_PLANS + ".");
        }
        String description = text(request, "Description");
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw validation("Description must be at most " + DESCRIPTION_MAX + " characters.");
        }
    }

    /** The data provider a map style name carries, such as {@code Esri} for {@code VectorEsriStreets}. */
    static String mapDataSource(String style) {
        for (String provider : List.of("Esri", "Here", "OpenData", "Grab")) {
            if (style.contains(provider)) {
                return provider;
            }
        }
        return "Esri";
    }

    private static String timestamp() {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now());
    }

    private static String key(String region, ResourceType type, String name) {
        return region + "::" + type.resource() + "::" + name;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    /**
     * One Location resource type: its ARN resource segment, its name and ARN members, the members
     * a create copies, the members an update may change, and the members a create requires.
     */
    public record ResourceType(String resource, String nameField, String arnField, List<String> createFields,
                               List<String> updateFields, Set<String> requiredFields) {
    }
}
