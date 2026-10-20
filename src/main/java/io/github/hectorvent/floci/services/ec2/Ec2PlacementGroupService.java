package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.PlacementGroup;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Placement groups: CreatePlacementGroup, DescribePlacementGroups and DeletePlacementGroup.
 * A group is {@code available} as soon as it exists and is gone once deleted.
 */
@ApplicationScoped
public class Ec2PlacementGroupService {

    private static final Set<String> STRATEGIES = Set.of("cluster", "spread", "partition");
    private static final Set<String> SPREAD_LEVELS = Set.of("host", "rack");
    private static final int DEFAULT_PARTITION_COUNT = 2;
    private static final int MAX_PARTITION_COUNT = 7;

    // region|groupName -> group
    private final StorageBackend<String, PlacementGroup> groups;
    private final RegionResolver regionResolver;

    @Inject
    public Ec2PlacementGroupService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("ec2", "ec2-placement-groups.json",
                new TypeReference<Map<String, PlacementGroup>>() {}), regionResolver);
    }

    Ec2PlacementGroupService(StorageBackend<String, PlacementGroup> groups, RegionResolver regionResolver) {
        this.groups = groups;
        this.regionResolver = regionResolver;
    }

    public synchronized PlacementGroup createPlacementGroup(String region, String groupName, String strategy,
                                                            Integer partitionCount, String spreadLevel) {
        if (groupName == null || groupName.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter groupName", 400);
        }
        String effectiveStrategy = strategy == null ? "cluster" : strategy;
        if (!STRATEGIES.contains(effectiveStrategy)) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + strategy + ") for parameter strategy is invalid. Valid values are cluster, spread, partition",
                    400);
        }
        if (partitionCount != null && !"partition".equals(effectiveStrategy)) {
            throw new AwsException("InvalidParameterCombination",
                    "PartitionCount is only valid for the partition strategy", 400);
        }
        if (partitionCount != null && (partitionCount < 1 || partitionCount > MAX_PARTITION_COUNT)) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + partitionCount + ") for parameter partitionCount is invalid. "
                            + "The partition count must be between 1 and " + MAX_PARTITION_COUNT, 400);
        }
        if (spreadLevel != null && (!"spread".equals(effectiveStrategy) || !SPREAD_LEVELS.contains(spreadLevel))) {
            throw new AwsException("InvalidParameterValue",
                    "Value (" + spreadLevel + ") for parameter spreadLevel is invalid", 400);
        }
        String key = key(region, groupName);
        if (groups.get(key).isPresent()) {
            throw new AwsException("InvalidPlacementGroup.Duplicate",
                    "The Placement Group '" + groupName + "' already exists.", 400);
        }
        PlacementGroup group = new PlacementGroup();
        group.setGroupId("pg-" + randomHex(17));
        group.setGroupName(groupName);
        group.setGroupArn(regionResolver.buildArn("ec2", region, "placement-group/" + groupName));
        group.setStrategy(effectiveStrategy);
        if ("partition".equals(effectiveStrategy)) {
            group.setPartitionCount(partitionCount != null ? partitionCount : DEFAULT_PARTITION_COUNT);
        }
        if ("spread".equals(effectiveStrategy)) {
            group.setSpreadLevel(spreadLevel != null ? spreadLevel : "rack");
        }
        group.setRegion(region);
        groups.put(key, group);
        return group;
    }

    public List<PlacementGroup> describePlacementGroups(String region, List<String> groupNames,
                                                        List<String> groupIds) {
        List<PlacementGroup> all = groups.scan(k -> k.startsWith(region + "|")).stream()
                .sorted(Comparator.comparing(PlacementGroup::getGroupName))
                .toList();
        for (String name : groupNames) {
            if (all.stream().noneMatch(g -> g.getGroupName().equals(name))) {
                throw new AwsException("InvalidPlacementGroup.Unknown",
                        "The Placement Group '" + name + "' is unknown.", 400);
            }
        }
        for (String id : groupIds) {
            if (all.stream().noneMatch(g -> g.getGroupId().equals(id))) {
                throw new AwsException("InvalidPlacementGroupId.NotFound",
                        "The Placement Group ID '" + id + "' does not exist.", 400);
            }
        }
        return all.stream()
                .filter(g -> groupNames.isEmpty() || groupNames.contains(g.getGroupName()))
                .filter(g -> groupIds.isEmpty() || groupIds.contains(g.getGroupId()))
                .toList();
    }

    public synchronized PlacementGroup deletePlacementGroup(String region, String groupName) {
        if (groupName == null || groupName.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter groupName", 400);
        }
        String key = key(region, groupName);
        PlacementGroup group = groups.get(key).orElseThrow(() -> new AwsException("InvalidPlacementGroup.Unknown",
                "The Placement Group '" + groupName + "' is unknown.", 400));
        groups.delete(key);
        return group;
    }

    /** Whether a filter value list matches a placement group, tags read from {@code tags}. */
    static boolean matchesFilter(PlacementGroup group, String name, List<String> values, List<Tag> tags) {
        return switch (name) {
            case "group-name" -> values.contains(group.getGroupName());
            case "group-arn" -> values.contains(group.getGroupArn());
            case "state" -> values.contains(group.getState());
            case "strategy" -> values.contains(group.getStrategy());
            case "spread-level" -> values.contains(group.getSpreadLevel());
            case "tag-key" -> tags.stream().anyMatch(t -> values.contains(t.getKey()));
            default -> {
                if (name.startsWith("tag:")) {
                    String tagKey = name.substring(4);
                    yield tags.stream().anyMatch(t -> t.getKey().equals(tagKey) && values.contains(t.getValue()));
                }
                throw new AwsException("InvalidParameterValue", "The filter '" + name + "' is invalid", 400);
            }
        };
    }

    private static String key(String region, String groupName) {
        return region + "|" + groupName;
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < len; i++) {
            sb.append(Character.forDigit(random.nextInt(16), 16));
        }
        return sb.toString();
    }
}
