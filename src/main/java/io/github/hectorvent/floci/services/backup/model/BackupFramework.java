package io.github.hectorvent.floci.services.backup.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/** An AWS Backup Audit Manager framework. Its controls are kept as the request sent them. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class BackupFramework {

    private String frameworkName;
    private String frameworkArn;
    private String frameworkDescription;
    private JsonNode frameworkControls;
    private long creationTime;
    private String idempotencyToken;
    private String region;
    private Map<String, String> tags = new HashMap<>();

    public BackupFramework() {}

    public String getFrameworkName() { return frameworkName; }
    public void setFrameworkName(String v) { this.frameworkName = v; }
    public String getFrameworkArn() { return frameworkArn; }
    public void setFrameworkArn(String v) { this.frameworkArn = v; }
    public String getFrameworkDescription() { return frameworkDescription; }
    public void setFrameworkDescription(String v) { this.frameworkDescription = v; }
    public JsonNode getFrameworkControls() { return frameworkControls; }
    public void setFrameworkControls(JsonNode v) { this.frameworkControls = v; }
    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long v) { this.creationTime = v; }
    public String getIdempotencyToken() { return idempotencyToken; }
    public void setIdempotencyToken(String v) { this.idempotencyToken = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v != null ? new HashMap<>(v) : new HashMap<>(); }
}
