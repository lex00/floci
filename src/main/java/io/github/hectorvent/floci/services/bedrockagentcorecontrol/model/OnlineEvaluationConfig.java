package io.github.hectorvent.floci.services.bedrockagentcorecontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** An AgentCore online evaluation configuration. Stored configuration only: no live traffic is sampled. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OnlineEvaluationConfig {

    private String onlineEvaluationConfigId;
    private String onlineEvaluationConfigName;
    private String description;
    private JsonNode rule;
    private JsonNode dataSourceConfig;
    private JsonNode evaluators;
    private JsonNode insights;
    private JsonNode clusteringConfig;
    private JsonNode outputConfig;
    private String evaluationExecutionRoleArn;
    private String status;
    private String executionStatus;
    private Instant createdAt;
    private Instant updatedAt;
    private String clientToken;
    private Map<String, String> tags = new HashMap<>();

    public String getOnlineEvaluationConfigId() {
        return onlineEvaluationConfigId;
    }

    public void setOnlineEvaluationConfigId(String onlineEvaluationConfigId) {
        this.onlineEvaluationConfigId = onlineEvaluationConfigId;
    }

    public String getOnlineEvaluationConfigName() {
        return onlineEvaluationConfigName;
    }

    public void setOnlineEvaluationConfigName(String onlineEvaluationConfigName) {
        this.onlineEvaluationConfigName = onlineEvaluationConfigName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public JsonNode getRule() {
        return rule;
    }

    public void setRule(JsonNode rule) {
        this.rule = rule;
    }

    public JsonNode getDataSourceConfig() {
        return dataSourceConfig;
    }

    public void setDataSourceConfig(JsonNode dataSourceConfig) {
        this.dataSourceConfig = dataSourceConfig;
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

    public JsonNode getClusteringConfig() {
        return clusteringConfig;
    }

    public void setClusteringConfig(JsonNode clusteringConfig) {
        this.clusteringConfig = clusteringConfig;
    }

    public JsonNode getOutputConfig() {
        return outputConfig;
    }

    public void setOutputConfig(JsonNode outputConfig) {
        this.outputConfig = outputConfig;
    }

    public String getEvaluationExecutionRoleArn() {
        return evaluationExecutionRoleArn;
    }

    public void setEvaluationExecutionRoleArn(String evaluationExecutionRoleArn) {
        this.evaluationExecutionRoleArn = evaluationExecutionRoleArn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(String executionStatus) {
        this.executionStatus = executionStatus;
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
