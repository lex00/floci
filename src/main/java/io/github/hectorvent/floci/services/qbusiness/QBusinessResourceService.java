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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The resources inside a Q Business application: indices and their data sources, retrievers,
 * plugins, web experiences and data accessors, plus each application's chat controls and
 * resource policy. Every resource is {@code ACTIVE} (a plugin {@code READY}) once created, and a
 * data source sync job succeeds at once. Records are keyed by the resource ARN.
 */
@ApplicationScoped
public class QBusinessResourceService {

    /**
     * One resource type. {@code collection} is the URL segment, {@code idMember} and
     * {@code arnMember} the response members, {@code arnSegment} the ARN path segment, and
     * {@code summary} the members a list returns.
     */
    public record Kind(String collection, String noun, String idMember, String arnMember, String arnSegment,
                       String listMember, List<String> required, List<String> summary) {
        public String listAction() {
            return "List" + Character.toUpperCase(listMember.charAt(0)) + listMember.substring(1);
        }
    }

    public static final Kind INDEX = new Kind("indices", "Index", "indexId", "indexArn", "index", "indices",
            List.of("displayName"), List.of("displayName", "indexId", "createdAt", "updatedAt", "status"));
    public static final Kind DATA_SOURCE = new Kind("datasources", "DataSource", "dataSourceId", "dataSourceArn", "data-source",
            "dataSources", List.of("displayName", "configuration"),
            List.of("displayName", "dataSourceId", "type", "createdAt", "updatedAt", "status"));
    public static final Kind RETRIEVER = new Kind("retrievers", "Retriever", "retrieverId", "retrieverArn", "retriever",
            "retrievers", List.of("type", "displayName", "configuration"),
            List.of("applicationId", "retrieverId", "type", "status", "displayName"));
    public static final Kind PLUGIN = new Kind("plugins", "Plugin", "pluginId", "pluginArn", "plugin", "plugins",
            List.of("displayName", "type", "authConfiguration"),
            List.of("pluginId", "displayName", "type", "serverUrl", "state", "buildStatus", "createdAt", "updatedAt"));
    public static final Kind WEB_EXPERIENCE = new Kind("experiences", "WebExperience", "webExperienceId", "webExperienceArn",
            "web-experience", "webExperiences", List.of(),
            List.of("webExperienceId", "createdAt", "updatedAt", "defaultEndpoint", "status"));
    public static final Kind DATA_ACCESSOR = new Kind("dataaccessors", "DataAccessor", "dataAccessorId", "dataAccessorArn",
            "data-accessor", "dataAccessors", List.of("principal", "actionConfigurations", "displayName"),
            List.of("displayName", "dataAccessorId", "dataAccessorArn", "idcApplicationArn", "principal",
                    "authenticationDetail", "createdAt", "updatedAt"));

    public static final Map<String, Kind> APPLICATION_KINDS = Map.of(
            INDEX.collection(), INDEX,
            RETRIEVER.collection(), RETRIEVER,
            PLUGIN.collection(), PLUGIN,
            WEB_EXPERIENCE.collection(), WEB_EXPERIENCE,
            DATA_ACCESSOR.collection(), DATA_ACCESSOR);

    private static final Set<String> NEVER_STORED = Set.of("tags", "clientToken", "applicationId", "indexId");
    private static final int MAX_PAGE = 100;
    private static final int TAGS_MAX = 200;

    private final StorageBackend<String, ObjectNode> resources;
    private final StorageBackend<String, ObjectNode> settings;
    private final QBusinessService applications;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public QBusinessResourceService(StorageFactory storageFactory, QBusinessService applications,
                                    RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("qbusiness", "qbusiness-resources.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create("qbusiness", "qbusiness-application-settings.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                applications, regionResolver, objectMapper);
    }

