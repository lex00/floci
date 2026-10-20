package io.github.hectorvent.floci.services.iot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

/** An AWS IoT role alias for the credentials provider. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IotRoleAlias {

    private String roleAlias;
    private String roleAliasArn;
    private String roleArn;
    private String owner;
    private int credentialDurationSeconds;
    private Instant creationDate;
    private Instant lastModifiedDate;
    private Map<String, String> tags = new TreeMap<>();

    public String getRoleAlias() { return roleAlias; }
    public void setRoleAlias(String roleAlias) { this.roleAlias = roleAlias; }

    public String getRoleAliasArn() { return roleAliasArn; }
    public void setRoleAliasArn(String roleAliasArn) { this.roleAliasArn = roleAliasArn; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }

    public int getCredentialDurationSeconds() { return credentialDurationSeconds; }
    public void setCredentialDurationSeconds(int credentialDurationSeconds) { this.credentialDurationSeconds = credentialDurationSeconds; }

    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }

    public Instant getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(Instant lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
