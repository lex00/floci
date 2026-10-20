package io.github.hectorvent.floci.services.opensearchserverless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.opensearchserverless.model.SecurityConfig;
import io.github.hectorvent.floci.services.opensearchserverless.model.SecurityPolicy;
import io.github.hectorvent.floci.services.opensearchserverless.model.ServerlessCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * OpenSearch Serverless control plane. It stores security configurations and policies, and the
 * collections the policies cover. A collection holds metadata only, with no search engine behind it.
 */
@ApplicationScoped
public class OpenSearchServerlessService implements Resettable {

    private static final Pattern CONFIG_NAME = Pattern.compile("[a-z][a-z0-9-]{2,31}");
    private static final Pattern POLICY_NAME = Pattern.compile("[a-z][a-z0-9-]{2,31}");
    private static final Pattern COLLECTION_NAME = Pattern.compile("[a-z][a-z0-9-]{2,31}");
    private static final Set<String> CONFIG_TYPES = Set.of("saml", "iamidentitycenter", "iamfederation");
    private static final Set<String> POLICY_TYPES = Set.of("encryption", "network");
    private static final Set<String> COLLECTION_TYPES = Set.of("SEARCH", "TIMESERIES", "VECTORSEARCH");
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicLong VERSION_SEQUENCE = new AtomicLong();

    private final StorageBackend<String, SecurityConfig> configs;
    private final StorageBackend<String, SecurityPolicy> policies;
    private final StorageBackend<String, ServerlessCollection> collections;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public OpenSearchServerlessService(StorageFactory storageFactory, RegionResolver regionResolver,
                                       ObjectMapper objectMapper) {
        this(storageFactory.create("opensearchserverless", "opensearchserverless-security-configs.json",
                        new TypeReference<Map<String, SecurityConfig>>() {}),
                storageFactory.create("opensearchserverless", "opensearchserverless-security-policies.json",
                        new TypeReference<Map<String, SecurityPolicy>>() {}),
                storageFactory.create("opensearchserverless", "opensearchserverless-collections.json",
                        new TypeReference<Map<String, ServerlessCollection>>() {}),
                regionResolver, objectMapper);
    }

