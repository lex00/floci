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

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Amazon Kendra indexes, data sources and documents. An index and a data source are
 * {@code ACTIVE} as soon as they are created. Documents arrive through {@code BatchPutDocument},
 * and {@code Query} and {@code Retrieve} rank them by how often the query terms occur. Each
 * record keeps the resource's describe members and its tags as a key to value object.
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
    private static final Set<String> DATA_SOURCE_TYPES = Set.of("S3", "SHAREPOINT", "DATABASE", "SALESFORCE",
            "ONEDRIVE", "SERVICENOW", "CUSTOM", "CONFLUENCE", "GOOGLEDRIVE", "WEBCRAWLER", "WORKDOCS", "FSX",
            "SLACK", "BOX", "QUIP", "JIRA", "GITHUB", "ALFRESCO", "TEMPLATE");
    private static final List<String> DATA_SOURCE_FIELDS = List.of("Configuration", "VpcConfiguration",
            "Description", "Schedule", "RoleArn", "LanguageCode", "CustomDocumentEnrichmentConfiguration");
    private static final TypeReference<Map<String, ObjectNode>> NODES = new TypeReference<>() {};
    private static final int EXCERPT_LENGTH = 200;
    private static final int MAX_PAGE = 100;
    private static final int TAGS_MAX = 200;

    private final StorageBackend<String, ObjectNode> indexes;
    private final StorageBackend<String, ObjectNode> dataSources;
    private final StorageBackend<String, ObjectNode> documents;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public KendraService(StorageFactory storageFactory, RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("kendra", "kendra-indexes.json", NODES),
                storageFactory.create("kendra", "kendra-data-sources.json", NODES),
                storageFactory.create("kendra", "kendra-documents.json", NODES), regionResolver, objectMapper);
    }

    KendraService(StorageBackend<String, ObjectNode> indexes, StorageBackend<String, ObjectNode> dataSources,
                  StorageBackend<String, ObjectNode> documents, RegionResolver regionResolver,
                  ObjectMapper objectMapper) {
        this.indexes = indexes;
        this.dataSources = dataSources;
        this.documents = documents;
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
        String id = text(request, "Id");
        ObjectNode index = find(region, id).get("Index").deepCopy();
        List<ObjectNode> stored = documents.scan(k -> k.startsWith(documentPrefix(region, id)));
        long bytes = stored.stream().mapToLong(d -> d.path("Body").asText().getBytes(StandardCharsets.UTF_8).length)
                .sum();
        ((ObjectNode) index.path("IndexStatistics").path("TextDocumentStatistics"))
                .put("IndexedTextDocumentsCount", stored.size())
                .put("IndexedTextBytes", bytes);
        return index;
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
        dataSources.scan(k -> k.startsWith(dataSourcePrefix(region, id))).forEach(record ->
                dataSources.delete(dataSourcePrefix(region, id) + record.path("DataSource").path("Id").asText()));
        documents.scan(k -> k.startsWith(documentPrefix(region, id))).forEach(document ->
                documents.delete(documentPrefix(region, id) + document.path("Id").asText()));
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
        saveTagged(record, region);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode untagResource(JsonNode request, String region) {
        ObjectNode record = findByArn(request, region);
        ObjectNode tags = (ObjectNode) record.get("Tags");
        request.path("TagKeys").forEach(tagKey -> tags.remove(tagKey.asText()));
        saveTagged(record, region);
        return objectMapper.createObjectNode();
    }

    private void saveTagged(ObjectNode record, String region) {
        if (record.has("DataSource")) {
            JsonNode dataSource = record.get("DataSource");
            dataSources.put(dataSourcePrefix(region, dataSource.path("IndexId").asText())
                    + dataSource.path("Id").asText(), record);
        } else {
            indexes.put(key(region, record.path("Index").path("Id").asText()), record);
        }
    }

    // ──────────────────────────── Data sources ────────────────────────────

    public synchronized ObjectNode createDataSource(JsonNode request, String region) {
        String indexId = text(request, "IndexId");
        find(region, indexId);
        String name = text(request, "Name");
        requireName(name);
        String type = text(request, "Type");
        if (type == null || !DATA_SOURCE_TYPES.contains(type)) {
            throw validation("Type must be one of " + DATA_SOURCE_TYPES + ".");
        }
        String role = text(request, "RoleArn");
        if (role != null && !ROLE_ARN.matcher(role).matches()) {
            throw validation("RoleArn must be an IAM role ARN.");
        }
        if (!"CUSTOM".equals(type) && role == null) {
            throw validation("RoleArn is required for a " + type + " data source.");
        }
        requireDescription(request);
        String prefix = dataSourcePrefix(region, indexId);
        boolean taken = dataSources.scan(k -> k.startsWith(prefix)).stream()
                .anyMatch(d -> name.equals(d.path("DataSource").path("Name").asText()));
        if (taken) {
            throw new AwsException("ResourceAlreadyExistException",
                    "A data source named " + name + " already exists in index " + indexId + ".", 400);
        }
        ObjectNode tags = tagObject(request.get("Tags"));
        String id = UUID.randomUUID().toString();
        double now = epochSeconds(Instant.now());
        ObjectNode dataSource = objectMapper.createObjectNode();
        dataSource.put("Id", id);
        dataSource.put("IndexId", indexId);
        dataSource.put("Name", name);
        dataSource.put("Type", type);
        for (String field : DATA_SOURCE_FIELDS) {
            if (request.hasNonNull(field)) {
                dataSource.set(field, request.get(field));
            }
        }
        if (!dataSource.has("LanguageCode")) {
            dataSource.put("LanguageCode", "en");
        }
        dataSource.put("CreatedAt", now);
        dataSource.put("UpdatedAt", now);
        dataSource.put("Status", "ACTIVE");

        ObjectNode record = objectMapper.createObjectNode();
        record.put("Arn", regionResolver.buildArn("kendra", region, "index/" + indexId + "/data-source/" + id));
        record.set("DataSource", dataSource);
        record.set("Tags", tags);
        record.putArray("SyncJobs");
        dataSources.put(prefix + id, record);
        return objectMapper.createObjectNode().put("Id", id);
    }

    public ObjectNode describeDataSource(JsonNode request, String region) {
        return findDataSource(request, region).get("DataSource").deepCopy();
    }

    public synchronized ObjectNode updateDataSource(JsonNode request, String region) {
        ObjectNode record = findDataSource(request, region);
        ObjectNode dataSource = (ObjectNode) record.get("DataSource");
        if (request.hasNonNull("Name")) {
            requireName(text(request, "Name"));
            dataSource.put("Name", text(request, "Name"));
        }
        String role = text(request, "RoleArn");
        if (role != null && !ROLE_ARN.matcher(role).matches()) {
            throw validation("RoleArn must be an IAM role ARN.");
        }
        requireDescription(request);
        for (String field : DATA_SOURCE_FIELDS) {
            if (request.hasNonNull(field)) {
                dataSource.set(field, request.get(field));
            }
        }
        dataSource.put("UpdatedAt", epochSeconds(Instant.now()));
        dataSources.put(dataSourcePrefix(region, dataSource.path("IndexId").asText()) + dataSource.path("Id").asText(),
                record);
        return objectMapper.createObjectNode();
    }

    public synchronized ObjectNode deleteDataSource(JsonNode request, String region) {
        ObjectNode record = findDataSource(request, region);
        JsonNode dataSource = record.get("DataSource");
        dataSources.delete(dataSourcePrefix(region, dataSource.path("IndexId").asText()) + dataSource.path("Id").asText());
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDataSources(JsonNode request, String region) {
        String indexId = text(request, "IndexId");
        find(region, indexId);
        List<ObjectNode> all = dataSources.scan(k -> k.startsWith(dataSourcePrefix(region, indexId)));
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(all,
                record -> record.path("DataSource").path("Id").asText(), maxResults, text(request, "NextToken"),
                MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("SummaryItems");
        for (ObjectNode record : page.items()) {
            JsonNode dataSource = record.get("DataSource");
            ObjectNode summary = items.addObject();
            for (String field : List.of("Name", "Id", "Type", "CreatedAt", "UpdatedAt", "Status", "LanguageCode")) {
                summary.set(field, dataSource.get(field));
            }
        }
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    public synchronized ObjectNode startDataSourceSyncJob(JsonNode request, String region) {
        ObjectNode record = findDataSource(request, region);
        String executionId = UUID.randomUUID().toString();
        double now = epochSeconds(Instant.now());
        ObjectNode job = ((ArrayNode) record.get("SyncJobs")).addObject();
        job.put("ExecutionId", executionId);
        job.put("StartTime", now);
        job.put("EndTime", now);
        job.put("Status", "SUCCEEDED");
        job.putObject("Metrics")
                .put("DocumentsAdded", "0")
                .put("DocumentsModified", "0")
                .put("DocumentsDeleted", "0")
                .put("DocumentsFailed", "0")
                .put("DocumentsScanned", "0");
        JsonNode dataSource = record.get("DataSource");
        dataSources.put(dataSourcePrefix(region, dataSource.path("IndexId").asText()) + dataSource.path("Id").asText(),
                record);
        return objectMapper.createObjectNode().put("ExecutionId", executionId);
    }

    public ObjectNode stopDataSourceSyncJob(JsonNode request, String region) {
        findDataSource(request, region);
        return objectMapper.createObjectNode();
    }

    public ObjectNode listDataSourceSyncJobs(JsonNode request, String region) {
        ObjectNode record = findDataSource(request, region);
        String status = text(request, "StatusFilter");
        List<ObjectNode> all = new ArrayList<>();
        for (JsonNode job : record.path("SyncJobs")) {
            if (status == null || status.equals(job.path("Status").asText())) {
                all.add(((ObjectNode) job).deepCopy());
            }
        }
        Integer maxResults = request.hasNonNull("MaxResults") ? request.get("MaxResults").asInt() : null;
        PaginatedResult<ObjectNode> page = Pagination.paginate(all, job -> job.path("ExecutionId").asText(),
                maxResults, text(request, "NextToken"), MAX_PAGE, "ValidationException");
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("History").addAll(page.items());
        if (page.nextToken() != null) {
            response.put("NextToken", page.nextToken());
        }
        return response;
    }

    private ObjectNode findDataSource(JsonNode request, String region) {
        String indexId = text(request, "IndexId");
        find(region, indexId);
        String id = text(request, "Id");
        if (id == null || id.isBlank()) {
            throw validation("Id is required.");
        }
        return dataSources.get(dataSourcePrefix(region, indexId) + id)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Data source " + id + " doesn't exist in index " + indexId + ".", 400));
    }

    private static String dataSourcePrefix(String region, String indexId) {
        return region + "::" + indexId + "::";
    }

    // ──────────────────────────── Documents ────────────────────────────

    public synchronized ObjectNode batchPutDocument(JsonNode request, String region) {
        String indexId = text(request, "IndexId");
        find(region, indexId);
        JsonNode documentList = request.path("Documents");
        if (!documentList.isArray() || documentList.isEmpty() || documentList.size() > 10) {
            throw validation("Documents must hold 1 to 10 documents.");
        }
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode failed = response.putArray("FailedDocuments");
        for (JsonNode document : documentList) {
            String id = text(document, "Id");
            if (id == null || id.isBlank()) {
                failed.addObject().put("ErrorCode", "InvalidRequest").put("ErrorMessage", "Id is required.");
                continue;
            }
            String body = "";
            if (document.hasNonNull("Blob")) {
                try {
                    body = new String(Base64.getDecoder().decode(document.get("Blob").asText()), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    failed.addObject().put("Id", id).put("ErrorCode", "InvalidRequest")
                            .put("ErrorMessage", "Blob is not valid base64.");
                    continue;
                }
            } else if (!document.hasNonNull("S3Path")) {
                failed.addObject().put("Id", id).put("ErrorCode", "InvalidRequest")
                        .put("ErrorMessage", "A document needs a Blob or an S3Path.");
                continue;
            }
            ObjectNode stored = objectMapper.createObjectNode();
            stored.put("Id", id);
            stored.put("Title", document.path("Title").asText(id));
            stored.put("Body", "HTML".equals(text(document, "ContentType")) ? body.replaceAll("<[^>]*>", " ") : body);
            stored.set("Attributes", document.path("Attributes").isArray()
                    ? document.get("Attributes").deepCopy() : objectMapper.createArrayNode());
            documents.put(documentPrefix(region, indexId) + id, stored);
        }
        return response;
    }

    public synchronized ObjectNode batchDeleteDocument(JsonNode request, String region) {
        String indexId = text(request, "IndexId");
        find(region, indexId);
        for (JsonNode id : request.path("DocumentIdList")) {
            documents.delete(documentPrefix(region, indexId) + id.asText());
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("FailedDocuments");
        return response;
    }

    // ──────────────────────────── Query and Retrieve ────────────────────────────

    public ObjectNode query(JsonNode request, String region) {
        String indexId = text(request, "IndexId");
        find(region, indexId);
        String queryId = UUID.randomUUID().toString();
        List<Scored> matches = search(request, region, indexId);
        String typeFilter = text(request, "QueryResultTypeFilter");
        if (typeFilter != null && !"DOCUMENT".equals(typeFilter)) {
            matches = List.of();
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("QueryId", queryId);
        ArrayNode items = response.putArray("ResultItems");
        for (Scored match : page(request, matches)) {
            JsonNode document = match.document();
            ObjectNode item = items.addObject();
            item.put("Id", queryId + "-" + document.path("Id").asText());
            item.put("Type", "DOCUMENT");
            item.put("Format", "TEXT");
            item.putArray("AdditionalAttributes");
            item.put("DocumentId", document.path("Id").asText());
            item.set("DocumentTitle", highlighted(document.path("Title").asText(), match.terms()));
            item.set("DocumentExcerpt", highlighted(excerpt(document.path("Body").asText(), match.terms()),
                    match.terms()));
            String uri = attributeString(document, "_source_uri");
            if (uri != null) {
                item.put("DocumentURI", uri);
            }
            item.set("DocumentAttributes", document.path("Attributes").deepCopy());
            item.putObject("ScoreAttributes").put("ScoreConfidence", confidence(match.score()));
            item.put("FeedbackToken", queryId + "." + document.path("Id").asText());
        }
        response.putArray("FacetResults");
        response.put("TotalNumberOfResults", matches.size());
        return response;
    }

    public ObjectNode retrieve(JsonNode request, String region) {
        String indexId = text(request, "IndexId");
        find(region, indexId);
        if (text(request, "QueryText") == null) {
            throw validation("QueryText is required.");
        }
        String queryId = UUID.randomUUID().toString();
        ObjectNode response = objectMapper.createObjectNode();
        response.put("QueryId", queryId);
        ArrayNode items = response.putArray("ResultItems");
        for (Scored match : page(request, search(request, region, indexId))) {
            JsonNode document = match.document();
            ObjectNode item = items.addObject();
            item.put("Id", queryId + "-" + document.path("Id").asText());
            item.put("DocumentId", document.path("Id").asText());
            item.put("DocumentTitle", document.path("Title").asText());
            item.put("Content", excerpt(document.path("Body").asText(), match.terms()));
            String uri = attributeString(document, "_source_uri");
            if (uri != null) {
                item.put("DocumentURI", uri);
            }
            item.set("DocumentAttributes", document.path("Attributes").deepCopy());
            item.putObject("ScoreAttributes").put("ScoreConfidence", confidence(match.score()));
        }
        return response;
    }

    private record Scored(JsonNode document, List<String> terms, int score) {}

    private List<Scored> search(JsonNode request, String region, String indexId) {
        String queryText = text(request, "QueryText");
        List<String> terms = queryText == null ? List.of() : Arrays.stream(queryText.toLowerCase().split("\\W+"))
                .filter(term -> !term.isBlank()).distinct().toList();
        List<Scored> result = new ArrayList<>();
        for (ObjectNode document : documents.scan(k -> k.startsWith(documentPrefix(region, indexId)))) {
            if (!matchesFilter(document, request.get("AttributeFilter"))) {
                continue;
            }
            String haystack = (document.path("Title").asText() + " " + document.path("Body").asText()).toLowerCase();
            int score = 0;
            for (String term : terms) {
                int at = haystack.indexOf(term);
                while (at >= 0) {
                    score++;
                    at = haystack.indexOf(term, at + term.length());
                }
            }
            if (score > 0 || terms.isEmpty()) {
                result.add(new Scored(document, terms, score));
            }
        }
        result.sort(Comparator.comparingInt(Scored::score).reversed()
                .thenComparing(s -> s.document().path("Id").asText()));
        return result;
    }

    private static List<Scored> page(JsonNode request, List<Scored> matches) {
        int size = Math.max(1, request.path("PageSize").asInt(10));
        int number = Math.max(1, request.path("PageNumber").asInt(1));
        int from = Math.min(matches.size(), (number - 1) * size);
        return matches.subList(from, Math.min(matches.size(), from + size));
    }

    private static boolean matchesFilter(JsonNode document, JsonNode filter) {
        if (filter == null || !filter.isObject() || filter.isEmpty()) {
            return true;
        }
        if (filter.has("AndAllFilters")) {
            for (JsonNode child : filter.get("AndAllFilters")) {
                if (!matchesFilter(document, child)) {
                    return false;
                }
            }
            return true;
        }
        if (filter.has("OrAllFilters")) {
            for (JsonNode child : filter.get("OrAllFilters")) {
                if (matchesFilter(document, child)) {
                    return true;
                }
            }
            return false;
        }
        if (filter.has("NotFilter")) {
            return !matchesFilter(document, filter.get("NotFilter"));
        }
        if (filter.has("EqualsTo")) {
            JsonNode wanted = filter.get("EqualsTo");
            JsonNode value = attribute(document, wanted.path("Key").asText());
            return value != null && value.equals(wanted.path("Value"));
        }
        if (filter.has("ContainsAny")) {
            JsonNode wanted = filter.get("ContainsAny");
            JsonNode value = attribute(document, wanted.path("Key").asText());
            if (value == null) {
                return false;
            }
            for (JsonNode candidate : wanted.path("Value").path("StringListValue")) {
                for (JsonNode present : value.path("StringListValue")) {
                    if (candidate.equals(present)) {
                        return true;
                    }
                }
                if (candidate.equals(value.path("StringValue"))) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static JsonNode attribute(JsonNode document, String key) {
        for (JsonNode attribute : document.path("Attributes")) {
            if (key.equals(attribute.path("Key").asText())) {
                return attribute.path("Value");
            }
        }
        return null;
    }

    private static String attributeString(JsonNode document, String key) {
        JsonNode value = attribute(document, key);
        return value == null || !value.hasNonNull("StringValue") ? null : value.get("StringValue").asText();
    }

    private static String excerpt(String body, List<String> terms) {
        String lower = body.toLowerCase();
        int at = -1;
        for (String term : terms) {
            int found = lower.indexOf(term);
            if (found >= 0 && (at < 0 || found < at)) {
                at = found;
            }
        }
        int from = Math.max(0, at - EXCERPT_LENGTH / 4);
        return body.substring(from, Math.min(body.length(), from + EXCERPT_LENGTH)).strip();
    }

    private ObjectNode highlighted(String text, List<String> terms) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("Text", text);
        ArrayNode highlights = result.putArray("Highlights");
        String lower = text.toLowerCase();
        for (String term : terms) {
            int at = lower.indexOf(term);
            while (at >= 0) {
                highlights.addObject()
                        .put("BeginOffset", at)
                        .put("EndOffset", at + term.length())
                        .put("TopAnswer", false)
                        .put("Type", "STANDARD");
                at = lower.indexOf(term, at + term.length());
            }
        }
        return result;
    }

    private static String confidence(int score) {
        if (score >= 5) {
            return "HIGH";
        }
        return score >= 2 ? "MEDIUM" : "LOW";
    }

    private static String documentPrefix(String region, String indexId) {
        return region + "::" + indexId + "::";
    }

    private ObjectNode findByArn(JsonNode request, String region) {
        String arn = text(request, "ResourceARN");
        int marker = arn == null ? -1 : arn.indexOf(":index/");
        String resource = marker < 0 ? "" : arn.substring(marker + ":index/".length());
        int dataSource = resource.indexOf("/data-source/");
        ObjectNode record;
        if (marker < 0) {
            record = null;
        } else if (dataSource >= 0) {
            record = dataSources.get(dataSourcePrefix(region, resource.substring(0, dataSource))
                    + resource.substring(dataSource + "/data-source/".length())).orElse(null);
        } else {
            record = indexes.get(key(region, resource)).orElse(null);
        }
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
