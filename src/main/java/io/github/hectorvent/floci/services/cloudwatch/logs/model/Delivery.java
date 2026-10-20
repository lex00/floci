package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A CloudWatch Logs delivery that connects a delivery source to a delivery destination. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Delivery {

    private String id;
    private String arn;
    private String deliverySourceName;
    private String deliveryDestinationArn;
    private String deliveryDestinationType;
    private List<String> recordFields = new ArrayList<>();
    private String fieldDelimiter;
    private String suffixPath;
    private Boolean enableHiveCompatiblePath;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDeliverySourceName() { return deliverySourceName; }
    public void setDeliverySourceName(String deliverySourceName) { this.deliverySourceName = deliverySourceName; }

    public String getDeliveryDestinationArn() { return deliveryDestinationArn; }
    public void setDeliveryDestinationArn(String deliveryDestinationArn) { this.deliveryDestinationArn = deliveryDestinationArn; }

    public String getDeliveryDestinationType() { return deliveryDestinationType; }
    public void setDeliveryDestinationType(String deliveryDestinationType) { this.deliveryDestinationType = deliveryDestinationType; }

    public List<String> getRecordFields() { return recordFields; }
    public void setRecordFields(List<String> recordFields) { this.recordFields = recordFields; }

    public String getFieldDelimiter() { return fieldDelimiter; }
    public void setFieldDelimiter(String fieldDelimiter) { this.fieldDelimiter = fieldDelimiter; }

    public String getSuffixPath() { return suffixPath; }
    public void setSuffixPath(String suffixPath) { this.suffixPath = suffixPath; }

    public Boolean getEnableHiveCompatiblePath() { return enableHiveCompatiblePath; }
    public void setEnableHiveCompatiblePath(Boolean enableHiveCompatiblePath) { this.enableHiveCompatiblePath = enableHiveCompatiblePath; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
