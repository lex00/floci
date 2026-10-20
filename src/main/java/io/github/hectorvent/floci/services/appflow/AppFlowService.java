package io.github.hectorvent.floci.services.appflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.appflow.model.ConnectorProfile;
import io.github.hectorvent.floci.services.appflow.model.Flow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Amazon AppFlow connector profiles and flows. Profiles keep their properties and drop their
 * credentials, which AppFlow never returns. Flows are stored and report run status, but no
 * data moves between connectors.
 */
@ApplicationScoped
public class AppFlowService implements Resettable, TagHandler {

    private static final Logger LOG = Logger.getLogger(AppFlowService.class);
    private static final String SERVICE = "appflow";
    private static final Pattern PROFILE_NAME = Pattern.compile("[\\w/!@#+=.-]+");
    private static final Pattern FLOW_NAME = Pattern.compile("[a-zA-Z0-9][\\w!@#.-]+");
    private static final Pattern TAG_KEY = Pattern.compile("^(?!aws:)[a-zA-Z+-=._:/]+$");
    private static final Set<String> CONNECTION_MODES = Set.of("Public", "Private");
    private static final Set<String> TRIGGER_TYPES = Set.of("Scheduled", "Event", "OnDemand");
    private static final Set<String> CONNECTOR_TYPES = Set.of(
            "Salesforce", "Singular", "Slack", "Redshift", "S3", "Marketo", "Googleanalytics", "Zendesk",
            "Servicenow", "Datadog", "Trendmicro", "Snowflake", "Dynatrace", "Infornexus", "Amplitude",
            "Veeva", "EventBridge", "LookoutMetrics", "Upsolver", "Honeycode", "CustomerProfiles",
            "SAPOData", "CustomConnector", "Pardot");
    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    private final AccountAwareStorageBackend<ConnectorProfile> profiles;
    private final AccountAwareStorageBackend<Flow> flows;

    @Inject
    public AppFlowService(StorageFactory storageFactory) {
        this(storageFactory.create(SERVICE, "appflow-connector-profiles.json",
                        new TypeReference<Map<String, ConnectorProfile>>() {}),
                storageFactory.create(SERVICE, "appflow-flows.json",
                        new TypeReference<Map<String, Flow>>() {}));
    }

    AppFlowService(AccountAwareStorageBackend<ConnectorProfile> profiles,
                   AccountAwareStorageBackend<Flow> flows) {
        this.profiles = profiles;
        this.flows = flows;
    }

    // ── Connector profiles ───────────────────────────────────────────────────

    public ConnectorProfile createConnectorProfile(JsonNode request, String region) {
        String name = requiredText(request, "connectorProfileName");
        validateProfileName(name);
        String connectorType = requiredText(request, "connectorType");
        validateEnum("connectorType", connectorType, CONNECTOR_TYPES);
        String connectionMode = requiredText(request, "connectionMode");
        validateEnum("connectionMode", connectionMode, CONNECTION_MODES);
        JsonNode config = requiredProfileConfig(request);
        if (profiles.get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException",
                    "Connector profile with name " + name + " already exists.", 409);
        }

