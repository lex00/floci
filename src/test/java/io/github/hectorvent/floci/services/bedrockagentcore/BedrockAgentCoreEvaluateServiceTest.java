package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.bedrockagentcore.model.BatchEvaluation;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationService;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Evaluator;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.OnlineEvaluationConfig;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockAgentCoreEvaluateServiceTest {

    private static final String REGION = "us-east-1";
    private static final String TRACE_A = "a".repeat(32);
    private static final String TRACE_B = "b".repeat(32);

    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private BedrockAgentCoreEvaluationService evaluations;
    private BedrockAgentCoreEvaluateService service;

    @BeforeEach
    void setUp() {
        RegionResolver resolver = new RegionResolver(REGION, "000000000000");
        StorageBackend<String, Evaluator> evaluators = new InMemoryStorage<>();
        StorageBackend<String, OnlineEvaluationConfig> configs = new InMemoryStorage<>();
        evaluations = new BedrockAgentCoreEvaluationService(evaluators, configs, resolver, mapper, clock);
        service = new BedrockAgentCoreEvaluateService(new InMemoryStorage<>(), evaluations, resolver, mapper,
                clock, 0.75);
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private JsonNode spans() {
        return json("""
                {"evaluationInput": {"sessionSpans": [
                  {"traceId": "%s", "spanId": "1111111111111111", "attributes": {"session.id": "sess-1"}},
                  {"traceId": "%s", "spanId": "2222222222222222",
                   "attributes": {"session.id": "sess-1", "gen_ai.operation.name": "execute_tool"}},
                  {"traceId": "%s", "spanId": "3333333333333333", "attributes": {"session.id": "sess-1"}}]}}
                """.formatted(TRACE_A, TRACE_A, TRACE_B));
    }

    @Test
    void traceLevelEvaluatorScoresEveryTrace() {
        ObjectNode out = service.evaluate("Builtin.Helpfulness", spans(), REGION);
        JsonNode results = out.get("evaluationResults");
        assertEquals(2, results.size());
        assertEquals(TRACE_A, results.get(0).at("/context/spanContext/traceId").asText());
        assertEquals(TRACE_B, results.get(1).at("/context/spanContext/traceId").asText());
        assertEquals("sess-1", results.get(0).at("/context/spanContext/sessionId").asText());
        assertEquals(0.75, results.get(0).get("value").asDouble());
        assertEquals("Builtin.Helpfulness", results.get(0).get("evaluatorName").asText());
        assertEquals("arn:aws:bedrock-agentcore:::evaluator/Builtin.Helpfulness",
                results.get(0).get("evaluatorArn").asText());
    }

    @Test
    void sessionAndToolLevelsFollowTheEvaluator() {
        JsonNode session = service.evaluate("Builtin.GoalSuccessRate", spans(), REGION).get("evaluationResults");
        assertEquals(1, session.size());
        assertFalse(session.get(0).at("/context/spanContext").has("traceId"));

        JsonNode tool = service.evaluate("Builtin.ToolSelectionAccuracy", spans(), REGION).get("evaluationResults");
        assertEquals(1, tool.size());
        assertEquals("2222222222222222", tool.get(0).at("/context/spanContext/spanId").asText());
    }

    @Test
    void evaluationTargetNarrowsTheTraces() {
        ObjectNode req = (ObjectNode) spans();
        req.set("evaluationTarget", json("{\"traceIds\": [\"" + TRACE_B + "\"]}"));
        JsonNode results = service.evaluate("Builtin.Helpfulness", req, REGION).get("evaluationResults");
        assertEquals(1, results.size());
        assertEquals(TRACE_B, results.get(0).at("/context/spanContext/traceId").asText());
    }

    @Test
    void unknownEvaluatorIsNotFoundAndMissingSessionIsInvalid() {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.evaluate("missing-abcdefghij", spans(), REGION)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.evaluate(
                "Builtin.Helpfulness", json("{\"evaluationInput\": {\"sessionSpans\": [{\"traceId\": \"t\"}]}}"),
                REGION)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.evaluate(
                "Builtin.Helpfulness", json("{\"evaluationInput\": {\"sessionSpans\": []}}"), REGION))
                .getErrorCode());
    }

    private BatchEvaluation start(String name) {
        return service.startBatch(json("""
                {"batchEvaluationName": "%s",
                 "evaluators": [{"evaluatorId": "Builtin.Helpfulness"}, {"evaluatorId": "Builtin.Correctness"}],
                 "dataSourceConfig": {"cloudWatchLogs": {"serviceNames": ["my.agent"],
                   "filterConfig": {"sessionIds": ["s1", "s2", "s3"]}}}}
                """.formatted(name)), REGION);
    }

    @Test
    void batchCompletesOnFirstReadWithCannedScores() {
        BatchEvaluation started = start("batchOne");
        assertEquals("IN_PROGRESS", started.getStatus());

        BatchEvaluation read = service.getBatch(started.getBatchEvaluationId(), REGION);
        assertEquals("COMPLETED", read.getStatus());
        JsonNode results = read.getEvaluationResults();
        assertEquals(3, results.get("totalNumberOfSessions").asInt());
        assertEquals(3, results.get("numberOfSessionsCompleted").asInt());
        assertEquals(2, results.get("evaluatorSummaries").size());
        assertEquals(0.75, results.at("/evaluatorSummaries/0/statistics/averageScore").asDouble());

        assertEquals("ConflictException", assertThrows(AwsException.class,
                () -> service.stopBatch(started.getBatchEvaluationId(), REGION)).getErrorCode());
        assertEquals("DELETING", service.deleteBatch(started.getBatchEvaluationId(), REGION).getStatus());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getBatch(started.getBatchEvaluationId(), REGION)).getErrorCode());
    }

    @Test
    void stopBeforeAnyReadStopsTheJob() {
        BatchEvaluation started = start("batchTwo");
        assertEquals("STOPPED", service.stopBatch(started.getBatchEvaluationId(), REGION).getStatus());
        assertEquals("STOPPED", service.getBatch(started.getBatchEvaluationId(), REGION).getStatus());
        assertEquals("arn:aws:bedrock-agentcore:us-east-1:000000000000:batch-evaluate/"
                + started.getBatchEvaluationId(), service.batchArn(started, REGION));
    }

    @Test
    void batchRefusesUnknownSourcesAndEvaluators() {
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.startBatch(json("""
                {"batchEvaluationName": "bad", "evaluators": [{"evaluatorId": "Builtin.Nope"}],
                 "dataSourceConfig": {"cloudWatchLogs": {"serviceNames": ["svc"]}}}
                """), REGION)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.startBatch(json("""
                {"batchEvaluationName": "bad", "dataSourceConfig": {"onlineEvaluationConfigSource": {
                  "onlineEvaluationConfigArn":
                    "arn:aws:bedrock-agentcore:us-east-1:000000000000:online-evaluation-config/none-abcdefghij"}}}
                """), REGION)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.startBatch(json("""
                {"batchEvaluationName": "bad", "dataSourceConfig": {"cloudWatchLogs": {"serviceNames": []}}}
                """), REGION)).getErrorCode());
    }

    @Test
    void clientTokenReplaysTheOriginalJob() {
        String token = "t".repeat(40);
        JsonNode req = json("""
                {"batchEvaluationName": "replay", "clientToken": "%s",
                 "dataSourceConfig": {"cloudWatchLogs": {"serviceNames": ["svc"]}}}
                """.formatted(token));
        assertEquals(service.startBatch(req, REGION).getBatchEvaluationId(),
                service.startBatch(req, REGION).getBatchEvaluationId());
    }
}
