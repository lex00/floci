package io.github.hectorvent.floci.services.kendra;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
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
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Kendra indexes. An index is {@code ACTIVE} as soon as it is created and holds no
 * documents, so its statistics stay at zero. Each record keeps the {@code DescribeIndex}
 * members, the region, and the index's tags as a key to value object.
 */
@ApplicationScoped
public class KendraService {

    private static final Pattern INDEX_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]*");
    private static final Pattern ROLE_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::[0-9]{12}:role/.+");
    private static final Set<String> EDITIONS =
            Set.of("DEVELOPER_EDITION", "ENTERPRISE_EDITION", "GEN_AI_ENTERPRISE_EDITION");
    private static final List<String> COPIED_FIELDS = List.of("RoleArn", "Description",
            "ServerSideEncryptionConfiguration", "UserTokenConfigurations", "UserContextPolicy",
            "UserGroupResolutionConfiguration");
    private static final List<String> UPDATABLE_FIELDS = List.of("Name", "RoleArn", "Description",
            "CapacityUnits", "UserTokenConfigurations", "UserContextPolicy", "UserGroupResolutionConfiguration");
    private static final int MAX_PAGE = 100;
    private static final int TAGS_MAX = 200;

    private final StorageBackend<String, ObjectNode> indexes;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public KendraService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("kendra", "kendra-indexes.json",
                new TypeReference<Map<String, ObjectNode>>() {}), regionResolver, objectMapper);
    }

    KendraService(StorageBackend<String, ObjectNode> indexes, RegionResolver regionResolver,
                  ObjectMapper objectMapper) {
        this.indexes = indexes;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode createIndex(JsonNode request, String region) {
        String name = text(request, "Name");
        requireName(name);
        String role = text(request, "RoleArn");
        if (role == null || !ROLE_ARN.matcher(role).matches()) {
            throw validation("RoleArn must be an IAM role ARN.");
        }
        String edition = text(request, "Edition");
        if (edition == null) {
            edition = "ENTERPRISE_EDITION";
        } else if (!EDITIONS.contains(edition)) {
            throw validation("Edition must be one of " + EDITIONS + ".");
        }
        requireDescription(request);
        ObjectNode tags = tagObject(request.get("Tags"));

        String id = UUID.randomUUID().toString();
        double now = epochSeconds(Instant.now());
        ObjectNode index = objectMapper.createObjectNode();
        index.put("Name", name);
        index.put("Id", id);
        index.put("Edition", edition);
        for (String field : COPIED_FIELDS) {
            if (request.hasNonNull(field)) {
                index.set(field, request.get(field));
            }
        }
        index.put("Status", "ACTIVE");
        index.put("CreatedAt", now);
        index.put("UpdatedAt", now);
        index.putArray("DocumentMetadataConfigurations");
        ObjectNode statistics = index.putObject("IndexStatistics");
        statistics.putObject("FaqStatistics").put("IndexedQuestionAnswersCount", 0);
        statistics.putObject("TextDocumentStatistics")
                .put("IndexedTextDocumentsCount", 0)
                .put("IndexedTextBytes", 0);
        index.putObject("CapacityUnits").put("StorageCapacityUnits", 0).put("QueryCapacityUnits", 0);

        ObjectNode record = objectMapper.createObjectNode();
        record.put("Region", region);
        record.put("Arn", regionResolver.buildArn("kendra", region, "index/" + id));
        record.set("Index", index);
        record.set("Tags", tags);
        indexes.put(key(region, id), record);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("Id", id);
        return response;
    }

    public ObjectNode describeIndex(JsonNode request, String region) {
        return find(region, text(request, "Id")).get("Index").deepCopy();
    }

    public synchronized ObjectNode updateIndex(JsonNode request, String region) {
        ObjectNode record = find(region, text(request, "Id"));
        if (request.hasNonNull("Name")) {
            requireName(text(request, "Name"));
        }
        requireDescription(request);
        ObjectNode index = (ObjectNode) record.get("Index");
        for (String field : UPDATABLE_FIELDS) {
            if (request.hasNonNull(field)) {
                index.set(field, request.get(field));
            }
        }
        if (request.path("DocumentMetadataConfigurationUpdates").isArray()) {
            index.set("DocumentMetadataConfigurations", request.get("DocumentMetadataConfigurationUpdates"));
        }
        index.put("UpdatedAt", epochSeconds(Instant.now()));
        indexes.put(key(region, index.path("Id").asText()), record);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteIndex(JsonNode request, String region) {
        String id = text(request, "Id");
        find(region, id);
        indexes.delete(key(region, id));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listIndices(JsonNode request, String region) {
        String prefix = region + "::";
        List<ObjectNode> all = indexes.scan(key -> key.startsWith(prefix));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(all,
                record -> record.path("Index").path("Id").asText(), maxResults, text(request, "NextToken"),
                MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("IndexConfigurationSummaryItems");
        for (ObjectNode record : page.items()) {
            JsonNode index = record.get("Index");
            ObjectNode summary = items.addObject();
            for (String field : List.of("Name", "Id", "Edition", "CreatedAt", "UpdatedAt", "Status")) {
                summary.set(field, index.get(field));
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public ObjectNode listTags(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tags = response.putArray("Tags");
        findByArn(request, region).path("Tags").properties().forEach(entry ->
                tags.addObject().put("Key", entry.getKey()).put("Value", entry.getValue().asText()));
        return response;
    }

    public synchronized ObjectNode tagResource(JsonNode request, String region) {
        ObjectNode record = findByArn(request, region);
        ObjectNode tags = ((ObjectNode) record.get("Tags")).deepCopy();
        tags.setAll(tagObject(request.get("Tags")));
        if (tags.size() > TAGS_MAX) {
            throw validation("A resource can have at most " + TAGS_MAX + " tags.");
        }
        record.set("Tags", tags);
        indexes.put(key(region, record.path("Index").path("Id").asText()), record);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        ObjectNode record = findByArn(request, region);
        ObjectNode tags = (ObjectNode) record.get("Tags");
        request.path("TagKeys").forEach(tagKey -> tags.remove(tagKey.asText()));
        indexes.put(key(region, record.path("Index").path("Id").asText()), record);
        return objectMapper.createObjectNode();
    }

    private ObjectNode findByArn(JsonNode request, String region) {
        String arn = text(request, "ResourceARN");
        int marker = arn == null ? -1 : arn.indexOf(":index/");
        ObjectNode record = marker < 0 ? null
                : indexes.get(key(region, arn.substring(marker + ":index/".length()))).orElse(null);
        if (record == null) {
            throw new AwsException("ResourceUnavailableException",
                    "The resource " + arn + " is unavailable.", 400);
        }
        return record;
    }

    private ObjectNode find(String region, String id) {
        if (id == null || id.isBlank()) {
            throw validation("Id is required.");
        }
        return indexes.get(key(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Index " + id + " doesn't exist.", 400));
    }

    private ObjectNode tagObject(JsonNode tagList) {
        ObjectNode tags = objectMapper.createObjectNode();
        if (tagList != null && tagList.isArray()) {
            if (tagList.size() > TAGS_MAX) {
                throw validation("Tags must have at most " + TAGS_MAX + " items.");
            }
            for (JsonNode tag : tagList) {
                String tagKey = text(tag, "Key");
                if (tagKey == null || tagKey.isEmpty() || tagKey.length() > 128) {
                    throw validation("Tag keys must be 1 to 128 characters.");
                }
                tags.put(tagKey, tag.path("Value").asText(""));
            }
        }
        return tags;
    }

    private static void requireName(String name) {
        if (name == null || name.length() > 1000 || !INDEX_NAME.matcher(name).matches()) {
            throw validation("Name must start with a letter or digit and hold letters, digits, "
                    + "hyphens or underscores.");
        }
    }

    private static void requireDescription(JsonNode request) {
        String description = text(request, "Description");
        if (description != null && description.length() > 1000) {
            throw validation("Description must be at most 1000 characters.");
        }
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
