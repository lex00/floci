package io.github.hectorvent.floci.services.cloudwatch.alarmmuterules.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A CloudWatch alarm mute rule with its schedule and the alarms it mutes. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AlarmMuteRule {

    private String name;
    private String arn;
    private String description;
    private String expression;
    private String duration;
    private String timezone;
    private List<String> alarmNames = new ArrayList<>();
    private Long startDate;
    private Long expireDate;
    private long lastUpdatedTimestamp;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getExpression() { return expression; }
    public void setExpression(String expression) { this.expression = expression; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }

    public List<String> getAlarmNames() { return alarmNames; }
    public void setAlarmNames(List<String> alarmNames) { this.alarmNames = alarmNames; }

    public Long getStartDate() { return startDate; }
    public void setStartDate(Long startDate) { this.startDate = startDate; }

    public Long getExpireDate() { return expireDate; }
    public void setExpireDate(Long expireDate) { this.expireDate = expireDate; }

    public long getLastUpdatedTimestamp() { return lastUpdatedTimestamp; }
    public void setLastUpdatedTimestamp(long lastUpdatedTimestamp) { this.lastUpdatedTimestamp = lastUpdatedTimestamp; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
