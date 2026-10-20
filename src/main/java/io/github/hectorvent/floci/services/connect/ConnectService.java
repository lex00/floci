package io.github.hectorvent.floci.services.connect;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.connect.model.ConnectInstance;
import io.github.hectorvent.floci.services.connect.model.ConnectStorageConfig;
import io.github.hectorvent.floci.services.iam.ServiceLinkedRoles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Amazon Connect instance control plane (signing name {@code connect}). Instances
 * are ACTIVE the moment {@code CreateInstance} returns, so {@code aws_connect_instance}'s
 * status poll completes on its first read.
 */
@ApplicationScoped
public class ConnectService implements TagHandler {

    public static final String ACTIVE = "ACTIVE";

    private static final Logger LOG = Logger.getLogger(ConnectService.class);

    /** {@code InstanceAttributeType} from the AWS model. */
    private static final List<String> ATTRIBUTE_TYPES = List.of(
            "INBOUND_CALLS",
            "OUTBOUND_CALLS",
            "CONTACTFLOW_LOGS",
            "CONTACT_LENS",
            "AUTO_RESOLVE_BEST_VOICES",
            "USE_CUSTOM_TTS_VOICES",
            "EARLY_MEDIA",
            "MULTI_PARTY_CONFERENCE",
            "HIGH_VOLUME_OUTBOUND",
            "ENHANCED_CONTACT_MONITORING",
            "ENHANCED_CHAT_MONITORING",
            "MULTI_PARTY_CHAT_CONFERENCE",
            "MESSAGE_STREAMING");

    /** {@code DirectoryType} from the AWS model. */
    private static final Set<String> IDENTITY_MANAGEMENT_TYPES =
            Set.of("SAML", "CONNECT_MANAGED", "EXISTING_DIRECTORY");

    /** {@code InstanceStorageResourceType} from the AWS model. */
    private static final Set<String> STORAGE_RESOURCE_TYPES = Set.of(
            "CHAT_TRANSCRIPTS",
            "CALL_RECORDINGS",
            "SCHEDULED_REPORTS",
            "MEDIA_STREAMS",
            "CONTACT_TRACE_RECORDS",
            "AGENT_EVENTS",
            "REAL_TIME_CONTACT_ANALYSIS_SEGMENTS",
            "ATTACHMENTS",
            "CONTACT_EVALUATIONS",
            "SCREEN_RECORDINGS",
            "REAL_TIME_CONTACT_ANALYSIS_CHAT_SEGMENTS",
            "REAL_TIME_CONTACT_ANALYSIS_VOICE_SEGMENTS",
            "EMAIL_MESSAGES");

    /** {@code StorageType} from the AWS model, mapped to the member carrying its configuration. */
    private static final Map<String, String> STORAGE_TYPE_MEMBERS = Map.of(
            "S3", "S3Config",
            "KINESIS_VIDEO_STREAM", "KinesisVideoStreamConfig",
            "KINESIS_STREAM", "KinesisStreamConfig",
            "KINESIS_FIREHOSE", "KinesisFirehoseConfig");

    private final StorageBackend<String, ConnectInstance> instances;
    private final StorageBackend<String, ConnectStorageConfig> storageConfigs;
    /** Hierarchy level members in order, as {@code HierarchyStructure} names them. */
    private static final List<String> HIERARCHY_LEVELS =
            List.of("LevelOne", "LevelTwo", "LevelThree", "LevelFour", "LevelFive");

    /** International calling codes for the countries the canned phone number search serves. */
    private static final Map<String, String> CALLING_CODES = Map.ofEntries(
            Map.entry("US", "1"), Map.entry("CA", "1"), Map.entry("GB", "44"), Map.entry("DE", "49"),
            Map.entry("FR", "33"), Map.entry("AU", "61"), Map.entry("JP", "81"), Map.entry("SG", "65"),
            Map.entry("NZ", "64"), Map.entry("IE", "353"), Map.entry("IT", "39"), Map.entry("ES", "34"),
            Map.entry("BR", "55"), Map.entry("MX", "52"), Map.entry("ZA", "27"), Map.entry("KR", "82"));

    private static final int MAX_SEARCH_RESULTS = 10;

    private final StorageBackend<String, ObjectNode> phoneNumbers;
    private final StorageBackend<String, ObjectNode> hierarchyStructures;
    private final RegionResolver regionResolver;

