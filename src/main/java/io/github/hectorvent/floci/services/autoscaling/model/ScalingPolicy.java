package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScalingPolicy {

    private String policyName;
    private String policyArn;
    private String autoScalingGroupName;
    private String policyType;          // SimpleScaling | StepScaling | TargetTrackingScaling | PredictiveScaling
    private String adjustmentType;      // ChangeInCapacity | ExactCapacity | PercentChangeInCapacity
    private int scalingAdjustment;
    private Integer cooldown;
    private Boolean enabled;
    private String metricAggregationType;
    private Integer minAdjustmentMagnitude;
    private Integer estimatedInstanceWarmup;
    private TargetTrackingConfiguration targetTrackingConfiguration;
    private List<StepAdjustment> stepAdjustments = new ArrayList<>();
    private PredictiveScalingConfiguration predictiveScalingConfiguration;
    private String region;

    public ScalingPolicy() {}

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String v) { this.policyName = v; }

    public String getPolicyArn() { return policyArn; }
    public void setPolicyArn(String v) { this.policyArn = v; }

    public String getAutoScalingGroupName() { return autoScalingGroupName; }
    public void setAutoScalingGroupName(String v) { this.autoScalingGroupName = v; }

    public String getPolicyType() { return policyType; }
    public void setPolicyType(String v) { this.policyType = v; }

    public String getAdjustmentType() { return adjustmentType; }
    public void setAdjustmentType(String v) { this.adjustmentType = v; }

    public int getScalingAdjustment() { return scalingAdjustment; }
    public void setScalingAdjustment(int v) { this.scalingAdjustment = v; }

    /** Set only on a SimpleScaling policy, the one type that uses it. */
    public Integer getCooldown() { return cooldown; }
    public void setCooldown(Integer v) { this.cooldown = v; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean v) { this.enabled = v; }

    public Integer getMinAdjustmentMagnitude() { return minAdjustmentMagnitude; }
    public void setMinAdjustmentMagnitude(Integer v) { this.minAdjustmentMagnitude = v; }

    public String getMetricAggregationType() { return metricAggregationType; }
    public void setMetricAggregationType(String v) { this.metricAggregationType = v; }

    public Integer getEstimatedInstanceWarmup() { return estimatedInstanceWarmup; }
    public void setEstimatedInstanceWarmup(Integer v) { this.estimatedInstanceWarmup = v; }

    public TargetTrackingConfiguration getTargetTrackingConfiguration() { return targetTrackingConfiguration; }
    public void setTargetTrackingConfiguration(TargetTrackingConfiguration v) { this.targetTrackingConfiguration = v; }

    public List<StepAdjustment> getStepAdjustments() { return stepAdjustments; }
    public void setStepAdjustments(List<StepAdjustment> v) {
        this.stepAdjustments = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }

    public PredictiveScalingConfiguration getPredictiveScalingConfiguration() { return predictiveScalingConfiguration; }
    public void setPredictiveScalingConfiguration(PredictiveScalingConfiguration v) {
        this.predictiveScalingConfiguration = v;
    }

    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetTrackingConfiguration {
        private PredefinedMetricSpecification predefinedMetricSpecification;
        private CustomizedMetricSpecification customizedMetricSpecification;
        private Double targetValue;
        private Boolean disableScaleIn;

        public TargetTrackingConfiguration() {}

        public PredefinedMetricSpecification getPredefinedMetricSpecification() { return predefinedMetricSpecification; }
        public void setPredefinedMetricSpecification(PredefinedMetricSpecification v) { this.predefinedMetricSpecification = v; }

        public CustomizedMetricSpecification getCustomizedMetricSpecification() { return customizedMetricSpecification; }
        public void setCustomizedMetricSpecification(CustomizedMetricSpecification v) { this.customizedMetricSpecification = v; }

        public Double getTargetValue() { return targetValue; }
        public void setTargetValue(Double v) { this.targetValue = v; }

        public Boolean getDisableScaleIn() { return disableScaleIn; }
        public void setDisableScaleIn(Boolean v) { this.disableScaleIn = v; }
    }

    /** CustomizedMetricSpecification, the alternative to naming a predefined metric. */
    @RegisterForReflection
    public static class CustomizedMetricSpecification {
        private String metricName;
        private String namespace;
        private String statistic;
        private String unit;
        private Integer period;
        private List<MetricDimension> dimensions = new ArrayList<>();
        private List<TargetTrackingMetricDataQuery> metrics = new ArrayList<>();

        public CustomizedMetricSpecification() {}

        public String getMetricName() { return metricName; }
        public void setMetricName(String v) { this.metricName = v; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String v) { this.namespace = v; }
        public String getStatistic() { return statistic; }
        public void setStatistic(String v) { this.statistic = v; }
        public String getUnit() { return unit; }
        public void setUnit(String v) { this.unit = v; }
        public Integer getPeriod() { return period; }
        public void setPeriod(Integer v) { this.period = v; }
        public List<MetricDimension> getDimensions() { return dimensions; }
        public void setDimensions(List<MetricDimension> v) { this.dimensions = v; }
        public List<TargetTrackingMetricDataQuery> getMetrics() { return metrics; }
        public void setMetrics(List<TargetTrackingMetricDataQuery> v) { this.metrics = v; }
    }

    /**
     * One entry of CustomizedMetricSpecification.Metrics, the metric data query form. It carries
     * either an Expression for metric math or a MetricStat naming a raw metric, never both. The
     * model requires Id.
     */
    @RegisterForReflection
    public static class TargetTrackingMetricDataQuery {
        private String id;
        private String expression;
        private String label;
        private Integer period;
        private Boolean returnData;
        private TargetTrackingMetricStat metricStat;

        public TargetTrackingMetricDataQuery() {}

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }
        public String getExpression() { return expression; }
        public void setExpression(String v) { this.expression = v; }
        public String getLabel() { return label; }
        public void setLabel(String v) { this.label = v; }
        /** The query's own Period, which the model keeps separate from MetricStat.Period. */
        public Integer getPeriod() { return period; }
        public void setPeriod(Integer v) { this.period = v; }
        public Boolean getReturnData() { return returnData; }
        public void setReturnData(Boolean v) { this.returnData = v; }
        public TargetTrackingMetricStat getMetricStat() { return metricStat; }
        public void setMetricStat(TargetTrackingMetricStat v) { this.metricStat = v; }
    }

    /** MetricStat, which the model requires to carry both a Metric and a Stat. */
    @RegisterForReflection
    public static class TargetTrackingMetricStat {
        private String stat;
        private String unit;
        private Integer period;
        private Metric metric;

        public TargetTrackingMetricStat() {}

        public String getStat() { return stat; }
        public void setStat(String v) { this.stat = v; }
        public String getUnit() { return unit; }
        public void setUnit(String v) { this.unit = v; }
        public Integer getPeriod() { return period; }
        public void setPeriod(Integer v) { this.period = v; }
        public Metric getMetric() { return metric; }
        public void setMetric(Metric v) { this.metric = v; }
    }

    /** The raw metric a MetricStat names. The model requires Namespace and MetricName. */
    @RegisterForReflection
    public static class Metric {
        private String namespace;
        private String metricName;
        private List<MetricDimension> dimensions = new ArrayList<>();

        public Metric() {}

        public String getNamespace() { return namespace; }
        public void setNamespace(String v) { this.namespace = v; }
        public String getMetricName() { return metricName; }
        public void setMetricName(String v) { this.metricName = v; }
        public List<MetricDimension> getDimensions() { return dimensions; }
        public void setDimensions(List<MetricDimension> v) { this.dimensions = v; }
    }

    /** A dimension of a customized metric. The model requires both members. */
    @RegisterForReflection
    public static class MetricDimension {
        private String name;
        private String value;

        public MetricDimension() {}

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getValue() { return value; }
        public void setValue(String v) { this.value = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredefinedMetricSpecification {
        private String predefinedMetricType;
        private String resourceLabel;

        public PredefinedMetricSpecification() {}

        public String getPredefinedMetricType() { return predefinedMetricType; }
        public void setPredefinedMetricType(String v) { this.predefinedMetricType = v; }

        public String getResourceLabel() { return resourceLabel; }
        public void setResourceLabel(String v) { this.resourceLabel = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class StepAdjustment {
        private Double metricIntervalLowerBound;
        private Double metricIntervalUpperBound;
        private int scalingAdjustment;

        public StepAdjustment() {}

        public Double getMetricIntervalLowerBound() { return metricIntervalLowerBound; }
        public void setMetricIntervalLowerBound(Double v) { this.metricIntervalLowerBound = v; }

        public Double getMetricIntervalUpperBound() { return metricIntervalUpperBound; }
        public void setMetricIntervalUpperBound(Double v) { this.metricIntervalUpperBound = v; }

        public int getScalingAdjustment() { return scalingAdjustment; }
        public void setScalingAdjustment(int v) { this.scalingAdjustment = v; }
    }

    /** PredictiveScalingConfiguration, covering the predefined metric specifications. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredictiveScalingConfiguration {
        private List<PredictiveScalingMetricSpecification> metricSpecifications = new ArrayList<>();
        private String mode;
        private Integer schedulingBufferTime;
        private String maxCapacityBreachBehavior;
        private Integer maxCapacityBuffer;

        public PredictiveScalingConfiguration() {}

        public List<PredictiveScalingMetricSpecification> getMetricSpecifications() { return metricSpecifications; }
        public void setMetricSpecifications(List<PredictiveScalingMetricSpecification> v) {
            this.metricSpecifications = v != null ? new ArrayList<>(v) : new ArrayList<>();
        }

        public String getMode() { return mode; }
        public void setMode(String v) { this.mode = v; }

        public Integer getSchedulingBufferTime() { return schedulingBufferTime; }
        public void setSchedulingBufferTime(Integer v) { this.schedulingBufferTime = v; }

        public String getMaxCapacityBreachBehavior() { return maxCapacityBreachBehavior; }
        public void setMaxCapacityBreachBehavior(String v) { this.maxCapacityBreachBehavior = v; }

        public Integer getMaxCapacityBuffer() { return maxCapacityBuffer; }
        public void setMaxCapacityBuffer(Integer v) { this.maxCapacityBuffer = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PredictiveScalingMetricSpecification {
        private Double targetValue;
        private PredefinedMetricSpecification predefinedMetricPairSpecification;
        private PredefinedMetricSpecification predefinedScalingMetricSpecification;
        private PredefinedMetricSpecification predefinedLoadMetricSpecification;

        public PredictiveScalingMetricSpecification() {}

        public Double getTargetValue() { return targetValue; }
        public void setTargetValue(Double v) { this.targetValue = v; }

        public PredefinedMetricSpecification getPredefinedMetricPairSpecification() {
            return predefinedMetricPairSpecification;
        }
        public void setPredefinedMetricPairSpecification(PredefinedMetricSpecification v) {
            this.predefinedMetricPairSpecification = v;
        }

        public PredefinedMetricSpecification getPredefinedScalingMetricSpecification() {
            return predefinedScalingMetricSpecification;
        }
        public void setPredefinedScalingMetricSpecification(PredefinedMetricSpecification v) {
            this.predefinedScalingMetricSpecification = v;
        }

        public PredefinedMetricSpecification getPredefinedLoadMetricSpecification() {
            return predefinedLoadMetricSpecification;
        }
        public void setPredefinedLoadMetricSpecification(PredefinedMetricSpecification v) {
            this.predefinedLoadMetricSpecification = v;
        }
    }
}
