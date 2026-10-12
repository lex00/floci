package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcore.model.BatchEvaluation;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationService;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Evaluator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.EVALUATOR_ID;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.ONLINE_EVALUATION_CONFIG_ARN;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.RESOURCE_ID;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.RESOURCE_NAME;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.has;
import static io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation.invalid;

/**
 * AgentCore Evaluations data plane: on-demand {@code Evaluate} and batch evaluations.
 *
 * <p>No judge model runs. {@code Evaluate} reads the session, trace and span ids out of the
 * OpenTelemetry spans it is given and returns one result per entity at the evaluator's level, each
 * carrying the configured canned score. A batch evaluation is accepted as {@code IN_PROGRESS} and
 * has completed, with the same canned score for every evaluator, by the first time it is read
 * ({@code GetBatchEvaluation}, {@code ListBatchEvaluations} or {@code DeleteBatchEvaluation}).
 * {@code StopBatchEvaluation} before that stops it instead. This is the same spirit as the
 * {@code InvokeAgentRuntime} stub: the wire contract is real, the judgement is not.
 */
@ApplicationScoped
public class BedrockAgentCoreEvaluateService {

    static final String CANNED_LABEL = "Canned";
    static final String CANNED_EXPLANATION = "Canned score from the Floci emulator. No evaluator model ran.";
    private static final String ARN_SERVICE = "bedrock-agentcore";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";
    private static final String STOPPED = "STOPPED";
    private static final String DELETING = "DELETING";
    private static final Set<String> TERMINAL = Set.of(COMPLETED, "COMPLETED_WITH_ERRORS", "FAILED", STOPPED);
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int MAX_PAGE = 100;

    private final StorageBackend<String, BatchEvaluation> batches;
    private final BedrockAgentCoreEvaluationService evaluationService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final double cannedScore;

