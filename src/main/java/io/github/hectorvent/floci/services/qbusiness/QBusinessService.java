package io.github.hectorvent.floci.services.qbusiness;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Q Business applications. An application is {@code ACTIVE} as soon as it is created.
 * Each stored record is the {@code GetApplication} response plus a {@code tags} map and the
 * region it lives in.
 */
@ApplicationScoped
public class QBusinessService {

    private static final Pattern APPLICATION_NAME = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_-]*");
    private static final Set<String> IDENTITY_TYPES = Set.of("AWS_IAM_IDP_SAML", "AWS_IAM_IDP_OIDC",
            "AWS_IAM_IDC", "AWS_QUICKSIGHT_IDP", "ANONYMOUS");
    private static final List<String> CREATE_FIELDS = List.of("roleArn", "identityType", "iamIdentityProviderArn",
            "clientIdsForOIDC", "description", "encryptionConfiguration", "attachmentsConfiguration",
            "qAppsConfiguration", "personalizationConfiguration", "quickSightConfiguration");
    private static final List<String> UPDATE_FIELDS = List.of("displayName", "description", "roleArn",
            "attachmentsConfiguration", "qAppsConfiguration", "personalizationConfiguration",
            "autoSubscriptionConfiguration");
    private static final List<String> SUMMARY_FIELDS = List.of("displayName", "applicationId", "createdAt",
            "updatedAt", "status", "identityType", "quickSightConfiguration");
    private static final int MAX_PAGE = 100;
    private static final int TAGS_MAX = 200;

    private final StorageBackend<String, ObjectNode> applications;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public QBusinessService(StorageFactory storageFactory, RegionResolver regionResolver,
                            ObjectMapper objectMapper) {
        this(storageFactory.create("qbusiness", "qbusiness-applications.json",
                new TypeReference<Map<String, ObjectNode>>() {}), regionResolver, objectMapper);
    }

    QBusinessService(StorageBackend<String, ObjectNode> applications, RegionResolver regionResolver,
                     ObjectMapper objectMapper) {
        this.applications = applications;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode createApplication(JsonNode request, String region) {
        String displayName = text(request, "displayName");
        requireName(displayName);
        String identityType = text(request, "identityType");
        if (identityType != null && !IDENTITY_TYPES.contains(identityType)) {
            throw validation("identityType must be one of " + IDENTITY_TYPES + ".");
        }
        requireDescription(request);
        ObjectNode tags = tagMap(request.get("tags"));

        String applicationId = UUID.randomUUID().toString();
        String arn = regionResolver.buildArn("qbusiness", region, "application/" + applicationId);
        double now = epochSeconds(Instant.now());
        ObjectNode application = objectMapper.createObjectNode();
        application.put("displayName", displayName);
        application.put("applicationId", applicationId);
        application.put("applicationArn", arn);
        for (String field : CREATE_FIELDS) {
            if (request.hasNonNull(field)) {
                application.set(field, request.get(field));
            }
        }
        if (!application.has("identityType")) {
            application.put("identityType", "AWS_IAM_IDC");
        }
        application.put("status", "ACTIVE");
        application.put("createdAt", now);
        application.put("updatedAt", now);
        if (!application.has("attachmentsConfiguration")) {
            application.putObject("attachmentsConfiguration").put("attachmentsControlMode", "DISABLED");
        }

        ObjectNode record = objectMapper.createObjectNode();
        record.put("region", region);
        record.set("application", application);
        record.set("tags", tags);
        applications.put(key(region, applicationId), record);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("applicationId", applicationId);
        response.put("applicationArn", arn);
        return response;
    }

    public ObjectNode getApplication(String applicationId, String region) {
        return find(applicationId, region).get("application").deepCopy();
    }

    public synchronized ObjectNode updateApplication(String applicationId, JsonNode request, String region) {
        ObjectNode record = find(applicationId, region);
        if (request.hasNonNull("displayName")) {
            requireName(text(request, "displayName"));
        }
        requireDescription(request);
        ObjectNode application = (ObjectNode) record.get("application");
        for (String field : UPDATE_FIELDS) {
            if (request.hasNonNull(field)) {
                application.set(field, request.get(field));
            }
        }
        application.put("updatedAt", epochSeconds(Instant.now()));
        applications.put(key(region, applicationId), record);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteApplication(String applicationId, String region) {
        find(applicationId, region);
        applications.delete(key(region, applicationId));
        return objectMapper.createObjectNode();
    }

    public ObjectNode listApplications(Integer maxResults, String nextToken, String region) {
        String prefix = region + "::";
        List<ObjectNode> all = applications.scan(key -> key.startsWith(prefix));
        PaginatedResult<ObjectNode> page = Pagination.paginate(all,
                record -> record.path("application").path("applicationId").asText(), maxResults, nextToken,
                MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("applications");
        for (ObjectNode record : page.items()) {
            JsonNode application = record.get("application");
            ObjectNode summary = summaries.addObject();
            for (String field : SUMMARY_FIELDS) {
                if (application.has(field)) {
                    summary.set(field, application.get(field));
                }
            }
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    public Map<String, String> listTags(String arn) {
        Map<String, String> tags = new HashMap<>();
        findByArn(arn).path("tags").properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        return tags;
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        ObjectNode record = findByArn(arn);
        ObjectNode current = (ObjectNode) record.get("tags");
        tags.forEach(current::put);
        if (current.size() > TAGS_MAX) {
            throw validation("A resource can have at most " + TAGS_MAX + " tags.");
        }
        applications.put(key(record.path("region").asText(), applicationIdOf(arn)), record);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        ObjectNode record = findByArn(arn);
        ObjectNode current = (ObjectNode) record.get("tags");
        tagKeys.forEach(current::remove);
        applications.put(key(record.path("region").asText(), applicationIdOf(arn)), record);
    }

    private ObjectNode findByArn(String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":", 6);
        if (parts.length < 6) {
            throw validation("resourceARN is not an ARN.");
        }
        return find(applicationIdOf(arn), parts[3]);
    }

    private static String applicationIdOf(String arn) {
        int marker = arn.indexOf(":application/");
        if (marker < 0) {
            throw new AwsException("ResourceNotFoundException", "Resource " + arn + " not found.", 404);
        }
        String rest = arn.substring(marker + ":application/".length());
        int slash = rest.indexOf('/');
        return slash < 0 ? rest : rest.substring(0, slash);
    }

    private ObjectNode find(String applicationId, String region) {
        return applications.get(key(region, applicationId)).orElseThrow(() -> new AwsException(
                "ResourceNotFoundException", "Application " + applicationId + " not found.", 404));
    }

    private ObjectNode tagMap(JsonNode tagList) {
        ObjectNode tags = objectMapper.createObjectNode();
        if (tagList != null && tagList.isArray()) {
            if (tagList.size() > TAGS_MAX) {
                throw validation("tags must have at most " + TAGS_MAX + " items.");
            }
            for (JsonNode tag : tagList) {
                String tagKey = text(tag, "key");
                if (tagKey == null || tagKey.isEmpty() || tagKey.length() > 128) {
                    throw validation("Tag keys must be 1 to 128 characters.");
                }
                tags.put(tagKey, tag.path("value").asText(""));
            }
        }
        return tags;
    }

    private static void requireName(String name) {
        if (name == null || name.length() > 1000 || !APPLICATION_NAME.matcher(name).matches()) {
            throw validation("displayName must start with a letter or digit and hold letters, digits, "
                    + "hyphens or underscores.");
        }
    }

    private static void requireDescription(JsonNode request) {
        String description = text(request, "description");
        if (description != null && description.length() > 1000) {
            throw validation("description must be at most 1000 characters.");
        }
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static String key(String region, String applicationId) {
        return region + "::" + applicationId;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