    QBusinessResourceService(StorageBackend<String, ObjectNode> resources, StorageBackend<String, ObjectNode> settings,
                             QBusinessService applications, RegionResolver regionResolver,
                             ObjectMapper objectMapper) {
        this.resources = resources;
        this.settings = settings;
        this.applications = applications;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ──────────────────────────── Generic resources ────────────────────────────

    /** Creates a resource; {@code indexId} is the parent index for a data source, else null. */
    public synchronized ObjectNode create(Kind kind, String applicationId, String indexId, JsonNode request,
                                          String region) {
        String applicationArn = applicationArn(applicationId, region);
        if (indexId != null) {
            require(INDEX, applicationId, null, indexId, region);
        }
        for (String member : kind.required()) {
            if (!request.hasNonNull(member)) {
                throw validation(member + " is required.");
            }
        }
        String id = UUID.randomUUID().toString();
        String arn = applicationArn + (indexId == null ? "" : "/index/" + indexId) + "/" + kind.arnSegment() + "/" + id;
        double now = epochSeconds(Instant.now());
        ObjectNode resource = objectMapper.createObjectNode();
        resource.put("applicationId", applicationId);
        if (indexId != null) {
            resource.put("indexId", indexId);
        }
        resource.put(kind.idMember(), id);
        resource.put(kind.arnMember(), arn);
        request.properties().forEach(entry -> {
            if (!NEVER_STORED.contains(entry.getKey()) && !entry.getValue().isNull()) {
                resource.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        resource.put("createdAt", now);
        resource.put("updatedAt", now);
        applyDefaults(kind, resource, id, region);

        ObjectNode record = objectMapper.createObjectNode();
        record.put("kind", kind.collection());
        record.put("parent", indexId == null ? applicationArn : applicationArn + "/index/" + indexId);
        record.set("resource", resource);
        record.set("tags", tagMap(request.get("tags")));
        record.putArray("syncJobs");
        resources.put(arn, record);

        ObjectNode response = objectMapper.createObjectNode();
        response.put(kind.idMember(), id);
        response.put(kind.arnMember(), arn);
        if (kind == PLUGIN) {
            response.put("buildStatus", "READY");
        }
        if (kind == DATA_ACCESSOR) {
            response.put("idcApplicationArn", resource.path("idcApplicationArn").asText());
        }
        return response;
    }

    public ObjectNode get(Kind kind, String applicationId, String indexId, String id, String region) {
        return require(kind, applicationId, indexId, id, region).get("resource").deepCopy();
    }

    public synchronized ObjectNode update(Kind kind, String applicationId, String indexId, String id,
                                          JsonNode request, String region) {
        ObjectNode record = require(kind, applicationId, indexId, id, region);
        ObjectNode resource = (ObjectNode) record.get("resource");
        request.properties().forEach(entry -> {
            String member = entry.getKey();
            if (!NEVER_STORED.contains(member) && !member.equals(kind.idMember()) && !entry.getValue().isNull()) {
                resource.set(member, entry.getValue().deepCopy());
            }
        });
        if (kind == DATA_SOURCE && resource.path("configuration").hasNonNull("type")) {
            resource.put("type", resource.path("configuration").path("type").asText());
        }
        resource.put("updatedAt", epochSeconds(Instant.now()));
        resources.put(resource.path(kind.arnMember()).asText(), record);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode delete(Kind kind, String applicationId, String indexId, String id, String region) {
        ObjectNode record = require(kind, applicationId, indexId, id, region);
        String arn = record.path("resource").path(kind.arnMember()).asText();
        resources.delete(arn);
        if (kind == INDEX) {
            resources.scan(k -> k.startsWith(arn + "/")).forEach(child ->
                    resources.delete(child.path("resource").path(DATA_SOURCE.arnMember()).asText()));
        }
        return objectMapper.createObjectNode();
    }

    public ObjectNode list(Kind kind, String applicationId, String indexId, Integer maxResults, String nextToken,
                           String region) {
        String parent = applicationArn(applicationId, region);
        if (indexId != null) {
            require(INDEX, applicationId, null, indexId, region);
            parent = parent + "/index/" + indexId;
        }
        String wanted = parent;
        List<ObjectNode> all = resources.scan(k -> true).stream()
                .filter(r -> kind.collection().equals(r.path("kind").asText()) && wanted.equals(r.path("parent").asText()))
                .toList();
        PaginatedResult<ObjectNode> page = Pagination.paginate(all,
                r -> r.path("resource").path(kind.idMember()).asText(), maxResults, nextToken, MAX_PAGE,
                "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray(kind.listMember());
        for (ObjectNode record : page.items()) {
            JsonNode resource = record.get("resource");
            ObjectNode summary = items.addObject();
            for (String member : kind.summary()) {
                if (resource.has(member)) {
                    summary.set(member, resource.get(member).deepCopy());
                }
            }
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    /** Removes everything that belongs to a deleted application. */
    public synchronized void deleteApplicationResources(String applicationArn) {
        for (String key : List.copyOf(resources.keys())) {
            if (key.startsWith(applicationArn + "/")) {
                resources.delete(key);
            }
        }
        settings.delete(applicationArn);
    }

    private void applyDefaults(Kind kind, ObjectNode resource, String id, String region) {
        if (kind == INDEX) {
            putIfAbsent(resource, "type", "ENTERPRISE");
            if (!resource.has("capacityConfiguration")) {
                resource.putObject("capacityConfiguration").put("units", 1);
            }
            resource.putArray("documentAttributeConfigurations");
            resource.putObject("indexStatistics").putObject("textDocumentStatistics")
                    .put("indexedTextBytes", 0).put("indexedTextDocumentCount", 0);
        } else if (kind == DATA_SOURCE) {
            resource.put("type", resource.path("configuration").path("type").asText("CUSTOM"));
        } else if (kind == PLUGIN) {
            resource.put("state", "ENABLED");
            resource.put("buildStatus", "READY");
            return;
        } else if (kind == WEB_EXPERIENCE) {
            resource.put("defaultEndpoint", "https://" + id.substring(0, 8) + ".chat.qbusiness." + region + ".on.aws/");
            putIfAbsent(resource, "samplePromptsControlMode", "DISABLED");
        } else if (kind == DATA_ACCESSOR) {
            resource.put("idcApplicationArn", regionResolver.buildGlobalArn("sso",
                    "application/ssoins-" + id.substring(0, 16).replace("-", "") + "/apl-"
                            + id.substring(19).replace("-", "")));
            return;
        }
        resource.put("status", "ACTIVE");
    }

    private ObjectNode require(Kind kind, String applicationId, String indexId, String id, String region) {
        String applicationArn = applicationArn(applicationId, region);
        String parent = indexId == null ? applicationArn : applicationArn + "/index/" + indexId;
        return resources.get(parent + "/" + kind.arnSegment() + "/" + id)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + id + " not found.", 404));
    }

    private String applicationArn(String applicationId, String region) {
        return applications.getApplication(applicationId, region).path("applicationArn").asText();
    }

    // ──────────────────────────── Data source sync jobs ────────────────────────────

    public synchronized ObjectNode startSync(String applicationId, String indexId, String dataSourceId, String region) {
        ObjectNode record = require(DATA_SOURCE, applicationId, indexId, dataSourceId, region);
        String executionId = UUID.randomUUID().toString();
        double now = epochSeconds(Instant.now());
        ObjectNode job = ((ArrayNode) record.get("syncJobs")).addObject();
        job.put("executionId", executionId);
        job.put("startTime", now);
        job.put("endTime", now);
        job.put("status", "SUCCEEDED");
        job.putObject("metrics")
                .put("documentsAdded", "0")
                .put("documentsModified", "0")
                .put("documentsDeleted", "0")
                .put("documentsFailed", "0")
                .put("documentsScanned", "0");
        resources.put(record.path("resource").path(DATA_SOURCE.arnMember()).asText(), record);
        return objectMapper.createObjectNode().put("executionId", executionId);
    }

    public ObjectNode stopSync(String applicationId, String indexId, String dataSourceId, String region) {
        require(DATA_SOURCE, applicationId, indexId, dataSourceId, region);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listSyncJobs(String applicationId, String indexId, String dataSourceId, String status,
                                   Integer maxResults, String nextToken, String region) {
        ObjectNode record = require(DATA_SOURCE, applicationId, indexId, dataSourceId, region);
        List<ObjectNode> jobs = new ArrayList<>();
        for (JsonNode job : record.path("syncJobs")) {
            if (status == null || status.equals(job.path("status").asText())) {
                jobs.add(((ObjectNode) job).deepCopy());
            }
        }
        PaginatedResult<ObjectNode> page = Pagination.paginate(jobs, j -> j.path("executionId").asText(),
                maxResults, nextToken, MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("history").addAll(page.items());
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return response;
    }

    // ──────────────────────────── Chat controls ────────────────────────────

    public ObjectNode getChatControls(String applicationId, String region) {
        return chatControls(applicationArn(applicationId, region)).deepCopy();
    }

    public synchronized ObjectNode updateChatControls(String applicationId, JsonNode request, String region) {
        String applicationArn = applicationArn(applicationId, region);
        ObjectNode applicationSettings = settings.get(applicationArn).orElse(objectMapper.createObjectNode());
        ObjectNode controls = chatControls(applicationArn);
        if (request.hasNonNull("responseScope")) {
            controls.put("responseScope", request.get("responseScope").asText());
        }
        if (request.get("orchestrationConfiguration") instanceof ObjectNode orchestration) {
            controls.putObject("orchestrationConfiguration")
                    .put("control", orchestration.path("control").asText("DISABLED"));
        }
        if (request.get("creatorModeConfiguration") instanceof ObjectNode creatorMode) {
            controls.putObject("creatorModeConfiguration")
                    .put("creatorModeControl", creatorMode.path("creatorModeControl").asText("DISABLED"));
        }
        if (request.get("hallucinationReductionConfiguration") instanceof ObjectNode reduction) {
            controls.set("hallucinationReductionConfiguration", reduction.deepCopy());
        }
        if (request.get("blockedPhrasesConfigurationUpdate") instanceof ObjectNode update) {
            ObjectNode blocked = (ObjectNode) controls.get("blockedPhrases");
            Set<String> phrases = new LinkedHashSet<>();
            blocked.path("blockedPhrases").forEach(p -> phrases.add(p.asText()));
            update.path("blockedPhrasesToCreateOrUpdate").forEach(p -> phrases.add(p.asText()));
            update.path("blockedPhrasesToDelete").forEach(p -> phrases.remove(p.asText()));
            ArrayNode list = blocked.putArray("blockedPhrases");
            phrases.forEach(list::add);
            if (update.hasNonNull("systemMessageOverride")) {
                blocked.put("systemMessageOverride", update.get("systemMessageOverride").asText());
            }
        }
        Map<String, JsonNode> topics = new LinkedHashMap<>();
        controls.path("topicConfigurations").forEach(t -> topics.put(t.path("name").asText(), t));
        request.path("topicConfigurationsToCreateOrUpdate").forEach(t -> topics.put(t.path("name").asText(), t));
        request.path("topicConfigurationsToDelete").forEach(t -> topics.remove(t.path("name").asText()));
        ArrayNode topicList = objectMapper.createArrayNode();
        topics.values().forEach(t -> topicList.add(t.deepCopy()));
        controls.set("topicConfigurations", topicList);
        applicationSettings.set("chatControls", controls);
        settings.put(applicationArn, applicationSettings);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteChatControls(String applicationId, String region) {
        String applicationArn = applicationArn(applicationId, region);
        settings.get(applicationArn).ifPresent(applicationSettings -> {
            applicationSettings.remove("chatControls");
            settings.put(applicationArn, applicationSettings);
        });
        return objectMapper.createObjectNode();
    }

    private ObjectNode chatControls(String applicationArn) {
        JsonNode stored = settings.get(applicationArn).map(s -> s.get("chatControls")).orElse(null);
        if (stored instanceof ObjectNode controls) {
            return controls;
        }
        ObjectNode controls = objectMapper.createObjectNode();
        controls.put("responseScope", "ENTERPRISE_CONTENT_ONLY");
        controls.putObject("orchestrationConfiguration").put("control", "DISABLED");
        controls.putObject("blockedPhrases").putArray("blockedPhrases");
        controls.putArray("topicConfigurations");
        controls.putObject("creatorModeConfiguration").put("creatorModeControl", "DISABLED");
        return controls;
    }

    // ──────────────────────────── Resource policy ────────────────────────────

    public synchronized ObjectNode associatePermission(String applicationId, JsonNode request, String region) {
        String applicationArn = applicationArn(applicationId, region);
        for (String member : List.of("statementId", "actions", "principal")) {
            if (!request.hasNonNull(member)) {
                throw validation(member + " is required.");
            }
        }
        String statementId = request.get("statementId").asText();
        ObjectNode applicationSettings = settings.get(applicationArn).orElse(objectMapper.createObjectNode());
        ObjectNode statements = applicationSettings.get("statements") instanceof ObjectNode existing
                ? existing : applicationSettings.putObject("statements");
        if (statements.has(statementId)) {
            throw new AwsException("ConflictException", "Statement " + statementId + " already exists.", 409);
        }
        ObjectNode statement = objectMapper.createObjectNode();
        statement.put("Sid", statementId);
        statement.put("Effect", "Allow");
        statement.putObject("Principal").put("AWS", request.get("principal").asText());
        statement.set("Action", request.get("actions").deepCopy());
        statement.put("Resource", applicationArn);
        if (request.get("conditions") instanceof ArrayNode conditions && !conditions.isEmpty()) {
            ObjectNode condition = statement.putObject("Condition");
            for (JsonNode entry : conditions) {
                ObjectNode operator = condition.get(entry.path("conditionOperator").asText()) instanceof ObjectNode o
                        ? o : condition.putObject(entry.path("conditionOperator").asText());
                operator.set(entry.path("conditionKey").asText(), entry.path("conditionValues").deepCopy());
            }
        }
        statements.set(statementId, statement);
        settings.put(applicationArn, applicationSettings);
        return objectMapper.createObjectNode().put("statement", statement.toString());
    }

    public synchronized ObjectNode disassociatePermission(String applicationId, String statementId, String region) {
        String applicationArn = applicationArn(applicationId, region);
        ObjectNode applicationSettings = settings.get(applicationArn).orElse(objectMapper.createObjectNode());
        if (!(applicationSettings.get("statements") instanceof ObjectNode statements) || !statements.has(statementId)) {
            throw new AwsException("ResourceNotFoundException", "Statement " + statementId + " not found.", 404);
        }
        statements.remove(statementId);
        settings.put(applicationArn, applicationSettings);
        return objectMapper.createObjectNode();
    }

    public ObjectNode getPolicy(String applicationId, String region) {
        String applicationArn = applicationArn(applicationId, region);
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("Version", "2012-10-17");
        ArrayNode statements = policy.putArray("Statement");
        settings.get(applicationArn).map(s -> s.path("statements")).ifPresent(stored ->
                stored.forEach(statements::add));
        return objectMapper.createObjectNode().put("policy", policy.toString());
    }

    // ──────────────────────────── Tags ────────────────────────────

    public boolean owns(String arn) {
        return arn != null && resources.get(arn).isPresent();
    }

    public Map<String, String> listTags(String arn) {
        Map<String, String> tags = new HashMap<>();
        requireArn(arn).path("tags").properties().forEach(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        return tags;
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        ObjectNode record = requireArn(arn);
        ObjectNode current = (ObjectNode) record.get("tags");
        tags.forEach(current::put);
        if (current.size() > TAGS_MAX) {
            throw validation("A resource can have at most " + TAGS_MAX + " tags.");
        }
        resources.put(arn, record);
    }

    public synchronized void untagResource(String arn, List<String> tagKeys) {
        ObjectNode record = requireArn(arn);
        tagKeys.forEach(((ObjectNode) record.get("tags"))::remove);
        resources.put(arn, record);
    }

    private ObjectNode requireArn(String arn) {
        return resources.get(arn == null ? "" : arn).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Resource " + arn + " not found.", 404));
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode tagMap(JsonNode tagList) {
        ObjectNode tags = objectMapper.createObjectNode();
        if (tagList != null && tagList.isArray()) {
            if (tagList.size() > TAGS_MAX) {
                throw validation("tags must have at most " + TAGS_MAX + " items.");
            }
            for (JsonNode tag : tagList) {
                tags.put(tag.path("key").asText(), tag.path("value").asText(""));
            }
        }
        return tags;
    }

    private static void putIfAbsent(ObjectNode node, String member, String value) {
        if (!node.hasNonNull(member)) {
            node.put(member, value);
        }
    }

    private static double epochSeconds(Instant instant) {
        return instant.toEpochMilli() / 1000.0;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }
}
