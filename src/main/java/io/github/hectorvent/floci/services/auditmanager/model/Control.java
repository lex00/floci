package io.github.hectorvent.floci.services.auditmanager.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A custom Audit Manager control. */
@RegisterForReflection
public class Control {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String testingInformation;
    private String actionPlanTitle;
    private String actionPlanInstructions;
    private List<Map<String, Object>> controlMappingSources = new ArrayList<>();
    private Instant createdAt;
    private Instant lastUpdatedAt;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getTestingInformation() { return testingInformation; }
    public void setTestingInformation(String testingInformation) { this.testingInformation = testingInformation; }

    public String getActionPlanTitle() { return actionPlanTitle; }
    public void setActionPlanTitle(String actionPlanTitle) { this.actionPlanTitle = actionPlanTitle; }

    public String getActionPlanInstructions() { return actionPlanInstructions; }
    public void setActionPlanInstructions(String actionPlanInstructions) {
        this.actionPlanInstructions = actionPlanInstructions;
    }

    public List<Map<String, Object>> getControlMappingSources() { return controlMappingSources; }
    public void setControlMappingSources(List<Map<String, Object>> controlMappingSources) {
        this.controlMappingSources = controlMappingSources;
    }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
