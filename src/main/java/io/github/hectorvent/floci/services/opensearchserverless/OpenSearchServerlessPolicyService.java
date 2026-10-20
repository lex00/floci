package io.github.hectorvent.floci.services.opensearchserverless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * OpenSearch Serverless data access policies, lifecycle policies, VPC endpoints, collection
 * groups, indexes and account settings. A VPC endpoint is {@code ACTIVE} as soon as it is
 * created. Records are keyed by kind and region, with the policy type and name or the resource id.
 */
@ApplicationScoped
public class OpenSearchServerlessPolicyService implements Resettable {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{2,31}");
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String ACCESS = "access";
    private static final String LIFECYCLE = "lifecycle";
    private static final String VPC_ENDPOINT = "vpce";
    private static final String GROUP = "group";
    private static final String INDEX = "index";
    private static final String SETTINGS = "settings";

    private final StorageBackend<String, ObjectNode> records;
    private final OpenSearchServerlessService collections;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public OpenSearchServerlessPolicyService(StorageFactory storageFactory, OpenSearchServerlessService collections,
                                             RegionResolver regionResolver, ObjectMapper objectMapper) {
        this(storageFactory.create("opensearchserverless", "aoss-policies.json",
                new TypeReference<Map<String, ObjectNode>>() {}), collections, regionResolver, objectMapper);
    }

    OpenSearchServerlessPolicyService(StorageBackend<String, ObjectNode> records,
                                      OpenSearchServerlessService collections, RegionResolver regionResolver,
                                      ObjectMapper objectMapper) {
        this.records = records;
        this.collections = collections;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        records.clear();
    }

    // ──────────────────────────── Access and lifecycle policies ────────────────────────────

    public synchronized ObjectNode createPolicy(String kind, JsonNode request, String region) {
        String type = requireType(kind, request);
        String name = text(request, "name");
        requireName(name);
        JsonNode document = policyDocument(kind, text(request, "policy"));
        String key = key(kind, region, type + "/" + name);
        if (records.get(key).isPresent()) {
            throw new AwsException("ConflictException", "Policy with name " + name + " and type " + type
                    + " already exists", 400);
        }
        long now = Instant.now().toEpochMilli();
        ObjectNode policy = objectMapper.createObjectNode();
        policy.put("type", type);
        policy.put("name", name);
        policy.put("policyVersion", version(now));
        description(request, policy);
        policy.set("policy", document);
        policy.put("createdDate", now);
        policy.put("lastModifiedDate", now);
        records.put(key, policy);
        return policy.deepCopy();
    }

    public ObjectNode getPolicy(String kind, JsonNode request, String region) {
        return requirePolicy(kind, requireType(kind, request), text(request, "name"), region).deepCopy();
    }

    public synchronized ObjectNode updatePolicy(String kind, JsonNode request, String region) {
        String type = requireType(kind, request);
        ObjectNode policy = requirePolicy(kind, type, text(request, "name"), region);
        String version = text(request, "policyVersion");
        if (version == null) {
            throw validation("policyVersion is required");
        }
        if (!version.equals(policy.path("policyVersion").asText())) {
            throw new AwsException("ConflictException", "Policy version specified in the request refers to an older "
                    + "version and policy has since changed", 400);
        }
        if (request.hasNonNull("policy")) {
            policy.set("policy", policyDocument(kind, text(request, "policy")));
        }
        description(request, policy);
        long now = Instant.now().toEpochMilli();
        policy.put("policyVersion", version(now));
        policy.put("lastModifiedDate", now);
        records.put(key(kind, region, type + "/" + policy.path("name").asText()), policy);
        return policy.deepCopy();
    }

    public synchronized void deletePolicy(String kind, JsonNode request, String region) {
        String type = requireType(kind, request);
        ObjectNode policy = requirePolicy(kind, type, text(request, "name"), region);
        records.delete(key(kind, region, type + "/" + policy.path("name").asText()));
    }

