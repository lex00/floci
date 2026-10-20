package io.github.hectorvent.floci.services.cloudwatch.alarmmuterules;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cloudwatch.alarmmuterules.model.AlarmMuteRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CloudWatchAlarmMuteRulesServiceTest {

    private static final String REGION = "us-east-1";

    private final CloudWatchAlarmMuteRulesService service = new CloudWatchAlarmMuteRulesService(
            new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"));

    private static AlarmMuteRule rule(String name, String expression) {
        AlarmMuteRule rule = new AlarmMuteRule();
        rule.setName(name);
        rule.setExpression(expression);
        rule.setDuration("PT1H");
        return rule;
    }

    @Test
    void oneTimeStatusFollowsTheWindow() {
        AlarmMuteRule rule = rule("window", "at(2030-06-01T10:00)");
        assertEquals("SCHEDULED", CloudWatchAlarmMuteRulesService.status(rule, Instant.parse("2030-06-01T09:59:00Z")));
        assertEquals("ACTIVE", CloudWatchAlarmMuteRulesService.status(rule, Instant.parse("2030-06-01T10:30:00Z")));
        assertEquals("EXPIRED", CloudWatchAlarmMuteRulesService.status(rule, Instant.parse("2030-06-01T11:00:00Z")));
        assertEquals(CloudWatchAlarmMuteRulesService.ONE_TIME, CloudWatchAlarmMuteRulesService.muteType(rule));
    }

    @Test
    void startAndExpireDatesBoundARecurringRule() {
        AlarmMuteRule rule = rule("bounded", "cron(0 0 * * ? *)");
        rule.setStartDate(Instant.parse("2030-01-01T00:00:00Z").getEpochSecond());
        rule.setExpireDate(Instant.parse("2030-02-01T00:00:00Z").getEpochSecond());
        assertEquals("SCHEDULED", CloudWatchAlarmMuteRulesService.status(rule, Instant.parse("2029-12-31T00:00:00Z")));
        assertEquals("ACTIVE", CloudWatchAlarmMuteRulesService.status(rule, Instant.parse("2030-01-15T00:00:00Z")));
        assertEquals("EXPIRED", CloudWatchAlarmMuteRulesService.status(rule, Instant.parse("2030-02-01T00:00:00Z")));
    }

    @Test
    void updateKeepsTagsAndReplacesConfiguration() {
        AlarmMuteRule created = rule("r", "cron(0 0 * * ? *)");
        created.getTags().put("owner", "ops");
        service.putAlarmMuteRule(created, REGION);

        AlarmMuteRule update = rule("r", "cron(0 1 * * ? *)");
        update.getTags().put("owner", "other");
        service.putAlarmMuteRule(update, REGION);

        AlarmMuteRule stored = service.getAlarmMuteRule("r", REGION);
        assertEquals("cron(0 1 * * ? *)", stored.getExpression());
        assertEquals(Map.of("owner", "ops"), service.listTagsForResource(stored.getArn(), REGION));
    }

    @Test
    void listFiltersByAlarmName() {
        AlarmMuteRule a = rule("a", "cron(0 0 * * ? *)");
        a.getAlarmNames().add("cpu");
        service.putAlarmMuteRule(a, REGION);
        service.putAlarmMuteRule(rule("b", "cron(0 0 * * ? *)"), REGION);

        assertEquals(List.of("a"), service.listAlarmMuteRules("cpu", List.of(), null, null, REGION)
                .items().stream().map(AlarmMuteRule::getName).toList());
        assertEquals(2, service.listAlarmMuteRules(null, List.of(), null, null, REGION).items().size());
    }

    @Test
    void rejectsBadDurationAndMissingRule() {
        AlarmMuteRule bad = rule("bad", "cron(0 0 * * ? *)");
        bad.setDuration("one hour");
        assertEquals("InvalidParameterValue",
                assertThrows(AwsException.class, () -> service.putAlarmMuteRule(bad, REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException",
                assertThrows(AwsException.class, () -> service.getAlarmMuteRule("missing", REGION)).getErrorCode());
    }
}
