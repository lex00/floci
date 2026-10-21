package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DataZone group profiles, user and group profile search, the portal login URL, a domain's data
 * export configuration and its notifications. Floci raises no notifications, so the list is empty.
 */
@ApplicationScoped
public class DataZoneProfileService extends DataZoneRecords {

    private static final String GROUP_PROFILE = "group-profile";
    private static final String DATA_EXPORT = "data-export-configuration";
    private static final String DATA_EXPORT_ID = "configuration";

    private static final Set<String> GROUP_STATUSES = Set.of("ASSIGNED", "NOT_ASSIGNED");
    private static final Set<String> GROUP_SEARCH_TYPES = Set.of("SSO_GROUP", "DATAZONE_SSO_GROUP",
            "IAM_ROLE_SESSION_GROUP");
    private static final Set<String> USER_SEARCH_TYPES = Set.of("SSO_USER", "DATAZONE_USER", "DATAZONE_SSO_USER",
            "DATAZONE_IAM_USER");
    private static final Set<String> NOTIFICATION_TYPES = Set.of("TASK", "EVENT");
    private static final Set<String> TASK_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> SSE_ALGORITHMS = Set.of("aws:kms", "AES256");

    private final DataZoneEnvironmentService environments;

    @Inject
    public DataZoneProfileService(StorageFactory storageFactory, DataZoneService domains,
                                  DataZoneEnvironmentService environments, RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-profiles.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, environments, regionResolver);
    }

    DataZoneProfileService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                           DataZoneEnvironmentService environments, RegionResolver regionResolver) {
        super(records, domains, regionResolver);
        this.environments = environments;
    }

    // ──────────────────────────── Group profiles ────────────────────────────

    public synchronized ObjectNode createGroupProfile(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String group = requiredText(request, "groupIdentifier");
        if (findGroup(region, domainId, group) != null) {
            throw conflict("A group profile for " + group + " already exists.");
        }
        ObjectNode profile = JSON.objectNode();
        profile.put("domainId", domainId);
        profile.put("id", DataZoneService.randomId());
        profile.put("status", "ASSIGNED");
        profile.put("groupName", group);
        String role = text(request, "rolePrincipalArn");
        if (role != null) {
            profile.put("rolePrincipalArn", role);
            profile.put("rolePrincipalId", "AROA" + DataZoneService.randomId().toUpperCase(Locale.ROOT));
        }
        profile.put("createdAt", now());
        put(GROUP_PROFILE, region, domainId, profile.path("id").asText(), profile);
        return publicGroup(profile);
    }

    public synchronized ObjectNode getGroupProfile(String region, String domainIdentifier, String groupIdentifier) {
        return publicGroup(requireGroup(region, domainId(region, domainIdentifier), groupIdentifier));
    }

    public synchronized ObjectNode updateGroupProfile(String region, String domainIdentifier, String groupIdentifier,
                                                      JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode profile = requireGroup(region, domainId, groupIdentifier);
        profile.put("status", requireEnum(requiredText(request, "status"), "status", GROUP_STATUSES));
        put(GROUP_PROFILE, region, domainId, profile.path("id").asText(), profile);
        return publicGroup(profile);
    }

    public synchronized List<ObjectNode> searchGroupProfiles(String region, String domainIdentifier,
                                                             JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String groupType = requireEnum(requiredText(request, "groupType"), "groupType", GROUP_SEARCH_TYPES);
        boolean roleSessions = "IAM_ROLE_SESSION_GROUP".equals(groupType);
        String searchText = text(request, "searchText");
        return list(GROUP_PROFILE, region, domainId).stream()
                .filter(p -> p.has("rolePrincipalArn") == roleSessions)
                .filter(p -> contains(p.path("groupName").asText(), searchText))
                .map(DataZoneProfileService::publicGroup)
                .toList();
    }

    private ObjectNode findGroup(String region, String domainId, String groupIdentifier) {
        return list(GROUP_PROFILE, region, domainId).stream()
                .filter(p -> groupIdentifier.equals(p.path("id").asText())
                        || groupIdentifier.equals(p.path("groupName").asText()))
                .findFirst().orElse(null);
    }

    private ObjectNode requireGroup(String region, String domainId, String groupIdentifier) {
        ObjectNode profile = findGroup(region, domainId, groupIdentifier);
        if (profile == null) {
            throw notFound("Group profile " + groupIdentifier + " not found.");
        }
        return profile;
    }

    private static ObjectNode publicGroup(ObjectNode profile) {
        ObjectNode out = profile.deepCopy();
        out.remove("createdAt");
        return out;
    }

    // ──────────────────────────── User profiles ────────────────────────────

    public List<ObjectNode> searchUserProfiles(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String userType = requireEnum(requiredText(request, "userType"), "userType", USER_SEARCH_TYPES);
        String searchText = text(request, "searchText");
        return environments.listUserProfiles(region, domainId).stream()
                .filter(p -> switch (userType) {
                    case "DATAZONE_IAM_USER" -> "IAM".equals(p.path("type").asText());
                    case "DATAZONE_USER" -> true;
                    default -> "SSO".equals(p.path("type").asText());
                })
                .filter(p -> searchText == null || contains(p.path("id").asText(), searchText)
                        || p.path("details").findValuesAsText("arn").stream().anyMatch(v -> contains(v, searchText))
                        || p.path("details").path("sso").properties().stream()
                                .anyMatch(e -> contains(e.getValue().asText(), searchText)))
                .toList();
    }

    public synchronized ObjectNode getIamPortalLoginUrl(String region, String domainIdentifier) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        ObjectNode request = JSON.objectNode()
                .put("userIdentifier", "arn:aws:iam::" + account() + ":root")
                .put("userType", "IAM_USER");
        String profileId = environments.createUserProfile(region, domain.getId(), request).path("id").asText();
        ObjectNode out = JSON.objectNode();
        out.put("authCodeUrl", domain.getPortalUrl() + "/auth?code=" + DataZoneService.randomId());
        out.put("userProfileId", profileId);
        return out;
    }

    // ──────────────────────────── Data export configuration ────────────────────────────

    public synchronized void putDataExportConfiguration(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        JsonNode enable = requiredNode(request, "enableExport");
        JsonNode encryption = request.get("encryptionConfiguration");
        if (encryption != null && !encryption.isNull()) {
            requireEnum(text(encryption, "sseAlgorithm"), "encryptionConfiguration.sseAlgorithm", SSE_ALGORITHMS);
        }
        BigDecimal now = now();
        ObjectNode configuration = find(DATA_EXPORT, region, domainId, DATA_EXPORT_ID).orElseGet(() -> {
            ObjectNode created = JSON.objectNode();
            created.put("createdAt", now);
            return created;
        });
        configuration.put("isExportEnabled", enable.asBoolean());
        configuration.put("status", "COMPLETED");
        if (encryption != null && !encryption.isNull()) {
            configuration.set("encryptionConfiguration", encryption.deepCopy());
        }
        configuration.put("s3TableBucketArn", "arn:aws:s3tables:" + region + ":" + account()
                + ":bucket/aws-sagemaker-catalog");
        configuration.put("updatedAt", now);
        put(DATA_EXPORT, region, domainId, DATA_EXPORT_ID, configuration);
    }

    public synchronized ObjectNode getDataExportConfiguration(String region, String domainIdentifier) {
        return find(DATA_EXPORT, region, domainId(region, domainIdentifier), DATA_EXPORT_ID)
                .orElseThrow(() -> notFound("Domain " + domainIdentifier + " has no data export configuration."))
                .deepCopy();
    }

    public synchronized void deleteDataExportConfiguration(String region, String domainIdentifier) {
        String domainId = domainId(region, domainIdentifier);
        getDataExportConfiguration(region, domainId);
        delete(DATA_EXPORT, region, domainId, DATA_EXPORT_ID);
    }

    // ──────────────────────────── Notifications ────────────────────────────

    public List<ObjectNode> listNotifications(String region, String domainIdentifier, String type,
                                              String taskStatus) {
        domainId(region, domainIdentifier);
        if (type == null) {
            throw required("type");
        }
        requireEnum(type, "type", NOTIFICATION_TYPES);
        requireEnum(taskStatus, "taskStatus", TASK_STATUSES);
        return List.of();
    }

    private static boolean contains(String value, String searchText) {
        return searchText == null || value.toLowerCase(Locale.ROOT).contains(searchText.toLowerCase(Locale.ROOT));
    }
}
