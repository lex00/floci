package io.github.hectorvent.floci.services.arcregionswitch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.arcregionswitch.model.RegionSwitchPlan;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ARC Region switch plans. A plan ARN carries no region, so every region of an account reads the
 * same plans.
 */
@ApplicationScoped
public class ArcRegionSwitchService implements Resettable {

    private static final Pattern PLAN_NAME = Pattern.compile("[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,30}[a-zA-Z0-9])?");
    private static final Pattern ROLE_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":iam::[0-9]{12}:role/.+");
    private static final Pattern REGION = Pattern.compile("[a-z]{2}-[a-z-]+-\\d+");
    private static final Set<String> RECOVERY_APPROACHES = Set.of("activeActive", "activePassive");
    private static final Set<String> TARGET_ACTIONS = Set.of("activate", "deactivate", "postRecovery");
    private static final String SUFFIX_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The plan settings that CreatePlan and UpdatePlan both carry. */
    public record PlanSettings(String description, JsonNode workflows, String executionRole,
                               Integer recoveryTimeObjectiveMinutes, JsonNode associatedAlarms,
                               JsonNode triggers, JsonNode reportConfiguration,
                               Boolean serviceQuotaChecksEnabled) {}

    private final StorageBackend<String, RegionSwitchPlan> plans;
    private final RegionResolver regionResolver;

    @Inject
    public ArcRegionSwitchService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("arcregionswitch", "arc-region-switch-plans.json",
                new TypeReference<Map<String, RegionSwitchPlan>>() {}), regionResolver);
    }

    ArcRegionSwitchService(StorageBackend<String, RegionSwitchPlan> plans, RegionResolver regionResolver) {
        this.plans = plans;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        plans.clear();
    }

    public synchronized RegionSwitchPlan createPlan(String name, List<String> regions, String recoveryApproach,
                                                    String primaryRegion, PlanSettings settings,
                                                    Map<String, String> tags) {
        if (name == null || name.length() > 32 || !PLAN_NAME.matcher(name).matches()) {
            throw illegalArgument("The plan name must be 1 to 32 letters, digits or hyphens, "
                    + "starting and ending with a letter or digit.");
        }
        if (regions == null || regions.size() != 2) {
            throw illegalArgument("A plan must name exactly two Regions.");
        }
        for (String region : regions) {
            if (!REGION.matcher(region).matches()) {
                throw illegalArgument("The Region " + region + " is not valid.");
            }
        }
        if (regions.get(0).equals(regions.get(1))) {
            throw illegalArgument("A plan must name two different Regions.");
        }
        if (recoveryApproach == null || !RECOVERY_APPROACHES.contains(recoveryApproach)) {
            throw illegalArgument("recoveryApproach must be one of activeActive or activePassive.");
        }
        if (primaryRegion != null && !regions.contains(primaryRegion)) {
            throw illegalArgument("primaryRegion must be one of the plan's Regions.");
        }
        validate(settings);
        String suffix = randomSuffix();
        RegionSwitchPlan plan = new RegionSwitchPlan();
        plan.setArn(regionResolver.buildGlobalArn("arc-region-switch", "plan/" + name + ":" + suffix));
        plan.setName(name);
        plan.setOwner(regionResolver.getAccountId());
        plan.setRegions(List.copyOf(regions));
        plan.setRecoveryApproach(recoveryApproach);
        plan.setPrimaryRegion(primaryRegion);
        apply(plan, settings);
        plan.setVersion(1);
        plan.setUpdatedAtMillis(System.currentTimeMillis());
        if (tags != null) {
            plan.getTags().putAll(tags);
        }
        plans.put(plan.getArn(), plan);
        return plan;
    }

    public RegionSwitchPlan getPlan(String arn) {
        if (arn == null || arn.isBlank()) {
            throw illegalArgument("arn is required.");
        }
        return plans.get(arn).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The plan " + arn + " was not found.", 404));
    }

    public synchronized RegionSwitchPlan updatePlan(String arn, PlanSettings settings) {
        RegionSwitchPlan plan = getPlan(arn);
        validate(settings);
        apply(plan, settings);
        plan.setVersion(plan.getVersion() + 1);
        plan.setUpdatedAtMillis(System.currentTimeMillis());
        plans.put(arn, plan);
        return plan;
    }

    public synchronized void deletePlan(String arn) {
        getPlan(arn);
        plans.delete(arn);
    }

    public List<RegionSwitchPlan> listPlans() {
        return plans.scan(k -> true).stream()
                .sorted(Comparator.comparing(RegionSwitchPlan::getArn))
                .toList();
    }

    public synchronized void tagResource(String arn, Map<String, String> tags) {
        RegionSwitchPlan plan = getPlan(arn);
        plan.getTags().putAll(tags);
        plans.put(arn, plan);
    }

    public synchronized void untagResource(String arn, List<String> keys) {
        RegionSwitchPlan plan = getPlan(arn);
        keys.forEach(plan.getTags()::remove);
        plans.put(arn, plan);
    }

    private static void validate(PlanSettings settings) {
        if (settings.executionRole() == null || !ROLE_ARN.matcher(settings.executionRole()).matches()) {
            throw illegalArgument("executionRole must be an IAM role ARN.");
        }
        if (settings.workflows() == null || !settings.workflows().isArray()) {
            throw illegalArgument("workflows is required.");
        }
        for (JsonNode workflow : settings.workflows()) {
            if (!TARGET_ACTIONS.contains(workflow.path("workflowTargetAction").asText())) {
                throw illegalArgument("workflowTargetAction must be one of activate, deactivate or postRecovery.");
            }
        }
        Integer rto = settings.recoveryTimeObjectiveMinutes();
        if (rto != null && (rto < 1 || rto > 10080)) {
            throw illegalArgument("recoveryTimeObjectiveMinutes must be between 1 and 10080.");
        }
    }

    private static void apply(RegionSwitchPlan plan, PlanSettings settings) {
        plan.setDescription(settings.description());
        plan.setWorkflows(settings.workflows());
        plan.setExecutionRole(settings.executionRole());
        plan.setRecoveryTimeObjectiveMinutes(settings.recoveryTimeObjectiveMinutes());
        plan.setAssociatedAlarms(settings.associatedAlarms());
        plan.setTriggers(settings.triggers());
        plan.setReportConfiguration(settings.reportConfiguration());
        plan.setServiceQuotaChecksEnabled(settings.serviceQuotaChecksEnabled());
    }

    private static String randomSuffix() {
        StringBuilder suffix = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            suffix.append(SUFFIX_ALPHABET.charAt(RANDOM.nextInt(SUFFIX_ALPHABET.length())));
        }
        return suffix.toString();
    }

    private static AwsException illegalArgument(String message) {
        return new AwsException("IllegalArgumentException", message, 400);
    }
}
