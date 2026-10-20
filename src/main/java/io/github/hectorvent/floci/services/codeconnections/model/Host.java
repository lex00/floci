package io.github.hectorvent.floci.services.codeconnections.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A stored CodeConnections host for an installed provider such as GitHub Enterprise Server. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Host {

    private String name;
    private String hostArn;
    private String providerType;
    private String providerEndpoint;
    private Map<String, Object> vpcConfiguration;
    private String status;
    private List<Map<String, String>> tags = new ArrayList<>();

    public Host() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getHostArn() { return hostArn; }
    public void setHostArn(String hostArn) { this.hostArn = hostArn; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public String getProviderEndpoint() { return providerEndpoint; }
    public void setProviderEndpoint(String providerEndpoint) { this.providerEndpoint = providerEndpoint; }

    public Map<String, Object> getVpcConfiguration() { return vpcConfiguration; }
    public void setVpcConfiguration(Map<String, Object> vpcConfiguration) {
        this.vpcConfiguration = vpcConfiguration;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<Map<String, String>> getTags() { return tags; }
    public void setTags(List<Map<String, String>> tags) { this.tags = tags; }
}