    @Inject
    public ConnectService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.instances = storageFactory.create("connect", "connect-instances.json",
                new TypeReference<Map<String, ConnectInstance>>() {});
        this.storageConfigs = storageFactory.create("connect", "connect-storage-configs.json",
                new TypeReference<Map<String, ConnectStorageConfig>>() {});
        this.phoneNumbers = storageFactory.create("connect", "connect-phone-numbers.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.hierarchyStructures = storageFactory.create("connect", "connect-hierarchy-structures.json",
                new TypeReference<Map<String, ObjectNode>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Instances ────────────────────────────

    public ConnectInstance createInstance(String identityManagementType, String instanceAlias,
                                          String directoryId, Boolean inboundCallsEnabled,
                                          Boolean outboundCallsEnabled, Map<String, String> tags,
                                          String region) {
        if (identityManagementType == null || identityManagementType.isBlank()) {
            throw new AwsException("InvalidRequestException", "IdentityManagementType is required", 400);
        }
        if (!IDENTITY_MANAGEMENT_TYPES.contains(identityManagementType)) {
            throw new AwsException("InvalidParameterException",
                    "IdentityManagementType " + identityManagementType + " is not supported.", 400);
        }
        if (inboundCallsEnabled == null) {
            throw new AwsException("InvalidRequestException", "InboundCallsEnabled is required", 400);
        }
        if (outboundCallsEnabled == null) {
            throw new AwsException("InvalidRequestException", "OutboundCallsEnabled is required", 400);
        }
        if ("EXISTING_DIRECTORY".equals(identityManagementType)
                && (directoryId == null || directoryId.isBlank())) {
            throw new AwsException("InvalidRequestException",
                    "DirectoryId is required when IdentityManagementType is EXISTING_DIRECTORY", 400);
        }
        if (instanceAlias != null) {
            for (ConnectInstance existing : listInstances(region)) {
                if (instanceAlias.equals(existing.getInstanceAlias())) {
                    throw new AwsException("ResourceConflictException",
                            "An instance with alias " + instanceAlias + " already exists.", 409);
                }
            }
        }

        String instanceId = UUID.randomUUID().toString();
        ConnectInstance instance = new ConnectInstance();
        instance.setId(instanceId);
        instance.setArn(regionResolver.buildArn("connect", region, "instance/" + instanceId));
        instance.setIdentityManagementType(identityManagementType);
        instance.setInstanceAlias(instanceAlias);
        instance.setDirectoryId(directoryId);
        instance.setCreatedTime(Instant.now());
        instance.setServiceRole(regionResolver.buildGlobalArn("iam",
                "role/aws-service-role/" + ServicePrincipals.of("connect") + "/"
                        + ServiceLinkedRoles.roleName(ServicePrincipals.of("connect")).orElseThrow()
                        + "_" + instanceId));
        instance.setInboundCallsEnabled(inboundCallsEnabled);
        instance.setOutboundCallsEnabled(outboundCallsEnabled);
        if (instanceAlias != null) {
            instance.setInstanceAccessUrl("https://" + instanceAlias + ".my.connect.aws/");
        }
        instance.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        instance.setAccountId(regionResolver.getAccountId());

        // Every attribute type is seeded so DescribeInstanceAttribute never 404s on a
        // valid enum value: aws_connect_instance reads them all on every refresh.
        Map<String, String> attributes = new LinkedHashMap<>();
        for (String attributeType : ATTRIBUTE_TYPES) {
            attributes.put(attributeType, "false");
        }
        attributes.put("INBOUND_CALLS", String.valueOf(inboundCallsEnabled));
        attributes.put("OUTBOUND_CALLS", String.valueOf(outboundCallsEnabled));
        instance.setAttributes(attributes);

        instances.put(key(region, instanceId), instance);
        LOG.infov("Created Connect instance: {0}", instanceId);
        return instance;
    }

    public ConnectInstance describeInstance(String instanceId, String region) {
        return instances.get(key(region, resolveInstanceId(instanceId)))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Instance " + instanceId + " does not exist.", 404));
    }

    public void deleteInstance(String instanceId, String region) {
        ConnectInstance instance = describeInstance(instanceId, region);
        String prefix = key(region, instance.getId()) + "::";
        for (ConnectStorageConfig config : storageConfigs.scan(k -> k.startsWith(prefix))) {
            storageConfigs.delete(storageKey(region, instance.getId(), config.getResourceType(),
                    config.getAssociationId()));
        }
        hierarchyStructures.delete(key(region, instance.getId()));
        instances.delete(key(region, instance.getId()));
        LOG.infov("Deleted Connect instance: {0}", instance.getId());
    }

    public List<ConnectInstance> listInstances(String region) {
        String prefix = region + "::";
        return instances.scan(k -> k.startsWith(prefix));
    }

    // ──────────────────────────── Attributes ────────────────────────────

    public ConnectInstance updateInstanceAttribute(String instanceId, String attributeType, String value,
                                                   String region) {
        validateAttributeType(attributeType);
        if (value == null || value.isBlank()) {
            throw new AwsException("InvalidRequestException", "Value is required", 400);
        }
        // Instance attributes are boolean-valued; storing the canonical form keeps the
        // attributes map and the inbound/outbound flags from diverging on read.
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new AwsException("InvalidParameterException",
                    "Value must be 'true' or 'false' for attribute " + attributeType, 400);
        }
        String canonical = value.toLowerCase(java.util.Locale.ROOT);
        ConnectInstance instance = describeInstance(instanceId, region);
        instance.getAttributes().put(attributeType, canonical);
        if ("INBOUND_CALLS".equals(attributeType)) {
            instance.setInboundCallsEnabled(Boolean.parseBoolean(canonical));
        } else if ("OUTBOUND_CALLS".equals(attributeType)) {
            instance.setOutboundCallsEnabled(Boolean.parseBoolean(canonical));
        }
        instances.put(key(region, instance.getId()), instance);
        return instance;
    }

