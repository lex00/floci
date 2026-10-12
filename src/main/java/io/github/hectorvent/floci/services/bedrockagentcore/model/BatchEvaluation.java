package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** An AgentCore batch evaluation job. No evaluator runs: the job completes with a canned score. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BatchEvaluation {

    private String batchEvaluationId;
    private String batchEvaluationName;
    private String description;
    private String status;
    private JsonNode evaluators;
    private JsonNode insights;
    private JsonNode dataSourceConfig;
    private JsonNode evaluationMetadata;
    private JsonNode outputConfig;
    private JsonNode evaluationResults;
    private String kmsKeyArn;
    private Instant createdAt;
    private Instant updatedAt;
    private String clientToken;
    private Map<String, String> tags = new HashMap<>();

    public String getBatchEvaluationId() {
        return batchEvaluationId;
    }

    public void setBatchEvaluationId(String batchEvaluationId) {
        this.batchEvaluationId = batchEvaluationId;
    }

    public String getBatchEvaluationName() {
        return batchEvaluationName;
    }

    public void setBatchEvaluationName(String batchEvaluationName) {
        this.batchEvaluationName = batchEvaluationName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public JsonNode getEvaluators() {
        return evaluators;
    }

    public void setEvaluators(JsonNode evaluators) {
        this.evaluators = evaluators;
    }

    public JsonNode getInsights() {
        return insights;
    }

    public void setInsights(JsonNode insights) {
        this.insights = insights;
    }

    public JsonNode getDataSourceConfig() {
        return dataSourceConfig;
    }

    public void setDataSourceConfig(JsonNode dataSourceConfig) {
        this.dataSourceConfig = dataSourceConfig;
    }

    public JsonNode getEvaluationMetadata() {
        return evaluationMetadata;
    }

    public void setEvaluationMetadata(JsonNode evaluationMetadata) {
        this.evaluationMetadata = evaluationMetadata;
    }

    public JsonNode getOutputConfig() {
        return outputConfig;
    }

    public void setOutputConfig(JsonNode outputConfig) {
        this.outputConfig = outputConfig;
    }

    public JsonNode getEvaluationResults() {
        return evaluationResults;
    }

    public void setEvaluationResults(JsonNode evaluationResults) {
        this.evaluationResults = evaluationResults;
    }

    public String getKmsKeyArn() {
        return kmsKeyArn;
    }

    public void setKmsKeyArn(String kmsKeyArn) {
        this.kmsKeyArn = kmsKeyArn;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getClientToken() {
        return clientToken;
    }

    public void setClientToken(String clientToken) {
        this.clientToken = clientToken;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
