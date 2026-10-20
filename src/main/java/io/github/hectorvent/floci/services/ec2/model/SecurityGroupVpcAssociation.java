package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A security group associated with a VPC other than its own (AssociateSecurityGroupVpc). */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityGroupVpcAssociation {

    private String groupId;
    private String groupOwnerId;
    private String vpcId;
    private String vpcOwnerId;
    private String state = "associated";
    private String region;

    public SecurityGroupVpcAssociation() {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupOwnerId() { return groupOwnerId; }
    public void setGroupOwnerId(String groupOwnerId) { this.groupOwnerId = groupOwnerId; }

    public String getVpcId() { return vpcId; }
    public void setVpcId(String vpcId) { this.vpcId = vpcId; }

    public String getVpcOwnerId() { return vpcOwnerId; }
    public void setVpcOwnerId(String vpcOwnerId) { this.vpcOwnerId = vpcOwnerId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