    /** Summaries of one kind and type, keeping those whose rules cover one of {@code resources}. */
    public List<ObjectNode> listPolicies(String kind, JsonNode request, String region) {
        String type = requireType(kind, request);
        Set<String> resources = new LinkedHashSet<>();
        request.path(ACCESS.equals(kind) ? "resource" : "resources").forEach(r -> resources.add(r.asText()));
        return scan(kind, region).stream()
                .filter(p -> type.equals(p.path("type").asText()))
                .filter(p -> resources.isEmpty() || resources.stream().anyMatch(r -> covers(p.get("policy"), r)))
                .sorted(Comparator.comparing(p -> p.path("name").asText()))
                .map(p -> {
                    ObjectNode summary = p.deepCopy();
                    summary.remove("policy");
                    return summary;
                })
                .toList();
    }

    public ObjectNode batchGetLifecyclePolicy(JsonNode identifiers, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("lifecyclePolicyDetails");
        ArrayNode errors = response.putArray("lifecyclePolicyErrorDetails");
        for (JsonNode identifier : identifiers) {
            String type = identifier.path("type").asText();
            String name = identifier.path("name").asText();
            ObjectNode policy = records.get(key(LIFECYCLE, region, type + "/" + name)).orElse(null);
            if (policy == null) {
                errors.addObject().put("type", type).put("name", name).put("errorCode", "ResourceNotFound")
                        .put("errorMessage", "Policy with name " + name + " and type " + type + " not found");
            } else {
                details.add(policy.deepCopy());
            }
        }
        return response;
    }

    /** The retention rule that applies to each index, from the first policy whose rule matches it. */
    public ObjectNode batchGetEffectiveLifecyclePolicy(JsonNode identifiers, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("effectiveLifecyclePolicyDetails");
        ArrayNode errors = response.putArray("effectiveLifecyclePolicyErrorDetails");
        List<ObjectNode> policies = scan(LIFECYCLE, region).stream()
                .sorted(Comparator.comparing(p -> p.path("name").asText())).toList();
        for (JsonNode identifier : identifiers) {
            String type = identifier.path("type").asText();
            String resource = identifier.path("resource").asText();
            ObjectNode effective = null;
            for (ObjectNode policy : policies) {
                for (JsonNode rule : policy.path("policy").path("Rules")) {
                    if (effective == null && ruleCovers(rule, resource)) {
                        effective = details.addObject();
                        effective.put("type", type);
                        effective.put("resource", resource);
                        effective.put("policyName", policy.path("name").asText());
                        effective.put("resourceType", "index");
                        if (rule.hasNonNull("MinIndexRetention")) {
                            effective.put("retentionPeriod", rule.get("MinIndexRetention").asText());
                        }
                        effective.put("noMinRetentionPeriod", rule.path("NoMinIndexRetention").asBoolean(false));
                    }
                }
            }
            if (effective == null) {
                errors.addObject().put("type", type).put("resource", resource).put("errorCode", "ResourceNotFound")
                        .put("errorMessage", "No lifecycle policy applies to " + resource);
            }
        }
        return response;
    }

    private ObjectNode requirePolicy(String kind, String type, String name, String region) {
        if (name == null) {
            throw validation("name is required");
        }
        return records.get(key(kind, region, type + "/" + name)).orElseThrow(() -> notFound(
                "Policy with name " + name + " and type " + type + " is not found"));
    }

    private static String requireType(String kind, JsonNode request) {
        String type = text(request, "type");
        String expected = ACCESS.equals(kind) ? "data" : "retention";
        if (!expected.equals(type)) {
            throw validation("1 validation error detected: Value '" + type + "' at 'type' failed to satisfy "
                    + "constraint: Member must satisfy enum value set: [" + expected + "]");
        }
        return type;
    }

    private JsonNode policyDocument(String kind, String policy) {
        if (policy == null || policy.isBlank()) {
            throw validation("policy is required");
        }
        JsonNode document;
        try {
            document = objectMapper.readTree(policy);
        } catch (JsonProcessingException e) {
            document = null;
        }
        boolean valid = document != null && (ACCESS.equals(kind)
                ? document.isArray() && !document.isEmpty()
                : document.isObject() && document.path("Rules").isArray());
        if (!valid) {
            throw validation("Policy json is invalid, error: [$: Invalid " + (ACCESS.equals(kind) ? "data access"
                    : "lifecycle") + " policy document]");
        }
        return document;
    }

