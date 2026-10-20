package io.github.hectorvent.floci.services.opensearchserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** An OpenSearch Serverless encryption or network policy. */
@RegisterForReflection
public class SecurityPolicy {

    private String type;
    private String name;
    private String region;
    private String policyVersion;
    private String description;
    private String policy;
    private long createdDate;
    private long lastModifiedDate;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPolicy() { return policy; }
    public void setPolicy(String policy) { this.policy = policy; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
