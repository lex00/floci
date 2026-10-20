package io.github.hectorvent.floci.services.opensearchserverless.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An OpenSearch Serverless security configuration. */
@RegisterForReflection
public class SecurityConfig {

    private String id;
    private String type;
    private String name;
    private String region;
    private String configVersion;
    private String description;
    private JsonNode samlOptions;
    private JsonNode iamIdentityCenterOptions;
    private JsonNode iamFederationOptions;
    private long createdDate;
    private long lastModifiedDate;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getConfigVersion() { return configVersion; }
    public void setConfigVersion(String configVersion) { this.configVersion = configVersion; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public JsonNode getSamlOptions() { return samlOptions; }
    public void setSamlOptions(JsonNode samlOptions) { this.samlOptions = samlOptions; }

    public JsonNode getIamIdentityCenterOptions() { return iamIdentityCenterOptions; }
    public void setIamIdentityCenterOptions(JsonNode iamIdentityCenterOptions) { this.iamIdentityCenterOptions = iamIdentityCenterOptions; }

    public JsonNode getIamFederationOptions() { return iamFederationOptions; }
    public void setIamFederationOptions(JsonNode iamFederationOptions) { this.iamFederationOptions = iamFederationOptions; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }
}
