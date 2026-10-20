package io.github.hectorvent.floci.services.appstream;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Amazon AppStream 2.0 control plane. Fleets start {@code STOPPED} and move straight to
 * {@code RUNNING} or {@code STOPPED} on Start and Stop, so waiters finish on their first poll.
 * No streaming instances run.
 */
@ApplicationScoped
public class AppStreamService implements Resettable {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final TypeReference<Map<String, ObjectNode>> NODES = new TypeReference<>() {};

    private static final Set<String> AUTHENTICATION_TYPES = Set.of("API", "SAML", "USERPOOL", "AWS_AD");

    /** The user setting actions a stack enables when its create request names none. */
    private static final List<String> DEFAULT_USER_SETTING_ACTIONS = List.of(
            "CLIPBOARD_COPY_FROM_LOCAL_DEVICE", "CLIPBOARD_COPY_TO_LOCAL_DEVICE", "FILE_UPLOAD",
            "FILE_DOWNLOAD", "PRINTING_TO_LOCAL_DEVICE", "DOMAIN_PASSWORD_SIGNIN", "DOMAIN_SMART_CARD_SIGNIN");

    /** Request members that are not stored on the resource itself. */
    private static final Set<String> STACK_REQUEST_ONLY =
            Set.of("Name", "Tags", "AttributesToDelete", "DeleteStorageConnectors");
    private static final Set<String> FLEET_REQUEST_ONLY =
            Set.of("Name", "Tags", "ComputeCapacity", "AttributesToDelete", "DeleteVpcConfig");

    /** {@code StackAttribute} values mapped to the stack member each one clears. */
    private static final Map<String, String> STACK_ATTRIBUTE_MEMBERS = Map.ofEntries(
            Map.entry("STORAGE_CONNECTORS", "StorageConnectors"),
            Map.entry("REDIRECT_URL", "RedirectURL"),
            Map.entry("FEEDBACK_URL", "FeedbackURL"),
            Map.entry("USER_SETTINGS", "UserSettings"),
            Map.entry("EMBED_HOST_DOMAINS", "EmbedHostDomains"),
            Map.entry("ACCESS_ENDPOINTS", "AccessEndpoints"),
            Map.entry("STREAMING_EXPERIENCE_SETTINGS", "StreamingExperienceSettings"),
            Map.entry("THEME_NAME", "ThemeName"),
            Map.entry("IAM_ROLE_ARN", "IamRoleArn"),
            Map.entry("CONTENT_REDIRECTION", "ContentRedirection"));

    /** {@code FleetAttribute} values mapped to the fleet member each one clears. */
    private static final Map<String, String> FLEET_ATTRIBUTE_MEMBERS = Map.of(
            "VPC_CONFIGURATION", "VpcConfig",
            "VPC_CONFIGURATION_SECURITY_GROUP_IDS", "VpcConfig.SecurityGroupIds",
            "DOMAIN_JOIN_INFO", "DomainJoinInfo",
            "IAM_ROLE_ARN", "IamRoleArn",
            "USB_DEVICE_FILTER_STRINGS", "UsbDeviceFilterStrings",
            "SESSION_SCRIPT_S3_LOCATION", "SessionScriptS3Location",
            "MAX_SESSIONS_PER_INSTANCE", "MaxSessionsPerInstance");

    private final StorageBackend<String, ObjectNode> stacks;
    private final StorageBackend<String, ObjectNode> fleets;
    private final StorageBackend<String, ObjectNode> users;
    private final StorageBackend<String, ObjectNode> associations;
    private final StorageBackend<String, ObjectNode> tags;
    private final RegionResolver regionResolver;

