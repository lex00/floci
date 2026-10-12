package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.CloudWatchLogsSource;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluateResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluationInput;
import software.amazon.awssdk.services.bedrockagentcore.model.GetBatchEvaluationResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Bedrock AgentCore Evaluations")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreEvaluationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/eval-role";

    private static BedrockAgentCoreControlClient control;
    private static BedrockAgentCoreClient data;
    private static String suffix;
    private static String evaluatorId;
    private static String configId;
    private static String batchId;

    @BeforeAll
    static void setup() {
        control = TestFixtures.bedrockAgentCoreControlClient();
        data = TestFixtures.bedrockAgentCoreClient();
        suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @AfterAll
    static void cleanup() {
        if (control != null) {
            try {
                control.deleteOnlineEvaluationConfig(b -> b.onlineEvaluationConfigId(configId));
            } catch (Exception ignored) {
            }
            try {
                control.deleteEvaluator(b -> b.evaluatorId(evaluatorId));
            } catch (Exception ignored) {
            }
            control.close();
        }
        if (data != null) {
            data.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("ListEvaluators includes the AWS built-in evaluators")
    void listIncludesBuiltins() {
        List<EvaluatorSummary> evaluators = control.listEvaluators(b -> b.maxResults(100)).evaluators();
        assertThat(evaluators).anySatisfy(e -> {
            assertThat(e.evaluatorId()).isEqualTo("Builtin.Helpfulness");
            assertThat(e.evaluatorTypeAsString()).isEqualTo("Builtin");
            assertThat(e.levelAsString()).isEqualTo("TRACE");
            assertThat(e.evaluatorArn()).endsWith(":bedrock-agentcore:::evaluator/Builtin.Helpfulness");
        });
    }

    @Test
    @Order(2)
    @DisplayName("CreateEvaluator and GetEvaluator round-trip an LLM-as-a-judge configuration")
    void createAndGetEvaluator() {
        CreateEvaluatorResponse created = control.createEvaluator(b -> b
                .evaluatorName("judge" + suffix)
                .level("TRACE")
                .evaluatorConfig(EvaluatorConfig.fromLlmAsAJudge(LlmAsAJudgeEvaluatorConfig.builder()
                        .instructions("Rate the answer")
                        .ratingScale(RatingScale.fromCategorical(List.of(CategoricalScaleDefinition.builder()
                                .definition("acceptable").label("OK").build())))
                        .modelConfig(EvaluatorModelConfig.fromBedrockEvaluatorModelConfig(
                                BedrockEvaluatorModelConfig.builder().modelId("some-model").build()))
                        .build())));
        evaluatorId = created.evaluatorId();
        assertThat(evaluatorId).startsWith("judge" + suffix + "-");
        assertThat(created.statusAsString()).isEqualTo("ACTIVE");
        assertThat(created.createdAt()).isNotNull();

        GetEvaluatorResponse fetched = control.getEvaluator(b -> b.evaluatorId(evaluatorId));
        assertThat(fetched.evaluatorTypeAsString()).isEqualTo("Custom");
        assertThat(fetched.evaluatorConfig().llmAsAJudge().ratingScale().categorical())
                .extracting(CategoricalScaleDefinition::label).containsExactly("OK");
    }

    @Test
    @Order(3)
    @DisplayName("An enabled online evaluation config locks its evaluator")
    void onlineConfigLocksEvaluator() {
        CreateOnlineEvaluationConfigResponse created = control.createOnlineEvaluationConfig(b -> b
                .onlineEvaluationConfigName("online" + suffix)
                .rule(Rule.builder().samplingConfig(SamplingConfig.builder().samplingPercentage(50.0).build()).build())
                .dataSourceConfig(DataSourceConfig.fromCloudWatchLogs(CloudWatchLogsInputConfig.builder()
                        .serviceNames("my.agent").build()))
                .evaluators(EvaluatorReference.fromEvaluatorId(evaluatorId))
                .evaluationExecutionRoleArn(ROLE)
                .enableOnCreate(true));
        configId = created.onlineEvaluationConfigId();
        assertThat(created.executionStatusAsString()).isEqualTo("ENABLED");

        assertThat(control.getEvaluator(b -> b.evaluatorId(evaluatorId)).lockedForModification()).isTrue();
        assertThatThrownBy(() -> control.deleteEvaluator(b -> b.evaluatorId(evaluatorId)))
                .isInstanceOf(ConflictException.class);
        assertThat(control.listOnlineEvaluationConfigs(b -> { }).onlineEvaluationConfigs())
                .extracting(OnlineEvaluationConfigSummary::onlineEvaluationConfigId).contains(configId);
    }

    @Test
    @Order(4)
    @DisplayName("Evaluate returns a canned score for each trace")
    void evaluate() {
        Document span = Document.mapBuilder()
                .putString("traceId", "0123456789abcdef0123456789abcdef")
                .putString("spanId", "0123456789abcdef")
                .putDocument("attributes", Document.mapBuilder().putString("session.id", "session-1").build())
                .build();
        EvaluateResponse response = data.evaluate(b -> b.evaluatorId("Builtin.Helpfulness")
                .evaluationInput(EvaluationInput.fromSessionSpans(List.of(span))));
        assertThat(response.evaluationResults()).hasSize(1);
        assertThat(response.evaluationResults().get(0).value()).isNotNull();
        assertThat(response.evaluationResults().get(0).context().spanContext().sessionId()).isEqualTo("session-1");
    }

    @Test
    @Order(5)
    @DisplayName("A batch evaluation reaches COMPLETED with per-evaluator scores")
    void batchEvaluation() {
        batchId = data.startBatchEvaluation(b -> b.batchEvaluationName("batch" + suffix)
                .evaluators(e -> e.evaluatorId("Builtin.Helpfulness"))
                .dataSourceConfig(d -> d.cloudWatchLogs(CloudWatchLogsSource.builder()
                        .serviceNames("my.agent").build()))).batchEvaluationId();

        GetBatchEvaluationResponse got = data.getBatchEvaluation(b -> b.batchEvaluationId(batchId));
        assertThat(got.statusAsString()).isEqualTo("COMPLETED");
        assertThat(got.createdAt()).isNotNull();
        assertThat(got.evaluationResults().evaluatorSummaries()).hasSize(1);
        assertThat(data.listBatchEvaluations(b -> { }).batchEvaluations())
                .anySatisfy(s -> assertThat(s.batchEvaluationId()).isEqualTo(batchId));

        assertThat(data.deleteBatchEvaluation(b -> b.batchEvaluationId(batchId)).statusAsString())
                .isEqualTo("DELETING");
    }
}
