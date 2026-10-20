package io.github.hectorvent.floci.services.workspaces;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Amazon WorkSpaces control plane. A WorkSpace is {@code AVAILABLE} on its first describe and a
 * connection alias is {@code CREATED} at once. No desktops run.
 */
@ApplicationScoped
public class WorkSpacesService implements Resettable {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final TypeReference<Map<String, ObjectNode>> NODES = new TypeReference<>() {};
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final Set<String> RUNNING_MODES = Set.of("AUTO_STOP", "ALWAYS_ON");

    private static final List<String> DEVICE_TYPES = List.of("DeviceTypeWindows", "DeviceTypeOsx",
            "DeviceTypeWeb", "DeviceTypeIos", "DeviceTypeAndroid", "DeviceTypeChromeOs", "DeviceTypeZeroClient",
            "DeviceTypeLinux", "DeviceTypeWorkSpacesThinClient");

    /** {@code PropertiesToDelete} values mapped to the property member each one clears. */
    private static final Map<String, String> DELETABLE_PROPERTIES = Map.of(
            "SAML_PROPERTIES_USER_ACCESS_URL", "UserAccessUrl",
            "SAML_PROPERTIES_RELAY_STATE_PARAMETER_NAME", "RelayStateParameterName",
            "CERTIFICATE_BASED_AUTH_PROPERTIES_CERTIFICATE_AUTHORITY_ARN", "CertificateAuthorityArn");

    /** Amazon-owned bundles: id, name, image id, compute type, root and user volume GiB. */
    private static final List<String[]> AMAZON_BUNDLES = List.of(
            new String[] {"wsb-bh8rsxt14", "Value with Windows 10 (Server 2019 based) (English)",
                "wsi-1f6vt5bw5", "VALUE", "80", "10"},
            new String[] {"wsb-gm4d5tx2v", "Standard with Windows 10 (Server 2019 based) (English)",
                "wsi-1f6vt5bw5", "STANDARD", "80", "50"},
            new String[] {"wsb-8vbljg4r6", "Performance with Windows 10 (Server 2019 based) (English)",
                "wsi-1f6vt5bw5", "PERFORMANCE", "80", "100"},
            new String[] {"wsb-1pzkp0bx4", "Standard with Windows Server 2022 (English)",
                "wsi-2c8sxxm1q", "STANDARD", "80", "50"},
            new String[] {"wsb-clj85qzj1", "Standard with Amazon Linux 2 (English)",
                "wsi-6nlnt9k1h", "STANDARD", "80", "50"},
            new String[] {"wsb-3t36q0xfc", "Power with Ubuntu 22.04 (English)",
                "wsi-9h0b4fwkd", "POWER", "175", "100"});
    private static final double AMAZON_BUNDLE_UPDATED = 1735689600.0;

    private final StorageBackend<String, ObjectNode> ipGroups;
    private final StorageBackend<String, ObjectNode> aliases;
    private final StorageBackend<String, ObjectNode> workspaces;
    private final StorageBackend<String, ObjectNode> pools;
    private final StorageBackend<String, ObjectNode> tags;
    private final StorageBackend<String, ObjectNode> directories;
    private final StorageBackend<String, ObjectNode> bundles;
    private final RegionResolver regionResolver;