    OpenSearchServerlessService(StorageBackend<String, SecurityConfig> configs,
                                StorageBackend<String, SecurityPolicy> policies,
                                StorageBackend<String, ServerlessCollection> collections,
                                RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.configs = configs;
        this.policies = policies;
        this.collections = collections;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public void clear() {
        configs.clear();
        policies.clear();
        collections.clear();
    }

    // ── Security configurations ────────────────────────────────────────────────

    public synchronized SecurityConfig createSecurityConfig(String region, String type, String name,
                                                            String description, JsonNode samlOptions,
                                                            JsonNode iamIdentityCenterOptions,
                                                            JsonNode iamFederationOptions) {
        requireEnum("type", type, CONFIG_TYPES);
        requirePattern("name", name, CONFIG_NAME);
        validateDescription(description);
        switch (type) {
            case "saml" -> {
                if (samlOptions == null || !samlOptions.path("metadata").isTextual()) {
                    throw validation("samlOptions with metadata is required for a security configuration of type saml");
                }
            }
            case "iamidentitycenter" -> {
                if (iamIdentityCenterOptions == null || !iamIdentityCenterOptions.path("instanceArn").isTextual()) {
                    throw validation("iamIdentityCenterOptions with instanceArn is required for a security "
                            + "configuration of type iamidentitycenter");
                }
            }
            default -> {
            }
        }
        String id = type + "/" + regionResolver.getAccountId() + "/" + name;
        String key = key(region, id);
        if (configs.get(key).isPresent()) {
            throw new AwsException("ConflictException",
                    "Security configuration with name " + name + " already exists", 400);
        }
        long now = System.currentTimeMillis();
        SecurityConfig config = new SecurityConfig();
        config.setId(id);
        config.setType(type);
        config.setName(name);
        config.setRegion(region);
        config.setDescription(description);
        config.setSamlOptions(samlOptions);
        config.setIamIdentityCenterOptions("iamidentitycenter".equals(type)
                ? identityCenterOptions(iamIdentityCenterOptions) : null);
        config.setIamFederationOptions(iamFederationOptions);
        config.setCreatedDate(now);
        config.setLastModifiedDate(now);
        config.setConfigVersion(newVersion(now));
        configs.put(key, config);
        return config;
    }

    public SecurityConfig getSecurityConfig(String region, String id) {
        requireText("id", id);
        return configs.get(key(region, id)).orElseThrow(() -> notFound("Security configuration " + id + " not found"));
    }

    public synchronized SecurityConfig updateSecurityConfig(String region, String id, String configVersion,
                                                            String description, JsonNode samlOptions,
                                                            JsonNode identityCenterUpdates,
                                                            JsonNode iamFederationOptions) {
        requireText("configVersion", configVersion);
        SecurityConfig config = getSecurityConfig(region, id);
        if (!configVersion.equals(config.getConfigVersion())) {
            throw validation("Config version specified in the request refers to an older version "
                    + "and config has since changed");
        }
        validateDescription(description);
        if (description != null) {
            config.setDescription(description);
        }
        if (samlOptions != null) {
            if (!"saml".equals(config.getType()) || !samlOptions.path("metadata").isTextual()) {
                throw validation("samlOptions with metadata applies only to a security configuration of type saml");
            }
            config.setSamlOptions(samlOptions);
        }
        if (identityCenterUpdates != null && config.getIamIdentityCenterOptions() instanceof ObjectNode options) {
            copyText(identityCenterUpdates, options, "userAttribute");
            copyText(identityCenterUpdates, options, "groupAttribute");
        }
        if (iamFederationOptions != null) {
            config.setIamFederationOptions(iamFederationOptions);
        }
        long now = System.currentTimeMillis();
        config.setLastModifiedDate(now);
        config.setConfigVersion(newVersion(now));
        configs.put(key(region, id), config);
        return config;
    }

    public synchronized void deleteSecurityConfig(String region, String id) {
        getSecurityConfig(region, id);
        configs.delete(key(region, id));
    }

    public List<SecurityConfig> listSecurityConfigs(String region, String type) {
        requireEnum("type", type, CONFIG_TYPES);
        return configs.scan(k -> k.startsWith(region + "|")).stream()
                .filter(c -> type.equals(c.getType()))
                .sorted(Comparator.comparing(SecurityConfig::getId))
                .toList();
    }

    // ── Security policies ──────────────────────────────────────────────────────

    public synchronized SecurityPolicy createSecurityPolicy(String region, String type, String name,
                                                            String description, String policy) {
        requireEnum("type", type, POLICY_TYPES);
        requirePattern("name", name, POLICY_NAME);
        validateDescription(description);
        validatePolicyDocument(type, policy);
        String key = key(region, type + "/" + name);
        if (policies.get(key).isPresent()) {
            throw new AwsException("ConflictException", "Policy with name " + name + " and type " + type
                    + " already exists", 400);
        }
        long now = System.currentTimeMillis();
        SecurityPolicy securityPolicy = new SecurityPolicy();
        securityPolicy.setType(type);
        securityPolicy.setName(name);
        securityPolicy.setRegion(region);
        securityPolicy.setDescription(description);
        securityPolicy.setPolicy(policy);
        securityPolicy.setCreatedDate(now);
        securityPolicy.setLastModifiedDate(now);
        securityPolicy.setPolicyVersion(newVersion(now));
        policies.put(key, securityPolicy);
        return securityPolicy;
    }

    public SecurityPolicy getSecurityPolicy(String region, String type, String name) {
        requireEnum("type", type, POLICY_TYPES);
        requireText("name", name);
        return policies.get(key(region, type + "/" + name))
                .orElseThrow(() -> notFound("Policy with name " + name + " and type " + type + " is not found"));
    }

    public synchronized SecurityPolicy updateSecurityPolicy(String region, String type, String name,
                                                            String policyVersion, String description,
                                                            String policy) {
        requireText("policyVersion", policyVersion);
        SecurityPolicy securityPolicy = getSecurityPolicy(region, type, name);
        if (!policyVersion.equals(securityPolicy.getPolicyVersion())) {
            throw validation("Policy version specified in the request refers to an older version "
                    + "and policy has since changed");
        }
        validateDescription(description);
        if (description != null) {
            securityPolicy.setDescription(description);
        }
        if (policy != null) {
            validatePolicyDocument(type, policy);
            securityPolicy.setPolicy(policy);
        }
        long now = System.currentTimeMillis();
        securityPolicy.setLastModifiedDate(now);
        securityPolicy.setPolicyVersion(newVersion(now));
        policies.put(key(region, type + "/" + name), securityPolicy);
        return securityPolicy;
    }

    public synchronized void deleteSecurityPolicy(String region, String type, String name) {
        getSecurityPolicy(region, type, name);
        policies.delete(key(region, type + "/" + name));
    }

    public List<SecurityPolicy> listSecurityPolicies(String region, String type, List<String> resources) {
        requireEnum("type", type, POLICY_TYPES);
        return policies.scan(k -> k.startsWith(region + "|")).stream()
                .filter(p -> type.equals(p.getType()))
                .filter(p -> resources == null || resources.isEmpty()
                        || resources.stream().anyMatch(r -> policyCovers(p, r)))
                .sorted(Comparator.comparing(SecurityPolicy::getName))
                .toList();
    }

    // ── Collections ────────────────────────────────────────────────────────────

    public synchronized ServerlessCollection createCollection(String region, String name, String type,
                                                              String description, Map<String, String> tags,
                                                              String standbyReplicas, JsonNode vectorOptions,
                                                              String collectionGroupName) {
        requirePattern("name", name, COLLECTION_NAME);
        String effectiveType = type == null ? "SEARCH" : type;
        requireEnum("type", effectiveType, COLLECTION_TYPES);
        boolean nameTaken = collections.scan(k -> k.startsWith(region + "|")).stream()
                .anyMatch(c -> name.equals(c.getName()));
        if (nameTaken) {
            throw new AwsException("ConflictException", "Collection with name " + name + " already exists", 400);
        }
        SecurityPolicy encryption = policies.scan(k -> k.startsWith(region + "|")).stream()
                .filter(p -> "encryption".equals(p.getType()))
                .filter(p -> policyCovers(p, "collection/" + name))
                .min(Comparator.comparing(SecurityPolicy::getName))
                .orElseThrow(() -> validation("No matching security policy of encryption type found for collection "
                        + "name: " + name + ". Please create security policy of encryption type for this collection."));
        String id = randomId();
        long now = System.currentTimeMillis();
        ServerlessCollection collection = new ServerlessCollection();
        collection.setId(id);
        collection.setName(name);
        collection.setRegion(region);
        collection.setArn(regionResolver.buildArn("aoss", region, "collection/" + id));
        collection.setType(effectiveType);
        collection.setDescription(description);
        collection.setStatus("ACTIVE");
        collection.setKmsKeyArn(kmsKeyArn(encryption));
        collection.setStandbyReplicas(standbyReplicas == null ? "ENABLED" : standbyReplicas);
        collection.setVectorOptions(vectorOptions);
        collection.setCollectionGroupName(collectionGroupName);
        collection.setCreatedDate(now);
        collection.setLastModifiedDate(now);
        if (tags != null) {
            collection.getTags().putAll(tags);
        }
        collections.put(key(region, id), collection);
        return collection;
    }

    public List<ServerlessCollection> listCollections(String region) {
        return collections.scan(k -> k.startsWith(region + "|")).stream()
                .sorted(Comparator.comparing(ServerlessCollection::getName))
                .toList();
    }

    public ServerlessCollection findCollectionById(String region, String id) {
        return id == null ? null : collections.get(key(region, id)).orElse(null);
    }

    public ServerlessCollection findCollectionByName(String region, String name) {
        return listCollections(region).stream().filter(c -> c.getName().equals(name)).findFirst().orElse(null);
    }

    public synchronized ServerlessCollection updateCollection(String region, String id, String description) {
        ServerlessCollection collection = findCollectionById(region, id);
        if (collection == null) {
            throw notFound("Collection " + id + " not found");
        }
        if (description != null) {
            collection.setDescription(description);
        }
        collection.setLastModifiedDate(System.currentTimeMillis());
        collections.put(key(region, id), collection);
        return collection;
    }

    public synchronized ServerlessCollection deleteCollection(String region, String id) {
        requireText("id", id);
        ServerlessCollection collection = findCollectionById(region, id);
        if (collection == null) {
            throw notFound("Collection " + id + " not found");
        }
        collections.delete(key(region, id));
        collection.setStatus("DELETING");
        return collection;
    }

    public synchronized void tagResource(String region, String arn, Map<String, String> tags) {
        ServerlessCollection collection = collectionForArn(region, arn);
        collection.getTags().putAll(tags);
        collections.put(key(region, collection.getId()), collection);
    }

    public synchronized void untagResource(String region, String arn, List<String> keys) {
        ServerlessCollection collection = collectionForArn(region, arn);
        keys.forEach(collection.getTags()::remove);
        collections.put(key(region, collection.getId()), collection);
    }

    public Map<String, String> listTags(String region, String arn) {
        return collectionForArn(region, arn).getTags();
    }

    /** The collection's data-plane endpoint, under the region's DNS suffix. */
    public String collectionEndpoint(ServerlessCollection collection) {
        return "https://" + collection.getId() + "." + collection.getRegion() + ".aoss."
                + AwsRegions.dnsSuffixFor(collection.getRegion());
    }

    private ServerlessCollection collectionForArn(String region, String arn) {
        requireText("resourceArn", arn);
        return collections.scan(k -> k.startsWith(region + "|")).stream()
                .filter(c -> arn.equals(c.getArn()))
                .findFirst()
                .orElseThrow(() -> notFound("Resource " + arn + " not found"));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean policyCovers(SecurityPolicy policy, String resource) {
        JsonNode document = parse(policy.getPolicy());
        if (document == null) {
            return false;
        }
        if (document.isArray()) {
            for (JsonNode entry : document) {
                if (rulesCover(entry.path("Rules"), resource)) {
                    return true;
                }
            }
            return false;
        }
        return rulesCover(document.path("Rules"), resource);
    }

    private static boolean rulesCover(JsonNode rules, String resource) {
        for (JsonNode rule : rules) {
            for (JsonNode pattern : rule.path("Resource")) {
                if (globMatches(pattern.asText(), resource)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean globMatches(String pattern, String value) {
        String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return value.matches(regex);
    }

    private String kmsKeyArn(SecurityPolicy encryption) {
        JsonNode document = parse(encryption.getPolicy());
        if (document != null && document.path("KmsARN").isTextual()) {
            return document.path("KmsARN").asText();
        }
        return "auto";
    }

    private void validatePolicyDocument(String type, String policy) {
        requireText("policy", policy);
        JsonNode document = parse(policy);
        boolean valid = document != null
                && ("encryption".equals(type) ? document.isObject() && document.path("Rules").isArray()
                        : document.isArray());
        if (!valid) {
            throw validation("Policy json is invalid, error: [$: Invalid " + type + " policy document]");
        }
    }

    private JsonNode parse(String document) {
        try {
            return objectMapper.readTree(document);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private JsonNode identityCenterOptions(JsonNode requested) {
        ObjectNode options = objectMapper.createObjectNode();
        String instanceArn = requested.path("instanceArn").asText();
        options.put("instanceArn", instanceArn);
        String instanceId = instanceArn.substring(instanceArn.lastIndexOf('/') + 1);
        options.put("applicationArn", regionResolver.buildGlobalArn("sso",
                "application/" + instanceId + "/apl-" + randomHex(16)));
        options.put("applicationName", "OpenSearchServerless-" + regionResolver.getAccountId());
        options.put("applicationDescription", "OpenSearch Serverless application");
        options.put("userAttribute", requested.path("userAttribute").asText("UserId"));
        options.put("groupAttribute", requested.path("groupAttribute").asText("GroupId"));
        return options;
    }

    private static void copyText(JsonNode from, ObjectNode to, String field) {
        if (from.path(field).isTextual()) {
            to.put(field, from.path(field).asText());
        }
    }

    private static String newVersion(long now) {
        String raw = now + "_" + VERSION_SEQUENCE.incrementAndGet();
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomId() {
        StringBuilder id = new StringBuilder(20);
        for (int i = 0; i < 20; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static String randomHex(int length) {
        StringBuilder hex = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            hex.append(Character.forDigit(RANDOM.nextInt(16), 16));
        }
        return hex.toString();
    }

    private static String key(String region, String id) {
        return region + "|" + id;
    }

    private static void requireText(String field, String value) {
        if (value == null || value.isBlank()) {
            throw validation("1 validation error detected: Value null at '" + field
                    + "' failed to satisfy constraint: Member must not be null");
        }
    }

    private static void requireEnum(String field, String value, Set<String> allowed) {
        requireText(field, value);
        if (!allowed.contains(value)) {
            throw validation("1 validation error detected: Value '" + value + "' at '" + field
                    + "' failed to satisfy constraint: Member must satisfy enum value set: "
                    + allowed.stream().sorted().toList());
        }
    }

    private static void requirePattern(String field, String value, Pattern pattern) {
        requireText(field, value);
        if (!pattern.matcher(value).matches()) {
            throw validation("1 validation error detected: Value '" + value + "' at '" + field
                    + "' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + "[a-z][a-z0-9-]+ and have length between 3 and 32");
        }
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > 1000) {
            throw validation("1 validation error detected: Value at 'description' failed to satisfy constraint: "
                    + "Member must have length less than or equal to 1000");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }
}