    @Inject
    public BedrockAgentCoreEvaluateService(StorageFactory storageFactory,
                                           BedrockAgentCoreEvaluationService evaluationService,
                                           RegionResolver regionResolver, ObjectMapper objectMapper,
                                           Clock clock, EmulatorConfig config) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-batch-evaluations.json",
                        new TypeReference<Map<String, BatchEvaluation>>() {}),
                evaluationService, regionResolver, objectMapper, clock,
                config.services().bedrockAgentCore().evaluationScore());
    }

    BedrockAgentCoreEvaluateService(StorageBackend<String, BatchEvaluation> batches,
                                    BedrockAgentCoreEvaluationService evaluationService,
                                    RegionResolver regionResolver, ObjectMapper objectMapper,
                                    Clock clock, double cannedScore) {
        this.batches = batches;
        this.evaluationService = evaluationService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.cannedScore = cannedScore;
    }

    // ── Evaluate ──

    public ObjectNode evaluate(String evaluatorId, JsonNode req, String region) {
        if (evaluatorId == null || evaluatorId.length() > 111 || !EVALUATOR_ID.matcher(evaluatorId).matches()) {
            throw invalid("evaluatorId must match the pattern " + EVALUATOR_ID.pattern() + ".");
        }
        JsonNode input = BedrockAgentCoreEvaluationValidation.requireMember(req, "evaluationInput", "");
        BedrockAgentCoreEvaluationValidation.union(input, "evaluationInput", Set.of("sessionSpans"));
        List<JsonNode> spans = BedrockAgentCoreEvaluationValidation.list(input, "sessionSpans", "evaluationInput.",
                true, 1, 20000);
        List<String> targetSpans = List.of();
        List<String> targetTraces = List.of();
        JsonNode target = BedrockAgentCoreEvaluationValidation.optionalObject(req, "evaluationTarget", "");
        if (target != null) {
            String kind = BedrockAgentCoreEvaluationValidation.union(target, "evaluationTarget",
                    Set.of("spanIds", "traceIds"));
            if ("spanIds".equals(kind)) {
                BedrockAgentCoreEvaluationValidation.stringList(target, kind, "evaluationTarget.", true,
                        1, 10, 16, 16, null);
                targetSpans = texts(target.get(kind));
            } else {
                BedrockAgentCoreEvaluationValidation.stringList(target, kind, "evaluationTarget.", true,
                        1, 10, 32, 32, null);
                targetTraces = texts(target.get(kind));
            }
        }
        validateReferenceInputs(req);

        Evaluator evaluator = evaluationService.findEvaluator(evaluatorId, region)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Evaluator not found: " + evaluatorId, 404));
        String sessionId = sessionId(spans);

        List<ObjectNode> contexts = new ArrayList<>();
        switch (evaluator.getLevel()) {
            case "SESSION" -> contexts.add(spanContext(sessionId, null, null));
            case "TOOL_CALL" -> {
                for (JsonNode span : spans) {
                    String spanId = span.path("spanId").asText(null);
                    boolean selected = targetSpans.isEmpty()
                            ? "execute_tool".equals(attribute(span, "gen_ai.operation.name"))
                            : targetSpans.contains(spanId);
                    if (spanId != null && selected) {
                        contexts.add(spanContext(sessionId, span.path("traceId").asText(null), spanId));
                    }
                }
            }
            default -> {
                Set<String> traces = new LinkedHashSet<>(targetTraces);
                if (traces.isEmpty()) {
                    spans.stream().map(s -> s.path("traceId").asText(null))
                            .filter(t -> t != null && !t.isEmpty())
                            .forEach(traces::add);
                }
                traces.forEach(trace -> contexts.add(spanContext(sessionId, trace, null)));
            }
        }

        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode results = out.putArray("evaluationResults");
        String evaluatorArn = evaluationService.evaluatorArn(evaluator, region);
        for (ObjectNode context : contexts) {
            ObjectNode result = results.addObject();
            result.put("evaluatorArn", evaluatorArn);
            result.put("evaluatorId", evaluator.getEvaluatorId());
            result.put("evaluatorName", evaluator.getEvaluatorName());
            result.put("explanation", CANNED_EXPLANATION);
            result.putObject("context").set("spanContext", context);
            result.put("value", cannedScore);
            result.put("label", CANNED_LABEL);
        }
        return out;
    }

    private void validateReferenceInputs(JsonNode req) {
        List<JsonNode> inputs = BedrockAgentCoreEvaluationValidation.list(req, "evaluationReferenceInputs", "",
                false, 1, 1000);
        for (int i = 0; i < inputs.size(); i++) {
            String p = "evaluationReferenceInputs[" + i + "].";
            JsonNode input = inputs.get(i);
            JsonNode context = BedrockAgentCoreEvaluationValidation.requireMember(input, "context", p);
            BedrockAgentCoreEvaluationValidation.union(context, p + "context", Set.of("spanContext"));
            BedrockAgentCoreEvaluationValidation.string(context.get("spanContext"), "sessionId",
                    p + "context.spanContext.", true, -1, -1, null);
            JsonNode expected = BedrockAgentCoreEvaluationValidation.optionalObject(input, "expectedResponse", p);
            if (expected != null) {
                validateContent(expected, p + "expectedResponse");
            }
            List<JsonNode> assertions = BedrockAgentCoreEvaluationValidation.list(input, "assertions", p, false,
                    1, 100);
            for (int j = 0; j < assertions.size(); j++) {
                validateContent(assertions.get(j), p + "assertions[" + j + "]");
            }
            JsonNode trajectory = BedrockAgentCoreEvaluationValidation.optionalObject(input, "expectedTrajectory", p);
            if (trajectory != null) {
                BedrockAgentCoreEvaluationValidation.stringList(trajectory, "toolNames", p + "expectedTrajectory.",
                        false, 0, 1000, 1, 500, null);
            }
        }
    }

    private static void validateContent(JsonNode content, String path) {
        BedrockAgentCoreEvaluationValidation.union(content, path, Set.of("text"));
        BedrockAgentCoreEvaluationValidation.string(content, "text", path + ".", true, 1, 100000, null);
    }

    /**
     * The session the spans belong to. AgentCore's OpenTelemetry spans carry it in the
     * {@code session.id} attribute; a span may also put it on its resource.
     */
    private static String sessionId(List<JsonNode> spans) {
        for (JsonNode span : spans) {
            String id = attribute(span, "session.id");
            if (id == null) {
                id = span.path("resource").path("attributes").path("session.id").asText(null);
            }
            if (id != null && !id.isEmpty()) {
                return id;
            }
        }
        throw invalid("evaluationInput.sessionSpans carries no span with a session.id attribute.");
    }

    private static String attribute(JsonNode span, String name) {
        JsonNode value = span.path("attributes").path(name);
        return value.isValueNode() ? value.asText() : null;
    }

    private ObjectNode spanContext(String sessionId, String traceId, String spanId) {
        ObjectNode context = objectMapper.createObjectNode();
        context.put("sessionId", sessionId);
        if (traceId != null) {
            context.put("traceId", traceId);
        }
        if (spanId != null) {
            context.put("spanId", spanId);
        }
        return context;
    }

    // ── Batch evaluations ──

    public BatchEvaluation startBatch(JsonNode req, String region) {
        BedrockAgentCoreEvaluationValidation.clientToken(req);
        String clientToken = BedrockAgentCoreEvaluationValidation.string(req, "clientToken", "", false, -1, -1, null);
        if (clientToken != null) {
            Optional<BatchEvaluation> replay = batches.scan(k -> k.startsWith(prefix(region))).stream()
                    .filter(b -> clientToken.equals(b.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        String name = BedrockAgentCoreEvaluationValidation.string(req, "batchEvaluationName", "", true, -1, -1,
                RESOURCE_NAME);
        List<JsonNode> evaluators = BedrockAgentCoreEvaluationValidation.list(req, "evaluators", "", false, 0, 10);
        for (int i = 0; i < evaluators.size(); i++) {
            String id = BedrockAgentCoreEvaluationValidation.string(evaluators.get(i), "evaluatorId",
                    "evaluators[" + i + "].", true, 1, 111, EVALUATOR_ID);
            if (evaluationService.findEvaluator(id, region).isEmpty()) {
                throw invalid("Evaluator not found: " + id);
            }
        }
        BedrockAgentCoreEvaluationValidation.insights(req, "", 10);
        validateDataSource(BedrockAgentCoreEvaluationValidation.requireMember(req, "dataSourceConfig", ""), region);
        List<String> metadataSessions = validateMetadata(req);
        BedrockAgentCoreEvaluationValidation.tags(req);
        BedrockAgentCoreEvaluationValidation.kmsKeyArn(req);
        String description = BedrockAgentCoreEvaluationValidation.string(req, "description", "", false, 0, 200, null);
        JsonNode output = BedrockAgentCoreEvaluationValidation.optionalObject(req, "outputConfig", "");
        if (output != null) {
            BedrockAgentCoreEvaluationValidation.union(output, "outputConfig", Set.of("cloudWatchConfig"));
            BedrockAgentCoreEvaluationService.validateCloudWatchOutput(output.get("cloudWatchConfig"),
                    "outputConfig.cloudWatchConfig.");
        }

        Instant now = Instant.now(clock);
        BatchEvaluation batch = new BatchEvaluation();
        batch.setBatchEvaluationId(name + "-" + random(10));
        batch.setBatchEvaluationName(name);
        batch.setDescription(description);
        batch.setStatus(IN_PROGRESS);
        batch.setEvaluators(has(req, "evaluators") ? req.get("evaluators").deepCopy() : null);
        batch.setInsights(has(req, "insights") ? req.get("insights").deepCopy() : null);
        batch.setDataSourceConfig(req.get("dataSourceConfig").deepCopy());
        batch.setEvaluationMetadata(has(req, "evaluationMetadata") ? req.get("evaluationMetadata").deepCopy() : null);
        batch.setOutputConfig(output == null ? null : output.deepCopy());
        batch.setKmsKeyArn(has(req, "kmsKeyArn") ? req.get("kmsKeyArn").asText() : null);
        batch.setCreatedAt(now);
        batch.setUpdatedAt(now);
        batch.setClientToken(clientToken);
        if (req.path("tags").isObject()) {
            req.get("tags").fields().forEachRemaining(e -> batch.getTags().put(e.getKey(), e.getValue().asText()));
        }
        // Remember how many sessions the caller named, which is what the canned results report.
        Set<String> sessions = new LinkedHashSet<>(metadataSessions);
        sessions.addAll(texts(req.path("dataSourceConfig").path("cloudWatchLogs").path("filterConfig")
                .path("sessionIds")));
        batch.setEvaluationResults(objectMapper.createObjectNode().put("totalNumberOfSessions",
                Math.max(sessions.size(), 1)));
        batches.put(key(region, batch.getBatchEvaluationId()), batch);
        return batch;
    }

    public BatchEvaluation getBatch(String id, String region) {
        BatchEvaluation batch = find(id, region);
        complete(batch, region);
        return batch;
    }

    public PaginatedResult<BatchEvaluation> listBatches(Integer maxResults, String nextToken, String region) {
        List<BatchEvaluation> all = batches.scan(k -> k.startsWith(prefix(region)));
        all.forEach(batch -> complete(batch, region));
        return Pagination.paginate(all, BatchEvaluation::getBatchEvaluationId, maxResults, nextToken, MAX_PAGE,
                "ValidationException");
    }

    public BatchEvaluation stopBatch(String id, String region) {
        BatchEvaluation batch = find(id, region);
        if (TERMINAL.contains(batch.getStatus())) {
            throw new AwsException("ConflictException",
                    "Batch evaluation " + id + " is " + batch.getStatus() + " and cannot be stopped.", 409);
        }
        batch.setStatus(STOPPED);
        batch.setEvaluationResults(results(batch, 0));
        batch.setUpdatedAt(Instant.now(clock));
        batches.put(key(region, id), batch);
        return batch;
    }

    public BatchEvaluation deleteBatch(String id, String region) {
        BatchEvaluation batch = find(id, region);
        complete(batch, region);
        if (!TERMINAL.contains(batch.getStatus())) {
            throw new AwsException("ConflictException",
                    "Batch evaluation " + id + " must be in a terminal state to be deleted.", 409);
        }
        batches.delete(key(region, id));
        batch.setStatus(DELETING);
        return batch;
    }

    public String batchArn(BatchEvaluation batch, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "batch-evaluate/" + batch.getBatchEvaluationId());
    }

    private BatchEvaluation find(String id, String region) {
        if (id == null || !RESOURCE_ID.matcher(id).matches()) {
            throw invalid("batchEvaluationId must match the pattern " + RESOURCE_ID.pattern() + ".");
        }
        return batches.get(key(region, id)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Batch evaluation not found: " + id, 404));
    }

    /** The first read of a running job finishes it, every session scored with the canned score. */
    private void complete(BatchEvaluation batch, String region) {
        if (!IN_PROGRESS.equals(batch.getStatus())) {
            return;
        }
        int sessions = batch.getEvaluationResults() == null ? 1
                : batch.getEvaluationResults().path("totalNumberOfSessions").asInt(1);
        batch.setStatus(COMPLETED);
        batch.setEvaluationResults(results(batch, sessions));
        batch.setUpdatedAt(Instant.now(clock));
        batches.put(key(region, batch.getBatchEvaluationId()), batch);
    }

    private ObjectNode results(BatchEvaluation batch, int completed) {
        int total = batch.getEvaluationResults() == null ? 1
                : batch.getEvaluationResults().path("totalNumberOfSessions").asInt(1);
        ObjectNode results = objectMapper.createObjectNode();
        results.put("numberOfSessionsCompleted", completed);
        results.put("numberOfSessionsInProgress", 0);
        results.put("numberOfSessionsFailed", 0);
        results.put("totalNumberOfSessions", total);
        results.put("numberOfSessionsIgnored", 0);
        ArrayNode summaries = results.putArray("evaluatorSummaries");
        if (completed > 0 && batch.getEvaluators() != null) {
            for (JsonNode ref : batch.getEvaluators()) {
                ObjectNode summary = summaries.addObject();
                summary.put("evaluatorId", ref.path("evaluatorId").asText());
                summary.putObject("statistics").put("averageScore", cannedScore);
                summary.put("totalEvaluated", completed);
                summary.put("totalFailed", 0);
            }
        }
        return results;
    }

    private void validateDataSource(JsonNode source, String region) {
        String kind = BedrockAgentCoreEvaluationValidation.union(source, "dataSourceConfig",
                Set.of("cloudWatchLogs", "onlineEvaluationConfigSource"));
        JsonNode body = source.get(kind);
        String p = "dataSourceConfig." + kind + ".";
        if ("cloudWatchLogs".equals(kind)) {
            BedrockAgentCoreEvaluationService.validateCloudWatchLogsSource(body, p);
            JsonNode filter = BedrockAgentCoreEvaluationValidation.optionalObject(body, "filterConfig", p);
            if (filter != null) {
                BedrockAgentCoreEvaluationValidation.stringList(filter, "sessionIds", p + "filterConfig.", false,
                        0, 500, -1, -1, null);
                validateTimeRange(filter, p + "filterConfig.");
            }
        } else {
            String arn = BedrockAgentCoreEvaluationValidation.string(body, "onlineEvaluationConfigArn", p, true,
                    -1, -1, ONLINE_EVALUATION_CONFIG_ARN);
            if (evaluationService.findOnlineConfigByArn(arn, region).isEmpty()) {
                throw invalid("Online evaluation configuration not found: " + arn);
            }
            validateTimeRange(body, p);
        }
    }

    private static void validateTimeRange(JsonNode node, String path) {
        JsonNode range = BedrockAgentCoreEvaluationValidation.optionalObject(node, "timeRange", path);
        if (range == null) {
            return;
        }
        for (String field : List.of("startTime", "endTime")) {
            if (has(range, field) && !range.get(field).isTextual() && !range.get(field).isNumber()) {
                throw invalid(path + "timeRange." + field + " must be a timestamp.");
            }
        }
    }

    private static List<String> validateMetadata(JsonNode req) {
        JsonNode metadata = BedrockAgentCoreEvaluationValidation.optionalObject(req, "evaluationMetadata", "");
        if (metadata == null) {
            return List.of();
        }
        BedrockAgentCoreEvaluationValidation.union(metadata, "evaluationMetadata", Set.of("sessionMetadata"));
        List<JsonNode> sessions = BedrockAgentCoreEvaluationValidation.list(metadata, "sessionMetadata",
                "evaluationMetadata.", true, 0, 500);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < sessions.size(); i++) {
            ids.add(BedrockAgentCoreEvaluationValidation.string(sessions.get(i), "sessionId",
                    "evaluationMetadata.sessionMetadata[" + i + "].", true, -1, -1, null));
        }
        return ids;
    }

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(v -> values.add(v.asText()));
        }
        return values;
    }

    private static String random(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALNUM.charAt(ThreadLocalRandom.current().nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    private static String key(String region, String id) {
        return prefix(region) + id;
    }

    private static String prefix(String region) {
        return "batch-evaluation:" + region + ":";
    }
}