    private static boolean covers(JsonNode document, String resource) {
        JsonNode policies = document.isArray() ? document : document.path("Rules");
        for (JsonNode entry : policies) {
            JsonNode rules = entry.has("Rules") ? entry.get("Rules") : entry;
            if (rules.isArray()) {
                for (JsonNode rule : rules) {
                    if (ruleCovers(rule, resource)) {
                        return true;
                    }
                }
            } else if (ruleCovers(rules, resource)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ruleCovers(JsonNode rule, String resource) {
        for (JsonNode pattern : rule.path("Resource")) {
            String regex = Pattern.quote(pattern.asText()).replace("*", "\\E.*\\Q");
            if (resource.matches(regex)) {
                return true;
            }
        }
        return false;
    }

    // ──────────────────────────── VPC endpoints ────────────────────────────

    public synchronized ObjectNode createVpcEndpoint(JsonNode request, String region) {
        String name = text(request, "name");
        requireName(name);
        String vpcId = text(request, "vpcId");
        if (vpcId == null || !request.path("subnetIds").isArray() || request.path("subnetIds").isEmpty()) {
            throw validation("vpcId and subnetIds are required");
        }
        boolean taken = scan(VPC_ENDPOINT, region).stream().anyMatch(e -> name.equals(e.path("name").asText()));
        if (taken) {
            throw new AwsException("ConflictException", "VPC endpoint with name " + name + " already exists", 400);
        }
        String id = "vpce-" + randomId(17);
        ObjectNode endpoint = objectMapper.createObjectNode();
        endpoint.put("id", id);
        endpoint.put("name", name);
        endpoint.put("vpcId", vpcId);
        endpoint.set("subnetIds", request.get("subnetIds").deepCopy());
        endpoint.set("securityGroupIds", request.path("securityGroupIds").isArray()
                ? request.get("securityGroupIds").deepCopy() : objectMapper.createArrayNode());
        endpoint.put("status", "ACTIVE");
        long now = Instant.now().toEpochMilli();
        endpoint.put("createdDate", now);
        endpoint.put("lastModifiedDate", now);
        records.put(key(VPC_ENDPOINT, region, id), endpoint);
        return summary(endpoint);
    }

    public ObjectNode batchGetVpcEndpoint(JsonNode ids, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("vpcEndpointDetails");
        ArrayNode errors = response.putArray("vpcEndpointErrorDetails");
        for (JsonNode id : ids) {
            ObjectNode endpoint = records.get(key(VPC_ENDPOINT, region, id.asText())).orElse(null);
            if (endpoint == null) {
                errors.addObject().put("id", id.asText()).put("errorCode", "NOT_FOUND")
                        .put("errorMessage", "VPC endpoint " + id.asText() + " not found");
            } else {
                ObjectNode detail = endpoint.deepCopy();
                detail.remove("lastModifiedDate");
                details.add(detail);
            }
        }
        return response;
    }

    public synchronized ObjectNode updateVpcEndpoint(JsonNode request, String region) {
        String id = text(request, "id");
        ObjectNode endpoint = requireVpcEndpoint(id, region);
        endpoint.set("subnetIds", merge(endpoint.path("subnetIds"), request.path("addSubnetIds"),
                request.path("removeSubnetIds")));
        endpoint.set("securityGroupIds", merge(endpoint.path("securityGroupIds"),
                request.path("addSecurityGroupIds"), request.path("removeSecurityGroupIds")));
        endpoint.put("lastModifiedDate", Instant.now().toEpochMilli());
        records.put(key(VPC_ENDPOINT, region, id), endpoint);
        ObjectNode detail = summary(endpoint);
        detail.set("subnetIds", endpoint.get("subnetIds").deepCopy());
        detail.set("securityGroupIds", endpoint.get("securityGroupIds").deepCopy());
        detail.put("lastModifiedDate", endpoint.path("lastModifiedDate").asLong());
        return detail;
    }

    public synchronized ObjectNode deleteVpcEndpoint(String id, String region) {
        ObjectNode endpoint = requireVpcEndpoint(id, region);
        records.delete(key(VPC_ENDPOINT, region, id));
        ObjectNode detail = summary(endpoint);
        detail.put("status", "DELETING");
        return detail;
    }

    public List<ObjectNode> listVpcEndpoints(JsonNode filters, String region) {
        String status = text(filters, "status");
        return scan(VPC_ENDPOINT, region).stream()
                .filter(e -> status == null || status.equals(e.path("status").asText()))
                .sorted(Comparator.comparing(e -> e.path("id").asText()))
                .map(this::summary)
                .toList();
    }

    private ObjectNode requireVpcEndpoint(String id, String region) {
        if (id == null) {
            throw validation("id is required");
        }
        return records.get(key(VPC_ENDPOINT, region, id)).orElseThrow(() -> notFound("VPC endpoint " + id
                + " not found"));
    }

    private ObjectNode summary(ObjectNode endpoint) {
        return objectMapper.createObjectNode()
                .put("id", endpoint.path("id").asText())
                .put("name", endpoint.path("name").asText())
                .put("status", endpoint.path("status").asText());
    }

    private ArrayNode merge(JsonNode current, JsonNode added, JsonNode removed) {
        Set<String> values = new LinkedHashSet<>();
        current.forEach(v -> values.add(v.asText()));
        added.forEach(v -> values.add(v.asText()));
        removed.forEach(v -> values.remove(v.asText()));
        ArrayNode result = objectMapper.createArrayNode();
        values.forEach(result::add);
        return result;
    }

    // ──────────────────────────── Collection groups ────────────────────────────

    public synchronized ObjectNode createCollectionGroup(JsonNode request, String region) {
        String name = text(request, "name");
        requireName(name);
        String standby = text(request, "standbyReplicas");
        if (!"ENABLED".equals(standby) && !"DISABLED".equals(standby)) {
            throw validation("standbyReplicas must be ENABLED or DISABLED");
        }
        if (findGroupByName(name, region) != null) {
            throw new AwsException("ConflictException", "Collection group with name " + name + " already exists",
                    400);
        }
        String id = randomId(20);
        ObjectNode group = objectMapper.createObjectNode();
        group.put("id", id);
        group.put("arn", regionResolver.buildArn("aoss", region, "collection-group/" + id));
        group.put("name", name);
        group.put("standbyReplicas", standby);
        description(request, group);
        group.set("tags", request.path("tags").isArray() ? request.get("tags").deepCopy()
                : objectMapper.createArrayNode());
        group.put("createdDate", Instant.now().toEpochMilli());
        if (request.get("capacityLimits") instanceof ObjectNode limits) {
            group.set("capacityLimits", limits.deepCopy());
        }
        records.put(key(GROUP, region, id), group);
        return group.deepCopy();
    }

    public ObjectNode batchGetCollectionGroup(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode details = response.putArray("collectionGroupDetails");
        ArrayNode errors = response.putArray("collectionGroupErrorDetails");
        List<ObjectNode> found = new ArrayList<>();
        for (JsonNode id : request.path("ids")) {
            ObjectNode group = records.get(key(GROUP, region, id.asText())).orElse(null);
            if (group == null) {
                errors.addObject().put("id", id.asText()).put("errorCode", "NOT_FOUND")
                        .put("errorMessage", "Collection group " + id.asText() + " not found");
            } else {
                found.add(group);
            }
        }
        for (JsonNode name : request.path("names")) {
            ObjectNode group = findGroupByName(name.asText(), region);
            if (group == null) {
                errors.addObject().put("name", name.asText()).put("errorCode", "NOT_FOUND")
                        .put("errorMessage", "Collection group " + name.asText() + " not found");
            } else {
                found.add(group);
            }
        }
        for (ObjectNode group : found) {
            ObjectNode detail = group.deepCopy();
            String name = group.path("name").asText();
            detail.put("numberOfCollections", collections.listCollections(region).stream()
                    .filter(c -> name.equals(c.getCollectionGroupName())).count());
            details.add(detail);
        }
        return response;
    }

    public synchronized ObjectNode updateCollectionGroup(JsonNode request, String region) {
        String id = text(request, "id");
        ObjectNode group = requireGroup(id, region);
        description(request, group);
        if (request.get("capacityLimits") instanceof ObjectNode limits) {
            group.set("capacityLimits", limits.deepCopy());
        }
        group.put("lastModifiedDate", Instant.now().toEpochMilli());
        records.put(key(GROUP, region, id), group);
        ObjectNode detail = group.deepCopy();
        detail.remove("tags");
        detail.remove("standbyReplicas");
        return detail;
    }

    public synchronized ObjectNode deleteCollectionGroup(String id, String region) {
        ObjectNode group = requireGroup(id, region);
        String name = group.path("name").asText();
        boolean inUse = collections.listCollections(region).stream()
                .anyMatch(c -> name.equals(c.getCollectionGroupName()));
        if (inUse) {
            throw new AwsException("ConflictException", "Collection group " + name + " still has collections", 400);
        }
        records.delete(key(GROUP, region, id));
        return objectMapper.createObjectNode();
    }

    public List<ObjectNode> listCollectionGroups(String region) {
        return scan(GROUP, region).stream()
                .sorted(Comparator.comparing(g -> g.path("name").asText()))
                .map(g -> {
                    ObjectNode summary = objectMapper.createObjectNode();
                    for (String member : List.of("id", "arn", "name", "numberOfCollections", "createdDate",
                            "capacityLimits")) {
                        if (g.has(member)) {
                            summary.set(member, g.get(member).deepCopy());
                        }
                    }
                    return summary;
                })
                .toList();
    }

    public boolean ownsGroupArn(String arn, String region) {
        return arn != null && arn.contains(":collection-group/")
                && records.get(key(GROUP, region, arn.substring(arn.lastIndexOf('/') + 1))).isPresent();
    }

    public synchronized void tagGroup(String arn, Map<String, String> added, List<String> removed, String region) {
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        ObjectNode group = requireGroup(id, region);
        Map<String, String> tags = groupTags(group);
        if (added != null) {
            tags.putAll(added);
        }
        if (removed != null) {
            removed.forEach(tags::remove);
        }
        ArrayNode list = group.putArray("tags");
        tags.forEach((key, value) -> list.addObject().put("key", key).put("value", value));
        records.put(key(GROUP, region, id), group);
    }

    public Map<String, String> listGroupTags(String arn, String region) {
        return groupTags(requireGroup(arn.substring(arn.lastIndexOf('/') + 1), region));
    }

    private static Map<String, String> groupTags(ObjectNode group) {
        Map<String, String> tags = new LinkedHashMap<>();
        group.path("tags").forEach(t -> tags.put(t.path("key").asText(), t.path("value").asText()));
        return tags;
    }

    private ObjectNode findGroupByName(String name, String region) {
        return scan(GROUP, region).stream().filter(g -> name.equals(g.path("name").asText())).findFirst().orElse(null);
    }

    private ObjectNode requireGroup(String id, String region) {
        if (id == null) {
            throw validation("id is required");
        }
        return records.get(key(GROUP, region, id)).orElseThrow(() -> notFound("Collection group " + id
                + " not found"));
    }

    // ──────────────────────────── Indexes ────────────────────────────

    public synchronized void createIndex(JsonNode request, String region) {
        String collectionId = requireCollection(request, region);
        String indexName = requireIndexName(request);
        String key = key(INDEX, region, collectionId + "/" + indexName);
        if (records.get(key).isPresent()) {
            throw new AwsException("ConflictException", "Index " + indexName + " already exists", 400);
        }
        ObjectNode index = objectMapper.createObjectNode();
        index.set("indexSchema", request.get("indexSchema") instanceof ObjectNode schema
                ? schema.deepCopy() : objectMapper.createObjectNode());
        records.put(key, index);
    }

    public ObjectNode getIndex(JsonNode request, String region) {
        String collectionId = requireCollection(request, region);
        String indexName = requireIndexName(request);
        return records.get(key(INDEX, region, collectionId + "/" + indexName))
                .orElseThrow(() -> notFound("Index " + indexName + " not found")).deepCopy();
    }

    public synchronized void updateIndex(JsonNode request, String region) {
        ObjectNode index = getIndex(request, region);
        if (request.get("indexSchema") instanceof ObjectNode schema) {
            index.set("indexSchema", schema.deepCopy());
        }
        records.put(key(INDEX, region, text(request, "id") + "/" + text(request, "indexName")), index);
    }

    public synchronized void deleteIndex(JsonNode request, String region) {
        getIndex(request, region);
        records.delete(key(INDEX, region, text(request, "id") + "/" + text(request, "indexName")));
    }

    private String requireCollection(JsonNode request, String region) {
        String id = text(request, "id");
        if (id == null || collections.findCollectionById(region, id) == null) {
            throw notFound("Collection " + id + " not found");
        }
        return id;
    }

    private static String requireIndexName(JsonNode request) {
        String name = text(request, "indexName");
        if (name == null || name.isBlank()) {
            throw validation("indexName is required");
        }
        return name;
    }

    // ──────────────────────────── Account settings and stats ────────────────────────────

    public ObjectNode getAccountSettings(String region) {
        ObjectNode settings = records.get(key(SETTINGS, region, regionResolver.getAccountId())).orElse(null);
        ObjectNode detail = objectMapper.createObjectNode();
        detail.set("capacityLimits", settings != null ? settings.get("capacityLimits").deepCopy()
                : objectMapper.createObjectNode().put("maxIndexingCapacityInOCU", 10).put("maxSearchCapacityInOCU", 10));
        return detail;
    }

    public synchronized ObjectNode updateAccountSettings(JsonNode request, String region) {
        ObjectNode current = getAccountSettings(region);
        if (request.get("capacityLimits") instanceof ObjectNode limits) {
            ((ObjectNode) current.get("capacityLimits")).setAll(limits.deepCopy());
        }
        records.put(key(SETTINGS, region, regionResolver.getAccountId()), current);
        return current.deepCopy();
    }

    public ObjectNode getPoliciesStats(String region) {
        long access = scan(ACCESS, region).size();
        long lifecycle = scan(LIFECYCLE, region).size();
        long encryption = collections.listSecurityPolicies(region, "encryption", List.of()).size();
        long network = collections.listSecurityPolicies(region, "network", List.of()).size();
        long saml = collections.listSecurityConfigs(region, "saml").size();
        ObjectNode stats = objectMapper.createObjectNode();
        stats.putObject("AccessPolicyStats").put("DataPolicyCount", access);
        stats.putObject("SecurityPolicyStats").put("EncryptionPolicyCount", encryption)
                .put("NetworkPolicyCount", network);
        stats.putObject("SecurityConfigStats").put("SamlConfigCount", saml);
        stats.putObject("LifecyclePolicyStats").put("RetentionPolicyCount", lifecycle);
        stats.put("TotalPolicyCount", access + lifecycle + encryption + network + saml);
        return stats;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private List<ObjectNode> scan(String kind, String region) {
        String prefix = kind + "|" + region + "|";
        return records.scan(k -> k.startsWith(prefix));
    }

    private static void description(JsonNode request, ObjectNode target) {
        String description = text(request, "description");
        if (description != null) {
            if (description.length() > 1000) {
                throw validation("description must be at most 1000 characters");
            }
            target.put("description", description);
        }
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw validation("1 validation error detected: Value '" + name + "' at 'name' failed to satisfy "
                    + "constraint: Member must satisfy regular expression pattern: " + NAME.pattern());
        }
    }

    private static String version(long now) {
        byte[] raw = (now + "_" + randomId(6)).getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String randomId(int length) {
        StringBuilder id = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static String key(String kind, String region, String id) {
        return kind + "|" + region + "|" + id;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }
}