    public String describeInstanceAttribute(String instanceId, String attributeType, String region) {
        validateAttributeType(attributeType);
        ConnectInstance instance = describeInstance(instanceId, region);
        String value = instance.getAttributes().get(attributeType);
        if (value == null) {
            throw new AwsException("ResourceNotFoundException",
                    "Attribute " + attributeType + " is not set on instance " + instanceId + ".", 404);
        }
        return value;
    }

    public Map<String, String> listInstanceAttributes(String instanceId, String region) {
        return describeInstance(instanceId, region).getAttributes();
    }

    // ──────────────────────── Instance storage configs ────────────────────────

    public ConnectStorageConfig associateInstanceStorageConfig(String instanceId, String resourceType,
                                                               JsonNode storageConfig, String region) {
        validateResourceType(resourceType);
        String storageType = validateStorageConfig(storageConfig);
        ConnectInstance instance = describeInstance(instanceId, region);

        String associationId = UUID.randomUUID().toString();
        ConnectStorageConfig config = new ConnectStorageConfig();
        config.setAssociationId(associationId);
        config.setInstanceId(instance.getId());
        config.setResourceType(resourceType);
        config.setStorageType(storageType);
        config.setStorageConfig(storageConfig);

        storageConfigs.put(storageKey(region, instance.getId(), resourceType, associationId), config);
        LOG.infov("Associated Connect storage config {0} ({1}) on instance {2}",
                associationId, resourceType, instance.getId());
        return config;
    }

