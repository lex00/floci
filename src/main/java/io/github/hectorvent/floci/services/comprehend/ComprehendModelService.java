package io.github.hectorvent.floci.services.comprehend;

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
import java.util.regex.Pattern;

/**
 * Custom Comprehend models: document classifiers and entity recognizers. Training needs real
 * data, so a model reports {@code TRAINED} with fixed metadata from its first read, which is
 * what {@code aws_comprehend_document_classifier}'s create waiter looks for.
 *
 * <p>Each stored record holds a {@code Kind}, the model's {@code Properties} in the response
 * shape, and its {@code Tags} as a key to value object.
 */
@ApplicationScoped
public class ComprehendModelService {

    static final String CLASSIFIER = "document-classifier";
    static final String RECOGNIZER = "entity-recognizer";

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9](-*[a-zA-Z0-9])*");
    private static final Pattern ROLE_ARN_PATTERN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::[0-9]{12}:role/.+");
    private static final Set<String> LANGUAGE_CODES =
            Set.of("en", "es", "fr", "de", "it", "pt", "ar", "hi", "ja", "ko", "zh", "zh-TW");
    private static final Set<String> MODEL_STATUSES = Set.of("SUBMITTED", "TRAINING", "DELETING",
            "STOP_REQUESTED", "STOPPED", "IN_ERROR", "TRAINED", "TRAINED_WITH_WARNING");
    private static final int NAME_MAX = 63;
    private static final int MAX_PAGE = 500;
    private static final int TAGS_PER_RESOURCE_MAX = 200;

    private static final List<String> CLASSIFIER_FIELDS = List.of("LanguageCode", "InputDataConfig",
            "OutputDataConfig", "DataAccessRoleArn", "VolumeKmsKeyId", "VpcConfig", "Mode", "ModelKmsKeyId",
            "VersionName");
    private static final List<String> RECOGNIZER_FIELDS = List.of("LanguageCode", "InputDataConfig",
            "DataAccessRoleArn", "VolumeKmsKeyId", "VpcConfig", "ModelKmsKeyId", "VersionName");

    private final StorageBackend<String, ObjectNode> models;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public ComprehendModelService(StorageFactory storageFactory, RegionResolver regionResolver,
                                  ObjectMapper objectMapper) {
        this(storageFactory.create("comprehend", "comprehend-models.json",
                new TypeReference<Map<String, ObjectNode>>() {}), regionResolver, objectMapper);
    }

    ComprehendModelService(StorageBackend<String, ObjectNode> models, RegionResolver regionResolver,
                           ObjectMapper objectMapper) {
        this.models = models;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public synchronized ObjectNode create(String kind, JsonNode request, String region) {
        String nameField = CLASSIFIER.equals(kind) ? "DocumentClassifierName" : "RecognizerName";
        String name = text(request, nameField);
        requireName(nameField, name);
        String version = text(request, "VersionName");
        if (version != null) {
            requireName("VersionName", version);
        }
        String role = text(request, "DataAccessRoleArn");
        if (role == null || !ROLE_ARN_PATTERN.matcher(role).matches()) {
            throw invalid("DataAccessRoleArn must be an IAM role ARN.");
        }
        if (!request.path("InputDataConfig").isObject()) {
            throw invalid("InputDataConfig is required.");
        }
        if (RECOGNIZER.equals(kind) && !request.path("InputDataConfig").path("EntityTypes").isArray()) {
            throw invalid("InputDataConfig.EntityTypes is required.");
        }
        String language = text(request, "LanguageCode");
        if (language == null || !LANGUAGE_CODES.contains(language)) {
            throw new AwsException("UnsupportedLanguageException",
                    "The language code " + language + " is not supported.", 400);
        }
        String resource = kind + "/" + name + (version != null ? "/version/" + version : "");
        String arn = regionResolver.buildArn("comprehend", region, resource);
        if (models.get(arn).isPresent()) {
            throw new AwsException("ResourceInUseException", "The resource " + arn + " already exists.", 400);
        }
        ObjectNode tags = tagObject(request.get("Tags"));
        Instant now = Instant.now();

        ObjectNode properties = objectMapper.createObjectNode();
        properties.put(CLASSIFIER.equals(kind) ? "DocumentClassifierArn" : "EntityRecognizerArn", arn);
        for (String field : CLASSIFIER.equals(kind) ? CLASSIFIER_FIELDS : RECOGNIZER_FIELDS) {
            if (request.hasNonNull(field)) {
                properties.set(field, request.get(field));
            }
        }
        if (CLASSIFIER.equals(kind) && !properties.has("Mode")) {
            properties.put("Mode", "MULTI_CLASS");
        }
        properties.put("Status", "TRAINED");
        properties.put("SubmitTime", epochSeconds(now));
        properties.put("TrainingStartTime", epochSeconds(now));
        properties.put("TrainingEndTime", epochSeconds(now));
        properties.put("EndTime", epochSeconds(now));
        if (CLASSIFIER.equals(kind)) {
            ObjectNode metadata = properties.putObject("ClassifierMetadata");
            metadata.put("NumberOfLabels", 2);
            metadata.put("NumberOfTrainedDocuments", 100);
            metadata.put("NumberOfTestDocuments", 10);
            ObjectNode metrics = metadata.putObject("EvaluationMetrics");
            metrics.put("Accuracy", 0.9);
            metrics.put("Precision", 0.9);
            metrics.put("Recall", 0.9);
            metrics.put("F1Score", 0.9);
        } else {
            ObjectNode metadata = properties.putObject("RecognizerMetadata");
            metadata.put("NumberOfTrainedDocuments", 100);
            metadata.put("NumberOfTestDocuments", 10);
            ObjectNode metrics = metadata.putObject("EvaluationMetrics");
            metrics.put("Precision", 0.9);
            metrics.put("Recall", 0.9);
            metrics.put("F1Score", 0.9);
            ArrayNode types = metadata.putArray("EntityTypes");
            for (JsonNode type : request.path("InputDataConfig").path("EntityTypes")) {
                types.addObject().put("Type", type.path("Type").asText());
            }
        }

        ObjectNode record = objectMapper.createObjectNode();
        record.put("Kind", kind);
        record.put("Name", name);
        record.put("Region", region);
        record.set("Properties", properties);
        record.set("Tags", tags);
        models.put(arn, record);

        ObjectNode response = objectMapper.createObjectNode();
        response.put(CLASSIFIER.equals(kind) ? "DocumentClassifierArn" : "EntityRecognizerArn", arn);
        return response;
    }

    public ObjectNode describe(String kind, String arn) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(CLASSIFIER.equals(kind) ? "DocumentClassifierProperties" : "EntityRecognizerProperties",
                find(kind, arn).get("Properties"));
        return response;
    }

    public ObjectNode list(String kind, JsonNode request, String region) {
        JsonNode filter = request.path("Filter");
        String status = filter.hasNonNull("Status") ? filter.get("Status").asText() : null;
        if (status != null && !MODEL_STATUSES.contains(status)) {
            throw invalid("Filter.Status is not a model status.");
        }
        String nameField = CLASSIFIER.equals(kind) ? "DocumentClassifierName" : "RecognizerName";
        String name = filter.hasNonNull(nameField) ? filter.get(nameField).asText() : null;
        List<ObjectNode> matches = models.scan(key -> true).stream()
                .filter(record -> kind.equals(record.path("Kind").asText()))
                .filter(record -> region.equals(record.path("Region").asText()))
                .filter(record -> name == null || name.equals(record.path("Name").asText()))
                .filter(record -> status == null || status.equals(record.path("Properties").path("Status").asText()))
                .toList();
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(matches,
                record -> record.path("Properties").path(arnField(kind)).asText(), maxResults,
                text(request, "NextToken"), MAX_PAGE, "InvalidRequestException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode list = response.putArray(CLASSIFIER.equals(kind)
                ? "DocumentClassifierPropertiesList" : "EntityRecognizerPropertiesList");
        page.items().forEach(record -> list.add(record.get("Properties")));
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode delete(String kind, String arn) {
        find(kind, arn);
        models.delete(arn);
        return objectMapper.createObjectNode();
    }

    /** A model that has already finished training keeps its status, as in AWS. */
    public ObjectNode stopTraining(String kind, String arn) {
        find(kind, arn);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listTags(String arn) {
        ObjectNode record = findAny(arn);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("ResourceArn", arn);
        ArrayNode tags = response.putArray("Tags");
        record.path("Tags").properties().forEach(entry ->
                tags.addObject().put("Key", entry.getKey()).put("Value", entry.getValue().asText()));
        return response;
    }

    public synchronized ObjectNode tag(String arn, JsonNode tagList) {
        ObjectNode record = findAny(arn);
        ObjectNode tags = ((ObjectNode) record.get("Tags")).deepCopy();
        tags.setAll(tagObject(tagList));
        if (tags.size() > TAGS_PER_RESOURCE_MAX) {
            throw new AwsException("TooManyTagsException",
                    "The resource " + arn + " would have more than " + TAGS_PER_RESOURCE_MAX + " tags.", 400);
        }
        record.set("Tags", tags);
        models.put(arn, record);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untag(String arn, JsonNode tagKeys) {
        ObjectNode record = findAny(arn);
        ObjectNode tags = (ObjectNode) record.get("Tags");
        if (tagKeys != null && tagKeys.isArray()) {
            tagKeys.forEach(key -> tags.remove(key.asText()));
        }
        models.put(arn, record);
        return objectMapper.createObjectNode();
    }

    private ObjectNode find(String kind, String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalid(arnField(kind) + " is required.");
        }
        ObjectNode record = models.get(arn).orElse(null);
        if (record == null || !kind.equals(record.path("Kind").asText())) {
            throw notFound(arn);
        }
        return record;
    }

    private ObjectNode findAny(String arn) {
        if (arn == null || arn.isBlank()) {
            throw invalid("ResourceArn is required.");
        }
        return models.get(arn).orElseThrow(() -> notFound(arn));
    }

    private ObjectNode tagObject(JsonNode tagList) {
        ObjectNode tags = objectMapper.createObjectNode();
        if (tagList != null && tagList.isArray()) {
            for (JsonNode tag : tagList) {
                String key = text(tag, "Key");
                if (key == null || key.isEmpty() || key.length() > 128) {
                    throw invalid("Tag keys must be 1 to 128 characters.");
                }
                tags.put(key, tag.path("Value").asText(""));
            }
        }
        if (tags.size() > TAGS_PER_RESOURCE_MAX) {
            throw new AwsException("TooManyTagsException",
                    "A resource can have at most " + TAGS_PER_RESOURCE_MAX + " tags.", 400);
        }
        return tags;
    }

    private static void requireName(String field, String value) {
        if (value == null || value.length() > NAME_MAX || !NAME_PATTERN.matcher(value).matches()) {
            throw invalid(field + " must be 1 to 63 letters, digits or hyphens.");
        }
    }

    private static String arnField(String kind) {
        return CLASSIFIER.equals(kind) ? "DocumentClassifierArn" : "EntityRecognizerArn";
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String arn) {
        return new AwsException("ResourceNotFoundException", "The resource " + arn + " was not found.", 400);
    }
}
