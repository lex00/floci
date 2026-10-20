package io.github.hectorvent.floci.services.iot.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** An AWS IoT topic rule destination: an HTTP URL, a VPC or an InfluxDB endpoint. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IotTopicRuleDestination {

    private String arn;
    private String status;
    private String statusReason;
    private Instant createdAt;
    private Instant lastUpdatedAt;
    private String confirmationUrl;
    private String confirmationToken;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> securityGroups = new ArrayList<>();
    private String vpcId;
    private String roleArn;
    private Map<String, String> influxDb;

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getStatusReason() { return statusReason; }
    public void setStatusReason(String statusReason) { this.statusReason = statusReason; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(Instant lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    public String getConfirmationUrl() { return confirmationUrl; }
    public void setConfirmationUrl(String confirmationUrl) { this.confirmationUrl = confirmationUrl; }

    public String getConfirmationToken() { return confirmationToken; }
    public void setConfirmationToken(String confirmationToken) { this.confirmationToken = confirmationToken; }

    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> subnetIds) { this.subnetIds = subnetIds; }

    public List<String> getSecurityGroups() { return securityGroups; }
    public void setSecurityGroups(List<String> securityGroups) { this.securityGroups = securityGroups; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public Map<String, String> getInfluxDb() { return influxDb; }
    public void setInfluxDb(Map<String, String> influxDb) { this.influxDb = influxDb; }

    public IotTopicRuleDestination copy() {
        IotTopicRuleDestination copy = new IotTopicRuleDestination();
        copy.arn = arn;
        copy.status = status;
        copy.statusReason = statusReason;
        copy.createdAt = createdAt;
        copy.lastUpdatedAt = lastUpdatedAt;
        copy.confirmationUrl = confirmationUrl;
        copy.confirmationToken = confirmationToken;
        copy.subnetIds = new ArrayList<>(subnetIds);
        copy.securityGroups = new ArrayList<>(securityGroups);
        copy.vpcId = vpcId;
        copy.roleArn = roleArn;
        copy.influxDb = influxDb;
        return copy;
    }
}
