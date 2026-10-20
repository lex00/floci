package io.github.hectorvent.floci.services.rekognition;

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
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Face collections. A collection holds no faces, since Floci indexes none, so its face and user
 * counts are zero. Each record keeps the {@code DescribeCollection} members, the region, and the
 * collection's tags.
 */
@ApplicationScoped
public class RekognitionCollectionService {

    private static final Pattern COLLECTION_ID = Pattern.compile("[a-zA-Z0-9_.\\-]{1,255}");
    private static final String FACE_MODEL_VERSION = "7.0";
    private static final int MAX_PAGE = 4096;

    private final StorageBackend<String, ObjectNode> collections;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public RekognitionCollectionService(StorageFactory storageFactory, RegionResolver regionResolver,
                                        ObjectMapper objectMapper) {
        this(storageFactory.create("rekognition", "rekognition-collections.json",
                new TypeReference<Map<String, ObjectNode>>() {}), regionResolver, objectMapper);
    }

    RekognitionCollectionService(StorageBackend<String, ObjectNode> collections, RegionResolver regionResolver,
                                 ObjectMapper objectMapper) {
        this.collections = collections;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode createCollection(JsonNode request, String region) {
        String collectionId = collectionId(request);
        if (collections.get(key(region, collectionId)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The collection id: " + collectionId + " already exists", 400);
        }
        String arn = regionResolver.buildArn("rekognition", region, "collection/" + collectionId);
        ObjectNode record = objectMapper.createObjectNode();
        record.put("CollectionId", collectionId);
        record.put("CollectionARN", arn);
        record.put("FaceModelVersion", FACE_MODEL_VERSION);
        record.put("CreationTimestamp", Instant.now().toEpochMilli() / 1000.0);
        record.set("Tags", tagMap(request.get("Tags")));
        collections.put(key(region, collectionId), record);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("StatusCode", 200);
        response.put("CollectionArn", arn);
        response.put("FaceModelVersion", FACE_MODEL_VERSION);
        return response;
    }

    public ObjectNode describeCollection(JsonNode request, String region) {
        ObjectNode record = find(region, collectionId(request));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("FaceCount", 0);
        response.put("FaceModelVersion", record.path("FaceModelVersion").asText());
        response.put("CollectionARN", record.path("CollectionARN").asText());
        response.set("CreationTimestamp", record.get("CreationTimestamp"));
        response.put("UserCount", 0);
        return response;
    }

    public ObjectNode listCollections(JsonNode request, String region) {
        String prefix = region + "::";
        List<ObjectNode> all = collections.scan(key -> key.startsWith(prefix));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        String nextToken = request.hasNonNull("NextToken") ? request.get("NextToken").asText() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(all, record -> record.path("CollectionId").asText(),
                maxResults, nextToken, MAX_PAGE, "InvalidPaginationTokenException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode ids = response.putArray("CollectionIds");
        ArrayNode versions = response.putArray("FaceModelVersions");
        for (ObjectNode record : page.items()) {
            ids.add(record.path("CollectionId").asText());
            versions.add(record.path("FaceModelVersion").asText());
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode deleteCollection(JsonNode request, String region) {
        String collectionId = collectionId(request);
        find(region, collectionId);
        collections.delete(key(region, collectionId));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("StatusCode", 200);
        return response;
    }

    public ObjectNode listTags(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Tags", findByArn(request, region).get("Tags"));
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode request, String region) {
        ObjectNode record = findByArn(request, region);
        ((ObjectNode) record.get("Tags")).setAll(tagMap(request.get("Tags")));
        collections.put(key(region, record.path("CollectionId").asText()), record);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        ObjectNode record = findByArn(request, region);
        ObjectNode tags = (ObjectNode) record.get("Tags");
        request.path("TagKeys").forEach(tagKey -> tags.remove(tagKey.asText()));
        collections.put(key(region, record.path("CollectionId").asText()), record);
        return objectMapper.createObjectNode();
    }

    private ObjectNode findByArn(JsonNode request, String region) {
        String arn = request.path("ResourceArn").asText("");
        int slash = arn.indexOf(":collection/");
        if (slash < 0) {
            throw new AwsException("ResourceNotFoundException", "The resource " + arn + " does not exist.", 400);
        }
        return find(region, arn.substring(slash + ":collection/".length()));
    }

    private ObjectNode find(String region, String collectionId) {
        return collections.get(key(region, collectionId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "The collection id: " + collectionId + " does not exist", 400));
    }

    private ObjectNode tagMap(JsonNode tags) {
        ObjectNode map = objectMapper.createObjectNode();
        if (tags != null && tags.isObject()) {
            tags.properties().forEach(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }

    private static String collectionId(JsonNode request) {
        JsonNode value = request.get("CollectionId");
        String collectionId = value == null || value.isNull() ? null : value.asText();
        if (collectionId == null || !COLLECTION_ID.matcher(collectionId).matches()) {
            throw new AwsException("InvalidParameterException",
                    "CollectionId must be 1 to 255 letters, digits, underscores, periods or hyphens.", 400);
        }
        return collectionId;
    }

    private static String key(String region, String collectionId) {
        return region + "::" + collectionId;
    }
}
