package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Evaluator;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.OnlineEvaluationConfig;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAgentCoreEvaluationServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ROLE = "arn:aws:iam::000000000000:role/eval-role";

    private final ObjectMapper mapper = new ObjectMapper();
    private BedrockAgentCoreEvaluationService service;

    @BeforeEach
    void setUp() {
        service = new BedrockAgentCoreEvaluationService(new InMemoryStorage<>(), new InMemoryStorage<>(),
                new RegionResolver(REGION, "000000000000"), mapper, new MutableClock());
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private JsonNode judgeRequest(String name) {
        return json("""
                {"evaluatorName": "%s", "level": "TRACE",
                 "evaluatorConfig": {"llmAsAJudge": {
                   "instructions": "Rate it",
                   "ratingScale": {"numerical": [{"definition": "good", "value": 1.0, "label": "Good"}]},
                   "modelConfig": {"bedrockEvaluatorModelConfig": {"modelId": "some-model"}}}}}
                """.formatted(name));
    }

    private OnlineEvaluationConfig onlineConfig(String name, String evaluatorId, boolean enabled) {
        return service.createOnlineConfig(json("""
                {"onlineEvaluationConfigName": "%s",
                 "rule": {"samplingConfig": {"samplingPercentage": 10}},
                 "dataSourceConfig": {"cloudWatchLogs": {"serviceNames": ["my.agent"], "logGroupNames": ["/aws/x"]}},
                 "evaluators": [{"evaluatorId": "%s"}],
                 "evaluationExecutionRoleArn": "%s", "enableOnCreate": %s}
                """.formatted(name, evaluatorId, ROLE, enabled)), REGION);
    }

    @Test
    void listIncludesBuiltinsWithGlobalArns() {
        PaginatedResult<Evaluator> page = service.listEvaluators(100, null, REGION);
        Evaluator helpfulness = page.items().stream()
                .filter(e -> "Builtin.Helpfulness".equals(e.getEvaluatorId())).findFirst().orElseThrow();
        assertEquals("Builtin", helpfulness.getEvaluatorType());
        assertEquals("AWS", helpfulness.getProvider());
        assertEquals("TRACE", helpfulness.getLevel());
        assertEquals("arn:aws:bedrock-agentcore:::evaluator/Builtin.Helpfulness",
                service.evaluatorArn(helpfulness, REGION));
        assertEquals("arn:aws-cn:bedrock-agentcore:::evaluator/Builtin.Helpfulness",
                service.evaluatorArn(helpfulness, "cn-north-1"));
        assertEquals(BedrockAgentCoreBuiltinEvaluators.all().size(), page.items().size());
    }

    @Test
    void createDerivesTypeAndRegionalArn() {
        Evaluator evaluator = service.createEvaluator(judgeRequest("judge"), REGION);
        assertTrue(evaluator.getEvaluatorId().matches("judge-[a-zA-Z0-9]{10}"), evaluator.getEvaluatorId());
        assertEquals("Custom", evaluator.getEvaluatorType());
        assertEquals("Custom", evaluator.getProvider());
        assertEquals("arn:aws:bedrock-agentcore:us-east-1:000000000000:evaluator/" + evaluator.getEvaluatorId(),
                service.evaluatorArn(evaluator, REGION));
    }

    @Test
    void derivedFromBuiltinTakesItsLevelAndProvider() {
        Evaluator evaluator = service.createEvaluator(json("""
                {"evaluatorName": "derivedOne", "level": "TRACE",
                 "evaluatorConfig": {"derived": {"baseEvaluatorId": "Builtin.GoalSuccessRate",
                   "modelConfig": {"bedrockEvaluatorModelConfig": {"modelId": "m"}}}}}
                """), REGION);
        assertEquals("CustomDerived", evaluator.getEvaluatorType());
        assertEquals("AWS", evaluator.getProvider());
        assertEquals("SESSION", evaluator.getLevel());
    }

    @Test
    void duplicateNameConflicts() {
        service.createEvaluator(judgeRequest("dup"), REGION);
        AwsException e = assertThrows(AwsException.class,
                () -> service.createEvaluator(judgeRequest("dup"), REGION));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void configMustSetExactlyOneUnionMember() {
        AwsException e = assertThrows(AwsException.class, () -> service.createEvaluator(json("""
                {"evaluatorName": "two", "level": "TRACE", "evaluatorConfig": {
                  "codeBased": {"lambdaConfig": {"lambdaArn": "arn:aws:lambda:us-east-1:000000000000:function:f"}},
                  "derived": {"baseEvaluatorId": "Builtin.Helpfulness",
                    "modelConfig": {"bedrockEvaluatorModelConfig": {"modelId": "m"}}}}}
                """), REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void modelledBoundsAreEnforced() {
        assertThrows(AwsException.class, () -> service.createEvaluator(json("""
                {"evaluatorName": "bad-name", "level": "TRACE",
                 "evaluatorConfig": {"codeBased": {"lambdaConfig": {
                   "lambdaArn": "arn:aws:lambda:us-east-1:000000000000:function:f"}}}}
                """), REGION));
        assertThrows(AwsException.class, () -> service.createEvaluator(json("""
                {"evaluatorName": "timeout", "level": "TRACE",
                 "evaluatorConfig": {"codeBased": {"lambdaConfig": {
                   "lambdaArn": "arn:aws:lambda:us-east-1:000000000000:function:f",
                   "lambdaTimeoutInSeconds": 301}}}}
                """), REGION));
        assertThrows(AwsException.class, () -> service.createEvaluator(json("""
                {"evaluatorName": "level", "level": "TURN",
                 "evaluatorConfig": {"codeBased": {"lambdaConfig": {
                   "lambdaArn": "arn:aws:lambda:us-east-1:000000000000:function:f"}}}}
                """), REGION));
        assertThrows(AwsException.class, () -> service.createEvaluator(json("""
                {"evaluatorName": "temp", "level": "TRACE",
                 "evaluatorConfig": {"llmAsAJudge": {"instructions": "x",
                   "ratingScale": {"categorical": [{"definition": "d", "label": "L"}]},
                   "modelConfig": {"bedrockEvaluatorModelConfig": {"modelId": "m",
                     "inferenceConfig": {"temperature": 1.5}}}}}}
                """), REGION));
    }

    @Test
    void enabledOnlineConfigLocksItsEvaluatorUntilDisabled() {
        Evaluator evaluator = service.createEvaluator(judgeRequest("locked"), REGION);
        OnlineEvaluationConfig config = onlineConfig("watch", evaluator.getEvaluatorId(), true);
        assertEquals("ENABLED", config.getExecutionStatus());
        assertTrue(service.isLocked(evaluator, REGION));

        AwsException update = assertThrows(AwsException.class, () -> service.updateEvaluator(
                evaluator.getEvaluatorId(), json("{\"description\": \"v2\"}"), REGION));
        assertEquals("ConflictException", update.getErrorCode());
        AwsException delete = assertThrows(AwsException.class,
                () -> service.deleteEvaluator(evaluator.getEvaluatorId(), REGION));
        assertEquals("ConflictException", delete.getErrorCode());

        service.updateOnlineConfig(config.getOnlineEvaluationConfigId(),
                json("{\"executionStatus\": \"DISABLED\"}"), REGION);
        assertFalse(service.isLocked(evaluator, REGION));
        assertEquals("v2", service.updateEvaluator(evaluator.getEvaluatorId(),
                json("{\"description\": \"v2\"}"), REGION).getDescription());
        assertEquals("DELETING", service.deleteEvaluator(evaluator.getEvaluatorId(), REGION).getStatus());
    }

    @Test
    void builtinsCannotBeUpdatedOrDeleted() {
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.updateEvaluator(
                "Builtin.Helpfulness", json("{\"description\": \"x\"}"), REGION)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.deleteEvaluator("Builtin.Helpfulness", REGION)).getErrorCode());
    }

    @Test
    void onlineConfigRefusesUnknownEvaluatorsAndBadSampling() {
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> onlineConfig("unknown", "Builtin.NoSuchThing", false)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.createOnlineConfig(json("""
                {"onlineEvaluationConfigName": "sampling",
                 "rule": {"samplingConfig": {"samplingPercentage": 0}},
                 "dataSourceConfig": {"cloudWatchLogs": {"serviceNames": ["svc"]}},
                 "evaluationExecutionRoleArn": "%s", "enableOnCreate": false}
                """.formatted(ROLE)), REGION)).getErrorCode());
    }

    @Test
    void onlineConfigArnResolvesBack() {
        OnlineEvaluationConfig config = onlineConfig("resolve", "Builtin.Helpfulness", false);
        String arn = service.onlineConfigArn(config, REGION);
        assertEquals("arn:aws:bedrock-agentcore:us-east-1:000000000000:online-evaluation-config/"
                + config.getOnlineEvaluationConfigId(), arn);
        assertTrue(service.findOnlineConfigByArn(arn, REGION).isPresent());
        assertFalse(service.findOnlineConfigByArn(arn.replace("us-east-1", "us-west-2"), REGION).isPresent());
    }

    @Test
    void tagsRoundTripByArn() {
        Evaluator evaluator = service.createEvaluator(judgeRequest("tagged"), REGION);
        String arn = service.evaluatorArn(evaluator, REGION);
        service.tagByArn(REGION, arn, Map.of("team", "eval"));
        assertEquals("eval", service.getTagsByArn(REGION, arn).get("team"));
        service.untagByArn(REGION, arn, List.of("team"));
        assertTrue(service.getTagsByArn(REGION, arn).isEmpty());
    }
}
