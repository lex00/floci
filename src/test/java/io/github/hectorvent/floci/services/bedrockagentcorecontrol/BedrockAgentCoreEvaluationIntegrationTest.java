package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockagentcore.BedrockAgentCoreClient;
import software.amazon.awssdk.services.bedrockagentcore.model.BatchEvaluationSummary;
import software.amazon.awssdk.services.bedrockagentcore.model.CloudWatchFilterConfig;
import software.amazon.awssdk.services.bedrockagentcore.model.CloudWatchLogsSource;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluateResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluationInput;
import software.amazon.awssdk.services.bedrockagentcore.model.EvaluationResultContent;
import software.amazon.awssdk.services.bedrockagentcore.model.GetBatchEvaluationResponse;
import software.amazon.awssdk.services.bedrockagentcore.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockagentcore.model.StartBatchEvaluationResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.BedrockAgentCoreControlClient;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.BedrockEvaluatorModelConfig;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CloudWatchLogsInputConfig;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ConflictException;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateEvaluatorResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.CreateOnlineEvaluationConfigResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.DataSourceConfig;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EvaluatorConfig;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EvaluatorModelConfig;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EvaluatorReference;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.EvaluatorSummary;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetEvaluatorResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.GetOnlineEvaluationConfigResponse;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.LlmAsAJudgeEvaluatorConfig;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.NumericalScaleDefinition;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.RatingScale;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.Rule;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.SamplingConfig;
import software.amazon.awssdk.services.bedrockagentcorecontrol.model.ValidationException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AgentCore Evaluations through the AWS SDK for Java v2, both planes. The SDK's own deserializers
 * are the check on wire shapes: epoch-second timestamps on the control plane, ISO-8601 on the
 * batch evaluation members, and the union members.
 */
