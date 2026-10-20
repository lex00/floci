package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DataZone environments and what they are built from: the managed environment blueprints, a
 * domain's blueprint configurations, environment profiles and environments. Also serves form
 * types, asset types and user profiles. Environments are {@code ACTIVE} as soon as they are
 * created and provision nothing. Records are keyed by type, region, domain and id.
 */
@ApplicationScoped
public class DataZoneEnvironmentService implements Resettable {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String PROVIDER = "Amazon DataZone";
    private static final List<String> MANAGED_BLUEPRINTS = List.of("DefaultDataLake", "DefaultDataWarehouse");
    private static final Set<String> USER_STATUSES = Set.of("ASSIGNED", "NOT_ASSIGNED", "ACTIVATED", "DEACTIVATED");

    private static final String BLUEPRINT_CONFIGURATION = "blueprint-configuration";
    private static final String ENVIRONMENT_PROFILE = "environment-profile";
    private static final String ENVIRONMENT = "environment";
    private static final String FORM_TYPE = "form-type";
    private static final String ASSET_TYPE = "asset-type";
    private static final String USER_PROFILE = "user-profile";

    private final StorageBackend<String, ObjectNode> records;
    private final DataZoneService domains;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneEnvironmentService(StorageFactory storageFactory, DataZoneService domains,
                                      RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-environments.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, regionResolver);
    }

    DataZoneEnvironmentService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                               RegionResolver regionResolver) {
        this.records = records;
        this.domains = domains;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        records.clear();
    }

    // ──────────────────────────── Environment blueprints ────────────────────────────

    public List<ObjectNode> listEnvironmentBlueprints(String region, String domainId, String name, Boolean managed) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        if (Boolean.FALSE.equals(managed)) {
            return List.of();
        }
        List<ObjectNode> result = new ArrayList<>();
        for (String blueprint : MANAGED_BLUEPRINTS) {
            if (name == null || blueprint.equals(name)) {
                ObjectNode summary = blueprint(domain, blueprint, region);
                summary.remove(List.of("deploymentProperties", "userParameters", "glossaryTerms"));
                result.add(summary);
            }
        }
        return result;
    }

    public ObjectNode getEnvironmentBlueprint(String region, String domainId, String identifier) {
        return blueprint(domains.getDomain(region, domainId), blueprintName(region, domainId, identifier), region);
    }

    private ObjectNode blueprint(DataZoneDomain domain, String name, String region) {
        ObjectNode blueprint = JSON.objectNode();
        blueprint.put("id", blueprintId(domain.getId(), name));
        blueprint.put("name", name);
        blueprint.put("description", name.contains("DataLake")
                ? "Load and query data in an AWS Glue and Amazon Athena data lake."
                : "Load and query data in an Amazon Redshift data warehouse.");
        blueprint.put("provider", PROVIDER);
        blueprint.putObject("provisioningProperties").putObject("cloudFormation").put("templateUrl",
                "https://s3." + region + "." + AwsRegions.dnsSuffixFor(region) + "/datazone-blueprints-" + region
                        + "/" + name + ".template.json");
        blueprint.putObject("deploymentProperties").put("startTimeoutMinutes", 120).put("endTimeoutMinutes", 120);
        blueprint.putArray("userParameters");
        blueprint.putArray("glossaryTerms");
        String created = Instant.ofEpochMilli(domain.getCreatedAt()).toString();
        blueprint.put("createdAt", created);
        blueprint.put("updatedAt", created);
        return blueprint;
    }

    private String blueprintName(String region, String domainId, String identifier) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        for (String name : MANAGED_BLUEPRINTS) {
            if (blueprintId(domain.getId(), name).equals(identifier)) {
                return name;
            }
        }
        throw notFound("Environment blueprint " + identifier + " not found.");
    }

    /** A stable 14-character blueprint id for each domain and blueprint name. */
    static String blueprintId(String domainId, String name) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((domainId + "/" + name).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 14);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    // ──────────────────────────── Blueprint configurations ────────────────────────────

    public synchronized ObjectNode putEnvironmentBlueprintConfiguration(String region, String domainId,
                                                                        String blueprintId, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        blueprintName(region, domainId, blueprintId);
        if (!request.path("enabledRegions").isArray()) {
            throw required("enabledRegions");
        }
        String key = key(BLUEPRINT_CONFIGURATION, region, domain.getId(), blueprintId);
        String now = Instant.now().toString();
        String created = records.get(key).map(c -> c.path("createdAt").asText()).orElse(now);
        ObjectNode configuration = JSON.objectNode();
        configuration.put("domainId", domain.getId());
        configuration.put("environmentBlueprintId", blueprintId);
        for (String member : List.of("provisioningRoleArn", "environmentRolePermissionBoundary",
                "manageAccessRoleArn", "enabledRegions", "regionalParameters", "provisioningConfigurations")) {
            if (request.hasNonNull(member)) {
                configuration.set(member, request.get(member).deepCopy());
            }
        }
        configuration.put("createdAt", created);
        configuration.put("updatedAt", now);
        records.put(key, configuration);
        return configuration.deepCopy();
    }

    public ObjectNode getEnvironmentBlueprintConfiguration(String region, String domainId, String blueprintId) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        return records.get(key(BLUEPRINT_CONFIGURATION, region, domain.getId(), blueprintId))
                .orElseThrow(() -> notFound("Environment blueprint configuration " + blueprintId + " not found."))
                .deepCopy();
    }

    public List<ObjectNode> listEnvironmentBlueprintConfigurations(String region, String domainId) {
        return list(BLUEPRINT_CONFIGURATION, region, domains.getDomain(region, domainId).getId(),
                "environmentBlueprintId");
    }

    public synchronized void deleteEnvironmentBlueprintConfiguration(String region, String domainId,
                                                                     String blueprintId) {
        records.delete(key(BLUEPRINT_CONFIGURATION, region, domains.getDomain(region, domainId).getId(), blueprintId));
    }

    // ──────────────────────────── Environment profiles ────────────────────────────

    public synchronized ObjectNode createEnvironmentProfile(String region, String domainId, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        String name = requiredText(request, "name");
        String blueprintId = requiredText(request, "environmentBlueprintIdentifier");
        String projectId = requiredText(request, "projectIdentifier");
        blueprintName(region, domainId, blueprintId);
        domains.getProject(region, domainId, projectId);
        boolean taken = list(ENVIRONMENT_PROFILE, region, domain.getId(), "id").stream()
                .anyMatch(p -> name.equals(p.path("name").asText()));
        if (taken) {
            throw conflict("An environment profile with name " + name + " already exists.");
        }
        String now = Instant.now().toString();
        ObjectNode profile = JSON.objectNode();
        profile.put("id", DataZoneService.randomId());
        profile.put("domainId", domain.getId());
        profile.put("awsAccountId", request.path("awsAccountId").asText(regionResolver.getAccountId()));
        profile.put("awsAccountRegion", request.path("awsAccountRegion").asText(region));
        profile.put("createdBy", regionResolver.getAccountId());
        profile.put("createdAt", now);
        profile.put("updatedAt", now);
        profile.put("name", name);
        if (request.hasNonNull("description")) {
            profile.put("description", request.get("description").asText());
        }
        profile.put("environmentBlueprintId", blueprintId);
        profile.put("projectId", projectId);
        profile.set("userParameters", customParameters(request.path("userParameters")));
        records.put(key(ENVIRONMENT_PROFILE, region, domain.getId(), profile.path("id").asText()), profile);
        return profile.deepCopy();
    }

    public ObjectNode getEnvironmentProfile(String region, String domainId, String identifier) {
        return requireRecord(ENVIRONMENT_PROFILE, region, domainId, identifier, "Environment profile").deepCopy();
    }

    public synchronized ObjectNode updateEnvironmentProfile(String region, String domainId, String identifier,
                                                            JsonNode request) {
        ObjectNode profile = requireRecord(ENVIRONMENT_PROFILE, region, domainId, identifier, "Environment profile");
        for (String member : List.of("name", "description", "awsAccountId", "awsAccountRegion")) {
            if (request.hasNonNull(member)) {
                profile.put(member, request.get(member).asText());
            }
        }
        if (request.path("userParameters").isArray()) {
            profile.set("userParameters", customParameters(request.get("userParameters")));
        }
        profile.put("updatedAt", Instant.now().toString());
        records.put(key(ENVIRONMENT_PROFILE, region, profile.path("domainId").asText(), identifier), profile);
        return profile.deepCopy();
    }

    public synchronized void deleteEnvironmentProfile(String region, String domainId, String identifier) {
        ObjectNode profile = requireRecord(ENVIRONMENT_PROFILE, region, domainId, identifier, "Environment profile");
        String domain = profile.path("domainId").asText();
        boolean inUse = list(ENVIRONMENT, region, domain, "id").stream()
                .anyMatch(e -> identifier.equals(e.path("environmentProfileId").asText()));
        if (inUse) {
            throw conflict("Environment profile " + identifier + " is used by an environment.");
        }
        records.delete(key(ENVIRONMENT_PROFILE, region, domain, identifier));
    }

    public List<ObjectNode> listEnvironmentProfiles(String region, String domainId, String projectId,
                                                    String blueprintId, String name) {
        return list(ENVIRONMENT_PROFILE, region, domains.getDomain(region, domainId).getId(), "id").stream()
                .filter(p -> projectId == null || projectId.equals(p.path("projectId").asText()))
                .filter(p -> blueprintId == null || blueprintId.equals(p.path("environmentBlueprintId").asText()))
                .filter(p -> name == null || p.path("name").asText().contains(name))
                .map(p -> {
                    p.remove("userParameters");
                    return p;
                })
                .toList();
    }

    // ──────────────────────────── Environments ────────────────────────────

    public synchronized ObjectNode createEnvironment(String region, String domainId, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        String name = requiredText(request, "name");
        String projectId = requiredText(request, "projectIdentifier");
        domains.getProject(region, domainId, projectId);
        String profileId = request.path("environmentProfileIdentifier").asText(null);
        ObjectNode profile = null;
        String blueprintId = request.path("environmentBlueprintIdentifier").asText(null);
        if (profileId != null) {
            profile = requireRecord(ENVIRONMENT_PROFILE, region, domainId, profileId, "Environment profile");
            blueprintId = profile.path("environmentBlueprintId").asText();
        } else if (blueprintId == null) {
            throw required("environmentProfileIdentifier");
        }
        String now = Instant.now().toString();
        String id = DataZoneService.randomId();
        ObjectNode environment = JSON.objectNode();
        environment.put("projectId", projectId);
        environment.put("id", id);
        environment.put("domainId", domain.getId());
        environment.put("createdBy", regionResolver.getAccountId());
        environment.put("createdAt", now);
        environment.put("updatedAt", now);
        environment.put("name", name);
        if (request.hasNonNull("description")) {
            environment.put("description", request.get("description").asText());
        }
        if (profileId != null) {
            environment.put("environmentProfileId", profileId);
        }
        environment.put("awsAccountId", profile != null ? profile.path("awsAccountId").asText()
                : request.path("environmentAccountIdentifier").asText(regionResolver.getAccountId()));
        environment.put("awsAccountRegion", profile != null ? profile.path("awsAccountRegion").asText()
                : request.path("environmentAccountRegion").asText(region));
        environment.put("provider", PROVIDER);
        environment.putArray("provisionedResources");
        environment.put("status", "ACTIVE");
        environment.putArray("environmentActions");
        environment.set("glossaryTerms", request.path("glossaryTerms").isArray()
                ? request.get("glossaryTerms").deepCopy() : JSON.arrayNode());
        environment.set("userParameters", customParameters(request.path("userParameters")));
        ObjectNode deployment = environment.putObject("lastDeployment");
        deployment.put("deploymentId", DataZoneService.randomId());
        deployment.put("deploymentType", "CREATE");
        deployment.put("deploymentStatus", "SUCCESSFUL");
        deployment.put("isDeploymentComplete", true);
        environment.put("environmentBlueprintId", blueprintId);
        if (request.hasNonNull("environmentConfigurationId")) {
            environment.put("environmentConfigurationId", request.get("environmentConfigurationId").asText());
        }
        records.put(key(ENVIRONMENT, region, domain.getId(), id), environment);
        return environment.deepCopy();
    }

    public ObjectNode getEnvironment(String region, String domainId, String identifier) {
        return requireRecord(ENVIRONMENT, region, domainId, identifier, "Environment").deepCopy();
    }

    public synchronized ObjectNode updateEnvironment(String region, String domainId, String identifier,
                                                     JsonNode request) {
        ObjectNode environment = requireRecord(ENVIRONMENT, region, domainId, identifier, "Environment");
        for (String member : List.of("name", "description")) {
            if (request.hasNonNull(member)) {
                environment.put(member, request.get(member).asText());
            }
        }
        if (request.path("glossaryTerms").isArray()) {
            environment.set("glossaryTerms", request.get("glossaryTerms").deepCopy());
        }
        if (request.path("userParameters").isArray()) {
            environment.set("userParameters", customParameters(request.get("userParameters")));
        }
        environment.put("updatedAt", Instant.now().toString());
        records.put(key(ENVIRONMENT, region, environment.path("domainId").asText(), identifier), environment);
        return environment.deepCopy();
    }

    public synchronized void deleteEnvironment(String region, String domainId, String identifier) {
        ObjectNode environment = requireRecord(ENVIRONMENT, region, domainId, identifier, "Environment");
        records.delete(key(ENVIRONMENT, region, environment.path("domainId").asText(), identifier));
    }

    public List<ObjectNode> listEnvironments(String region, String domainId, String projectId, String profileId,
                                             String blueprintId, String status, String name) {
        return list(ENVIRONMENT, region, domains.getDomain(region, domainId).getId(), "id").stream()
                .filter(e -> projectId == null || projectId.equals(e.path("projectId").asText()))
                .filter(e -> profileId == null || profileId.equals(e.path("environmentProfileId").asText()))
                .filter(e -> blueprintId == null || blueprintId.equals(e.path("environmentBlueprintId").asText()))
                .filter(e -> status == null || status.equals(e.path("status").asText()))
                .filter(e -> name == null || e.path("name").asText().contains(name))
                .map(e -> {
                    ObjectNode summary = JSON.objectNode();
                    for (String member : List.of("projectId", "id", "domainId", "createdBy", "createdAt",
                            "updatedAt", "name", "description", "environmentProfileId", "awsAccountId",
                            "awsAccountRegion", "provider", "status", "environmentConfigurationId")) {
                        if (e.has(member)) {
                            summary.set(member, e.get(member));
                        }
                    }
                    return summary;
                })
                .toList();
    }

    public boolean projectHasEnvironments(String region, String domainId, String projectId) {
        return list(ENVIRONMENT, region, domainId, "id").stream()
                .anyMatch(e -> projectId.equals(e.path("projectId").asText()));
    }

    // ──────────────────────────── Form types and asset types ────────────────────────────

    public synchronized ObjectNode createFormType(String region, String domainId, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        String name = requiredText(request, "name");
        if (!request.path("model").isObject()) {
            throw required("model");
        }
        String projectId = requiredText(request, "owningProjectIdentifier");
        domains.getProject(region, domainId, projectId);
        String key = key(FORM_TYPE, region, domain.getId(), name);
        int revision = records.get(key).map(f -> f.path("revision").asInt() + 1).orElse(1);
        ObjectNode formType = JSON.objectNode();
        formType.put("domainId", domain.getId());
        formType.put("name", name);
        formType.put("revision", Integer.toString(revision));
        formType.set("model", request.get("model").deepCopy());
        formType.put("owningProjectId", projectId);
        formType.put("originDomainId", domain.getId());
        formType.put("originProjectId", projectId);
        formType.put("status", request.path("status").asText("ENABLED"));
        formType.put("createdAt", epochSeconds(Instant.now()));
        formType.put("createdBy", regionResolver.getAccountId());
        if (request.hasNonNull("description")) {
            formType.put("description", request.get("description").asText());
        }
        formType.putArray("imports");
        records.put(key, formType);
        ObjectNode response = formType.deepCopy();
        response.remove(List.of("model", "status", "createdAt", "createdBy", "imports"));
        return response;
    }

    public ObjectNode getFormType(String region, String domainId, String identifier) {
        return requireRecord(FORM_TYPE, region, domainId, identifier, "Form type").deepCopy();
    }

    public synchronized void deleteFormType(String region, String domainId, String identifier) {
        ObjectNode formType = requireRecord(FORM_TYPE, region, domainId, identifier, "Form type");
        String domain = formType.path("domainId").asText();
        boolean inUse = list(ASSET_TYPE, region, domain, "name").stream().anyMatch(a ->
                a.path("formsOutput").findValues("typeName").stream().anyMatch(n -> identifier.equals(n.asText())));
        if (inUse) {
            throw conflict("Form type " + identifier + " is used by an asset type.");
        }
        records.delete(key(FORM_TYPE, region, domain, identifier));
    }

    public synchronized ObjectNode createAssetType(String region, String domainId, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        String name = requiredText(request, "name");
        if (!request.path("formsInput").isObject()) {
            throw required("formsInput");
        }
        String projectId = requiredText(request, "owningProjectIdentifier");
        domains.getProject(region, domainId, projectId);
        ObjectNode forms = JSON.objectNode();
        request.get("formsInput").properties().forEach(entry -> {
            String typeName = entry.getValue().path("typeIdentifier").asText();
            if (records.get(key(FORM_TYPE, region, domain.getId(), typeName)).isEmpty()) {
                throw notFound("Form type " + typeName + " not found.");
            }
            ObjectNode form = forms.putObject(entry.getKey());
            form.put("typeName", typeName);
            form.put("typeRevision", entry.getValue().path("typeRevision").asText("1"));
            form.put("required", entry.getValue().path("required").asBoolean(false));
        });
        String key = key(ASSET_TYPE, region, domain.getId(), name);
        int revision = records.get(key).map(a -> a.path("revision").asInt() + 1).orElse(1);
        BigDecimal now = epochSeconds(Instant.now());
        ObjectNode assetType = JSON.objectNode();
        assetType.put("domainId", domain.getId());
        assetType.put("name", name);
        assetType.put("revision", Integer.toString(revision));
        if (request.hasNonNull("description")) {
            assetType.put("description", request.get("description").asText());
        }
        assetType.set("formsOutput", forms);
        assetType.put("owningProjectId", projectId);
        assetType.put("originDomainId", domain.getId());
        assetType.put("originProjectId", projectId);
        assetType.put("createdAt", now);
        assetType.put("createdBy", regionResolver.getAccountId());
        assetType.put("updatedAt", now);
        assetType.put("updatedBy", regionResolver.getAccountId());
        records.put(key, assetType);
        return assetType.deepCopy();
    }

    public ObjectNode getAssetType(String region, String domainId, String identifier) {
        return requireRecord(ASSET_TYPE, region, domainId, identifier, "Asset type").deepCopy();
    }

    public synchronized void deleteAssetType(String region, String domainId, String identifier) {
        ObjectNode assetType = requireRecord(ASSET_TYPE, region, domainId, identifier, "Asset type");
        records.delete(key(ASSET_TYPE, region, assetType.path("domainId").asText(), identifier));
    }

    // ──────────────────────────── User profiles ────────────────────────────

    public synchronized ObjectNode createUserProfile(String region, String domainId, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        String user = requiredText(request, "userIdentifier");
        String userType = request.path("userType").asText(user.startsWith("arn:") ? "IAM_ROLE" : "SSO_USER");
        String key = key(USER_PROFILE, region, domain.getId(), user);
        ObjectNode existing = records.get(key).orElse(null);
        if (existing != null) {
            return existing.deepCopy();
        }
        ObjectNode profile = JSON.objectNode();
        profile.put("domainId", domain.getId());
        profile.put("id", DataZoneService.randomId());
        boolean sso = "SSO_USER".equals(userType);
        profile.put("type", sso ? "SSO" : "IAM");
        profile.put("status", "ASSIGNED");
        ObjectNode details = profile.putObject("details");
        if (sso) {
            details.putObject("sso").put("username", user);
        } else {
            details.putObject("iam").put("arn", user).put("principalId", "AROA" + DataZoneService.randomId()
                    .toUpperCase());
        }
        profile.put("userIdentifier", user);
        records.put(key, profile);
        return publicProfile(profile);
    }

    public ObjectNode getUserProfile(String region, String domainId, String userIdentifier) {
        return publicProfile(requireUserProfile(region, domainId, userIdentifier));
    }

    public synchronized ObjectNode updateUserProfile(String region, String domainId, String userIdentifier,
                                                     JsonNode request) {
        ObjectNode profile = requireUserProfile(region, domainId, userIdentifier);
        String status = requiredText(request, "status");
        if (!USER_STATUSES.contains(status)) {
            throw SmithyValidationErrors.validationException("Value '" + status
                    + "' at 'status' failed to satisfy constraint: Member must satisfy enum value set: "
                    + USER_STATUSES);
        }
        profile.put("status", status);
        records.put(key(USER_PROFILE, region, profile.path("domainId").asText(),
                profile.path("userIdentifier").asText()), profile);
        return publicProfile(profile);
    }

    private ObjectNode requireUserProfile(String region, String domainId, String userIdentifier) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        ObjectNode byIdentifier = records.get(key(USER_PROFILE, region, domain.getId(), userIdentifier)).orElse(null);
        if (byIdentifier != null) {
            return byIdentifier;
        }
        return list(USER_PROFILE, region, domain.getId(), "id").stream()
                .filter(p -> userIdentifier.equals(p.path("id").asText()))
                .findFirst()
                .map(p -> records.get(key(USER_PROFILE, region, domain.getId(), p.path("userIdentifier").asText()))
                        .orElseThrow())
                .orElseThrow(() -> notFound("User profile " + userIdentifier + " not found."));
    }

    private static ObjectNode publicProfile(ObjectNode profile) {
        ObjectNode copy = profile.deepCopy();
        copy.remove("userIdentifier");
        return copy;
    }

    // ──────────────────────────── Domains ────────────────────────────

    public synchronized void deleteDomainResources(String region, String domainId) {
        for (String key : List.copyOf(records.keys())) {
            if (key.contains("::" + region + "::" + domainId + "::")) {
                records.delete(key);
            }
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode requireRecord(String type, String region, String domainId, String id, String label) {
        DataZoneDomain domain = domains.getDomain(region, domainId);
        return records.get(key(type, region, domain.getId(), id))
                .orElseThrow(() -> notFound(label + " " + id + " not found."));
    }

    private List<ObjectNode> list(String type, String region, String domainId, String sortMember) {
        String prefix = type + "::" + region + "::" + domainId + "::";
        return records.scan(k -> k.startsWith(prefix)).stream()
                .map(ObjectNode::deepCopy)
                .sorted(Comparator.comparing((ObjectNode n) -> n.path(sortMember).asText()))
                .toList();
    }

    /** Turns {@code EnvironmentParameter} input into {@code CustomParameter} output. */
    private static ArrayNode customParameters(JsonNode parameters) {
        ArrayNode result = JSON.arrayNode();
        for (JsonNode parameter : parameters) {
            ObjectNode custom = result.addObject();
            custom.put("keyName", parameter.path("name").asText());
            custom.put("fieldType", "String");
            if (parameter.hasNonNull("value")) {
                custom.put("defaultValue", parameter.get("value").asText());
            }
            custom.put("isEditable", true);
            custom.put("isOptional", true);
        }
        return result;
    }

    private static BigDecimal epochSeconds(Instant instant) {
        return BigDecimal.valueOf(instant.toEpochMilli()).movePointLeft(3);
    }

    private static String key(String type, String region, String domainId, String id) {
        return type + "::" + region + "::" + domainId + "::" + id;
    }

    private static String requiredText(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw required(member);
        }
        return value.asText();
    }

    private static AwsException required(String member) {
        return SmithyValidationErrors.validationException(
                "Value null at '" + member + "' failed to satisfy constraint: Member must not be null");
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
