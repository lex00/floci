package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/**
 * Business logic for the Amazon Bedrock AgentCore control plane (runtime registry).
 *
 * <p>Stateful CRUD only: no real agent execution. Runtimes reach {@code READY}
 * immediately. Each {@code UpdateAgentRuntime} appends an immutable version snapshot.
 */
@ApplicationScoped
public class BedrockAgentCoreControlService {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreControlService.class);

    static final String STATUS_READY = "READY";
    static final String STATUS_DELETING = "DELETING";
    private static final String ARN_SERVICE = "bedrock-agentcore";
    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,47}");
    private static final List<String> NETWORK_MODES = List.of("PUBLIC", "VPC");
    private static final List<String> SERVER_PROTOCOLS = List.of("MCP", "HTTP", "A2A", "AGUI");
    /** The prefix of the custom headers a runtime's request header allowlist may name. */
    public static final String CUSTOM_HEADER_PREFIX = "X-Amzn-Bedrock-AgentCore-Runtime-Custom-";
    private static final int DEFAULT_IDLE_SECONDS = 900;
    private static final int DEFAULT_MAX_LIFETIME_SECONDS = 28800;
    private static final String ID_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int MAX_PAGE = 100;

    private final StorageBackend<String, AgentRuntime> storage;
    private final RegionResolver regionResolver;
    private final BedrockAgentCoreIdentityService identityService;
    // clientToken idempotency: tokens of completed deletes, so a replayed delete succeeds
    // instead of 404ing. In-memory only (reset on restart), and each entry expires after
    // DeletedTokenLedger.TTL, after which a replay 404s like an unknown token.
    private final DeletedTokenLedger deletedRuntimeTokens;
    private final DeletedTokenLedger deletedEndpointTokens;
    private final List<BiConsumer<String, AgentRuntime>> deleteListeners = new CopyOnWriteArrayList<>();

    @Inject
    public BedrockAgentCoreControlService(StorageFactory storageFactory, RegionResolver regionResolver,
                                          BedrockAgentCoreIdentityService identityService, Clock clock) {
        this(storageFactory.create("bedrockagentcore", "bedrock-agentcore-runtimes.json",
                new TypeReference<Map<String, AgentRuntime>>() {}), regionResolver, identityService, clock);
    }

    BedrockAgentCoreControlService(StorageBackend<String, AgentRuntime> storage, RegionResolver regionResolver) {
        this(storage, regionResolver, null);
    }

    BedrockAgentCoreControlService(StorageBackend<String, AgentRuntime> storage, RegionResolver regionResolver,
                                   BedrockAgentCoreIdentityService identityService) {
        this(storage, regionResolver, identityService, Clock.systemUTC());
    }

    BedrockAgentCoreControlService(StorageBackend<String, AgentRuntime> storage, RegionResolver regionResolver,
                                   BedrockAgentCoreIdentityService identityService, Clock clock) {
        this.storage = storage;
        this.regionResolver = regionResolver;
        this.identityService = identityService;
        this.deletedRuntimeTokens = new DeletedTokenLedger(clock);
        this.deletedEndpointTokens = new DeletedTokenLedger(clock);
    }

    public AgentRuntime createAgentRuntime(String name, JsonNode artifact, JsonNode networkConfiguration,
                                           String roleArn, String description, Map<String, String> environmentVariables,
                                           JsonNode authorizerConfiguration, JsonNode protocolConfiguration,
                                           String clientToken, String region) {
        return createAgentRuntime(name, artifact, networkConfiguration, roleArn, description, environmentVariables,
                authorizerConfiguration, protocolConfiguration, null, null, clientToken, region);
    }

    /** As above, with the session lifecycle and the request headers the runtime receives. */
    public AgentRuntime createAgentRuntime(String name, JsonNode artifact, JsonNode networkConfiguration,
                                           String roleArn, String description, Map<String, String> environmentVariables,
                                           JsonNode authorizerConfiguration, JsonNode protocolConfiguration,
                                           JsonNode lifecycleConfiguration, JsonNode requestHeaderConfiguration,
                                           String clientToken, String region) {
        if (clientToken != null) {
            Optional<AgentRuntime> replay = storage.scan(k -> k.startsWith(keyPrefix(region))).stream()
                    .filter(r -> clientToken.equals(r.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "agentRuntimeName must match [a-zA-Z][a-zA-Z0-9_]{0,47}", 400);
        }
        if (artifact == null || artifact.isNull()) {
            throw new AwsException("ValidationException", "agentRuntimeArtifact is required", 400);
        }
        if (networkConfiguration == null || networkConfiguration.isNull()) {
            throw new AwsException("ValidationException", "networkConfiguration is required", 400);
        }
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("ValidationException", "roleArn is required", 400);
        }
        validateConfiguration(networkConfiguration, protocolConfiguration);
        validateRuntimeOptions(networkConfiguration, authorizerConfiguration, requestHeaderConfiguration);

        Instant now = Instant.now();
        String id = name + "-" + randomId();
        String uuid = UUID.randomUUID().toString();

        AgentRuntime runtime = new AgentRuntime();
        runtime.setAgentRuntimeId(id);
        runtime.setAgentRuntimeName(name);
        runtime.setUuid(uuid);
        runtime.setRoleArn(roleArn);
        runtime.setDescription(description);
        runtime.setStatus(STATUS_READY);
        runtime.setLatestVersion(1);
        runtime.setCreatedAt(now);
        runtime.setLastUpdatedAt(now);
        runtime.setAccountId(regionResolver.getAccountId());
        runtime.setAgentRuntimeArtifact(artifact);
        runtime.setNetworkConfiguration(networkConfiguration);
        runtime.setAuthorizerConfiguration(authorizerConfiguration);
        runtime.setProtocolConfiguration(protocolConfiguration);
        runtime.setLifecycleConfiguration(lifecycle(lifecycleConfiguration));
        runtime.setRequestHeaderConfiguration(nullIfMissing(requestHeaderConfiguration));
        runtime.setEnvironmentVariables(environmentVariables != null ? new HashMap<>(environmentVariables) : new HashMap<>());
        runtime.setClientToken(clientToken);
        String workloadName = name + "-" + randomId();
        if (identityService != null) {
            runtime.setWorkloadIdentityArn(
                    identityService.createForRuntime(workloadName, region).getWorkloadIdentityArn());
        } else {
            runtime.setWorkloadIdentityArn(regionResolver.buildArn(ARN_SERVICE, region,
                    "workload-identity-directory/default/workload-identity/" + workloadName));
        }
        runtime.getVersions().add(snapshot(runtime, "1", now));
        runtime.getEndpoints().add(newEndpoint("DEFAULT", "1", null, now));

        storage.put(key(region, id), runtime);
        LOG.infov("Created AgentCore runtime {0} (id={1})", name, id);
        return runtime;
    }

    public AgentRuntime getAgentRuntime(String id, String region) {
        return storage.get(key(region, id))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore runtime not found: " + id, 404));
    }

    /** Returns the snapshot matching {@code version}, or the latest when {@code version} is null. */
    public AgentRuntimeVersion resolveVersion(AgentRuntime runtime, String version) {
        String wanted = version != null ? version : String.valueOf(runtime.getLatestVersion());
        return runtime.getVersions().stream()
                .filter(v -> wanted.equals(v.getVersion()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore runtime version not found: " + wanted, 404));
    }

    /**
     * UpdateAgentRuntime is a PUT that publishes a new version holding exactly the configuration
     * the request carries, so an omitted (null) description, authorizer, protocol or environment
     * is cleared on the new version rather than carried over from the previous one.
     */
    public AgentRuntime updateAgentRuntime(String id, JsonNode artifact, JsonNode networkConfiguration,
                                           String roleArn, String description, Map<String, String> environmentVariables,
                                           JsonNode authorizerConfiguration, JsonNode protocolConfiguration,
                                           String region) {
        return updateAgentRuntime(id, artifact, networkConfiguration, roleArn, description, environmentVariables,
                authorizerConfiguration, protocolConfiguration, null, null, region);
    }

    /** As above, with the session lifecycle and the request headers the runtime receives. */
    public AgentRuntime updateAgentRuntime(String id, JsonNode artifact, JsonNode networkConfiguration,
                                           String roleArn, String description, Map<String, String> environmentVariables,
                                           JsonNode authorizerConfiguration, JsonNode protocolConfiguration,
                                           JsonNode lifecycleConfiguration, JsonNode requestHeaderConfiguration,
                                           String region) {
        if (artifact == null || artifact.isNull()) {
            throw new AwsException("ValidationException", "agentRuntimeArtifact is required", 400);
        }
        if (networkConfiguration == null || networkConfiguration.isNull()) {
            throw new AwsException("ValidationException", "networkConfiguration is required", 400);
        }
        if (roleArn == null || roleArn.isBlank()) {
            throw new AwsException("ValidationException", "roleArn is required", 400);
        }
        validateConfiguration(networkConfiguration, protocolConfiguration);
        validateRuntimeOptions(networkConfiguration, authorizerConfiguration, requestHeaderConfiguration);
        JsonNode lifecycle = lifecycle(lifecycleConfiguration);
        AgentRuntime runtime = getAgentRuntime(id, region);
        Instant now = Instant.now();

        int newVersion = runtime.getLatestVersion() + 1;
        runtime.setLatestVersion(newVersion);
        runtime.setRoleArn(roleArn);
        runtime.setDescription(description);
        runtime.setAgentRuntimeArtifact(artifact);
        runtime.setNetworkConfiguration(networkConfiguration);
        runtime.setAuthorizerConfiguration(authorizerConfiguration);
        runtime.setProtocolConfiguration(protocolConfiguration);
        runtime.setLifecycleConfiguration(lifecycle);
        runtime.setRequestHeaderConfiguration(nullIfMissing(requestHeaderConfiguration));
        runtime.setEnvironmentVariables(environmentVariables != null ? new HashMap<>(environmentVariables) : new HashMap<>());
        runtime.setLastUpdatedAt(now);
        runtime.getVersions().add(snapshot(runtime, String.valueOf(newVersion), now));

        storage.put(key(region, id), runtime);
        LOG.infov("Updated AgentCore runtime {0} to version {1}", id, newVersion);
        return runtime;
    }

    /**
     * The enum members of a runtime's network and protocol configuration, rejected with the API's
     * ValidationException wording. The artifact and the rest of each blob stay opaque.
     */
    private static void validateConfiguration(JsonNode networkConfiguration, JsonNode protocolConfiguration) {
        requireEnum(networkConfiguration.path("networkMode"), "networkConfiguration.networkMode", NETWORK_MODES);
        if (protocolConfiguration != null && !protocolConfiguration.isNull()) {
            requireEnum(protocolConfiguration.path("serverProtocol"), "protocolConfiguration.serverProtocol",
                    SERVER_PROTOCOLS);
        }
    }

    /**
     * VPC mode needs subnets and security groups, which PUBLIC mode refuses. A JWT authorizer needs an
     * OpenID discovery URL. The header allowlist takes {@code Authorization} and the
     * {@code X-Amzn-Bedrock-AgentCore-Runtime-Custom-} headers, up to 20 of them.
     */
    private static void validateRuntimeOptions(JsonNode network, JsonNode authorizer, JsonNode requestHeaders) {
        JsonNode modeConfig = network.path("networkModeConfig");
        if ("VPC".equals(network.path("networkMode").asText())) {
            for (String member : List.of("subnets", "securityGroups")) {
                JsonNode ids = modeConfig.path(member);
                if (!ids.isArray() || ids.isEmpty() || ids.size() > 16) {
                    throw new AwsException("ValidationException",
                            "networkConfiguration.networkModeConfig." + member
                                    + " must list between 1 and 16 ids in VPC mode", 400);
                }
                String prefix = "subnets".equals(member) ? "subnet-" : "sg-";
                for (JsonNode value : ids) {
                    if (!value.isTextual() || !value.asText().matches(prefix + "[0-9a-zA-Z]{8,17}")) {
                        throw SmithyValidationErrors.validationException("Value '" + value.asText() + "' at "
                                + "'networkConfiguration.networkModeConfig." + member
                                + "' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                                + prefix + "[0-9a-zA-Z]{8,17}");
                    }
                }
            }
        } else if (!modeConfig.isMissingNode() && !modeConfig.isNull()) {
            throw new AwsException("ValidationException",
                    "networkConfiguration.networkModeConfig is only allowed in VPC mode", 400);
        }
        JsonNode jwt = authorizer == null ? null : authorizer.path("customJWTAuthorizer");
        if (jwt != null && !jwt.isMissingNode() && !jwt.isNull()) {
            String discoveryUrl = jwt.path("discoveryUrl").asText("");
            if (!discoveryUrl.matches("^.+/\\.well-known/openid-configuration$")) {
                throw new AwsException("ValidationException", "authorizerConfiguration.customJWTAuthorizer."
                        + "discoveryUrl must be an OpenID Connect discovery URL ending in "
                        + "/.well-known/openid-configuration", 400);
            }
        }
        JsonNode allowlist = requestHeaders == null ? null : requestHeaders.path("requestHeaderAllowlist");
        if (allowlist != null && allowlist.isArray()) {
            if (allowlist.size() > 20) {
                throw new AwsException("ValidationException",
                        "requestHeaderConfiguration.requestHeaderAllowlist takes at most 20 headers", 400);
            }
            for (JsonNode header : allowlist) {
                String name = header.asText("");
                if (!"Authorization".equalsIgnoreCase(name)
                        && !name.regionMatches(true, 0, CUSTOM_HEADER_PREFIX, 0, CUSTOM_HEADER_PREFIX.length())) {
                    throw new AwsException("ValidationException", "Header " + name + " cannot be allowlisted: "
                            + "allow Authorization or a header starting with " + CUSTOM_HEADER_PREFIX, 400);
                }
            }
        }
    }

    /**
     * The session lifecycle with AWS's defaults filled in: 900 seconds idle and 28800 seconds in all,
     * each between 60 and 28800, the idle timeout no longer than the lifetime.
     */
    private JsonNode lifecycle(JsonNode requested) {
        int idle = DEFAULT_IDLE_SECONDS;
        int max = DEFAULT_MAX_LIFETIME_SECONDS;
        if (requested != null && !requested.isNull() && !requested.isMissingNode()) {
            if (requested.hasNonNull("idleRuntimeSessionTimeout")) {
                idle = requested.path("idleRuntimeSessionTimeout").asInt();
            }
            if (requested.hasNonNull("maxLifetime")) {
                max = requested.path("maxLifetime").asInt();
            }
        }
        for (Map.Entry<String, Integer> value : Map.of("idleRuntimeSessionTimeout", idle, "maxLifetime", max)
                .entrySet()) {
            if (value.getValue() < 60 || value.getValue() > DEFAULT_MAX_LIFETIME_SECONDS) {
                throw SmithyValidationErrors.validationException("Value '" + value.getValue()
                        + "' at 'lifecycleConfiguration." + value.getKey() + "' failed to satisfy constraint: "
                        + "Member must have value between 60 and 28800");
            }
        }
        if (idle > max) {
            throw new AwsException("ValidationException",
                    "lifecycleConfiguration.idleRuntimeSessionTimeout must not exceed maxLifetime", 400);
        }
        ObjectNode lifecycle = JsonNodeFactory.instance.objectNode();
        lifecycle.put("idleRuntimeSessionTimeout", idle);
        lifecycle.put("maxLifetime", max);
        return lifecycle;
    }

    private static JsonNode nullIfMissing(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : node;
    }

    private static void requireEnum(JsonNode value, String member, List<String> allowed) {
        if (value.isMissingNode() || value.isNull()) {
            throw new AwsException("ValidationException", member + " is required", 400);
        }
        if (!value.isTextual() || !allowed.contains(value.asText())) {
            throw SmithyValidationErrors.validationException(
                    "Value '" + value.asText() + "' at '" + member
                            + "' failed to satisfy constraint: Member must satisfy enum value set: "
                            + allowed);
        }
    }

    public AgentRuntime deleteAgentRuntime(String id, String clientToken, String region) {
        Optional<AgentRuntime> found = storage.get(key(region, id));
        if (found.isEmpty()) {
            if (clientToken != null && deletedRuntimeTokens.contains(tokenKey(region, clientToken))) {
                AgentRuntime marker = new AgentRuntime();
                marker.setAgentRuntimeId(id);
                marker.setStatus(STATUS_DELETING);
                return marker;
            }
            throw new AwsException("ResourceNotFoundException",
                    "AgentCore runtime not found: " + id, 404);
        }
        AgentRuntime runtime = found.get();
        storage.delete(key(region, id));
        deleteListeners.forEach(listener -> listener.accept(region, runtime));
        if (clientToken != null) {
            deletedRuntimeTokens.record(tokenKey(region, clientToken));
        }
        runtime.setStatus(STATUS_DELETING);
        LOG.infov("Deleted AgentCore runtime {0}", id);
        return runtime;
    }

    /** Registers a callback that receives the region and the runtime of every deleted runtime. */
    public void onRuntimeDeleted(BiConsumer<String, AgentRuntime> listener) {
        deleteListeners.add(listener);
    }

    /**
     * The runtime an invoke ARN names, either Floci's {@code agent/<uuid>:<version>} form or the
     * {@code runtime/<agentRuntimeId>} form, or empty when no runtime in the region matches.
     */
    public Optional<AgentRuntime> findRuntimeByInvokeArn(String region, String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6) {
            return Optional.empty();
        }
        String prefix = keyPrefix(region);
        if (parts[5].startsWith("agent/")) {
            String uuid = parts[5].substring("agent/".length());
            return storage.scan(k -> k.startsWith(prefix)).stream()
                    .filter(r -> uuid.equals(r.getUuid()))
                    .findFirst();
        }
        if (parts[5].startsWith("runtime/")) {
            return storage.get(key(region, parts[5].substring("runtime/".length())));
        }
        return Optional.empty();
    }

    private static String tokenKey(String region, String clientToken) {
        return region + " " + clientToken;
    }

    public PaginatedResult<AgentRuntime> listAgentRuntimes(Integer maxResults, String nextToken, String region) {
        String prefix = keyPrefix(region);
        List<AgentRuntime> all = storage.scan(k -> k.startsWith(prefix));
        return Pagination.paginate(all, AgentRuntime::getAgentRuntimeId, maxResults, nextToken,
                MAX_PAGE, "ValidationException");
    }

    public PaginatedResult<AgentRuntimeVersion> listAgentRuntimeVersions(String id, Integer maxResults, String nextToken,
                                                                    String region) {
        AgentRuntime runtime = getAgentRuntime(id, region);
        // Cursor must be zero-padded so lexicographic order matches numeric version order;
        // Pagination.paginate() sorts by the cursor, keeping sort order and resume order consistent.
        return Pagination.paginate(runtime.getVersions(), v -> pad(v.getVersion()), maxResults, nextToken,
                MAX_PAGE, "ValidationException");
    }

    private static String pad(String version) {
        return String.format("%05d", Integer.parseInt(version));
    }

    public String arn(AgentRuntime runtime, String version, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "agent/" + runtime.getUuid() + ":" + version);
    }

    /**
     * Whether a runtime referenced by an invoke ARN exists. Returns {@code true} for
     * inputs that aren't a full runtime ARN (e.g. a bare agent id), since those can't be
     * validated here — the data plane treats them permissively.
     */
    public boolean runtimeArnExists(String region, String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6 || !parts[5].startsWith("agent/")) {
            return true;
        }
        String uuid = parts[5].substring("agent/".length());
        String prefix = keyPrefix(region);
        return storage.scan(k -> k.startsWith(prefix)).stream()
                .anyMatch(r -> uuid.equals(r.getUuid()));
    }

    public String endpointArn(AgentRuntimeEndpoint endpoint, String region) {
        return regionResolver.buildArn(ARN_SERVICE, region, "agentEndpoint/" + endpoint.getUuid());
    }

    // ──────────────────────────── Tagging (Phase 4) ────────────────────────────

    public Map<String, String> getTagsByArn(String region, String arn) {
        return new HashMap<>(getAgentRuntimeByArn(region, arn).getTags());
    }

    public void tagByArn(String region, String arn, Map<String, String> tags) {
        AgentRuntime runtime = getAgentRuntimeByArn(region, arn);
        runtime.getTags().putAll(tags);
        storage.put(key(region, runtime.getAgentRuntimeId()), runtime);
    }

    public void untagByArn(String region, String arn, List<String> keys) {
        AgentRuntime runtime = getAgentRuntimeByArn(region, arn);
        keys.forEach(runtime.getTags()::remove);
        storage.put(key(region, runtime.getAgentRuntimeId()), runtime);
    }

    /** The runtime a runtime ARN names, at any version; ResourceNotFoundException when none does. */
    public AgentRuntime getAgentRuntimeByArn(String region, String arn) {
        String uuid = uuidFromArn(arn);
        String prefix = keyPrefix(region);
        return storage.scan(k -> k.startsWith(prefix)).stream()
                .filter(r -> uuid.equals(r.getUuid()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore resource not found: " + arn, 404));
    }

    private static String uuidFromArn(String arn) {
        // arn:aws:bedrock-agentcore:<region>:<account>:agent/<uuid>:<version>
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6 || !parts[5].startsWith("agent/")) {
            throw new AwsException("ValidationException", "Unsupported resource ARN: " + arn, 400);
        }
        return parts[5].substring("agent/".length());
    }

    // ──────────────────────────── Endpoints (Phase 2) ────────────────────────────

    public AgentRuntimeEndpoint createEndpoint(String runtimeId, String name, String agentRuntimeVersion,
                                               String description, String clientToken, String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        if (clientToken != null) {
            Optional<AgentRuntimeEndpoint> replay = runtime.getEndpoints().stream()
                    .filter(e -> clientToken.equals(e.getClientToken()))
                    .findFirst();
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    "endpoint name must match [a-zA-Z][a-zA-Z0-9_]{0,47}", 400);
        }
        boolean exists = runtime.getEndpoints().stream().anyMatch(e -> name.equals(e.getName()));
        if (exists) {
            throw new AwsException("ConflictException",
                    "AgentCore runtime endpoint already exists: " + name, 409);
        }
        String version = agentRuntimeVersion != null ? agentRuntimeVersion
                : String.valueOf(runtime.getLatestVersion());
        requirePublishedVersion(runtime, version);
        AgentRuntimeEndpoint endpoint = newEndpoint(name, version, description, Instant.now());
        endpoint.setClientToken(clientToken);
        runtime.getEndpoints().add(endpoint);
        storage.put(key(region, runtimeId), runtime);
        LOG.infov("Created AgentCore endpoint {0} on runtime {1}", name, runtimeId);
        return endpoint;
    }

    public AgentRuntimeEndpoint getEndpoint(String runtimeId, String name, String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        return findEndpoint(runtime, name);
    }

    /** UpdateAgentRuntimeEndpoint on the API: a null version or description is left as it is. */
    public AgentRuntimeEndpoint updateEndpoint(String runtimeId, String name, String agentRuntimeVersion,
                                               String description, String region) {
        return updateEndpoint(runtimeId, name, agentRuntimeVersion, description, false, region);
    }

    /**
     * Updates an endpoint from arguments that are its whole declared configuration when
     * {@code replaceOmitted} is true: a null description then clears the stored one, which the API
     * call leaves alone. A null version keeps the endpoint on the version it serves either way, as
     * UpdateAgentRuntimeEndpoint does.
     */
    public AgentRuntimeEndpoint updateEndpoint(String runtimeId, String name, String agentRuntimeVersion,
                                               String description, boolean replaceOmitted, String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        AgentRuntimeEndpoint endpoint = findEndpoint(runtime, name);
        if (agentRuntimeVersion != null) {
            requirePublishedVersion(runtime, agentRuntimeVersion);
            endpoint.setTargetVersion(agentRuntimeVersion);
            endpoint.setLiveVersion(agentRuntimeVersion);
        }
        if (description != null || replaceOmitted) {
            endpoint.setDescription(description);
        }
        endpoint.setLastUpdatedAt(Instant.now());
        storage.put(key(region, runtimeId), runtime);
        return endpoint;
    }

    /**
     * An endpoint can only target a version the runtime actually published. Storing an unknown one
     * produced a READY endpoint whose generated runtime ARN resolves to nothing.
     */
    private void requirePublishedVersion(AgentRuntime runtime, String version) {
        boolean published = runtime.getVersions().stream()
                .anyMatch(v -> version.equals(v.getVersion()));
        if (!published) {
            throw new AwsException("ValidationException",
                    "AgentCore runtime has no version " + version, 400);
        }
    }

    public AgentRuntimeEndpoint deleteEndpoint(String runtimeId, String name, String clientToken, String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        Optional<AgentRuntimeEndpoint> found = runtime.getEndpoints().stream()
                .filter(e -> e.getName().equals(name)).findFirst();
        if (found.isEmpty()) {
            if (clientToken != null && deletedEndpointTokens.contains(tokenKey(region, clientToken))) {
                AgentRuntimeEndpoint marker = new AgentRuntimeEndpoint();
                marker.setName(name);
                marker.setStatus(STATUS_DELETING);
                return marker;
            }
            throw new AwsException("ResourceNotFoundException",
                    "AgentCore runtime endpoint not found: " + name, 404);
        }
        AgentRuntimeEndpoint endpoint = found.get();
        runtime.getEndpoints().removeIf(e -> name.equals(e.getName()));
        if (clientToken != null) {
            deletedEndpointTokens.record(tokenKey(region, clientToken));
        }
        endpoint.setStatus(STATUS_DELETING);
        storage.put(key(region, runtimeId), runtime);
        return endpoint;
    }

    public PaginatedResult<AgentRuntimeEndpoint> listEndpoints(String runtimeId, Integer maxResults, String nextToken,
                                                          String region) {
        AgentRuntime runtime = getAgentRuntime(runtimeId, region);
        return Pagination.paginate(runtime.getEndpoints(), AgentRuntimeEndpoint::getName, maxResults, nextToken,
                MAX_PAGE, "ValidationException");
    }

    /** An endpoint together with the runtime that holds it. */
    public record EndpointLocation(AgentRuntime runtime, AgentRuntimeEndpoint endpoint) {
    }

    /**
     * The endpoint an endpoint ARN ({@code agentEndpoint/<uuid>}) names, with its runtime, or empty
     * when no runtime in the region holds it. Endpoints are addressed by runtime id and name on the
     * API, so this is the only way back from the ARN CloudFormation keeps as the physical id.
     */
    public Optional<EndpointLocation> findEndpointByArn(String region, String arn) {
        String[] parts = arn == null ? new String[0] : arn.split(":");
        if (parts.length < 6 || !parts[5].startsWith("agentEndpoint/")) {
            return Optional.empty();
        }
        String uuid = parts[5].substring("agentEndpoint/".length());
        String prefix = keyPrefix(region);
        for (AgentRuntime runtime : storage.scan(k -> k.startsWith(prefix))) {
            for (AgentRuntimeEndpoint endpoint : runtime.getEndpoints()) {
                if (uuid.equals(endpoint.getUuid())) {
                    return Optional.of(new EndpointLocation(runtime, endpoint));
                }
            }
        }
        return Optional.empty();
    }

    private AgentRuntimeEndpoint findEndpoint(AgentRuntime runtime, String name) {
        return runtime.getEndpoints().stream()
                .filter(e -> e.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore runtime endpoint not found: " + name, 404));
    }

    private AgentRuntimeEndpoint newEndpoint(String name, String version, String description, Instant now) {
        AgentRuntimeEndpoint endpoint = new AgentRuntimeEndpoint();
        endpoint.setName(name);
        endpoint.setUuid(UUID.randomUUID().toString());
        endpoint.setTargetVersion(version);
        endpoint.setLiveVersion(version);
        endpoint.setStatus(STATUS_READY);
        endpoint.setDescription(description);
        endpoint.setCreatedAt(now);
        endpoint.setLastUpdatedAt(now);
        return endpoint;
    }

    private AgentRuntimeVersion snapshot(AgentRuntime runtime, String version, Instant now) {
        AgentRuntimeVersion snap = new AgentRuntimeVersion();
        snap.setVersion(version);
        snap.setCreatedAt(now);
        snap.setRoleArn(runtime.getRoleArn());
        snap.setDescription(runtime.getDescription());
        snap.setAgentRuntimeArtifact(runtime.getAgentRuntimeArtifact());
        snap.setNetworkConfiguration(runtime.getNetworkConfiguration());
        snap.setAuthorizerConfiguration(runtime.getAuthorizerConfiguration());
        snap.setProtocolConfiguration(runtime.getProtocolConfiguration());
        snap.setLifecycleConfiguration(runtime.getLifecycleConfiguration());
        snap.setRequestHeaderConfiguration(runtime.getRequestHeaderConfiguration());
        snap.setEnvironmentVariables(runtime.getEnvironmentVariables() != null
                ? new HashMap<>(runtime.getEnvironmentVariables()) : null);
        return snap;
    }


    private static String key(String region, String id) {
        return keyPrefix(region) + id;
    }

    private static String keyPrefix(String region) {
        return "runtime:" + region + ":";
    }

    private static String randomId() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append(ID_ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
