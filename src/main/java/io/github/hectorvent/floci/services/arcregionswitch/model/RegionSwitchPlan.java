package io.github.hectorvent.floci.services.arcregionswitch.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An ARC Region switch plan. */
@RegisterForReflection
public class RegionSwitchPlan {

    private String arn;
    private String name;
    private String owner;
    private String description;
    private String executionRole;
    private String recoveryApproach;
    private String primaryRegion;
    private List<String> regions = new ArrayList<>();
    private JsonNode workflows;
    private Integer recoveryTimeObjectiveMinutes;
    private JsonNode associatedAlarms;
    private JsonNode triggers;
    private JsonNode reportConfiguration;
    private Boolean serviceQuotaChecksEnabled;
    private int version;
    private long updatedAtMillis;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExecutionRole() { return executionRole; }
    public void setExecutionRole(String executionRole) { this.executionRole = executionRole; }

    public String getRecoveryApproach() { return recoveryApproach; }
    public void setRecoveryApproach(String recoveryApproach) { this.recoveryApproach = recoveryApproach; }

    public String getPrimaryRegion() { return primaryRegion; }
    public void setPrimaryRegion(String primaryRegion) { this.primaryRegion = primaryRegion; }

    public List<String> getRegions() { return regions; }
    public void setRegions(List<String> regions) { this.regions = regions; }

    public JsonNode getWorkflows() { return workflows; }
    public void setWorkflows(JsonNode workflows) { this.workflows = workflows; }

    public Integer getRecoveryTimeObjectiveMinutes() { return recoveryTimeObjectiveMinutes; }
    public void setRecoveryTimeObjectiveMinutes(Integer recoveryTimeObjectiveMinutes) { this.recoveryTimeObjectiveMinutes = recoveryTimeObjectiveMinutes; }

    public JsonNode getAssociatedAlarms() { return associatedAlarms; }
    public void setAssociatedAlarms(JsonNode associatedAlarms) { this.associatedAlarms = associatedAlarms; }

    public JsonNode getTriggers() { return triggers; }
    public void setTriggers(JsonNode triggers) { this.triggers = triggers; }

    public JsonNode getReportConfiguration() { return reportConfiguration; }
    public void setReportConfiguration(JsonNode reportConfiguration) { this.reportConfiguration = reportConfiguration; }

    public Boolean getServiceQuotaChecksEnabled() { return serviceQuotaChecksEnabled; }
    public void setServiceQuotaChecksEnabled(Boolean serviceQuotaChecksEnabled) { this.serviceQuotaChecksEnabled = serviceQuotaChecksEnabled; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public long getUpdatedAtMillis() { return updatedAtMillis; }
    public void setUpdatedAtMillis(long updatedAtMillis) { this.updatedAtMillis = updatedAtMillis; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
