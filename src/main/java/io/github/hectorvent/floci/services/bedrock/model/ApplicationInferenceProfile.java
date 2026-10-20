package io.github.hectorvent.floci.services.bedrock.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RegisterForReflection
public class ApplicationInferenceProfile {

    private String inferenceProfileId;
    private String inferenceProfileName;
    private String description;
    private String inferenceProfileArn;
    private List<String> modelArns;
    private Instant createdAt;
    private Instant updatedAt;
    private Map<String, String> tags;

    public String getInferenceProfileId() { return inferenceProfileId; }
    public void setInferenceProfileId(String inferenceProfileId) { this.inferenceProfileId = inferenceProfileId; }

    public String getInferenceProfileName() { return inferenceProfileName; }
    public void setInferenceProfileName(String inferenceProfileName) {
        this.inferenceProfileName = inferenceProfileName;
    }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getInferenceProfileArn() { return inferenceProfileArn; }
    public void setInferenceProfileArn(String inferenceProfileArn) { this.inferenceProfileArn = inferenceProfileArn; }

    public List<String> getModelArns() { return modelArns; }
    public void setModelArns(List<String> modelArns) { this.modelArns = modelArns; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