    public ConnectStorageConfig describeInstanceStorageConfig(String instanceId, String associationId,
                                                              String resourceType, String region) {
        validateResourceType(resourceType);
        ConnectInstance instance = describeInstance(instanceId, region);
        return storageConfigs.get(storageKey(region, instance.getId(), resourceType, associationId))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Storage config " + associationId + " does not exist on instance "
                                + instance.getId() + ".", 404));
    }

    public ConnectStorageConfig updateInstanceStorageConfig(String instanceId, String associationId,
                                                            String resourceType, JsonNode storageConfig,
                                                            String region) {
        String storageType = validateStorageConfig(storageConfig);
        ConnectStorageConfig config =
                describeInstanceStorageConfig(instanceId, associationId, resourceType, region);
        config.setStorageType(storageType);
        config.setStorageConfig(storageConfig);
        storageConfigs.put(storageKey(region, config.getInstanceId(), resourceType, associationId), config);
        return config;
    }

    public void disassociateInstanceStorageConfig(String instanceId, String associationId,
                                                  String resourceType, String region) {
        ConnectStorageConfig config =
                describeInstanceStorageConfig(instanceId, associationId, resourceType, region);
        storageConfigs.delete(storageKey(region, config.getInstanceId(), resourceType, associationId));
        LOG.infov("Disassociated Connect storage config {0} from instance {1}",
                associationId, config.getInstanceId());
    }

    public List<ConnectStorageConfig> listInstanceStorageConfigs(String instanceId, String resourceType,
                                                                 String region) {
        validateResourceType(resourceType);
        ConnectInstance instance = describeInstance(instanceId, region);
        String prefix = key(region, instance.getId()) + "::" + resourceType + "::";
        return storageConfigs.scan(k -> k.startsWith(prefix));
    }

    // ──────────────────────────── Phone numbers ────────────────────────────

    /**
     * Answers {@code SearchAvailablePhoneNumbers} with generated numbers for the requested country
     * and type, starting with the requested prefix and skipping numbers already claimed.
     */
    public List<ObjectNode> searchAvailablePhoneNumbers(String targetArn, String instanceId, String countryCode,
                                                        String phoneNumberType, String prefix,
                                                        Integer maxResults, String region) {
        requireOneTarget(targetArn, instanceId);
        if (countryCode == null || countryCode.isBlank()) {
            throw new AwsException("InvalidParameterException", "PhoneNumberCountryCode is required", 400);
        }
        if (phoneNumberType == null || phoneNumberType.isBlank()) {
            throw new AwsException("InvalidParameterException", "PhoneNumberType is required", 400);
        }
        if (instanceId != null) {
            describeInstance(instanceId, region);
        }
        int limit = maxResults == null ? MAX_SEARCH_RESULTS : maxResults;
        if (limit < 1 || limit > MAX_SEARCH_RESULTS) {
            throw new AwsException("InvalidParameterException",
                    "MaxResults must be between 1 and " + MAX_SEARCH_RESULTS, 400);
        }
        String callingCode = CALLING_CODES.getOrDefault(countryCode, "1");
        String start = prefix != null && !prefix.isBlank()
                ? (prefix.startsWith("+") ? prefix : "+" + prefix)
                : "+" + callingCode + ("TOLL_FREE".equals(phoneNumberType) ? "800" : "206");
        int length = "1".equals(callingCode) ? 12 : 1 + callingCode.length() + 9;
        Set<String> claimed = new HashSet<>();
        for (ObjectNode number : phoneNumbers.scan(k -> k.startsWith(region + "::"))) {
            claimed.add(number.path("PhoneNumber").asText());
        }
        List<ObjectNode> result = new ArrayList<>();
        int digits = Math.max(length - start.length(), 1);
        for (int i = 0; result.size() < limit && i < Math.pow(10, Math.min(digits, 6)); i++) {
            String suffix = String.format("%0" + digits + "d", 5550100 + i);
            String candidate = start + suffix.substring(suffix.length() - digits);
            if (claimed.contains(candidate)) {
                continue;
            }
            ObjectNode summary = JsonNodeFactory.instance.objectNode();
            summary.put("PhoneNumber", candidate);
            summary.put("PhoneNumberCountryCode", countryCode);
            summary.put("PhoneNumberType", phoneNumberType);
            result.add(summary);
        }
        return result;
    }

    public ObjectNode claimPhoneNumber(String targetArn, String instanceId, String phoneNumber,
                                       String description, Map<String, String> tags, String region) {
        requireOneTarget(targetArn, instanceId);
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new AwsException("InvalidParameterException", "PhoneNumber is required", 400);
        }
        String target = targetArn;
        String resolvedInstanceId = null;
        if (instanceId != null) {
            ConnectInstance instance = describeInstance(instanceId, region);
            target = instance.getArn();
            resolvedInstanceId = instance.getId();
        }
        for (ObjectNode existing : phoneNumbers.scan(k -> k.startsWith(region + "::"))) {
            if (phoneNumber.equals(existing.path("PhoneNumber").asText())) {
                throw new AwsException("InvalidParameterException",
                        "Phone number " + phoneNumber + " is already claimed.", 400);
            }
        }
        String id = UUID.randomUUID().toString();
        ObjectNode number = JsonNodeFactory.instance.objectNode();
        number.put("PhoneNumberId", id);
        number.put("PhoneNumberArn", regionResolver.buildArn("connect", region, "phone-number/" + id));
        number.put("PhoneNumber", phoneNumber);
        number.put("PhoneNumberCountryCode", countryFor(phoneNumber));
        number.put("PhoneNumberType", phoneNumber.matches("\\+18(00|33|44|55|66|77|88)\\d*") ? "TOLL_FREE" : "DID");
        if (description != null) {
            number.put("PhoneNumberDescription", description);
        }
        number.put("TargetArn", target);
        if (resolvedInstanceId != null) {
            number.put("InstanceId", resolvedInstanceId);
        }
        ObjectNode tagNode = number.putObject("Tags");
        if (tags != null) {
            tags.forEach(tagNode::put);
        }
        number.putObject("PhoneNumberStatus").put("Status", "CLAIMED");
        phoneNumbers.put(phoneKey(region, id), number);
        return number;
    }

    public ObjectNode describePhoneNumber(String phoneNumberId, String region) {
        String id = phoneNumberId != null && phoneNumberId.startsWith("arn:")
                ? phoneNumberId.substring(phoneNumberId.lastIndexOf('/') + 1) : phoneNumberId;
        return phoneNumbers.get(phoneKey(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Phone number " + phoneNumberId + " does not exist.", 404));
    }

    public void releasePhoneNumber(String phoneNumberId, String region) {
        ObjectNode number = describePhoneNumber(phoneNumberId, region);
        phoneNumbers.delete(phoneKey(region, number.path("PhoneNumberId").asText()));
    }

    private static void requireOneTarget(String targetArn, String instanceId) {
        if ((targetArn == null) == (instanceId == null)) {
            throw new AwsException("InvalidParameterException",
                    "Exactly one of TargetArn or InstanceId is required.", 400);
        }
    }

    private static String countryFor(String phoneNumber) {
        String digits = phoneNumber.startsWith("+") ? phoneNumber.substring(1) : phoneNumber;
        String best = "US";
        int bestLength = 0;
        for (Map.Entry<String, String> entry : CALLING_CODES.entrySet()) {
            String code = entry.getValue();
            boolean preferred = !"1".equals(code) || "US".equals(entry.getKey());
            if (preferred && digits.startsWith(code) && code.length() > bestLength) {
                best = entry.getKey();
                bestLength = code.length();
            }
        }
        return best;
    }

    // ──────────────────────── User hierarchy structure ────────────────────────

    /**
     * Replaces the instance's hierarchy levels. A level keeps its id while it stays at the same
     * position, and a level left out of the request is removed.
     */
    public void updateUserHierarchyStructure(String instanceId, JsonNode structure, String region) {
        ConnectInstance instance = describeInstance(instanceId, region);
        if (structure == null || !structure.isObject()) {
            throw new AwsException("InvalidRequestException", "HierarchyStructure is required", 400);
        }
        ObjectNode previous = hierarchyStructures.get(key(region, instance.getId()))
                .orElse(JsonNodeFactory.instance.objectNode());
        ObjectNode updated = JsonNodeFactory.instance.objectNode();
        boolean gap = false;
        for (String level : HIERARCHY_LEVELS) {
            JsonNode requested = structure.get(level);
            if (requested == null || requested.isNull()) {
                gap = true;
                continue;
            }
            String name = requested.path("Name").asText(null);
            if (name == null || name.isBlank()) {
                throw new AwsException("InvalidParameterException", level + ".Name is required", 400);
            }
            if (gap) {
                throw new AwsException("InvalidParameterException",
                        "Hierarchy levels must be defined in order without gaps.", 400);
            }
            JsonNode prior = previous.get(level);
            ObjectNode node = updated.putObject(level);
            String id = prior != null ? prior.path("Id").asText() : UUID.randomUUID().toString();
            node.put("Id", id);
            node.put("Arn", instance.getArn() + "/agent-group-level/" + id);
            node.put("Name", name);
            boolean unchanged = prior != null && name.equals(prior.path("Name").asText());
            node.put("LastModifiedTime", unchanged
                    ? prior.path("LastModifiedTime").asLong() : Instant.now().getEpochSecond());
            node.put("LastModifiedRegion", unchanged ? prior.path("LastModifiedRegion").asText(region) : region);
        }
        hierarchyStructures.put(key(region, instance.getId()), updated);
    }

    public ObjectNode describeUserHierarchyStructure(String instanceId, String region) {
        ConnectInstance instance = describeInstance(instanceId, region);
        return hierarchyStructures.get(key(region, instance.getId()))
                .orElse(JsonNodeFactory.instance.objectNode());
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return "connect";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        if (isPhoneNumberArn(arn)) {
            Map<String, String> tags = new LinkedHashMap<>();
            describePhoneNumberByArn(arn, region).path("Tags").fields()
                    .forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
            return tags;
        }
        ConnectInstance instance = findByArn(arn, region);
        return instance.getTags() != null ? instance.getTags() : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (isPhoneNumberArn(arn)) {
            ObjectNode number = describePhoneNumberByArn(arn, region);
            ObjectNode tagNode = tagsOf(number);
            tags.forEach(tagNode::put);
            phoneNumbers.put(phoneKey(region, number.path("PhoneNumberId").asText()), number);
            return;
        }
        ConnectInstance instance = findByArn(arn, region);
        if (instance.getTags() == null) {
            instance.setTags(new LinkedHashMap<>());
        }
        instance.getTags().putAll(tags);
        instances.put(key(region, instance.getId()), instance);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (isPhoneNumberArn(arn)) {
            ObjectNode number = describePhoneNumberByArn(arn, region);
            if (tagKeys != null) {
                tagsOf(number).remove(tagKeys);
            }
            phoneNumbers.put(phoneKey(region, number.path("PhoneNumberId").asText()), number);
            return;
        }
        ConnectInstance instance = findByArn(arn, region);
        if (instance.getTags() != null && tagKeys != null) {
            tagKeys.forEach(instance.getTags()::remove);
        }
        instances.put(key(region, instance.getId()), instance);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static ObjectNode tagsOf(ObjectNode number) {
        return number.get("Tags") instanceof ObjectNode tags ? tags : number.putObject("Tags");
    }

    private static boolean isPhoneNumberArn(String arn) {
        return arn != null && arn.contains(":phone-number/");
    }

    private ObjectNode describePhoneNumberByArn(String arn, String region) {
        ObjectNode number = describePhoneNumber(arn, region);
        if (!arn.equals(number.path("PhoneNumberArn").asText())) {
            throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
        }
        return number;
    }

    private ConnectInstance findByArn(String arn, String region) {
        String prefix = region + "::";
        for (ConnectInstance instance : instances.scan(k -> k.startsWith(prefix))) {
            if (arn.equals(instance.getArn())) {
                return instance;
            }
        }
        throw new AwsException("ResourceNotFoundException", "Resource " + arn + " does not exist.", 404);
    }

    /** Both a bare instance id and a full instance ARN are accepted, as AWS does. */
    private String resolveInstanceId(String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            throw new AwsException("InvalidRequestException", "InstanceId is required", 400);
        }
        if (!instanceId.startsWith("arn:")) {
            return instanceId;
        }
        String resource;
        try {
            resource = AwsArnUtils.parse(instanceId).resource();
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterException", "Invalid instance ARN: " + instanceId, 400);
        }
        int slash = resource.indexOf('/');
        if (slash < 0 || slash == resource.length() - 1) {
            throw new AwsException("InvalidParameterException", "Invalid instance ARN: " + instanceId, 400);
        }
        return resource.substring(slash + 1);
    }

    private void validateAttributeType(String attributeType) {
        if (attributeType == null || !ATTRIBUTE_TYPES.contains(attributeType)) {
            throw new AwsException("InvalidParameterException",
                    "AttributeType " + attributeType + " is not supported.", 400);
        }
    }

    private void validateResourceType(String resourceType) {
        if (resourceType == null || !STORAGE_RESOURCE_TYPES.contains(resourceType)) {
            throw new AwsException("InvalidParameterException",
                    "ResourceType " + resourceType + " is not supported.", 400);
        }
    }

    private String validateStorageConfig(JsonNode storageConfig) {
        if (storageConfig == null || !storageConfig.isObject()) {
            throw new AwsException("InvalidRequestException", "StorageConfig is required", 400);
        }
        JsonNode storageTypeNode = storageConfig.get("StorageType");
        if (storageTypeNode == null || storageTypeNode.isNull()) {
            throw new AwsException("InvalidRequestException", "StorageConfig.StorageType is required", 400);
        }
        String storageType = storageTypeNode.asText();
        String member = STORAGE_TYPE_MEMBERS.get(storageType);
        if (member == null) {
            throw new AwsException("InvalidParameterException",
                    "StorageType " + storageType + " is not supported.", 400);
        }
        if (storageConfig.get(member) == null || storageConfig.get(member).isNull()) {
            throw new AwsException("InvalidRequestException",
                    "StorageConfig." + member + " is required when StorageType is " + storageType, 400);
        }
        return storageType;
    }

    private String key(String region, String instanceId) {
        return region + "::" + instanceId;
    }

    private String phoneKey(String region, String phoneNumberId) {
        return region + "::phone-number::" + phoneNumberId;
    }

    private String storageKey(String region, String instanceId, String resourceType, String associationId) {
        return key(region, instanceId) + "::" + resourceType + "::" + associationId;
    }
}