    @Inject
    public WorkSpacesService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("workspaces", "workspaces-ip-groups.json", NODES),
                storageFactory.create("workspaces", "workspaces-connection-aliases.json", NODES),
                storageFactory.create("workspaces", "workspaces-workspaces.json", NODES),
                storageFactory.create("workspaces", "workspaces-pools.json", NODES),
                storageFactory.create("workspaces", "workspaces-tags.json", NODES),
                storageFactory.create("workspaces", "workspaces-directories.json", NODES),
                storageFactory.create("workspaces", "workspaces-bundles.json", NODES),
                regionResolver);
    }

    WorkSpacesService(StorageBackend<String, ObjectNode> ipGroups, StorageBackend<String, ObjectNode> aliases,
                      StorageBackend<String, ObjectNode> workspaces, StorageBackend<String, ObjectNode> pools,
                      StorageBackend<String, ObjectNode> tags, StorageBackend<String, ObjectNode> directories,
                      StorageBackend<String, ObjectNode> bundles, RegionResolver regionResolver) {
        this.ipGroups = ipGroups;
        this.aliases = aliases;
        this.workspaces = workspaces;
        this.pools = pools;
        this.tags = tags;
        this.directories = directories;
        this.bundles = bundles;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        ipGroups.clear();
        aliases.clear();
        workspaces.clear();
        pools.clear();
        tags.clear();
        directories.clear();
        bundles.clear();
    }

    // ──────────────────────────── IP groups ────────────────────────────

    public synchronized String createIpGroup(JsonNode request, String region) {
        String name = required(request, "GroupName");
        for (ObjectNode group : ipGroups.scan(k -> k.startsWith(region + "::"))) {
            if (name.equals(group.path("groupName").asText())) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "The IP access control group " + name + " already exists.", 400);
            }
        }
        String id = newId("wsipg-");
        ObjectNode group = JSON.objectNode();
        group.put("groupId", id);
        group.put("groupName", name);
        if (request.hasNonNull("GroupDesc")) {
            group.put("groupDesc", request.path("GroupDesc").asText());
        }
        group.set("userRules", rules(request.path("UserRules")));
        ipGroups.put(key(region, id), group);
        writeTags(region, id, request.path("Tags"));
        return id;
    }

    public List<ObjectNode> describeIpGroups(JsonNode groupIds, String region) {
        if (groupIds != null && groupIds.isArray() && !groupIds.isEmpty()) {
            List<ObjectNode> result = new ArrayList<>();
            for (JsonNode id : groupIds) {
                ipGroups.get(key(region, id.asText())).ifPresent(g -> result.add(g.deepCopy()));
            }
            return result;
        }
        return sorted(ipGroups.scan(k -> k.startsWith(region + "::")), "groupId");
    }

    public synchronized void deleteIpGroup(String groupId, String region) {
        requireIpGroup(groupId, region);
        ipGroups.delete(key(region, groupId));
        tags.delete(key(region, groupId));
    }

    public synchronized void authorizeIpRules(String groupId, JsonNode userRules, String region) {
        ObjectNode group = requireIpGroup(groupId, region);
        ArrayNode current = (ArrayNode) group.get("userRules");
        Set<String> present = new LinkedHashSet<>();
        current.forEach(rule -> present.add(rule.path("ipRule").asText()));
        for (JsonNode rule : rules(userRules)) {
            if (present.add(rule.path("ipRule").asText())) {
                current.add(rule);
            }
        }
        ipGroups.put(key(region, groupId), group);
    }

    public synchronized void revokeIpRules(String groupId, JsonNode revoked, String region) {
        ObjectNode group = requireIpGroup(groupId, region);
        Set<String> removed = new LinkedHashSet<>();
        revoked.forEach(rule -> removed.add(rule.asText()));
        ArrayNode kept = JSON.arrayNode();
        group.get("userRules").forEach(rule -> {
            if (!removed.contains(rule.path("ipRule").asText())) {
                kept.add(rule);
            }
        });
        group.set("userRules", kept);
        ipGroups.put(key(region, groupId), group);
    }

    public synchronized void updateRulesOfIpGroup(String groupId, JsonNode userRules, String region) {
        ObjectNode group = requireIpGroup(groupId, region);
        group.set("userRules", rules(userRules));
        ipGroups.put(key(region, groupId), group);
    }

    private ObjectNode requireIpGroup(String groupId, String region) {
        return ipGroups.get(key(region, groupId)).orElseThrow(() -> notFound("IP access control group", groupId));
    }

    private static ArrayNode rules(JsonNode requested) {
        ArrayNode rules = JSON.arrayNode();
        for (JsonNode rule : requested) {
            ObjectNode copy = rules.addObject();
            copy.put("ipRule", rule.path("ipRule").asText());
            if (rule.hasNonNull("ruleDesc")) {
                copy.put("ruleDesc", rule.path("ruleDesc").asText());
            }
        }
        return rules;
    }

    // ──────────────────────────── Connection aliases ────────────────────────────

    public synchronized String createConnectionAlias(JsonNode request, String region) {
        String connectionString = required(request, "ConnectionString");
        for (ObjectNode alias : aliases.scan(k -> k.startsWith(region + "::"))) {
            if (connectionString.equals(alias.path("ConnectionString").asText())) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "A connection alias for " + connectionString + " already exists.", 400);
            }
        }
        String id = newId("wsca-");
        ObjectNode alias = JSON.objectNode();
        alias.put("ConnectionString", connectionString);
        alias.put("AliasId", id);
        alias.put("State", "CREATED");
        alias.put("OwnerAccountId", regionResolver.getAccountId());
        alias.putArray("Associations");
        aliases.put(key(region, id), alias);
        writeTags(region, id, request.path("Tags"));
        return id;
    }

    public List<ObjectNode> describeConnectionAliases(JsonNode aliasIds, String region) {
        if (aliasIds != null && aliasIds.isArray() && !aliasIds.isEmpty()) {
            List<ObjectNode> result = new ArrayList<>();
            for (JsonNode id : aliasIds) {
                aliases.get(key(region, id.asText())).ifPresent(a -> result.add(a.deepCopy()));
            }
            return result;
        }
        return sorted(aliases.scan(k -> k.startsWith(region + "::")), "AliasId");
    }

    public synchronized void deleteConnectionAlias(String aliasId, String region) {
        if (aliases.get(key(region, aliasId)).isEmpty()) {
            throw notFound("connection alias", aliasId);
        }
        aliases.delete(key(region, aliasId));
        tags.delete(key(region, aliasId));
    }

    // ──────────────────────────── WorkSpaces ────────────────────────────

    /** Returns the {@code PendingRequests} and {@code FailedRequests} lists, in that order. */
    public synchronized List<List<ObjectNode>> createWorkspaces(JsonNode requests, String region) {
        if (requests == null || !requests.isArray() || requests.isEmpty()) {
            throw new AwsException("InvalidParameterValuesException", "Workspaces must contain at least one request.",
                    400);
        }
        List<ObjectNode> pending = new ArrayList<>();
        List<ObjectNode> failed = new ArrayList<>();
        for (JsonNode request : requests) {
            String directoryId = request.path("DirectoryId").asText("");
            String userName = request.path("UserName").asText("");
            String bundleId = request.path("BundleId").asText("");
            String error = null;
            if (directoryId.isBlank() || userName.isBlank() || bundleId.isBlank()) {
                error = "DirectoryId, UserName and BundleId are required.";
            } else if (findWorkspace(region, directoryId, userName).isPresent()) {
                error = "The specified user already has a WorkSpace in this directory.";
            }
            if (error != null) {
                ObjectNode failure = JSON.objectNode();
                failure.set("WorkspaceRequest", request.deepCopy());
                failure.put("ErrorCode", "ResourceExists.WorkSpace");
                failure.put("ErrorMessage", error);
                failed.add(failure);
                continue;
            }
            String id = newId("ws-");
            ObjectNode workspace = JSON.objectNode();
            workspace.put("WorkspaceId", id);
            workspace.put("DirectoryId", directoryId);
            workspace.put("UserName", userName);
            workspace.put("IpAddress", "10.0." + RANDOM.nextInt(256) + "." + (1 + RANDOM.nextInt(254)));
            workspace.put("State", "AVAILABLE");
            workspace.put("BundleId", bundleId);
            workspace.put("ComputerName", "WSAMZN-" + id.substring(3).toUpperCase());
            for (String member : List.of("VolumeEncryptionKey", "WorkspaceName", "Ipv6Address")) {
                if (request.hasNonNull(member)) {
                    workspace.put(member, request.path(member).asText());
                }
            }
            workspace.put("UserVolumeEncryptionEnabled", request.path("UserVolumeEncryptionEnabled").asBoolean(false));
            workspace.put("RootVolumeEncryptionEnabled", request.path("RootVolumeEncryptionEnabled").asBoolean(false));
            ObjectNode properties = workspace.putObject("WorkspaceProperties");
            properties.put("RunningMode", "AUTO_STOP");
            properties.put("RunningModeAutoStopTimeoutInMinutes", 60);
            properties.put("RootVolumeSizeGib", 80);
            properties.put("UserVolumeSizeGib", 10);
            properties.put("ComputeTypeName", "VALUE");
            if (request.get("WorkspaceProperties") instanceof ObjectNode requested) {
                properties.setAll(requested.deepCopy());
            }
            workspace.putArray("ModificationStates");
            workspaces.put(key(region, id), workspace);
            writeTags(region, id, request.path("Tags"));
            ObjectNode created = workspace.deepCopy();
            created.put("State", "PENDING");
            pending.add(created);
        }
        return List.of(pending, failed);
    }

    public List<ObjectNode> describeWorkspaces(JsonNode request, String region) {
        JsonNode ids = request.get("WorkspaceIds");
        List<ObjectNode> candidates;
        if (ids != null && ids.isArray() && !ids.isEmpty()) {
            candidates = new ArrayList<>();
            for (JsonNode id : ids) {
                workspaces.get(key(region, id.asText())).ifPresent(candidates::add);
            }
        } else {
            candidates = workspaces.scan(k -> k.startsWith(region + "::"));
        }
        return sorted(candidates.stream()
                .filter(w -> matches(request, w, "DirectoryId"))
                .filter(w -> matches(request, w, "UserName"))
                .filter(w -> matches(request, w, "BundleId"))
                .filter(w -> matches(request, w, "WorkspaceName"))
                .toList(), "WorkspaceId");
    }

    public synchronized void modifyWorkspaceProperties(String workspaceId, JsonNode properties, String region) {
        ObjectNode workspace = workspaces.get(key(region, workspaceId))
                .orElseThrow(() -> notFound("WorkSpace", workspaceId));
        if (properties instanceof ObjectNode requested) {
            ((ObjectNode) workspace.get("WorkspaceProperties")).setAll(requested.deepCopy());
        }
        workspaces.put(key(region, workspaceId), workspace);
    }

    /** Returns the {@code FailedRequests} list. */
    public synchronized List<ObjectNode> terminateWorkspaces(JsonNode requests, String region) {
        List<ObjectNode> failed = new ArrayList<>();
        for (JsonNode request : requests) {
            String id = request.path("WorkspaceId").asText();
            if (workspaces.get(key(region, id)).isEmpty()) {
                ObjectNode failure = JSON.objectNode();
                failure.put("WorkspaceId", id);
                failure.put("ErrorCode", "ResourceNotFound.Workspace");
                failure.put("ErrorMessage", "The WorkSpace " + id + " could not be found.");
                failed.add(failure);
                continue;
            }
            workspaces.delete(key(region, id));
            tags.delete(key(region, id));
        }
        return failed;
    }

    private Optional<ObjectNode> findWorkspace(String region, String directoryId, String userName) {
        return workspaces.scan(k -> k.startsWith(region + "::")).stream()
                .filter(w -> directoryId.equals(w.path("DirectoryId").asText())
                        && userName.equalsIgnoreCase(w.path("UserName").asText()))
                .findFirst();
    }

    private static boolean matches(JsonNode request, JsonNode resource, String field) {
        return !request.hasNonNull(field) || request.path(field).asText().equals(resource.path(field).asText());
    }

    // ──────────────────────────── Pools ────────────────────────────

    public synchronized ObjectNode createWorkspacesPool(JsonNode request, String region) {
        String name = required(request, "PoolName");
        required(request, "Description");
        required(request, "BundleId");
        required(request, "DirectoryId");
        if (!request.path("Capacity").has("DesiredUserSessions")) {
            throw new AwsException("InvalidParameterValuesException", "Capacity.DesiredUserSessions is required.", 400);
        }
        for (ObjectNode pool : pools.scan(k -> k.startsWith(region + "::"))) {
            if (name.equals(pool.path("PoolName").asText())) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "The WorkSpaces pool " + name + " already exists.", 400);
            }
        }
        String runningMode = request.path("RunningMode").asText("AUTO_STOP");
        if (!RUNNING_MODES.contains(runningMode)) {
            throw new AwsException("InvalidParameterValuesException", "Invalid RunningMode " + runningMode, 400);
        }
        String id = newId("wspool-");
        ObjectNode pool = JSON.objectNode();
        pool.put("PoolId", id);
        pool.put("PoolArn", regionResolver.buildArn("workspaces", region, "workspacespool/" + id));
        pool.put("PoolName", name);
        pool.put("State", "STOPPED");
        pool.put("CreatedAt", Instant.now().toEpochMilli() / 1000.0);
        pool.put("RunningMode", runningMode);
        pool.putArray("Errors");
        applyPoolUpdate(pool, request, region);
        pools.put(key(region, id), pool);
        writeTags(region, id, request.path("Tags"));
        return pool.deepCopy();
    }

    public List<ObjectNode> describeWorkspacesPools(JsonNode request, String region) {
        JsonNode ids = request.get("PoolIds");
        List<ObjectNode> candidates;
        if (ids != null && ids.isArray() && !ids.isEmpty()) {
            candidates = new ArrayList<>();
            for (JsonNode id : ids) {
                candidates.add(requirePool(id.asText(), region));
            }
        } else {
            candidates = pools.scan(k -> k.startsWith(region + "::"));
        }
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode pool : candidates) {
            if (poolMatchesFilters(pool, request.path("Filters"))) {
                result.add(pool);
            }
        }
        return sorted(result, "PoolId");
    }

    public synchronized ObjectNode updateWorkspacesPool(JsonNode request, String region) {
        String id = required(request, "PoolId");
        ObjectNode pool = requirePool(id, region);
        if (request.hasNonNull("RunningMode")) {
            String runningMode = request.path("RunningMode").asText();
            if (!RUNNING_MODES.contains(runningMode)) {
                throw new AwsException("InvalidParameterValuesException", "Invalid RunningMode " + runningMode, 400);
            }
            pool.put("RunningMode", runningMode);
        }
        applyPoolUpdate(pool, request, region);
        pools.put(key(region, id), pool);
        return pool.deepCopy();
    }

    public synchronized void setPoolRunning(String poolId, String region, boolean running) {
        ObjectNode pool = requirePool(poolId, region);
        pool.put("State", running ? "RUNNING" : "STOPPED");
        pool.set("CapacityStatus", capacityStatus(pool.path("CapacityStatus").path("DesiredUserSessions").asInt(),
                running));
        pools.put(key(region, poolId), pool);
    }

    public synchronized void terminateWorkspacesPool(String poolId, String region) {
        requirePool(poolId, region);
        pools.delete(key(region, poolId));
        tags.delete(key(region, poolId));
    }

    private void applyPoolUpdate(ObjectNode pool, JsonNode request, String region) {
        for (String member : List.of("Description", "BundleId", "DirectoryId")) {
            if (request.hasNonNull(member)) {
                pool.put(member, request.path(member).asText());
            }
        }
        if (request.path("Capacity").has("DesiredUserSessions")) {
            pool.set("CapacityStatus", capacityStatus(request.path("Capacity").path("DesiredUserSessions").asInt(),
                    "RUNNING".equals(pool.path("State").asText())));
        }
        if (request.get("ApplicationSettings") instanceof ObjectNode settings) {
            ObjectNode response = JSON.objectNode();
            String status = settings.path("Status").asText("DISABLED");
            response.put("Status", status);
            if (settings.hasNonNull("SettingsGroup")) {
                response.put("SettingsGroup", settings.path("SettingsGroup").asText());
            }
            if ("ENABLED".equals(status)) {
                response.put("S3BucketName", "wspool-app-settings-" + region + "-" + regionResolver.getAccountId());
            }
            pool.set("ApplicationSettings", response);
        }
        if (request.get("TimeoutSettings") instanceof ObjectNode timeouts) {
            pool.set("TimeoutSettings", timeouts.deepCopy());
        }
    }

    private static ObjectNode capacityStatus(int desired, boolean running) {
        ObjectNode status = JSON.objectNode();
        status.put("AvailableUserSessions", running ? desired : 0);
        status.put("DesiredUserSessions", desired);
        status.put("ActualUserSessions", running ? desired : 0);
        status.put("ActiveUserSessions", 0);
        return status;
    }

    private static boolean poolMatchesFilters(JsonNode pool, JsonNode filters) {
        for (JsonNode filter : filters) {
            if (!"PoolName".equals(filter.path("Name").asText())) {
                continue;
            }
            String name = pool.path("PoolName").asText();
            boolean any = false;
            for (JsonNode value : filter.path("Values")) {
                String v = value.asText();
                any |= switch (filter.path("Operator").asText("EQUALS")) {
                    case "NOTEQUALS" -> !name.equals(v);
                    case "CONTAINS" -> name.contains(v);
                    case "NOTCONTAINS" -> !name.contains(v);
                    default -> name.equals(v);
                };
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private ObjectNode requirePool(String poolId, String region) {
        return pools.get(key(region, poolId)).orElseThrow(() -> notFound("WorkSpaces pool", poolId));
    }

    // ──────────────────────────── Directories ────────────────────────────

    public synchronized ObjectNode registerWorkspaceDirectory(JsonNode request, String region) {
        String identityType = request.path("UserIdentityType").asText("AWS_DIRECTORY_SERVICE");
        String directoryId = request.path("DirectoryId").asText("");
        if (directoryId.isBlank()) {
            if ("AWS_DIRECTORY_SERVICE".equals(identityType)) {
                throw new AwsException("InvalidParameterValuesException", "DirectoryId is required.", 400);
            }
            directoryId = newId("wsd-");
        }
        if (directories.get(key(region, directoryId)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The directory " + directoryId + " is already registered.", 400);
        }
        ObjectNode directory = JSON.objectNode();
        directory.put("DirectoryId", directoryId);
        directory.put("Alias", directoryId);
        String name = request.path("WorkspaceDirectoryName").asText(directoryId + ".workspaces.local");
        directory.put("DirectoryName", name);
        directory.put("RegistrationCode", "WSpdx+" + newId("").toUpperCase());
        directory.set("SubnetIds", request.path("SubnetIds").isArray()
                ? request.path("SubnetIds").deepCopy() : JSON.arrayNode());
        ArrayNode dns = directory.putArray("DnsIpAddresses");
        if ("AWS_DIRECTORY_SERVICE".equals(identityType)) {
            dns.add("10.0.0.10").add("10.0.1.10");
            directory.put("CustomerUserName", "Administrator");
        }
        directory.put("IamRoleId", regionResolver.buildGlobalArn("iam", "role/workspaces_DefaultRole"));
        directory.put("DirectoryType", switch (identityType) {
            case "CUSTOMER_MANAGED" -> "CUSTOMER_MANAGED";
            case "AWS_IAM_IDENTITY_CENTER" -> "AWS_IAM_IDENTITY_CENTER";
            default -> "SIMPLE_AD";
        });
        directory.put("WorkspaceSecurityGroupId", "sg-" + newId("") + "abcdefgh");
        directory.put("State", "REGISTERED");
        directory.set("WorkspaceCreationProperties", JSON.objectNode()
                .put("EnableInternetAccess", false)
                .put("UserEnabledAsLocalAdministrator", true)
                .put("EnableMaintenanceMode", true));
        directory.putArray("ipGroupIds");
        ObjectNode access = directory.putObject("WorkspaceAccessProperties");
        for (String device : DEVICE_TYPES) {
            access.put(device, "DeviceTypeZeroClient".equals(device) ? "DENY" : "ALLOW");
        }
        directory.put("Tenancy", request.path("Tenancy").asText("SHARED"));
        boolean selfService = request.path("EnableSelfService").asBoolean(false);
        directory.set("SelfservicePermissions", JSON.objectNode()
                .put("RestartWorkspace", "ENABLED")
                .put("IncreaseVolumeSize", selfService ? "ENABLED" : "DISABLED")
                .put("ChangeComputeType", selfService ? "ENABLED" : "DISABLED")
                .put("SwitchRunningMode", selfService ? "ENABLED" : "DISABLED")
                .put("RebuildWorkspace", selfService ? "ENABLED" : "DISABLED"));
        directory.set("SamlProperties", JSON.objectNode().put("Status", "DISABLED"));
        directory.set("CertificateBasedAuthProperties", JSON.objectNode().put("Status", "DISABLED"));
        directory.put("EndpointEncryptionMode", "STANDARD_TLS");
        directory.put("UserIdentityType", identityType);
        directory.put("WorkspaceType", request.path("WorkspaceType").asText("PERSONAL"));
        for (String member : List.of("WorkspaceDirectoryName", "WorkspaceDirectoryDescription",
                "MicrosoftEntraConfig", "ActiveDirectoryConfig")) {
            if (request.hasNonNull(member)) {
                directory.set(member, request.get(member).deepCopy());
            }
        }
        if (request.hasNonNull("IdcInstanceArn")) {
            directory.set("IDCConfig", JSON.objectNode().put("InstanceArn", request.path("IdcInstanceArn").asText()));
        }
        directory.set("ClientProperties", JSON.objectNode()
                .put("ReconnectEnabled", "ENABLED").put("LogUploadEnabled", "ENABLED"));
        directories.put(key(region, directoryId), directory);
        writeTags(region, directoryId, request.path("Tags"));
        return JSON.objectNode().put("DirectoryId", directoryId).put("State", "REGISTERED");
    }

    public List<ObjectNode> describeWorkspaceDirectories(JsonNode request, String region) {
        Set<String> ids = new HashSet<>();
        request.path("DirectoryIds").forEach(id -> ids.add(id.asText()));
        Set<String> names = new HashSet<>();
        request.path("WorkspaceDirectoryNames").forEach(n -> names.add(n.asText()));
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode directory : directories.scan(k -> k.startsWith(region + "::"))) {
            if (!ids.isEmpty() && !ids.contains(directory.path("DirectoryId").asText())) {
                continue;
            }
            if (!names.isEmpty() && !names.contains(directory.path("WorkspaceDirectoryName").asText())) {
                continue;
            }
            if (directoryMatchesFilters(directory, request.path("Filters"))) {
                ObjectNode copy = directory.deepCopy();
                copy.remove("ClientProperties");
                result.add(copy);
            }
        }
        result.sort(Comparator.comparing((ObjectNode n) -> n.path("DirectoryId").asText()));
        return result;
    }

    public synchronized void deregisterWorkspaceDirectory(String directoryId, String region) {
        requireDirectory(directoryId, region);
        boolean inUse = workspaces.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(w -> directoryId.equals(w.path("DirectoryId").asText()));
        if (inUse) {
            throw new AwsException("InvalidResourceStateException",
                    "The directory " + directoryId + " has WorkSpaces.", 400);
        }
        directories.delete(key(region, directoryId));
        tags.delete(key(region, directoryId));
    }

    /** Replaces or merges one directory property, as the {@code Modify*Properties} actions do. */
    public synchronized void modifyDirectoryProperty(String directoryId, String member, JsonNode value,
                                                     JsonNode propertiesToDelete, String region) {
        ObjectNode directory = requireDirectory(directoryId, region);
        ObjectNode current = directory.get(member) instanceof ObjectNode o ? o : directory.putObject(member);
        if (value instanceof ObjectNode requested) {
            current.setAll(requested.deepCopy());
        }
        if (propertiesToDelete != null) {
            for (JsonNode property : propertiesToDelete) {
                String field = DELETABLE_PROPERTIES.get(property.asText());
                if (field != null) {
                    current.remove(field);
                }
            }
        }
        directories.put(key(region, directoryId), directory);
    }

    public synchronized void modifyEndpointEncryptionMode(String directoryId, String mode, String region) {
        ObjectNode directory = requireDirectory(directoryId, region);
        directory.put("EndpointEncryptionMode", mode);
        directories.put(key(region, directoryId), directory);
    }

    public List<ObjectNode> describeClientProperties(JsonNode resourceIds, String region) {
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode id : resourceIds) {
            ObjectNode directory = requireDirectory(id.asText(), region);
            ObjectNode entry = JSON.objectNode().put("ResourceId", id.asText());
            entry.set("ClientProperties", directory.path("ClientProperties").deepCopy());
            result.add(entry);
        }
        return result;
    }

    public synchronized void setIpGroups(String directoryId, JsonNode groupIds, String region, boolean associate) {
        ObjectNode directory = requireDirectory(directoryId, region);
        Set<String> current = new LinkedHashSet<>();
        directory.path("ipGroupIds").forEach(id -> current.add(id.asText()));
        for (JsonNode id : groupIds) {
            if (associate) {
                requireIpGroup(id.asText(), region);
                current.add(id.asText());
            } else {
                current.remove(id.asText());
            }
        }
        ArrayNode updated = directory.putArray("ipGroupIds");
        current.forEach(updated::add);
        directories.put(key(region, directoryId), directory);
    }

    private ObjectNode requireDirectory(String directoryId, String region) {
        if (directoryId == null || directoryId.isBlank()) {
            throw new AwsException("InvalidParameterValuesException", "DirectoryId is required.", 400);
        }
        return directories.get(key(region, directoryId)).orElseThrow(() -> notFound("directory", directoryId));
    }

    private static boolean directoryMatchesFilters(JsonNode directory, JsonNode filters) {
        for (JsonNode filter : filters) {
            String member = switch (filter.path("Name").asText()) {
                case "USER_IDENTITY_TYPE" -> "UserIdentityType";
                case "WORKSPACE_TYPE" -> "WorkspaceType";
                default -> null;
            };
            if (member == null) {
                continue;
            }
            boolean any = false;
            for (JsonNode value : filter.path("Values")) {
                any |= value.asText().equals(directory.path(member).asText());
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    // ──────────────────────────── Bundles ────────────────────────────

    public synchronized ObjectNode createWorkspaceBundle(JsonNode request, String region) {
        String name = required(request, "BundleName");
        required(request, "BundleDescription");
        String imageId = required(request, "ImageId");
        if (!request.path("ComputeType").has("Name") || !request.path("UserStorage").has("Capacity")) {
            throw new AwsException("InvalidParameterValuesException",
                    "ComputeType.Name and UserStorage.Capacity are required.", 400);
        }
        for (ObjectNode bundle : bundles.scan(k -> k.startsWith(region + "::"))) {
            if (name.equals(bundle.path("Name").asText())) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "The bundle " + name + " already exists.", 400);
            }
        }
        double now = Instant.now().toEpochMilli() / 1000.0;
        String id = newId("wsb-");
        ObjectNode bundle = JSON.objectNode();
        bundle.put("BundleId", id);
        bundle.put("Name", name);
        bundle.put("Owner", regionResolver.getAccountId());
        bundle.put("Description", request.path("BundleDescription").asText());
        bundle.put("ImageId", imageId);
        bundle.set("RootStorage", request.path("RootStorage").isObject()
                ? request.path("RootStorage").deepCopy() : JSON.objectNode().put("Capacity", "80"));
        bundle.set("UserStorage", request.path("UserStorage").deepCopy());
        bundle.set("ComputeType", request.path("ComputeType").deepCopy());
        bundle.put("LastUpdatedTime", now);
        bundle.put("CreationTime", now);
        bundle.put("State", "AVAILABLE");
        bundle.put("BundleType", "REGULAR");
        bundles.put(key(region, id), bundle);
        writeTags(region, id, request.path("Tags"));
        return bundle.deepCopy();
    }

    public List<ObjectNode> describeWorkspaceBundles(JsonNode request, String region) {
        JsonNode ids = request.path("BundleIds");
        String owner = request.path("Owner").asText("");
        if (!ids.isEmpty() && !owner.isEmpty()) {
            throw new AwsException("InvalidParameterValuesException", "BundleIds and Owner cannot be combined.", 400);
        }
        List<ObjectNode> amazon = amazonBundles();
        List<ObjectNode> owned = sorted(bundles.scan(k -> k.startsWith(region + "::")), "BundleId");
        if (!ids.isEmpty()) {
            Set<String> wanted = new HashSet<>();
            ids.forEach(id -> wanted.add(id.asText()));
            List<ObjectNode> result = new ArrayList<>();
            for (ObjectNode bundle : owned) {
                if (wanted.contains(bundle.path("BundleId").asText())) {
                    result.add(bundle);
                }
            }
            for (ObjectNode bundle : amazon) {
                if (wanted.contains(bundle.path("BundleId").asText())) {
                    result.add(bundle);
                }
            }
            return result;
        }
        if ("AMAZON".equals(owner)) {
            return amazon;
        }
        return owned;
    }

    public synchronized void updateWorkspaceBundle(JsonNode request, String region) {
        String id = required(request, "BundleId");
        ObjectNode bundle = requireOwnedBundle(id, region);
        if (request.hasNonNull("ImageId")) {
            bundle.put("ImageId", request.path("ImageId").asText());
        }
        bundle.put("LastUpdatedTime", Instant.now().toEpochMilli() / 1000.0);
        bundles.put(key(region, id), bundle);
    }

    public synchronized void deleteWorkspaceBundle(String bundleId, String region) {
        requireOwnedBundle(bundleId, region);
        boolean inUse = workspaces.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(w -> bundleId.equals(w.path("BundleId").asText()))
                || pools.scan(k -> k.startsWith(region + "::")).stream()
                        .anyMatch(p -> bundleId.equals(p.path("BundleId").asText()));
        if (inUse) {
            throw new AwsException("ResourceAssociatedException", "The bundle " + bundleId + " is in use.", 400);
        }
        bundles.delete(key(region, bundleId));
        tags.delete(key(region, bundleId));
    }

    public List<ObjectNode> describeBundleAssociations(String bundleId, String region) {
        requireOwnedBundle(bundleId, region);
        return List.of();
    }

    private ObjectNode requireOwnedBundle(String bundleId, String region) {
        if (bundleId == null || bundleId.isBlank()) {
            throw new AwsException("InvalidParameterValuesException", "BundleId is required.", 400);
        }
        return bundles.get(key(region, bundleId)).orElseThrow(() -> notFound("bundle", bundleId));
    }

    private static List<ObjectNode> amazonBundles() {
        List<ObjectNode> result = new ArrayList<>();
        for (String[] row : AMAZON_BUNDLES) {
            ObjectNode bundle = JSON.objectNode();
            bundle.put("BundleId", row[0]);
            bundle.put("Name", row[1]);
            bundle.put("Owner", "Amazon");
            bundle.put("Description", row[1]);
            bundle.put("ImageId", row[2]);
            bundle.set("RootStorage", JSON.objectNode().put("Capacity", row[4]));
            bundle.set("UserStorage", JSON.objectNode().put("Capacity", row[5]));
            bundle.set("ComputeType", JSON.objectNode().put("Name", row[3]));
            bundle.put("LastUpdatedTime", AMAZON_BUNDLE_UPDATED);
            bundle.put("CreationTime", AMAZON_BUNDLE_UPDATED);
            bundle.put("State", "AVAILABLE");
            bundle.put("BundleType", "REGULAR");
            result.add(bundle);
        }
        return result;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public synchronized void createTags(String resourceId, JsonNode newTags, String region) {
        requireTaggable(resourceId, region);
        writeTags(region, resourceId, newTags);
    }

    public synchronized void deleteTags(String resourceId, JsonNode tagKeys, String region) {
        requireTaggable(resourceId, region);
        ObjectNode current = tags.get(key(region, resourceId)).orElse(JSON.objectNode());
        tagKeys.forEach(tagKey -> current.remove(tagKey.asText()));
        tags.put(key(region, resourceId), current);
    }

    public List<ObjectNode> describeTags(String resourceId, String region) {
        requireTaggable(resourceId, region);
        List<ObjectNode> result = new ArrayList<>();
        tags.get(key(region, resourceId)).orElse(JSON.objectNode()).fields().forEachRemaining(e ->
                result.add(JSON.objectNode().put("Key", e.getKey()).put("Value", e.getValue().asText())));
        return result;
    }

    private void writeTags(String region, String resourceId, JsonNode newTags) {
        ObjectNode current = tags.get(key(region, resourceId)).orElse(JSON.objectNode());
        for (JsonNode tag : newTags) {
            current.put(tag.path("Key").asText(), tag.path("Value").asText(""));
        }
        tags.put(key(region, resourceId), current);
    }

    private void requireTaggable(String resourceId, String region) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new AwsException("InvalidParameterValuesException", "ResourceId is required.", 400);
        }
        String id = resourceId.startsWith("arn:") ? resourceId.substring(resourceId.lastIndexOf('/') + 1) : resourceId;
        String key = key(region, id);
        boolean exists = ipGroups.get(key).isPresent() || aliases.get(key).isPresent()
                || workspaces.get(key).isPresent() || pools.get(key).isPresent()
                || directories.get(key).isPresent() || bundles.get(key).isPresent();
        if (!exists) {
            throw notFound("resource", resourceId);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static List<ObjectNode> sorted(List<ObjectNode> nodes, String field) {
        return nodes.stream().map(ObjectNode::deepCopy)
                .sorted(Comparator.comparing((ObjectNode n) -> n.path(field).asText()))
                .toList();
    }

    private static String required(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new AwsException("InvalidParameterValuesException", field + " is required.", 400);
        }
        return value.asText();
    }

    private static AwsException notFound(String type, String id) {
        return new AwsException("ResourceNotFoundException", "The " + type + " " + id + " could not be found.", 400);
    }

    private static String newId(String prefix) {
        StringBuilder id = new StringBuilder(prefix);
        for (int i = 0; i < 9; i++) {
            id.append(ID_CHARS.charAt(RANDOM.nextInt(ID_CHARS.length())));
        }
        return id.toString();
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }
}
