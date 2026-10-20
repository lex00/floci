package io.github.hectorvent.floci.services.appstream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    /** Catalog key prefixes, one per resource type kept in {@code appstream-catalog.json}. */
    private static final String IMAGE_BUILDER = "builder";
    private static final String IMAGE = "image";
    private static final String APP_BLOCK = "appblock";
    private static final String APPLICATION = "application";
    private static final String APP_FLEET = "appfleet";
    private static final String ENTITLEMENT = "entitlement";
    private static final List<String> TAGGABLE = List.of(IMAGE_BUILDER, IMAGE, APP_BLOCK, APPLICATION);

    /** Members kept on stored resources and left out of responses. */
    private static final String IMAGE_PERMISSIONS = "SharedImagePermissions";
    private static final String ENTITLED_APPLICATIONS = "EntitledApplications";

    private static final Set<String> BUILDER_REQUEST_ONLY =
            Set.of("Name", "Tags", "ImageName", "ImageArn", "SoftwaresToInstall", "SoftwaresToUninstall");

    /** AWS-owned base images every region lists as {@code PUBLIC}. */
    private static final List<String> PUBLIC_IMAGES = List.of(
            "AppStream-WinServer2016", "AppStream-WinServer2019", "AppStream-WinServer2022",
            "AppStream-WinServer2025", "AppStream-AmazonLinux2", "AppStream-RHEL8", "AppStream-RockyLinux8",
            "AppStream-UbuntuPro2404");
    private static final double PUBLIC_IMAGE_RELEASED = 1735689600.0;

    private final StorageBackend<String, ObjectNode> stacks;
    private final StorageBackend<String, ObjectNode> fleets;
    private final StorageBackend<String, ObjectNode> users;
    private final StorageBackend<String, ObjectNode> associations;
    private final StorageBackend<String, ObjectNode> tags;
    private final StorageBackend<String, ObjectNode> catalog;
    private final RegionResolver regionResolver;

    @Inject
    public AppStreamService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("appstream", "appstream-stacks.json", NODES),
                storageFactory.create("appstream", "appstream-fleets.json", NODES),
                storageFactory.create("appstream", "appstream-users.json", NODES),
                storageFactory.create("appstream", "appstream-associations.json", NODES),
                storageFactory.create("appstream", "appstream-tags.json", NODES),
                storageFactory.create("appstream", "appstream-catalog.json", NODES),
                regionResolver);
    }

    AppStreamService(StorageBackend<String, ObjectNode> stacks, StorageBackend<String, ObjectNode> fleets,
                     StorageBackend<String, ObjectNode> users, StorageBackend<String, ObjectNode> associations,
                     StorageBackend<String, ObjectNode> tags, StorageBackend<String, ObjectNode> catalog,
                     RegionResolver regionResolver) {
        this.stacks = stacks;
        this.fleets = fleets;
        this.users = users;
        this.associations = associations;
        this.tags = tags;
        this.catalog = catalog;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        stacks.clear();
        fleets.clear();
        users.clear();
        associations.clear();
        tags.clear();
        catalog.clear();
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

    // ──────────────────────────── Image builders ────────────────────────────

    public synchronized ObjectNode createImageBuilder(JsonNode request, String region) {
        String name = required(request, "Name");
        required(request, "InstanceType");
        if (request.path("ImageName").asText("").isBlank() && request.path("ImageArn").asText("").isBlank()) {
            throw new AwsException("InvalidParameterCombinationException",
                    "Either ImageName or ImageArn is required.", 400);
        }
        String builderKey = catalogKey(IMAGE_BUILDER, region, name);
        if (catalog.get(builderKey).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The image builder " + name + " already exists.", 400);
        }
        String imageArn = request.hasNonNull("ImageArn") ? request.path("ImageArn").asText()
                : imageArn(request.path("ImageName").asText(), region);
        ObjectNode builder = JSON.objectNode();
        builder.put("Arn", regionResolver.buildArn("appstream", region, "image-builder/" + name));
        builder.put("Name", name);
        builder.put("ImageArn", imageArn);
        builder.put("Platform", platformFor(imageArn.substring(imageArn.lastIndexOf('/') + 1)));
        builder.put("State", "RUNNING");
        builder.put("CreatedTime", now());
        builder.put("EnableDefaultInternetAccess", false);
        builder.put("LatestAppstreamAgentVersion", "TRUE");
        copyMembers(request, builder, BUILDER_REQUEST_ONLY);
        builder.putArray("ImageBuilderErrors");
        catalog.put(builderKey, builder);
        putTags(builder.path("Arn").asText(), request.get("Tags"));
        return builder.deepCopy();
    }

    public List<ObjectNode> describeImageBuilders(JsonNode names, String region) {
        return describeByName(IMAGE_BUILDER, "image builder", names, region);
    }

    public synchronized ObjectNode setImageBuilderState(String name, String region, boolean running) {
        String builderKey = catalogKey(IMAGE_BUILDER, region, name);
        ObjectNode builder = requireCatalog(builderKey, "image builder", name);
        builder.put("State", running ? "RUNNING" : "STOPPED");
        catalog.put(builderKey, builder);
        return builder.deepCopy();
    }

    public synchronized ObjectNode deleteImageBuilder(String name, String region) {
        String builderKey = catalogKey(IMAGE_BUILDER, region, name);
        ObjectNode builder = requireCatalog(builderKey, "image builder", name);
        catalog.delete(builderKey);
        tags.delete(builder.path("Arn").asText());
        builder.put("State", "DELETING");
        return builder;
    }

    public ObjectNode createImageBuilderStreamingUrl(JsonNode request, String region) {
        String name = required(request, "Name");
        ObjectNode builder = requireCatalog(catalogKey(IMAGE_BUILDER, region, name), "image builder", name);
        if (!"RUNNING".equals(builder.path("State").asText())) {
            throw new AwsException("OperationNotPermittedException",
                    "The image builder " + name + " is not running.", 400);
        }
        long validity = request.path("Validity").asLong(3600);
        return JSON.objectNode()
                .put("StreamingURL", "https://appstream2." + region + "." + AwsRegions.dnsSuffixFor(region)
                        + "/authenticate?parameters=" + UUID.randomUUID())
                .put("Expires", now() + validity);
    }

    // ──────────────────────────── Images ────────────────────────────

    public List<ObjectNode> describeImages(JsonNode request, String region) {
        Map<String, ObjectNode> all = new LinkedHashMap<>();
        for (String name : PUBLIC_IMAGES) {
            all.put(name, publicImage(name, region));
        }
        for (ObjectNode image : catalog.scan(k -> k.startsWith(IMAGE + "::" + region + "::"))) {
            all.put(image.path("Name").asText(), image.deepCopy());
        }
        List<ObjectNode> result = new ArrayList<>();
        JsonNode names = request.path("Names");
        JsonNode arns = request.path("Arns");
        if (!names.isEmpty() && !arns.isEmpty()) {
            throw new AwsException("InvalidParameterCombinationException",
                    "Names and Arns cannot be specified together.", 400);
        }
        if (!names.isEmpty()) {
            for (JsonNode name : names) {
                ObjectNode image = all.get(name.asText());
                if (image == null) {
                    throw new AwsException("ResourceNotFoundException",
                            "The image " + name.asText() + " was not found.", 400);
                }
                result.add(image);
            }
        } else if (!arns.isEmpty()) {
            for (JsonNode arn : arns) {
                ObjectNode image = all.values().stream()
                        .filter(i -> arn.asText().equals(i.path("Arn").asText())).findFirst()
                        .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                                "The image " + arn.asText() + " was not found.", 400));
                result.add(image);
            }
        } else {
            result.addAll(all.values());
        }
        String type = request.path("Type").asText("");
        return result.stream()
                .filter(i -> type.isEmpty() || type.equals(i.path("Visibility").asText()))
                .peek(i -> i.remove(IMAGE_PERMISSIONS))
                .sorted(Comparator.comparing((ObjectNode n) -> n.path("Name").asText()))
                .toList();
    }

    public synchronized ObjectNode createUpdatedImage(JsonNode request, String region) {
        String existingName = required(request, "existingImageName");
        String newName = required(request, "newImageName");
        ObjectNode existing = requireImage(existingName, region);
        if (imageExists(newName, region)) {
            throw new AwsException("ResourceAlreadyExistsException", "The image " + newName + " already exists.", 400);
        }
        ObjectNode response = JSON.objectNode();
        response.put("canUpdateImage", true);
        if (request.path("dryRun").asBoolean(false)) {
            return response;
        }
        ObjectNode image = privateImage(existing, newName, region,
                request.path("newImageDescription").asText(null), request.path("newImageDisplayName").asText(null));
        catalog.put(catalogKey(IMAGE, region, newName), image);
        putTags(image.path("Arn").asText(), request.get("newImageTags"));
        response.set("image", image.deepCopy());
        return response;
    }

    public synchronized String copyImage(JsonNode request, String region) {
        String sourceName = required(request, "SourceImageName");
        String destinationName = required(request, "DestinationImageName");
        String destinationRegion = required(request, "DestinationRegion");
        ObjectNode source = requireImage(sourceName, region);
        if (!"PRIVATE".equals(source.path("Visibility").asText())) {
            throw new AwsException("ResourceNotAvailableException",
                    "Only private images can be copied.", 400);
        }
        if (imageExists(destinationName, destinationRegion)) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The image " + destinationName + " already exists.", 400);
        }
        ObjectNode copy = privateImage(source, destinationName, destinationRegion,
                request.path("DestinationImageDescription").asText(source.path("Description").asText(null)),
                source.path("DisplayName").asText(null));
        copy.put("BaseImageArn", source.path("BaseImageArn").asText());
        catalog.put(catalogKey(IMAGE, destinationRegion, destinationName), copy);
        return destinationName;
    }

    public synchronized ObjectNode deleteImage(String name, String region) {
        ObjectNode image = requireImage(name, region);
        if (!"PRIVATE".equals(image.path("Visibility").asText())) {
            throw new AwsException("OperationNotPermittedException",
                    "The image " + name + " is not owned by this account.", 400);
        }
        boolean inUse = fleets.scan(k -> k.startsWith(region + "::")).stream()
                .anyMatch(f -> name.equals(f.path("ImageName").asText()));
        if (inUse) {
            throw new AwsException("ResourceInUseException", "The image " + name + " is in use by a fleet.", 400);
        }
        catalog.delete(catalogKey(IMAGE, region, name));
        tags.delete(image.path("Arn").asText());
        image.remove(IMAGE_PERMISSIONS);
        image.put("State", "DELETING");
        return image;
    }

    public ObjectNode describeImagePermissions(JsonNode request, String region) {
        String name = required(request, "Name");
        ObjectNode image = requirePrivateImage(name, region);
        Set<String> wanted = new HashSet<>();
        request.path("SharedAwsAccountIds").forEach(id -> wanted.add(id.asText()));
        ObjectNode response = JSON.objectNode().put("Name", name);
        ArrayNode list = response.putArray("SharedImagePermissionsList");
        image.path(IMAGE_PERMISSIONS).fields().forEachRemaining(e -> {
            if (wanted.isEmpty() || wanted.contains(e.getKey())) {
                list.addObject().put("sharedAccountId", e.getKey()).set("imagePermissions", e.getValue().deepCopy());
            }
        });
        return response;
    }

    public synchronized void updateImagePermissions(JsonNode request, String region) {
        String name = required(request, "Name");
        String account = required(request, "SharedAccountId");
        ObjectNode image = requirePrivateImage(name, region);
        JsonNode permissions = request.path("ImagePermissions");
        ObjectNode shared = image.get(IMAGE_PERMISSIONS) instanceof ObjectNode o ? o : image.putObject(IMAGE_PERMISSIONS);
        shared.set(account, permissions.isObject() ? permissions.deepCopy() : JSON.objectNode());
        image.put("ImageSharedWithOthers", "TRUE");
        catalog.put(catalogKey(IMAGE, region, name), image);
    }

    public synchronized void deleteImagePermissions(JsonNode request, String region) {
        String name = required(request, "Name");
        String account = required(request, "SharedAccountId");
        ObjectNode image = requirePrivateImage(name, region);
        if (!(image.get(IMAGE_PERMISSIONS) instanceof ObjectNode shared) || !shared.has(account)) {
            throw new AwsException("ResourceNotFoundException",
                    "The image " + name + " is not shared with account " + account + ".", 400);
        }
        shared.remove(account);
        image.put("ImageSharedWithOthers", shared.isEmpty() ? "FALSE" : "TRUE");
        catalog.put(catalogKey(IMAGE, region, name), image);
    }

    private ObjectNode privateImage(ObjectNode base, String name, String region, String description,
                                    String displayName) {
        ObjectNode image = JSON.objectNode();
        image.put("Name", name);
        image.put("Arn", regionResolver.buildArn("appstream", region, "image/" + name));
        image.put("BaseImageArn", base.path("BaseImageArn").asText(base.path("Arn").asText()));
        if (displayName != null) {
            image.put("DisplayName", displayName);
        }
        if (description != null) {
            image.put("Description", description);
        }
        image.put("State", "AVAILABLE");
        image.put("Visibility", "PRIVATE");
        image.put("ImageBuilderSupported", true);
        image.put("Platform", base.path("Platform").asText());
        image.put("CreatedTime", now());
        image.put("AppstreamAgentVersion", "LATEST");
        image.putArray("Applications");
        image.putArray("ImageErrors");
        image.put("LatestAppstreamAgentVersion", "TRUE");
        image.put("ImageSharedWithOthers", "FALSE");
        image.put("ImageType", "CUSTOM");
        return image;
    }

    private ObjectNode publicImage(String name, String region) {
        ObjectNode image = JSON.objectNode();
        image.put("Name", name);
        image.put("Arn", imageArn(name, region));
        image.put("DisplayName", name);
        image.put("State", "AVAILABLE");
        image.put("Visibility", "PUBLIC");
        image.put("ImageBuilderSupported", true);
        image.put("Platform", platformFor(name));
        image.put("CreatedTime", PUBLIC_IMAGE_RELEASED);
        image.put("PublicBaseImageReleasedDate", PUBLIC_IMAGE_RELEASED);
        image.put("AppstreamAgentVersion", "LATEST");
        image.putArray("Applications");
        image.putArray("ImageErrors");
        image.put("LatestAppstreamAgentVersion", "TRUE");
        image.put("ImageType", "NATIVE");
        return image;
    }

    private String imageArn(String name, String region) {
        ObjectNode owned = catalog.get(catalogKey(IMAGE, region, name)).orElse(null);
        if (owned != null) {
            return owned.path("Arn").asText();
        }
        return regionResolver.buildArn("appstream", region, "", "image/" + name);
    }

    private boolean imageExists(String name, String region) {
        return PUBLIC_IMAGES.contains(name) || catalog.get(catalogKey(IMAGE, region, name)).isPresent();
    }

    private ObjectNode requireImage(String name, String region) {
        if (PUBLIC_IMAGES.contains(name)) {
            return publicImage(name, region);
        }
        return requireCatalog(catalogKey(IMAGE, region, name), "image", name);
    }

    private ObjectNode requirePrivateImage(String name, String region) {
        return requireCatalog(catalogKey(IMAGE, region, name), "image", name);
    }

    private static String platformFor(String imageName) {
        String lower = imageName.toLowerCase();
        if (lower.contains("amazonlinux2")) {
            return "AMAZON_LINUX2";
        }
        if (lower.contains("rhel8")) {
            return "RHEL8";
        }
        if (lower.contains("rocky")) {
            return "ROCKY_LINUX8";
        }
        if (lower.contains("ubuntu")) {
            return "UBUNTU_PRO_2404";
        }
        if (lower.contains("2016")) {
            return "WINDOWS_SERVER_2016";
        }
        if (lower.contains("2022")) {
            return "WINDOWS_SERVER_2022";
        }
        if (lower.contains("2025")) {
            return "WINDOWS_SERVER_2025";
        }
        return "WINDOWS_SERVER_2019";
    }

    // ──────────────────────────── App blocks ────────────────────────────

    public synchronized ObjectNode createAppBlock(JsonNode request, String region) {
        String name = required(request, "Name");
        if (!request.path("SourceS3Location").isObject()) {
            required(request, "SourceS3Location");
        }
        String blockKey = catalogKey(APP_BLOCK, region, name);
        if (catalog.get(blockKey).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException", "The app block " + name + " already exists.", 400);
        }
        ObjectNode block = JSON.objectNode();
        block.put("Name", name);
        block.put("Arn", regionResolver.buildArn("appstream", region, "app-block/" + name));
        block.put("CreatedTime", now());
        block.put("PackagingType", "CUSTOM");
        copyMembers(request, block, Set.of("Name", "Tags"));
        block.put("State", "APPSTREAM2".equals(block.path("PackagingType").asText()) ? "INACTIVE" : "ACTIVE");
        block.putArray("AppBlockErrors");
        catalog.put(blockKey, block);
        putTags(block.path("Arn").asText(), request.get("Tags"));
        return block.deepCopy();
    }

    public List<ObjectNode> describeAppBlocks(JsonNode arns, String region) {
        return describeByArn(APP_BLOCK, arns, region);
    }

    public synchronized void deleteAppBlock(String name, String region) {
        String blockKey = catalogKey(APP_BLOCK, region, name);
        ObjectNode block = requireCatalog(blockKey, "app block", name);
        String arn = block.path("Arn").asText();
        boolean inUse = catalog.scan(k -> k.startsWith(APPLICATION + "::" + region + "::")).stream()
                .anyMatch(a -> arn.equals(a.path("AppBlockArn").asText()));
        if (inUse) {
            throw new AwsException("ResourceInUseException", "The app block " + name + " is used by an application.", 400);
        }
        catalog.delete(blockKey);
        tags.delete(arn);
    }

    // ──────────────────────────── Applications ────────────────────────────

    public synchronized ObjectNode createApplication(JsonNode request, String region) {
        String name = required(request, "Name");
        required(request, "LaunchPath");
        String appBlockArn = required(request, "AppBlockArn");
        if (!request.path("IconS3Location").isObject()) {
            required(request, "IconS3Location");
        }
        requireAppBlockArn(appBlockArn, region);
        String appKey = catalogKey(APPLICATION, region, name);
        if (catalog.get(appKey).isPresent()) {
            throw new AwsException("ResourceAlreadyExistsException",
                    "The application " + name + " already exists.", 400);
        }
        ObjectNode application = JSON.objectNode();
        application.put("Name", name);
        application.put("Arn", regionResolver.buildArn("appstream", region, "application/" + name));
        application.put("Enabled", true);
        application.putObject("Metadata");
        application.put("CreatedTime", now());
        copyMembers(request, application, Set.of("Name", "Tags"));
        catalog.put(appKey, application);
        putTags(application.path("Arn").asText(), request.get("Tags"));
        return application.deepCopy();
    }

    public List<ObjectNode> describeApplications(JsonNode arns, String region) {
        return describeByArn(APPLICATION, arns, region);
    }

    public synchronized ObjectNode updateApplication(JsonNode request, String region) {
        String name = required(request, "Name");
        String appKey = catalogKey(APPLICATION, region, name);
        ObjectNode application = requireCatalog(appKey, "application", name);
        if (request.hasNonNull("AppBlockArn")) {
            requireAppBlockArn(request.path("AppBlockArn").asText(), region);
        }
        copyMembers(request, application, Set.of("Name", "AttributesToDelete"));
        for (JsonNode attribute : request.path("AttributesToDelete")) {
            switch (attribute.asText()) {
                case "LAUNCH_PARAMETERS" -> application.remove("LaunchParameters");
                case "WORKING_DIRECTORY" -> application.remove("WorkingDirectory");
                default -> {
                    // Unknown attribute names leave the application unchanged.
                }
            }
        }
        catalog.put(appKey, application);
        return application.deepCopy();
    }

    public synchronized void deleteApplication(String name, String region) {
        String appKey = catalogKey(APPLICATION, region, name);
        ObjectNode application = requireCatalog(appKey, "application", name);
        String arn = application.path("Arn").asText();
        if (!catalog.scan(k -> k.startsWith(APP_FLEET + "::" + region + "::") && k.endsWith("::" + arn)).isEmpty()) {
            throw new AwsException("ResourceInUseException",
                    "The application " + name + " is associated with a fleet.", 400);
        }
        catalog.delete(appKey);
        tags.delete(arn);
    }

    public synchronized ObjectNode associateApplicationFleet(String fleetName, String applicationArn, String region) {
        requireFleet(fleetName, region);
        requireApplicationArn(applicationArn, region);
        ObjectNode association = JSON.objectNode().put("FleetName", fleetName).put("ApplicationArn", applicationArn);
        catalog.put(APP_FLEET + "::" + region + "::" + fleetName + "::" + applicationArn, association);
        return association.deepCopy();
    }

    public synchronized void disassociateApplicationFleet(String fleetName, String applicationArn, String region) {
        catalog.delete(APP_FLEET + "::" + region + "::" + fleetName + "::" + applicationArn);
    }

    public List<ObjectNode> describeApplicationFleetAssociations(JsonNode request, String region) {
        String fleetName = request.path("FleetName").asText("");
        String applicationArn = request.path("ApplicationArn").asText("");
        if (fleetName.isEmpty() && applicationArn.isEmpty()) {
            throw new AwsException("InvalidParameterCombinationException",
                    "Either FleetName or ApplicationArn is required.", 400);
        }
        return catalog.scan(k -> k.startsWith(APP_FLEET + "::" + region + "::")).stream()
                .filter(a -> fleetName.isEmpty() || fleetName.equals(a.path("FleetName").asText()))
                .filter(a -> applicationArn.isEmpty() || applicationArn.equals(a.path("ApplicationArn").asText()))
                .map(ObjectNode::deepCopy)
                .toList();
    }

    private void requireAppBlockArn(String arn, String region) {
        boolean exists = catalog.scan(k -> k.startsWith(APP_BLOCK + "::" + region + "::")).stream()
                .anyMatch(b -> arn.equals(b.path("Arn").asText()));
        if (!exists) {
            throw new AwsException("ResourceNotFoundException", "The app block " + arn + " was not found.", 400);
        }
    }

    private void requireApplicationArn(String arn, String region) {
        boolean exists = catalog.scan(k -> k.startsWith(APPLICATION + "::" + region + "::")).stream()
                .anyMatch(a -> arn.equals(a.path("Arn").asText()));
        if (!exists) {
            throw new AwsException("ResourceNotFoundException", "The application " + arn + " was not found.", 400);
        }
    }

    // ──────────────────────────── Entitlements ────────────────────────────

    public synchronized ObjectNode createEntitlement(JsonNode request, String region) {
        String name = required(request, "Name");
        String stackName = required(request, "StackName");
        String visibility = required(request, "AppVisibility");
        if (!request.path("Attributes").isArray()) {
            required(request, "Attributes");
        }
        requireStack(stackName, region);
        String entitlementKey = entitlementKey(region, stackName, name);
        if (catalog.get(entitlementKey).isPresent()) {
            throw new AwsException("EntitlementAlreadyExistsException",
                    "The entitlement " + name + " already exists.", 400);
        }
        double created = now();
        ObjectNode entitlement = JSON.objectNode();
        entitlement.put("Name", name);
        entitlement.put("StackName", stackName);
        if (request.hasNonNull("Description")) {
            entitlement.put("Description", request.path("Description").asText());
        }
        entitlement.put("AppVisibility", visibility);
        entitlement.set("Attributes", request.path("Attributes").deepCopy());
        entitlement.put("CreatedTime", created);
        entitlement.put("LastModifiedTime", created);
        catalog.put(entitlementKey, entitlement);
        return entitlement.deepCopy();
    }

    public List<ObjectNode> describeEntitlements(JsonNode request, String region) {
        String stackName = required(request, "StackName");
        requireStack(stackName, region);
        if (request.hasNonNull("Name")) {
            return List.of(stripEntitled(requireEntitlement(region, stackName, request.path("Name").asText())));
        }
        return catalog.scan(k -> k.startsWith(ENTITLEMENT + "::" + region + "::" + stackName + "::")).stream()
                .map(AppStreamService::stripEntitled)
                .sorted(Comparator.comparing((ObjectNode n) -> n.path("Name").asText()))
                .toList();
    }

    public synchronized ObjectNode updateEntitlement(JsonNode request, String region) {
        String name = required(request, "Name");
        String stackName = required(request, "StackName");
        requireStack(stackName, region);
        ObjectNode entitlement = requireEntitlement(region, stackName, name);
        for (String member : List.of("Description", "AppVisibility", "Attributes")) {
            if (request.hasNonNull(member)) {
                entitlement.set(member, request.get(member).deepCopy());
            }
        }
        entitlement.put("LastModifiedTime", now());
        catalog.put(entitlementKey(region, stackName, name), entitlement);
        return stripEntitled(entitlement);
    }

    public synchronized void deleteEntitlement(String name, String stackName, String region) {
        requireStack(stackName, region);
        requireEntitlement(region, stackName, name);
        catalog.delete(entitlementKey(region, stackName, name));
    }

    public synchronized void setEntitledApplication(JsonNode request, String region, boolean entitled) {
        String stackName = required(request, "StackName");
        String name = required(request, "EntitlementName");
        String application = required(request, "ApplicationIdentifier");
        requireStack(stackName, region);
        ObjectNode entitlement = requireEntitlement(region, stackName, name);
        ArrayNode current = entitlement.get(ENTITLED_APPLICATIONS) instanceof ArrayNode a ? a
                : entitlement.putArray(ENTITLED_APPLICATIONS);
        ArrayNode updated = JSON.arrayNode();
        current.forEach(a -> {
            if (!application.equals(a.asText())) {
                updated.add(a);
            }
        });
        if (entitled) {
            updated.add(application);
        }
        entitlement.set(ENTITLED_APPLICATIONS, updated);
        catalog.put(entitlementKey(region, stackName, name), entitlement);
    }

    public List<ObjectNode> listEntitledApplications(JsonNode request, String region) {
        String stackName = required(request, "StackName");
        String name = required(request, "EntitlementName");
        requireStack(stackName, region);
        ObjectNode entitlement = requireEntitlement(region, stackName, name);
        List<ObjectNode> result = new ArrayList<>();
        entitlement.path(ENTITLED_APPLICATIONS)
                .forEach(a -> result.add(JSON.objectNode().put("ApplicationIdentifier", a.asText())));
        return result;
    }

    private ObjectNode requireEntitlement(String region, String stackName, String name) {
        return catalog.get(entitlementKey(region, stackName, name)).orElseThrow(() -> new AwsException(
                "EntitlementNotFoundException", "The entitlement " + name + " was not found.", 400));
    }

    private static ObjectNode stripEntitled(ObjectNode entitlement) {
        ObjectNode copy = entitlement.deepCopy();
        copy.remove(ENTITLED_APPLICATIONS);
        return copy;
    }

    private static String entitlementKey(String region, String stackName, String name) {
        return ENTITLEMENT + "::" + region + "::" + stackName + "::" + name;
    }

    // ──────────────────────────── Catalog helpers ────────────────────────────

    private List<ObjectNode> describeByName(String type, String label, JsonNode names, String region) {
        if (names != null && names.isArray() && !names.isEmpty()) {
            List<ObjectNode> result = new ArrayList<>();
            for (JsonNode name : names) {
                result.add(requireCatalog(catalogKey(type, region, name.asText()), label, name.asText()).deepCopy());
            }
            return result;
        }
        return sorted(catalog.scan(k -> k.startsWith(type + "::" + region + "::")), "Name");
    }

    private List<ObjectNode> describeByArn(String type, JsonNode arns, String region) {
        Set<String> wanted = new HashSet<>();
        if (arns != null) {
            arns.forEach(a -> wanted.add(a.asText()));
        }
        return sorted(catalog.scan(k -> k.startsWith(type + "::" + region + "::")), "Name").stream()
                .filter(n -> wanted.isEmpty() || wanted.contains(n.path("Arn").asText()))
                .toList();
    }

    private ObjectNode requireCatalog(String catalogKey, String label, String name) {
        return catalog.get(catalogKey).orElseThrow(() -> new AwsException("ResourceNotFoundException",
                "The " + label + " " + name + " was not found.", 400));
    }

    private static String catalogKey(String type, String region, String name) {
        return type + "::" + region + "::" + name;
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
                || fleets.scan(k -> true).stream().anyMatch(f -> arn.equals(f.path("Arn").asText()))
                || catalog.scan(k -> TAGGABLE.stream().anyMatch(t -> k.startsWith(t + "::"))).stream()
                        .anyMatch(r -> arn.equals(r.path("Arn").asText()));
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