        long now = Instant.now().toEpochMilli();
        String account = profiles.accountId();
        ConnectorProfile profile = new ConnectorProfile();
        profile.setConnectorProfileName(name);
        profile.setConnectorProfileArn(AwsArnUtils.Arn.of(
                SERVICE, region, account, "connectorprofile/" + name).toString());
        profile.setConnectorType(connectorType);
        profile.setConnectorLabel(optionalText(request, "connectorLabel"));
        profile.setConnectionMode(connectionMode);
        profile.setKmsArn(optionalText(request, "kmsArn"));
        profile.setConnectorProfileProperties(config.get("connectorProfileProperties").deepCopy());
        profile.setCredentialsArn(credentialsArn(region, account, name, now));
        profile.setCreatedAt(now);
        profile.setLastUpdatedAt(now);
        profiles.put(key(region, name), profile);
        LOG.infov("Created AppFlow connector profile: {0}", name);
        return profile;
    }

    public List<ConnectorProfile> describeConnectorProfiles(String region, List<String> names,
                                                            String connectorType, String connectorLabel) {
        if (connectorType != null) {
            validateEnum("connectorType", connectorType, CONNECTOR_TYPES);
        }
        Set<String> requested = names == null ? Set.of() : new LinkedHashSet<>(names);
        return profiles.scan(k -> k.startsWith(region + "::")).stream()
                .filter(p -> requested.isEmpty() || requested.contains(p.getConnectorProfileName()))
                .filter(p -> connectorType == null || connectorType.equals(p.getConnectorType()))
                .filter(p -> connectorLabel == null || connectorLabel.equals(p.getConnectorLabel()))
                .sorted(Comparator.comparing(ConnectorProfile::getConnectorProfileName))
                .toList();
    }

    public ConnectorProfile updateConnectorProfile(JsonNode request, String region) {
        String name = requiredText(request, "connectorProfileName");
        String connectionMode = requiredText(request, "connectionMode");
        validateEnum("connectionMode", connectionMode, CONNECTION_MODES);
        JsonNode config = requiredProfileConfig(request);
        ConnectorProfile profile = profiles.get(key(region, name))
                .orElseThrow(() -> profileNotFound(name));
        profile.setConnectionMode(connectionMode);
        profile.setConnectorProfileProperties(config.get("connectorProfileProperties").deepCopy());
        profile.setLastUpdatedAt(Instant.now().toEpochMilli());
        profiles.put(key(region, name), profile);
        return profile;
    }

    public void deleteConnectorProfile(String name, boolean forceDelete, String region) {
        if (name == null || name.isBlank()) {
            throw validation("connectorProfileName is required.");
        }
        if (profiles.get(key(region, name)).isEmpty()) {
            throw profileNotFound(name);
        }
        if (!forceDelete) {
            for (Flow flow : flows.scan(k -> k.startsWith(region + "::"))) {
                if (usesProfile(flow, name)) {
                    throw new AwsException("ConflictException", "Connector profile " + name
                            + " is in use by flow " + flow.getFlowName() + ".", 409);
                }
            }
        }
        profiles.delete(key(region, name));
        LOG.infov("Deleted AppFlow connector profile: {0}", name);
    }

    // ── Flows ────────────────────────────────────────────────────────────────

    public Flow createFlow(JsonNode request, String region) {
        String name = requiredText(request, "flowName");
        validateFlowName(name);
        JsonNode trigger = requiredTrigger(request);
        JsonNode source = requiredObject(request, "sourceFlowConfig");
        JsonNode destinations = requiredArray(request, "destinationFlowConfigList");
        JsonNode tasks = requiredArray(request, "tasks");
        validateProfileReferences(region, source, destinations);
        if (flows.get(key(region, name)).isPresent()) {
            throw new AwsException("ConflictException", "Flow with name " + name + " already exists.", 409);
        }
        Map<String, String> tags = readTags(request.get("tags"));

        long now = Instant.now().toEpochMilli();
        String account = flows.accountId();
        String caller = AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "iam", account, "root").toString();
        Flow flow = new Flow();
        flow.setFlowName(name);
        flow.setFlowArn(AwsArnUtils.Arn.of(SERVICE, region, account, "flow/" + name).toString());
        flow.setDescription(optionalText(request, "description"));
        flow.setKmsArn(optionalText(request, "kmsArn"));
        flow.setTriggerConfig(trigger.deepCopy());
        flow.setSourceFlowConfig(source.deepCopy());
        flow.setDestinationFlowConfigList(destinations.deepCopy());
        flow.setTasks(tasks.deepCopy());
        flow.setMetadataCatalogConfig(optionalObject(request, "metadataCatalogConfig"));
        flow.setFlowStatus(isOnDemand(trigger) ? "Active" : "Draft");
        flow.setCreatedBy(caller);
        flow.setLastUpdatedBy(caller);
        flow.setCreatedAt(now);
        flow.setLastUpdatedAt(now);
        flow.setSchemaVersion(1);
        flow.setTags(tags);
        flows.put(key(region, name), flow);
        LOG.infov("Created AppFlow flow: {0}", name);
        return flow;
    }

    public Flow describeFlow(String name, String region) {
        if (name == null || name.isBlank()) {
            throw validation("flowName is required.");
        }
        return flows.get(key(region, name)).orElseThrow(() -> flowNotFound(name));
    }

    public List<Flow> listFlows(String region) {
        return flows.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(Flow::getFlowName))
                .toList();
    }

    public Flow updateFlow(JsonNode request, String region) {
        String name = requiredText(request, "flowName");
        JsonNode trigger = requiredTrigger(request);
        JsonNode source = requiredObject(request, "sourceFlowConfig");
        JsonNode destinations = requiredArray(request, "destinationFlowConfigList");
        JsonNode tasks = requiredArray(request, "tasks");
        Flow flow = describeFlow(name, region);
        validateProfileReferences(region, source, destinations);
        boolean mappingChanged = !tasks.equals(flow.getTasks());
        boolean triggerTypeChanged = !trigger.path("triggerType").equals(flow.getTriggerConfig().path("triggerType"));
        if (request.has("description")) {
            flow.setDescription(optionalText(request, "description"));
        }
        flow.setTriggerConfig(trigger.deepCopy());
        flow.setSourceFlowConfig(source.deepCopy());
        flow.setDestinationFlowConfigList(destinations.deepCopy());
        flow.setTasks(tasks.deepCopy());
        if (request.has("metadataCatalogConfig")) {
            flow.setMetadataCatalogConfig(optionalObject(request, "metadataCatalogConfig"));
        }
        if (triggerTypeChanged) {
            flow.setFlowStatus(isOnDemand(trigger) ? "Active" : "Draft");
        }
        if (mappingChanged) {
            flow.setSchemaVersion(flow.getSchemaVersion() + 1);
        }
        flow.setLastUpdatedAt(Instant.now().toEpochMilli());
        flows.put(key(region, name), flow);
        return flow;
    }

    public void deleteFlow(String name, String region) {
        describeFlow(name, region);
        flows.delete(key(region, name));
        LOG.infov("Deleted AppFlow flow: {0}", name);
    }

    /** Starts a flow. An on-demand flow records a successful run; a scheduled or event flow activates. */
    public FlowRun startFlow(String name, String region) {
        Flow flow = describeFlow(name, region);
        String executionId = null;
        if (isOnDemand(flow.getTriggerConfig())) {
            executionId = UUID.randomUUID().toString();
            flow.setLastRunStatus("Successful");
            flow.setLastRunAt(Instant.now().toEpochMilli());
        }
        flow.setFlowStatus("Active");
        flows.put(key(region, name), flow);
        return new FlowRun(flow, executionId);
    }

    /** Stops a scheduled or event flow, which AppFlow reports as suspended. */
    public Flow stopFlow(String name, String region) {
        Flow flow = describeFlow(name, region);
        if (isOnDemand(flow.getTriggerConfig())) {
            throw new AwsException("UnsupportedOperationException",
                    "Flow " + name + " is an on-demand flow and cannot be stopped.", 400);
        }
        if (!"Active".equals(flow.getFlowStatus())) {
            throw new AwsException("ConflictException", "Flow " + name + " is not active.", 409);
        }
        flow.setFlowStatus("Suspended");
        flows.put(key(region, name), flow);
        return flow;
    }

    public record FlowRun(Flow flow, String executionId) {}

    // ── Tagging ──────────────────────────────────────────────────────────────

    @Override
    public String serviceKey() {
        return SERVICE;
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return Map.copyOf(flowForArn(region, arn).getTags());
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        Flow flow = flowForArn(region, arn);
        Map<String, String> validated = readTagMap(tags);
        Map<String, String> updated = new LinkedHashMap<>(flow.getTags());
        updated.putAll(validated);
        if (updated.size() > 50) {
            throw validation("A flow can have at most 50 tags.");
        }
        flow.setTags(updated);
        flows.put(key(region, flow.getFlowName()), flow);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Flow flow = flowForArn(region, arn);
        Map<String, String> updated = new LinkedHashMap<>(flow.getTags());
        if (tagKeys != null) {
            tagKeys.forEach(updated::remove);
        }
        flow.setTags(updated);
        flows.put(key(region, flow.getFlowName()), flow);
    }

    @Override
    public void clear() {
        profiles.clear();
        flows.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Flow flowForArn(String region, String arn) {
        return flows.scan(k -> k.startsWith(region + "::")).stream()
                .filter(flow -> flow.getFlowArn().equals(arn))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private void validateProfileReferences(String region, JsonNode source, JsonNode destinations) {
        requireConnectorType(source, "sourceFlowConfig");
        requireProfileExists(region, source.path("connectorProfileName").asText(null));
        for (JsonNode destination : destinations) {
            requireConnectorType(destination, "destinationFlowConfigList");
            requireProfileExists(region, destination.path("connectorProfileName").asText(null));
        }
    }

    private static void requireConnectorType(JsonNode config, String member) {
        String type = config.path("connectorType").asText(null);
        if (type == null) {
            throw validation(member + ".connectorType is required.");
        }
        validateEnum(member + ".connectorType", type, CONNECTOR_TYPES);
    }

    private void requireProfileExists(String region, String profileName) {
        if (profileName != null && !profileName.isBlank()
                && profiles.get(key(region, profileName)).isEmpty()) {
            throw profileNotFound(profileName);
        }
    }

    private static boolean usesProfile(Flow flow, String profileName) {
        if (profileName.equals(flow.getSourceFlowConfig().path("connectorProfileName").asText(null))) {
            return true;
        }
        for (JsonNode destination : flow.getDestinationFlowConfigList()) {
            if (profileName.equals(destination.path("connectorProfileName").asText(null))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isOnDemand(JsonNode trigger) {
        return "OnDemand".equals(trigger.path("triggerType").asText());
    }

    private static String credentialsArn(String region, String account, String name, long now) {
        StringBuilder suffix = new StringBuilder();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            suffix.append(ALPHANUMERIC.charAt(random.nextInt(ALPHANUMERIC.length())));
        }
        return AwsArnUtils.Arn.of("secretsmanager", region, account,
                "secret:appflow!" + account + "-" + name + "-" + (now / 1000) + "-" + suffix).toString();
    }

    private static JsonNode requiredProfileConfig(JsonNode request) {
        JsonNode config = requiredObject(request, "connectorProfileConfig");
        JsonNode properties = config.get("connectorProfileProperties");
        if (properties == null || !properties.isObject()) {
            throw validation("connectorProfileConfig.connectorProfileProperties is required.");
        }
        return config;
    }

    private static JsonNode requiredTrigger(JsonNode request) {
        JsonNode trigger = requiredObject(request, "triggerConfig");
        String type = trigger.path("triggerType").asText(null);
        if (type == null) {
            throw validation("triggerConfig.triggerType is required.");
        }
        validateEnum("triggerConfig.triggerType", type, TRIGGER_TYPES);
        return trigger;
    }

    private static Map<String, String> readTags(JsonNode node) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (node == null || node.isNull()) {
            return tags;
        }
        if (!node.isObject()) {
            throw validation("tags must be a map of strings.");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            tags.put(field.getKey(), field.getValue().asText());
        }
        return readTagMap(tags);
    }

    private static Map<String, String> readTagMap(Map<String, String> tags) {
        if (tags == null) {
            throw validation("tags is required.");
        }
        for (Map.Entry<String, String> tag : tags.entrySet()) {
            String tagKey = tag.getKey();
            if (tagKey == null || tagKey.isEmpty() || tagKey.length() > 128 || !TAG_KEY.matcher(tagKey).matches()) {
                throw validation("Invalid tag key: " + tagKey);
            }
            if (tag.getValue() == null || tag.getValue().length() > 256) {
                throw validation("Invalid value for tag " + tagKey);
            }
        }
        if (tags.size() > 50) {
            throw validation("A flow can have at most 50 tags.");
        }
        return new LinkedHashMap<>(tags);
    }

    private static void validateProfileName(String name) {
        if (name.length() > 256 || !PROFILE_NAME.matcher(name).matches()) {
            throw validation("1 validation error detected: Value '" + name
                    + "' at 'connectorProfileName' failed to satisfy constraint: "
                    + "Member must satisfy regular expression pattern: [\\w/!@#+=.-]+");
        }
    }

    private static void validateFlowName(String name) {
        if (name.length() > 256 || !FLOW_NAME.matcher(name).matches()) {
            throw validation("1 validation error detected: Value '" + name
                    + "' at 'flowName' failed to satisfy constraint: "
                    + "Member must satisfy regular expression pattern: [a-zA-Z0-9][\\w!@#.-]+");
        }
    }

    private static void validateEnum(String member, String value, Set<String> allowed) {
        if (!allowed.contains(value)) {
            throw validation("1 validation error detected: Value '" + value + "' at '" + member
                    + "' failed to satisfy constraint: Member must satisfy enum value set: "
                    + allowed.stream().sorted().toList());
        }
    }

    private static String requiredText(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw validation(member + " is required.");
        }
        return value.textValue();
    }

    private static String optionalText(JsonNode request, String member) {
        JsonNode value = request.get(member);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static JsonNode requiredObject(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || !value.isObject()) {
            throw validation(member + " is required.");
        }
        return value;
    }

    private static JsonNode optionalObject(JsonNode request, String member) {
        JsonNode value = request.get(member);
        return value == null || value.isNull() ? null : value.deepCopy();
    }

    private static JsonNode requiredArray(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || !value.isArray()) {
            throw validation(member + " is required.");
        }
        return value;
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException profileNotFound(String name) {
        return new AwsException("ResourceNotFoundException",
                "Connector profile " + name + " does not exist.", 404);
    }

    private static AwsException flowNotFound(String name) {
        return new AwsException("ResourceNotFoundException", "Flow " + name + " does not exist.", 404);
    }
}