@QuarkusTest
class BedrockAgentCoreEvaluationIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/eval-role";
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    @TestHTTPResource
    URI endpoint;

    private BedrockAgentCoreControlClient control() {
        return BedrockAgentCoreControlClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private BedrockAgentCoreClient data() {
        return BedrockAgentCoreClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private static EvaluatorConfig judge() {
        return EvaluatorConfig.fromLlmAsAJudge(LlmAsAJudgeEvaluatorConfig.builder()
                .instructions("Rate the answer")
                .ratingScale(RatingScale.fromNumerical(List.of(NumericalScaleDefinition.builder()
                        .definition("good").value(1.0).label("Good").build())))
                .modelConfig(EvaluatorModelConfig.fromBedrockEvaluatorModelConfig(
                        BedrockEvaluatorModelConfig.builder().modelId("some-model").build()))
                .build());
    }

    @Test
    void listEvaluatorsIncludesTheAwsBuiltins() {
        try (BedrockAgentCoreControlClient client = control()) {
            List<EvaluatorSummary> evaluators = client.listEvaluators(b -> b.maxResults(100)).evaluators();
            EvaluatorSummary helpfulness = evaluators.stream()
                    .filter(e -> "Builtin.Helpfulness".equals(e.evaluatorId())).findFirst().orElseThrow();
            assertEquals("Builtin", helpfulness.evaluatorTypeAsString());
            assertEquals("AWS", helpfulness.providerAsString());
            assertEquals("TRACE", helpfulness.levelAsString());
            assertEquals("ACTIVE", helpfulness.statusAsString());
            assertEquals("arn:aws:bedrock-agentcore:::evaluator/Builtin.Helpfulness", helpfulness.evaluatorArn());
            assertNotNull(helpfulness.createdAt());

            GetEvaluatorResponse builtin = client.getEvaluator(b -> b.evaluatorId("Builtin.GoalSuccessRate"));
            assertEquals("SESSION", builtin.levelAsString());
        }
    }

    @Test
    void evaluatorCrudAndOnlineConfigLock() {
        try (BedrockAgentCoreControlClient client = control()) {
            String name = unique("judge");
            CreateEvaluatorResponse created = client.createEvaluator(b -> b.evaluatorName(name)
                    .evaluatorConfig(judge()).level("TRACE").tags(Map.of("team", "eval")));
            String id = created.evaluatorId();
            assertTrue(id.startsWith(name + "-"), id);
            assertEquals("arn:aws:bedrock-agentcore:us-east-1:000000000000:evaluator/" + id, created.evaluatorArn());
            assertNotNull(created.createdAt());

            GetEvaluatorResponse fetched = client.getEvaluator(b -> b.evaluatorId(id));
            assertEquals("Custom", fetched.evaluatorTypeAsString());
            assertEquals("Rate the answer", fetched.evaluatorConfig().llmAsAJudge().instructions());
            assertEquals("Good", fetched.evaluatorConfig().llmAsAJudge().ratingScale().numerical().get(0).label());
            assertFalse(fetched.lockedForModification());
            GetEvaluatorResponse metadataOnly = client.getEvaluator(b -> b.evaluatorId(id)
                    .includedData("METADATA_ONLY"));
            assertEquals(null, metadataOnly.evaluatorConfig().llmAsAJudge().instructions());
            assertEquals(Map.of("team", "eval"),
                    client.listTagsForResource(b -> b.resourceArn(created.evaluatorArn())).tags());

            String configName = unique("online");
            CreateOnlineEvaluationConfigResponse config = client.createOnlineEvaluationConfig(b -> b
                    .onlineEvaluationConfigName(configName)
                    .rule(Rule.builder().samplingConfig(SamplingConfig.builder().samplingPercentage(25.0).build())
                            .build())
                    .dataSourceConfig(DataSourceConfig.fromCloudWatchLogs(CloudWatchLogsInputConfig.builder()
                            .serviceNames("my.agent").logGroupNames("/aws/bedrock-agentcore/runtimes/x").build()))
                    .evaluators(EvaluatorReference.fromEvaluatorId(id),
                            EvaluatorReference.fromEvaluatorId("Builtin.Helpfulness"))
                    .evaluationExecutionRoleArn(ROLE)
                    .enableOnCreate(true));
            assertEquals("ENABLED", config.executionStatusAsString());
            String configId = config.onlineEvaluationConfigId();

            assertTrue(client.getEvaluator(b -> b.evaluatorId(id)).lockedForModification());
            assertThrows(ConflictException.class,
                    () -> client.updateEvaluator(b -> b.evaluatorId(id).description("v2")));
            assertThrows(ConflictException.class, () -> client.deleteEvaluator(b -> b.evaluatorId(id)));

            GetOnlineEvaluationConfigResponse got = client.getOnlineEvaluationConfig(
                    b -> b.onlineEvaluationConfigId(configId));
            assertEquals(configName, got.onlineEvaluationConfigName());
            assertEquals(25.0, got.rule().samplingConfig().samplingPercentage());
            assertEquals(2, got.evaluators().size());
            assertEquals(ROLE, got.evaluationExecutionRoleArn());
            assertTrue(client.listOnlineEvaluationConfigs(b -> { }).onlineEvaluationConfigs().stream()
                    .anyMatch(c -> configId.equals(c.onlineEvaluationConfigId())));

            assertEquals("DISABLED", client.updateOnlineEvaluationConfig(b -> b.onlineEvaluationConfigId(configId)
                    .executionStatus("DISABLED")).executionStatusAsString());
            assertEquals(id, client.updateEvaluator(b -> b.evaluatorId(id).description("v2")).evaluatorId());
            assertEquals("v2", client.getEvaluator(b -> b.evaluatorId(id)).description());

            assertEquals("DELETING", client.deleteOnlineEvaluationConfig(
                    b -> b.onlineEvaluationConfigId(configId)).statusAsString());
            assertEquals("DELETING", client.deleteEvaluator(b -> b.evaluatorId(id)).statusAsString());
        }
    }

    @Test
    void createEvaluatorValidatesTheModel() {
        try (BedrockAgentCoreControlClient client = control()) {
            assertThrows(ValidationException.class, () -> client.createEvaluator(b -> b
                    .evaluatorName("has-hyphen").evaluatorConfig(judge()).level("TRACE")));
            assertThrows(ValidationException.class, () -> client.createEvaluator(b -> b
                    .evaluatorName(unique("nolevel")).evaluatorConfig(judge())));
            assertThrows(ValidationException.class,
                    () -> client.updateEvaluator(b -> b.evaluatorId("Builtin.Helpfulness").description("x")));
        }
    }

    @Test
    void evaluateReturnsACannedScorePerTrace() {
        Document span = Document.mapBuilder()
                .putString("traceId", TRACE)
                .putString("spanId", "0123456789abcdef")
                .putDocument("attributes", Document.mapBuilder().putString("session.id", "session-1").build())
                .build();
        try (BedrockAgentCoreClient client = data()) {
            EvaluateResponse response = client.evaluate(b -> b.evaluatorId("Builtin.Helpfulness")
                    .evaluationInput(EvaluationInput.fromSessionSpans(List.of(span))));
            assertEquals(1, response.evaluationResults().size());
            EvaluationResultContent result = response.evaluationResults().get(0);
            assertEquals("Builtin.Helpfulness", result.evaluatorName());
            assertEquals(1.0, result.value());
            assertEquals("session-1", result.context().spanContext().sessionId());
            assertEquals(TRACE, result.context().spanContext().traceId());

            assertThrows(ResourceNotFoundException.class,
                    () -> client.evaluate(b -> b.evaluatorId("missing-abcdefghij")
                            .evaluationInput(EvaluationInput.fromSessionSpans(List.of(span)))));
        }
    }

    @Test
    void batchEvaluationReachesATerminalStatus() {
        try (BedrockAgentCoreClient client = data()) {
            String name = unique("batch");
            StartBatchEvaluationResponse started = client.startBatchEvaluation(b -> b.batchEvaluationName(name)
                    .evaluators(e -> e.evaluatorId("Builtin.Helpfulness"))
                    .dataSourceConfig(d -> d.cloudWatchLogs(CloudWatchLogsSource.builder()
                            .serviceNames("my.agent")
                            .filterConfig(CloudWatchFilterConfig.builder().sessionIds("s1", "s2").build())
                            .build())));
            assertEquals("IN_PROGRESS", started.statusAsString());
            assertNotNull(started.createdAt());
            String id = started.batchEvaluationId();
            assertEquals("arn:aws:bedrock-agentcore:us-east-1:000000000000:batch-evaluate/" + id,
                    started.batchEvaluationArn());

            GetBatchEvaluationResponse got = client.getBatchEvaluation(b -> b.batchEvaluationId(id));
            assertEquals("COMPLETED", got.statusAsString());
            assertEquals(2, got.evaluationResults().totalNumberOfSessions());
            assertEquals(1.0, got.evaluationResults().evaluatorSummaries().get(0).statistics().averageScore());
            assertNotNull(got.updatedAt());

            List<BatchEvaluationSummary> listed = client.listBatchEvaluations(b -> { }).batchEvaluations();
            assertTrue(listed.stream().anyMatch(s -> id.equals(s.batchEvaluationId())));

            // Qualified: both SDK packages define ConflictException, and the control plane's is imported.
            assertThrows(software.amazon.awssdk.services.bedrockagentcore.model.ConflictException.class,
                    () -> client.stopBatchEvaluation(b -> b.batchEvaluationId(id)));
            assertEquals("DELETING", client.deleteBatchEvaluation(b -> b.batchEvaluationId(id)).statusAsString());
        }
    }

    @Test
    void stopBeforeCompletionStopsTheBatch() {
        try (BedrockAgentCoreClient client = data()) {
            String id = client.startBatchEvaluation(b -> b.batchEvaluationName(unique("stopme"))
                    .dataSourceConfig(d -> d.cloudWatchLogs(CloudWatchLogsSource.builder()
                            .serviceNames("my.agent").build()))).batchEvaluationId();
            assertEquals("STOPPED", client.stopBatchEvaluation(b -> b.batchEvaluationId(id)).statusAsString());
            assertEquals("STOPPED", client.getBatchEvaluation(b -> b.batchEvaluationId(id)).statusAsString());
        }
    }
}
