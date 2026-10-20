package io.github.hectorvent.floci.services.codeconnections.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A stored CodeConnections connection and its tags. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Connection {

    private String connectionName;
    private String connectionArn;
    private String providerType;
    private String ownerAccountId;
    private String connectionStatus;
    private String hostArn;
    private List<Map<String, String>> tags = new ArrayList<>();

    public Connection() {}

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public String getConnectionArn() { return connectionArn; }
    public void setConnectionArn(String connectionArn) { this.connectionArn = connectionArn; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }

    public String getConnectionStatus() { return connectionStatus; }
    public void setConnectionStatus(String connectionStatus) { this.connectionStatus = connectionStatus; }

    public String getHostArn() { return hostArn; }
    public void setHostArn(String hostArn) { this.hostArn = hostArn; }

    public List<Map<String, String>> getTags() { return tags; }
    public void setTags(List<Map<String, String>> tags) { this.tags = tags; }
}
