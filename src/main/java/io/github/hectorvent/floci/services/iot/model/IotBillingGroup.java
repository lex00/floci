package io.github.hectorvent.floci.services.iot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** An AWS IoT billing group. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IotBillingGroup {

    private String billingGroupName;
    private String billingGroupId;
    private String billingGroupArn;
    private String description;
    private long version;
    private Instant creationDate;
    private List<String> thingNames = new ArrayList<>();
    private Map<String, String> tags = new TreeMap<>();

    public String getBillingGroupName() { return billingGroupName; }
    public void setBillingGroupName(String billingGroupName) { this.billingGroupName = billingGroupName; }

    public String getBillingGroupId() { return billingGroupId; }
    public void setBillingGroupId(String billingGroupId) { this.billingGroupId = billingGroupId; }

    public String getBillingGroupArn() { return billingGroupArn; }
    public void setBillingGroupArn(String billingGroupArn) { this.billingGroupArn = billingGroupArn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public List<String> getThingNames() { return thingNames; }
    public void setThingNames(List<String> thingNames) { this.thingNames = thingNames; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
