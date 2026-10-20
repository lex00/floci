package io.github.hectorvent.floci.services.auditmanager.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A custom Audit Manager assessment framework. */
@RegisterForReflection
public class Framework {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String complianceType;
    private List<FrameworkControlSet> controlSets = new ArrayList<>();
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

    public String getComplianceType() { return complianceType; }
    public void setComplianceType(String complianceType) { this.complianceType = complianceType; }

    public List<FrameworkControlSet> getControlSets() { return controlSets; }
    public void setControlSets(List<FrameworkControlSet> controlSets) { this.controlSets = controlSets; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
