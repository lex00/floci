package io.github.hectorvent.floci.services.cloudwatch.alarmmuterules;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.alarmmuterules.model.AlarmMuteRule;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CloudWatch alarm mute rules. Floci stores each rule and reports its status from the schedule
 * and the start and expire dates. Alarm actions are not suppressed while a rule is active.
 */
@ApplicationScoped
public class CloudWatchAlarmMuteRulesService {

    public static final String ONE_TIME = "ONE_TIME";
    public static final String RECURRING = "RECURRING";

    /** ListAlarmMuteRules documents MaxRecords as 1 to 100. */
    private static final int MAX_PAGE = 100;
    private static final int MAX_TARGETS = 100;

    private final StorageBackend<String, AlarmMuteRule> ruleStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchAlarmMuteRulesService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("cloudwatchmetrics", "cwalarmmuterules.json",
                new TypeReference<Map<String, AlarmMuteRule>>() {}), regionResolver);
    }

    public CloudWatchAlarmMuteRulesService(StorageBackend<String, AlarmMuteRule> ruleStore,
                                           RegionResolver regionResolver) {
        this.ruleStore = ruleStore;
        this.regionResolver = regionResolver;
    }

    /**
     * Creates the rule, or replaces the configuration of an existing rule with the same name. An
     * update keeps the rule's tags, since Tags apply to a new rule only.
     */
    public AlarmMuteRule putAlarmMuteRule(AlarmMuteRule rule, String region) {
        requireParameter(rule.getName(), "Name");
        requireParameter(rule.getExpression(), "Rule.Schedule.Expression");
        requireParameter(rule.getDuration(), "Rule.Schedule.Duration");
        if (!rule.getExpression().startsWith("cron(") && !rule.getExpression().startsWith("at(")) {
            throw invalid("Rule.Schedule.Expression must be a cron(...) or at(...) expression.");
        }
        parseDuration(rule.getDuration());
        if (rule.getTimezone() != null) {
            try {
                ZoneId.of(rule.getTimezone());
            } catch (RuntimeException e) {
                throw invalid("Rule.Schedule.Timezone " + rule.getTimezone() + " is not a valid time zone.");
            }
        }
        if (rule.getAlarmNames().size() > MAX_TARGETS) {
            throw invalid("MuteTargets.AlarmNames can name at most " + MAX_TARGETS + " alarms.");
        }
        if (rule.getStartDate() != null && rule.getExpireDate() != null
                && rule.getExpireDate() <= rule.getStartDate()) {
            throw invalid("ExpireDate must be later than StartDate.");
        }
        String key = key(region, rule.getName());
        Optional<AlarmMuteRule> existing = ruleStore.get(key);
        rule.setArn(regionResolver.buildArn("cloudwatch", region, "alarm-mute-rule:" + rule.getName()));
        existing.ifPresent(previous -> rule.setTags(previous.getTags()));
        rule.setLastUpdatedTimestamp(Instant.now().getEpochSecond());
        ruleStore.put(key, rule);
        return rule;
    }

    public AlarmMuteRule getAlarmMuteRule(String name, String region) {
        requireParameter(name, "AlarmMuteRuleName");
        return ruleStore.get(key(region, name)).orElseThrow(() -> notFound(name));
    }

    /** Deleting a rule that does not exist succeeds; the operation declares no errors. */
    public void deleteAlarmMuteRule(String name, String region) {
        requireParameter(name, "AlarmMuteRuleName");
        ruleStore.delete(key(region, name));
    }

    public PaginatedResult<AlarmMuteRule> listAlarmMuteRules(String alarmName, List<String> statuses,
                                                            Integer maxRecords, String nextToken,
                                                            String region) {
        Instant now = Instant.now();
        List<AlarmMuteRule> rules = ruleStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(r -> alarmName == null || r.getAlarmNames().contains(alarmName))
                .filter(r -> statuses == null || statuses.isEmpty() || statuses.contains(status(r, now)))
                .sorted(Comparator.comparing(AlarmMuteRule::getName))
                .toList();
        return Pagination.paginate(rules, AlarmMuteRule::getName, maxRecords, nextToken, MAX_PAGE,
                "InvalidNextToken");
    }

    public static String muteType(AlarmMuteRule rule) {
        return rule.getExpression() != null && rule.getExpression().startsWith("at(") ? ONE_TIME : RECURRING;
    }

    /**
     * SCHEDULED before the rule's window opens, EXPIRED after it closes, otherwise ACTIVE. A
     * one-time window runs from the {@code at(...)} time for the rule's duration. Start and
     * expire dates bound every rule.
     */
    public static String status(AlarmMuteRule rule, Instant now) {
        if (rule.getExpireDate() != null && !now.isBefore(Instant.ofEpochSecond(rule.getExpireDate()))) {
            return "EXPIRED";
        }
        if (rule.getStartDate() != null && now.isBefore(Instant.ofEpochSecond(rule.getStartDate()))) {
            return "SCHEDULED";
        }
        if (ONE_TIME.equals(muteType(rule))) {
            Instant at = oneTimeStart(rule);
            if (at != null) {
                if (now.isBefore(at)) {
                    return "SCHEDULED";
                }
                if (!now.isBefore(at.plus(parseDuration(rule.getDuration())))) {
                    return "EXPIRED";
                }
            }
        }
        return "ACTIVE";
    }

    public static boolean isAlarmMuteRuleArn(String resourceArn) {
        return resourceArn != null && resourceArn.contains(":alarm-mute-rule:");
    }

    public Map<String, String> listTagsForResource(String resourceArn, String region) {
        return requireRule(resourceArn, region).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        AlarmMuteRule rule = requireRule(resourceArn, region);
        rule.getTags().putAll(tags);
        ruleStore.put(key(region, rule.getName()), rule);
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        AlarmMuteRule rule = requireRule(resourceArn, region);
        tagKeys.forEach(rule.getTags()::remove);
        ruleStore.put(key(region, rule.getName()), rule);
    }

    private AlarmMuteRule requireRule(String resourceArn, String region) {
        return ruleStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(r -> resourceArn.equals(r.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No CloudWatch resource matches the ARN " + resourceArn + ".", 404));
    }

    private static Instant oneTimeStart(AlarmMuteRule rule) {
        String expression = rule.getExpression();
        if (!expression.endsWith(")")) {
            return null;
        }
        String value = expression.substring("at(".length(), expression.length() - 1);
        try {
            ZoneId zone = rule.getTimezone() != null ? ZoneId.of(rule.getTimezone()) : ZoneOffset.UTC;
            return LocalDateTime.parse(value).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Duration parseDuration(String duration) {
        try {
            Duration parsed = Duration.parse(duration);
            if (parsed.isNegative() || parsed.isZero()) {
                throw invalid("Rule.Schedule.Duration must be a positive ISO 8601 duration.");
            }
            return parsed;
        } catch (DateTimeParseException e) {
            throw invalid("Rule.Schedule.Duration " + duration + " is not an ISO 8601 duration.");
        }
    }

    private static void requireParameter(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AwsException("MissingParameter", "The parameter " + name + " is required.", 400);
        }
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterValue", message, 400);
    }

    private static AwsException notFound(String name) {
        return new AwsException("ResourceNotFoundException",
                "Alarm mute rule " + name + " does not exist.", 404);
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }
}
