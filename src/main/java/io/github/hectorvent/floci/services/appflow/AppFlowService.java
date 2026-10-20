package io.github.hectorvent.floci.services.appflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.ArrayList;
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
 * Amazon AppFlow connector profiles, flows and custom connectors. Profiles keep their properties
 * and drop their credentials, which AppFlow never returns. Flows are stored and record their
 * runs, but no data moves between connectors.
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

    private static final Pattern CONNECTOR_LABEL = Pattern.compile("[a-zA-Z0-9][\\w!@#.-]{0,255}");
    private static final Set<String> DESTINATION_TYPES = Set.of("Salesforce", "Redshift", "S3", "Zendesk",
            "Snowflake", "EventBridge", "LookoutMetrics", "Upsolver", "Honeycode", "CustomerProfiles", "SAPOData",
            "Marketo");
    private static final Set<String> EVENT_SOURCES = Set.of("Salesforce", "Pardot");
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final AccountAwareStorageBackend<ConnectorProfile> profiles;
    private final AccountAwareStorageBackend<Flow> flows;
    private final AccountAwareStorageBackend<ObjectNode> connectors;
    private final AccountAwareStorageBackend<ObjectNode> executions;

    @Inject
    public AppFlowService(StorageFactory storageFactory) {
        this(storageFactory.create(SERVICE, "appflow-connector-profiles.json",
                        new TypeReference<Map<String, ConnectorProfile>>() {}),
                storageFactory.create(SERVICE, "appflow-flows.json",
                        new TypeReference<Map<String, Flow>>() {}),
                storageFactory.create(SERVICE, "appflow-connectors.json",
                        new TypeReference<Map<String, ObjectNode>>() {}),
                storageFactory.create(SERVICE, "appflow-flow-executions.json",
                        new TypeReference<Map<String, ObjectNode>>() {}));
    }

    AppFlowService(AccountAwareStorageBackend<ConnectorProfile> profiles,
                   AccountAwareStorageBackend<Flow> flows, AccountAwareStorageBackend<ObjectNode> connectors,
                   AccountAwareStorageBackend<ObjectNode> executions) {
        this.profiles = profiles;
        this.flows = flows;
        this.connectors = connectors;
        this.executions = executions;
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
        executions.delete(key(region, name));
        LOG.infov("Deleted AppFlow flow: {0}", name);
    }

    /** Starts a flow. An on-demand flow records a successful run; a scheduled or event flow activates. */
    public FlowRun startFlow(String name, String region) {
        Flow flow = describeFlow(name, region);
        String executionId = null;
        if (isOnDemand(flow.getTriggerConfig())) {
            executionId = UUID.randomUUID().toString();
            long now = Instant.now().toEpochMilli();
            flow.setLastRunStatus("Successful");
            flow.setLastRunAt(now);
            recordExecution(region, name, executionId, now);
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

    // ── Flow executions ──────────────────────────────────────────────────────

    /** Execution records of a flow, newest first. */
    public List<JsonNode> describeFlowExecutionRecords(String name, String region) {
        describeFlow(name, region);
        List<JsonNode> records = new ArrayList<>();
        executions.get(key(region, name)).ifPresent(stored -> stored.path("records").forEach(records::add));
        records.sort(Comparator.comparingDouble((JsonNode r) -> r.path("startedAt").asDouble()).reversed());
        return records;
    }

    /** Every recorded execution has already finished, so each one named comes back as invalid. */
    public List<String> cancelFlowExecutions(String name, JsonNode executionIds, String region) {
        describeFlow(name, region);
        List<String> invalid = new ArrayList<>();
        if (executionIds != null) {
            executionIds.forEach(id -> invalid.add(id.asText()));
        }
        return invalid;
    }

    private void recordExecution(String region, String name, String executionId, long now) {
        ObjectNode stored = executions.get(key(region, name)).orElseGet(JSON::objectNode);
        ArrayNode records = stored.get("records") instanceof ArrayNode existing ? existing : stored.putArray("records");
        double seconds = now / 1000.0;
        ObjectNode record = records.addObject();
        record.put("executionId", executionId);
        record.put("executionStatus", "Successful");
        record.putObject("executionResult")
                .put("bytesProcessed", 0)
                .put("bytesWritten", 0)
                .put("recordsProcessed", 0)
                .put("numParallelProcesses", 1)
                .put("maxPageSize", 0);
        record.put("startedAt", seconds);
        record.put("lastUpdatedAt", seconds);
        record.put("dataPullStartTime", seconds);
        record.put("dataPullEndTime", seconds);
        executions.put(key(region, name), stored);
    }

    // ── Connectors ───────────────────────────────────────────────────────────

    public String registerConnector(JsonNode request, String region) {
        String label = requiredText(request, "connectorLabel");
        if (!CONNECTOR_LABEL.matcher(label).matches()) {
            throw validation("connectorLabel must be 1 to 256 letters, digits, hyphens or underscores.");
        }
        String provisioningType = optionalText(request, "connectorProvisioningType");
        if (provisioningType == null) {
            provisioningType = "LAMBDA";
        }
        validateEnum("connectorProvisioningType", provisioningType, Set.of("LAMBDA"));
        String lambdaArn = request.path("connectorProvisioningConfig").path("lambda").path("lambdaArn").asText("");
        if (lambdaArn.isBlank()) {
            throw validation("connectorProvisioningConfig.lambda.lambdaArn is required.");
        }
        if (connectors.get(key(region, label)).isPresent()) {
            throw new AwsException("ConflictException", "Connector with label " + label + " already exists.", 409);
        }
        double now = Instant.now().toEpochMilli() / 1000.0;
        String account = connectors.accountId();
        ObjectNode connector = JSON.objectNode();
        connector.put("connectorArn", AwsArnUtils.Arn.of(SERVICE, region, account, "connector/" + label).toString());
        connector.put("connectorLabel", label);
        connector.put("connectorType", "CustomConnector");
        connector.put("connectorName", label);
        connector.put("connectorOwner", account);
        connector.put("connectorVersion", "1.0");
        connector.put("applicationType", "Custom");
        if (request.hasNonNull("description")) {
            connector.put("connectorDescription", request.get("description").asText());
        }
        connector.put("connectorProvisioningType", provisioningType);
        connector.set("connectorProvisioningConfig", request.get("connectorProvisioningConfig").deepCopy());
        connector.put("registeredAt", now);
        connector.put("registeredBy", account);
        connector.putArray("connectorModes").add("SOURCE").add("DESTINATION");
        connector.putArray("supportedDataTransferTypes").add("RECORD");
        connector.putObject("tags");
        connectors.put(key(region, label), connector);
        return connector.path("connectorArn").asText();
    }

    public String updateConnectorRegistration(JsonNode request, String region) {
        String label = requiredText(request, "connectorLabel");
        ObjectNode connector = requireConnector(label, region);
        if (request.hasNonNull("description")) {
            connector.put("connectorDescription", request.get("description").asText());
        }
        if (request.get("connectorProvisioningConfig") instanceof ObjectNode config) {
            if (config.path("lambda").path("lambdaArn").asText("").isBlank()) {
                throw validation("connectorProvisioningConfig.lambda.lambdaArn is required.");
            }
            connector.set("connectorProvisioningConfig", config.deepCopy());
        }
        connectors.put(key(region, label), connector);
        return connector.path("connectorArn").asText();
    }

    public void unregisterConnector(String label, boolean forceDelete, String region) {
        requireConnector(label, region);
        boolean inUse = profiles.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(p -> label.equals(p.getConnectorLabel()));
        if (inUse && !forceDelete) {
            throw new AwsException("ConflictException",
                    "Connector " + label + " is used by a connector profile.", 409);
        }
        connectors.delete(key(region, label));
    }

    /** The configuration of a built-in connector type, or of a registered custom connector. */
    public ObjectNode describeConnector(String connectorType, String label, String region) {
        validateEnum("connectorType", connectorType, CONNECTOR_TYPES);
        if ("CustomConnector".equals(connectorType)) {
            if (label == null) {
                throw validation("connectorLabel is required for a CustomConnector.");
            }
            return customConfiguration(requireConnector(label, region));
        }
        return builtInConfiguration(connectorType);
    }

    public List<ObjectNode> listConnectors(String region) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode connector : connectors.scan(k -> k.startsWith(region + "::"))) {
            ObjectNode detail = connector.deepCopy();
            detail.remove(List.of("connectorArn", "connectorProvisioningConfig", "tags"));
            result.add(detail);
        }
        result.sort(Comparator.comparing(c -> c.path("connectorLabel").asText()));
        return result;
    }

    /** Built-in configurations keyed by connector type, for the requested types or all of them. */
    public Map<String, ObjectNode> describeConnectors(JsonNode connectorTypes) {
        Map<String, ObjectNode> result = new LinkedHashMap<>();
        List<String> types = new ArrayList<>();
        if (connectorTypes != null && connectorTypes.isArray() && !connectorTypes.isEmpty()) {
            connectorTypes.forEach(t -> types.add(t.asText()));
        } else {
            types.addAll(CONNECTOR_TYPES.stream().sorted().toList());
        }
        for (String type : types) {
            validateEnum("connectorTypes", type, CONNECTOR_TYPES);
            if (!"CustomConnector".equals(type)) {
                result.put(type, builtInConfiguration(type));
            }
        }
        return result;
    }

    /** Checks the named profile; Floci keeps no connector metadata to reset. */
    public void resetConnectorMetadataCache(JsonNode request, String region) {
        String profileName = optionalText(request, "connectorProfileName");
        if (profileName != null) {
            requireProfileExists(region, profileName);
        }
    }

    private ObjectNode requireConnector(String label, String region) {
        return connectors.get(key(region, label)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "Connector with label " + label + " does not exist.", 404));
    }

    private static ObjectNode builtInConfiguration(String connectorType) {
        boolean destination = DESTINATION_TYPES.contains(connectorType);
        boolean source = !Set.of("EventBridge", "LookoutMetrics", "Upsolver", "Honeycode", "CustomerProfiles",
                "Redshift", "Snowflake").contains(connectorType);
        ObjectNode configuration = JSON.objectNode();
        configuration.put("canUseAsSource", source);
        configuration.put("canUseAsDestination", destination);
        ArrayNode destinations = configuration.putArray("supportedDestinationConnectors");
        if (source) {
            DESTINATION_TYPES.stream().sorted().forEach(destinations::add);
        }
        configuration.putArray("supportedSchedulingFrequencies").add("BYMINUTE").add("HOURLY").add("DAILY")
                .add("WEEKLY").add("MONTHLY").add("ONCE");
        configuration.put("isPrivateLinkEnabled", false);
        configuration.put("isPrivateLinkEndpointUrlRequired", false);
        ArrayNode triggers = configuration.putArray("supportedTriggerTypes").add("OnDemand").add("Scheduled");
        if (EVENT_SOURCES.contains(connectorType)) {
            triggers.add("Event");
        }
        configuration.putObject("connectorMetadata");
        configuration.put("connectorType", connectorType);
        configuration.put("connectorOwner", "AWS");
        configuration.put("connectorName", connectorType);
        configuration.putArray("supportedDataTransferTypes").add("RECORD");
        return configuration;
    }

    private static ObjectNode customConfiguration(ObjectNode connector) {
        ObjectNode configuration = JSON.objectNode();
        configuration.put("canUseAsSource", true);
        configuration.put("canUseAsDestination", true);
        configuration.putArray("supportedDestinationConnectors");
        configuration.putArray("supportedSchedulingFrequencies").add("BYMINUTE").add("HOURLY").add("DAILY");
        configuration.put("isPrivateLinkEnabled", false);
        configuration.put("isPrivateLinkEndpointUrlRequired", false);
        configuration.putArray("supportedTriggerTypes").add("OnDemand").add("Scheduled");
        configuration.putObject("connectorMetadata");
        for (String member : List.of("connectorType", "connectorLabel", "connectorDescription", "connectorOwner",
                "connectorName", "connectorVersion", "connectorArn", "connectorModes", "connectorProvisioningType",
                "connectorProvisioningConfig", "registeredAt", "registeredBy", "supportedDataTransferTypes")) {
            if (connector.has(member)) {
                configuration.set(member, connector.get(member).deepCopy());
            }
        }
        return configuration;
    }

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
        ObjectNode connector = connectorForArn(region, arn);
        if (connector != null) {
            Map<String, String> tags = new LinkedHashMap<>();
            connector.path("tags").properties().forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
            return tags;
        }
        return Map.copyOf(flowForArn(region, arn).getTags());
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        ObjectNode connector = connectorForArn(region, arn);
        if (connector != null) {
            ObjectNode current = (ObjectNode) connector.get("tags");
            readTagMap(tags).forEach(current::put);
            if (current.size() > 50) {
                throw validation("A connector can have at most 50 tags.");
            }
            connectors.put(key(region, connector.path("connectorLabel").asText()), connector);
            return;
        }
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
        ObjectNode connector = connectorForArn(region, arn);
        if (connector != null) {
            if (tagKeys != null) {
                tagKeys.forEach(((ObjectNode) connector.get("tags"))::remove);
            }
            connectors.put(key(region, connector.path("connectorLabel").asText()), connector);
            return;
        }
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
        connectors.clear();
        executions.clear();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ObjectNode connectorForArn(String region, String arn) {
        return connectors.scan(k -> k.startsWith(region + "::")).stream()
                .filter(connector -> connector.path("connectorArn").asText().equals(arn))
                .findFirst()
                .orElse(null);
    }

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
