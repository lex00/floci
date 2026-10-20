package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An EC2 placement group from {@code CreatePlacementGroup}. Tags live in the EC2 tag store. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlacementGroup {

    private String groupId;
    private String groupName;
    private String groupArn;
    private String strategy;
    private Integer partitionCount;
    private String spreadLevel;
    private String state = "available";
    private String region;

    public PlacementGroup() {}

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupName() { return groupName; }
    public void setGroupName(String groupName) { this.groupName = groupName; }

    public String getGroupArn() { return groupArn; }
    public void setGroupArn(String groupArn) { this.groupArn = groupArn; }

    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }

    public Integer getPartitionCount() { return partitionCount; }
    public void setPartitionCount(Integer partitionCount) { this.partitionCount = partitionCount; }

    public String getSpreadLevel() { return spreadLevel; }
    public void setSpreadLevel(String spreadLevel) { this.spreadLevel = spreadLevel; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
}