    @Inject
    public AppStreamService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("appstream", "appstream-stacks.json", NODES),
                storageFactory.create("appstream", "appstream-fleets.json", NODES),
                storageFactory.create("appstream", "appstream-users.json", NODES),
                storageFactory.create("appstream", "appstream-associations.json", NODES),
                storageFactory.create("appstream", "appstream-tags.json", NODES),
                regionResolver);
    }

    AppStreamService(StorageBackend<String, ObjectNode> stacks, StorageBackend<String, ObjectNode> fleets,
                     StorageBackend<String, ObjectNode> users, StorageBackend<String, ObjectNode> associations,
                     StorageBackend<String, ObjectNode> tags, RegionResolver regionResolver) {
        this.stacks = stacks;
        this.fleets = fleets;
        this.users = users;
        this.associations = associations;
        this.tags = tags;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        stacks.clear();
        fleets.clear();
        users.clear();
        associations.clear();
        tags.clear();
    }

    // ──────────────────────────── Stacks ────────────────────────────

    public synchronized ObjectNode createStack(JsonNode request, String region) {
        String name = required(request, "Name");
        if (stacks.get(key(region, name)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "The stack " + name + " already exists.", 400);
        }
        ObjectNode stack = JSON.objectNode();
        stack.put("Arn", regionResolver.buildArn("appstream", region, "stack/" + name));
        stack.put("Name", name);
        stack.put("CreatedTime", now());
        copyMembers(request, stack, STACK_REQUEST_ONLY);
        if (!stack.has("UserSettings")) {
            ArrayNode settings = stack.putArray("UserSettings");
            for (String action : DEFAULT_USER_SETTING_ACTIONS) {
                settings.addObject().put("Action", action).put("Permission", "ENABLED");
            }
        }
        applicationSettingsResponse(stack, region);
        stack.putArray("StackErrors");
        stacks.put(key(region, name), stack);
        putTags(stack.path("Arn").asText(), request.get("Tags"));
        return stack.deepCopy();
    }

    public List<ObjectNode> describeStacks(JsonNode names, String region) {
        if (names != null && names.isArray() && !names.isEmpty()) {
            List<ObjectNode> result = new ArrayList<>();
            for (JsonNode name : names) {
                result.add(requireStack(name.asText(), region).deepCopy());
            }
            return result;
        }
        return sorted(stacks.scan(k -> k.startsWith(region + "::")), "Name");
    }

    public synchronized ObjectNode updateStack(JsonNode request, String region) {
        ObjectNode stack = requireStack(required(request, "Name"), region);
        copyMembers(request, stack, STACK_REQUEST_ONLY);
        if (request.path("DeleteStorageConnectors").asBoolean(false)) {
            stack.remove("StorageConnectors");
        }
        for (JsonNode attribute : request.path("AttributesToDelete")) {
            String member = STACK_ATTRIBUTE_MEMBERS.get(attribute.asText());
            if (member != null) {
                stack.remove(member);
            } else if (attribute.asText().startsWith("STORAGE_CONNECTOR_")
                    && stack.get("StorageConnectors") instanceof ArrayNode connectors) {
                String type = attribute.asText().substring("STORAGE_CONNECTOR_".length());
                ArrayNode kept = JSON.arrayNode();
                connectors.forEach(c -> {
                    if (!type.equals(c.path("ConnectorType").asText())) {
                        kept.add(c);
                    }
                });
                stack.set("StorageConnectors", kept);
            }
        }
        applicationSettingsResponse(stack, region);
        stacks.put(key(region, stack.path("Name").asText()), stack);
        return stack.deepCopy();
    }

    public synchronized void deleteStack(String name, String region) {
        ObjectNode stack = requireStack(name, region);
        if (!associations.scan(k -> k.startsWith(region + "::") && k.endsWith("::" + name)).isEmpty()) {
            throw new AwsException("ResourceInUseException",
                    "The stack " + name + " is associated with a fleet.", 400);
        }
        stacks.delete(key(region, name));
        tags.delete(stack.path("Arn").asText());
    }

    // ──────────────────────────── Fleets ────────────────────────────

    public synchronized ObjectNode createFleet(JsonNode request, String region) {
        String name = required(request, "Name");
        required(request, "InstanceType");
        if (fleets.get(key(region, name)).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "The fleet " + name + " already exists.", 400);
        }
        ObjectNode fleet = JSON.objectNode();
        fleet.put("Arn", regionResolver.buildArn("appstream", region, "fleet/" + name));
        fleet.put("Name", name);
        fleet.put("FleetType", "ON_DEMAND");
        fleet.put("State", "STOPPED");
        fleet.put("CreatedTime", now());
        fleet.put("StreamView", "APP");
        fleet.put("MaxUserDurationInSeconds", 57600);
        fleet.put("DisconnectTimeoutInSeconds", 900);
        fleet.put("EnableDefaultInternetAccess", false);
        copyMembers(request, fleet, FLEET_REQUEST_ONLY);
        if (!fleet.has("ImageName") && fleet.has("ImageArn")) {
            String imageArn = fleet.path("ImageArn").asText();
            fleet.put("ImageName", imageArn.substring(imageArn.lastIndexOf('/') + 1));
        }
        fleet.set("ComputeCapacityStatus", capacityStatus(request.path("ComputeCapacity"), false));
        fleet.putArray("FleetErrors");
        fleets.put(key(region, name), fleet);
        putTags(fleet.path("Arn").asText(), request.get("Tags"));
        return fleet.deepCopy();
    }

    public List<ObjectNode> describeFleets(JsonNode names, String region) {
        if (names != null && names.isArray() && !names.isEmpty()) {
            List<ObjectNode> result = new ArrayList<>();
            for (JsonNode name : names) {
                result.add(requireFleet(name.asText(), region).deepCopy());
            }
            return result;
        }
        return sorted(fleets.scan(k -> k.startsWith(region + "::")), "Name");
    }

    public synchronized ObjectNode updateFleet(JsonNode request, String region) {
        String name = required(request, "Name");
        ObjectNode fleet = requireFleet(name, region);
        copyMembers(request, fleet, FLEET_REQUEST_ONLY);
        if (request.path("DeleteVpcConfig").asBoolean(false)) {
            fleet.remove("VpcConfig");
        }
        for (JsonNode attribute : request.path("AttributesToDelete")) {
            String member = FLEET_ATTRIBUTE_MEMBERS.get(attribute.asText());
            if (member == null) {
                continue;
            }
            if (member.contains(".")) {
                if (fleet.get("VpcConfig") instanceof ObjectNode vpc) {
                    vpc.remove(member.substring(member.indexOf('.') + 1));
                }
            } else {
                fleet.remove(member);
            }
        }
        if (request.get("ComputeCapacity") instanceof ObjectNode capacity) {
            fleet.set("ComputeCapacityStatus", capacityStatus(capacity, "RUNNING".equals(fleet.path("State").asText())));
        }
        fleets.put(key(region, name), fleet);
        return fleet.deepCopy();
    }

    public synchronized void startFleet(String name, String region) {
        setFleetState(name, region, true);
    }

    public synchronized void stopFleet(String name, String region) {
        setFleetState(name, region, false);
    }

    public synchronized void deleteFleet(String name, String region) {
        ObjectNode fleet = requireFleet(name, region);
        if (!"STOPPED".equals(fleet.path("State").asText())) {
            throw new AwsException("ResourceInUseException",
                    "The fleet " + name + " must be stopped before it can be deleted.", 400);
        }
        if (!associations.scan(k -> k.startsWith(key(region, name) + "::")).isEmpty()) {
            throw new AwsException("ResourceInUseException",
                    "The fleet " + name + " is associated with a stack.", 400);
        }
        fleets.delete(key(region, name));
        tags.delete(fleet.path("Arn").asText());
    }

    private void setFleetState(String name, String region, boolean running) {
        ObjectNode fleet = requireFleet(name, region);
        fleet.put("State", running ? "RUNNING" : "STOPPED");
        JsonNode status = fleet.path("ComputeCapacityStatus");
        ObjectNode capacity = JSON.objectNode();
        if (status.has("DesiredUserSessions")) {
            capacity.put("DesiredSessions", status.path("DesiredUserSessions").asInt());
        } else {
            capacity.put("DesiredInstances", status.path("Desired").asInt());
        }
        fleet.set("ComputeCapacityStatus", capacityStatus(capacity, running));
        fleets.put(key(region, name), fleet);
    }

    private static ObjectNode capacityStatus(JsonNode capacity, boolean running) {
        ObjectNode status = JSON.objectNode();
        int desired = capacity.path("DesiredInstances").asInt(capacity.path("DesiredSessions").isMissingNode() ? 1 : 0);
        status.put("Desired", desired);
        status.put("Running", running ? desired : 0);
        status.put("InUse", 0);
        status.put("Available", running ? desired : 0);
        if (capacity.has("DesiredSessions")) {
            int sessions = capacity.path("DesiredSessions").asInt();
            status.put("DesiredUserSessions", sessions);
            status.put("AvailableUserSessions", running ? sessions : 0);
            status.put("ActiveUserSessions", 0);
            status.put("ActualUserSessions", running ? sessions : 0);
        }
        return status;
    }

    // ──────────────────────── Fleet-stack associations ────────────────────────

    public synchronized void associateFleet(String fleetName, String stackName, String region) {
        requireFleet(fleetName, region);
        requireStack(stackName, region);
        ObjectNode association = JSON.objectNode().put("FleetName", fleetName).put("StackName", stackName);
        associations.put(key(region, fleetName) + "::" + stackName, association);
    }

    public synchronized void disassociateFleet(String fleetName, String stackName, String region) {
        requireFleet(fleetName, region);
        requireStack(stackName, region);
        associations.delete(key(region, fleetName) + "::" + stackName);
    }

    public List<String> listAssociatedStacks(String fleetName, String region) {
        return associations.scan(k -> k.startsWith(key(region, fleetName) + "::")).stream()
                .map(a -> a.path("StackName").asText()).sorted().toList();
    }

    public List<String> listAssociatedFleets(String stackName, String region) {
        return associations.scan(k -> k.startsWith(region + "::") && k.endsWith("::" + stackName)).stream()
                .map(a -> a.path("FleetName").asText()).sorted().toList();
    }

    // ──────────────────────────── Users ────────────────────────────

    public synchronized void createUser(JsonNode request, String region) {
        String userName = required(request, "UserName");
        String authenticationType = authenticationType(request);
        String userKey = userKey(region, authenticationType, userName);
        if (users.get(userKey).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "The user " + userName + " already exists.", 400);
        }
        ObjectNode user = JSON.objectNode();
        user.put("Arn", regionResolver.buildArn("appstream", region,
                "user/" + authenticationType.toLowerCase() + "/" + userName));
        user.put("UserName", userName);
        user.put("Enabled", true);
        user.put("Status", "FORCE_CHANGE_PASSWORD");
        if (request.hasNonNull("FirstName")) {
            user.put("FirstName", request.path("FirstName").asText());
        }
        if (request.hasNonNull("LastName")) {
            user.put("LastName", request.path("LastName").asText());
        }
        user.put("CreatedTime", now());
        user.put("AuthenticationType", authenticationType);
        users.put(userKey, user);
    }

    public List<ObjectNode> describeUsers(JsonNode request, String region) {
        String authenticationType = authenticationType(request);
        return sorted(users.scan(k -> k.startsWith(region + "::" + authenticationType + "::")), "UserName");
    }

    public synchronized void setUserEnabled(JsonNode request, String region, boolean enabled) {
        String userKey = userKey(region, authenticationType(request), required(request, "UserName"));
        ObjectNode user = requireUser(userKey, request);
        user.put("Enabled", enabled);
        users.put(userKey, user);
    }

    public synchronized void deleteUser(JsonNode request, String region) {
        String userKey = userKey(region, authenticationType(request), required(request, "UserName"));
        requireUser(userKey, request);
        users.delete(userKey);
    }

    private ObjectNode requireUser(String userKey, JsonNode request) {
        return users.get(userKey).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The user " + request.path("UserName").asText() + " was not found.", 400));
    }

    private static String authenticationType(JsonNode request) {
        String type = required(request, "AuthenticationType");
        if (!AUTHENTICATION_TYPES.contains(type)) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + type
                    + "' at 'authenticationType' failed to satisfy constraint: Member must satisfy enum value set: "
                    + "[API, SAML, USERPOOL, AWS_AD]", 400);
        }
        return type;
    }

    // ──────────────────────────── Tags ────────────────────────────

    public synchronized void tagResource(String arn, JsonNode newTags) {
        requireTaggable(arn);
        putTags(arn, newTags);
    }

    public synchronized void untagResource(String arn, JsonNode tagKeys) {
        requireTaggable(arn);
        ObjectNode current = tags.get(arn).orElse(JSON.objectNode());
        for (JsonNode tagKey : tagKeys) {
            current.remove(tagKey.asText());
        }
        tags.put(arn, current);
    }

    public ObjectNode listTagsForResource(String arn) {
        requireTaggable(arn);
        return tags.get(arn).orElse(JSON.objectNode()).deepCopy();
    }

    private void putTags(String arn, JsonNode newTags) {
        ObjectNode current = tags.get(arn).orElse(JSON.objectNode());
        if (newTags != null && newTags.isObject()) {
            newTags.fields().forEachRemaining(e -> current.put(e.getKey(), e.getValue().asText()));
        }
        tags.put(arn, current);
    }

    private void requireTaggable(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ValidationException", "ResourceArn is required.", 400);
        }
        boolean exists = stacks.scan(k -> true).stream().anyMatch(s -> arn.equals(s.path("Arn").asText()))
                || fleets.scan(k -> true).stream().anyMatch(f -> arn.equals(f.path("Arn").asText()));
        if (!exists) {
            throw new AwsException("ResourceNotFoundException", "The resource " + arn + " was not found.", 400);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode requireStack(String name, String region) {
        return stacks.get(key(region, name)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The stack " + name + " was not found.", 400));
    }

    private ObjectNode requireFleet(String name, String region) {
        return fleets.get(key(region, name)).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The fleet " + name + " was not found.", 400));
    }

    private void applicationSettingsResponse(ObjectNode stack, String region) {
        JsonNode requested = stack.get("ApplicationSettings");
        if (requested == null || !requested.isObject()) {
            stack.set("ApplicationSettings", JSON.objectNode().put("Enabled", false));
            return;
        }
        ObjectNode response = JSON.objectNode();
        boolean enabled = requested.path("Enabled").asBoolean(false);
        response.put("Enabled", enabled);
        if (requested.hasNonNull("SettingsGroup")) {
            response.put("SettingsGroup", requested.path("SettingsGroup").asText());
        }
        if (enabled) {
            response.put("S3BucketName", "appstream-app-settings-" + region + "-" + regionResolver.getAccountId());
        }
        stack.set("ApplicationSettings", response);
    }

    private static void copyMembers(JsonNode request, ObjectNode target, Set<String> skipped) {
        request.fields().forEachRemaining(e -> {
            if (!skipped.contains(e.getKey()) && !e.getValue().isNull()) {
                target.set(e.getKey(), e.getValue().deepCopy());
            }
        });
    }

    private static List<ObjectNode> sorted(List<ObjectNode> nodes, String field) {
        return nodes.stream().map(ObjectNode::deepCopy)
                .sorted(Comparator.comparing((ObjectNode n) -> n.path(field).asText()))
                .toList();
    }

    private static String required(JsonNode request, String field) {
        JsonNode value = request.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw new AwsException("ValidationException", "1 validation error detected: Value null at '"
                    + Character.toLowerCase(field.charAt(0)) + field.substring(1)
                    + "' failed to satisfy constraint: Member must not be null", 400);
        }
        return value.asText();
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }

    private static String userKey(String region, String authenticationType, String userName) {
        return region + "::" + authenticationType + "::" + userName;
    }
}
