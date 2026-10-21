package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DataZone domain administration: project profiles, metadata rules, account pools, and the
 * actions, roles and credentials of an environment.
 */
@ApplicationScoped
public class DataZoneAdministrationService extends DataZoneRecords {

    private static final String PROJECT_PROFILE = "project-profile";
    private static final String RULE = "rule";
    private static final String RULE_REVISION = "rule-revision";
    private static final String ACCOUNT_POOL = "account-pool";
    private static final String ENVIRONMENT_ACTION = "environment-action";
    private static final String ENVIRONMENT_ROLE = "environment-role";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX")
            .withZone(ZoneOffset.UTC);
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Set<String> SORT_ORDERS = Set.of("ASCENDING", "DESCENDING");
    private static final Set<String> DEPLOYMENT_MODES = Set.of("ON_CREATE", "ON_DEMAND");
    private static final Set<String> RULE_ACTIONS = Set.of("CREATE_LISTING_CHANGE_SET", "CREATE_SUBSCRIPTION_REQUEST");
    private static final Set<String> RULE_TYPES = Set.of("METADATA_FORM_ENFORCEMENT", "GLOSSARY_TERM_ENFORCEMENT");
    private static final Set<String> SELECTION_MODES = Set.of("ALL", "SPECIFIC");
    private static final Map<String, String> RULE_TYPE_BY_DETAIL = Map.of(
            "metadataFormEnforcementDetail", "METADATA_FORM_ENFORCEMENT",
            "glossaryTermEnforcementDetail", "GLOSSARY_TERM_ENFORCEMENT");

    private final DataZoneGovernanceService governance;
    private final DataZoneEnvironmentService environments;

    @Inject
    public DataZoneAdministrationService(StorageFactory storageFactory, DataZoneService domains,
                                         DataZoneGovernanceService governance,
                                         DataZoneEnvironmentService environments, RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-administration.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, governance, environments, regionResolver);
    }

    DataZoneAdministrationService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                                  DataZoneGovernanceService governance, DataZoneEnvironmentService environments,
                                  RegionResolver regionResolver) {
        super(records, domains, regionResolver);
        this.governance = governance;
        this.environments = environments;
    }

    // ──────────────────────────── Project profiles ────────────────────────────

    public synchronized ObjectNode createProjectProfile(String region, String domainIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String name = requiredText(request, "name");
        requireUniqueName(PROJECT_PROFILE, region, domain.getId(), name, null, "project profile");
        requireEnum(text(request, "status"), "status", STATUSES);
        String now = iso(Instant.now());
        ObjectNode profile = JSON.objectNode();
        profile.put("domainId", domain.getId());
        profile.put("id", DataZoneService.randomId());
        profile.put("name", name);
        copy(request, profile, "description");
        profile.put("status", request.path("status").asText("ENABLED"));
        profile.set("projectResourceTags", request.path("projectResourceTags").isArray()
                ? request.get("projectResourceTags").deepCopy() : JSON.arrayNode());
        profile.put("allowCustomProjectResourceTags", request.path("allowCustomProjectResourceTags").asBoolean(false));
        copy(request, profile, "projectResourceTagsDescription");
        profile.set("environmentConfigurations", environmentConfigurations(request.path("environmentConfigurations")));
        profile.put("createdBy", account());
        profile.put("createdAt", now);
        profile.put("lastUpdatedAt", now);
        profile.put("domainUnitId", domainUnitId(region, domain, text(request, "domainUnitIdentifier")));
        put(PROJECT_PROFILE, region, domain.getId(), profile.path("id").asText(), profile);
        return profile.deepCopy();
    }

    public synchronized ObjectNode getProjectProfile(String region, String domainIdentifier, String identifier) {
        return require(PROJECT_PROFILE, region, domainId(region, domainIdentifier), identifier, "Project profile")
                .deepCopy();
    }

    public synchronized ObjectNode updateProjectProfile(String region, String domainIdentifier, String identifier,
                                                        JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        ObjectNode profile = require(PROJECT_PROFILE, region, domain.getId(), identifier, "Project profile");
        String name = text(request, "name");
        if (name != null) {
            requireUniqueName(PROJECT_PROFILE, region, domain.getId(), name, identifier, "project profile");
        }
        requireEnum(text(request, "status"), "status", STATUSES);
        copy(request, profile, "name", "description", "status", "projectResourceTags",
                "allowCustomProjectResourceTags", "projectResourceTagsDescription");
        if (request.path("environmentConfigurations").isArray()) {
            profile.set("environmentConfigurations",
                    environmentConfigurations(request.get("environmentConfigurations")));
        }
        if (request.hasNonNull("domainUnitIdentifier")) {
            profile.put("domainUnitId", domainUnitId(region, domain, request.get("domainUnitIdentifier").asText()));
        }
        profile.put("lastUpdatedAt", iso(Instant.now()));
        put(PROJECT_PROFILE, region, domain.getId(), identifier, profile);
        return profile.deepCopy();
    }

    public synchronized void deleteProjectProfile(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        require(PROJECT_PROFILE, region, domainId, identifier, "Project profile");
        delete(PROJECT_PROFILE, region, domainId, identifier);
    }

    public synchronized List<ObjectNode> listProjectProfiles(String region, String domainIdentifier,
                                                             Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(query.get("sortBy"), "sortBy", Set.of("NAME"));
        return sorted(list(PROJECT_PROFILE, region, domainId), query).stream()
                .filter(p -> query.get("name") == null || p.path("name").asText().contains(query.get("name")))
                .map(p -> pick(p, "domainId", "id", "name", "description", "status", "createdBy", "createdAt",
                        "lastUpdatedAt", "domainUnitId"))
                .toList();
    }

    /** Each {@code EnvironmentConfiguration} keeps its id, or gets one. */
    private static ArrayNode environmentConfigurations(JsonNode input) {
        ArrayNode out = JSON.arrayNode();
        for (JsonNode configuration : input) {
            requiredText(configuration, "name");
            requiredText(configuration, "environmentBlueprintId");
            requireEnum(text(configuration, "deploymentMode"), "deploymentMode", DEPLOYMENT_MODES);
            ObjectNode copy = (ObjectNode) configuration.deepCopy();
            if (!copy.hasNonNull("id")) {
                copy.put("id", DataZoneService.randomId());
            }
            out.add(copy);
        }
        return out;
    }

    // ──────────────────────────── Rules ────────────────────────────

    public synchronized ObjectNode createRule(String region, String domainIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String name = requiredText(request, "name");
        JsonNode target = requiredNode(request, "target");
        unionMember(target, "target");
        JsonNode unitTarget = target.get("domainUnitTarget");
        if (unitTarget == null) {
            throw SmithyValidationErrors.validationException("target must name a domainUnitTarget.");
        }
        String unitId = governance.getDomainUnit(region, domain.getId(), requiredText(unitTarget, "domainUnitId"))
                .path("id").asText();
        String action = requireEnum(requiredText(request, "action"), "action", RULE_ACTIONS);
        JsonNode scope = requiredNode(request, "scope");
        validateScope(scope);
        JsonNode detail = requiredNode(request, "detail");
        String ruleType = ruleType(detail);
        requireUniqueName(RULE, region, domain.getId(), name, null, "rule");
        BigDecimal now = now();
        ObjectNode rule = JSON.objectNode();
        rule.put("identifier", DataZoneService.randomId());
        rule.put("revision", "1");
        rule.put("name", name);
        rule.put("ruleType", ruleType);
        ObjectNode storedTarget = rule.putObject("target").putObject("domainUnitTarget");
        storedTarget.put("domainUnitId", unitId);
        storedTarget.put("includeChildDomainUnits", unitTarget.path("includeChildDomainUnits").asBoolean(false));
        rule.put("action", action);
        rule.set("scope", scope.deepCopy());
        rule.set("detail", detail.deepCopy());
        rule.put("targetType", "DOMAIN_UNIT");
        copy(request, rule, "description");
        rule.put("createdAt", now);
        rule.put("updatedAt", now);
        rule.put("createdBy", account());
        rule.put("lastUpdatedBy", account());
        String id = rule.path("identifier").asText();
        put(RULE, region, domain.getId(), id, rule);
        put(RULE_REVISION, region, domain.getId(), id + "@1", rule.deepCopy());
        ObjectNode response = rule.deepCopy();
        response.remove(List.of("revision", "updatedAt", "lastUpdatedBy"));
        return response;
    }

    public synchronized ObjectNode getRule(String region, String domainIdentifier, String identifier,
                                           String revision) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode rule = require(RULE, region, domainId, identifier, "Rule");
        if (revision == null || revision.equals(rule.path("revision").asText())) {
            return rule.deepCopy();
        }
        return find(RULE_REVISION, region, domainId, identifier + "@" + revision)
                .orElseThrow(() -> notFound("Rule " + identifier + " revision " + revision + " not found."))
                .deepCopy();
    }

    public synchronized ObjectNode updateRule(String region, String domainIdentifier, String identifier,
                                              JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode rule = require(RULE, region, domainId, identifier, "Rule");
        String name = text(request, "name");
        if (name != null) {
            requireUniqueName(RULE, region, domainId, name, identifier, "rule");
        }
        if (request.hasNonNull("scope")) {
            validateScope(request.get("scope"));
        }
        if (request.hasNonNull("detail") && !rule.path("ruleType").asText().equals(ruleType(request.get("detail")))) {
            throw SmithyValidationErrors.validationException("The detail of rule " + identifier + " must stay "
                    + rule.path("ruleType").asText() + ".");
        }
        copy(request, rule, "name", "description", "scope", "detail");
        if (request.hasNonNull("includeChildDomainUnits")) {
            ((ObjectNode) rule.path("target").path("domainUnitTarget"))
                    .put("includeChildDomainUnits", request.get("includeChildDomainUnits").asBoolean());
        }
        String revision = Integer.toString(rule.path("revision").asInt() + 1);
        rule.put("revision", revision);
        rule.put("updatedAt", now());
        rule.put("lastUpdatedBy", account());
        put(RULE, region, domainId, identifier, rule);
        put(RULE_REVISION, region, domainId, identifier + "@" + revision, rule.deepCopy());
        return rule.deepCopy();
    }

    public synchronized void deleteRule(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode rule = require(RULE, region, domainId, identifier, "Rule");
        for (int revision = 1; revision <= rule.path("revision").asInt(); revision++) {
            delete(RULE_REVISION, region, domainId, identifier + "@" + revision);
        }
        delete(RULE, region, domainId, identifier);
    }

    public synchronized List<ObjectNode> listRules(String region, String domainIdentifier, String targetType,
                                                   String targetIdentifier, Map<String, String> query,
                                                   List<String> projectIds, List<String> assetTypes) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        requireEnum(targetType, "targetType", Set.of("DOMAIN_UNIT"));
        requireEnum(query.get("ruleType"), "ruleType", RULE_TYPES);
        requireEnum(query.get("ruleAction"), "ruleAction", RULE_ACTIONS);
        String unitId = governance.getDomainUnit(region, domain.getId(), targetIdentifier).path("id").asText();
        Set<String> ancestors = Boolean.parseBoolean(query.get("includeCascaded"))
                ? ancestors(region, domain.getId(), unitId) : Set.of();
        return list(RULE, region, domain.getId()).stream()
                .filter(r -> {
                    JsonNode target = r.path("target").path("domainUnitTarget");
                    String ruleUnit = target.path("domainUnitId").asText();
                    return unitId.equals(ruleUnit)
                            || (ancestors.contains(ruleUnit) && target.path("includeChildDomainUnits").asBoolean());
                })
                .filter(r -> query.get("ruleType") == null || query.get("ruleType").equals(r.path("ruleType").asText()))
                .filter(r -> query.get("ruleAction") == null
                        || query.get("ruleAction").equals(r.path("action").asText()))
                .filter(r -> scopeMatches(r.path("scope").path("project"), "specificProjects", projectIds))
                .filter(r -> scopeMatches(r.path("scope").path("assetType"), "specificAssetTypes", assetTypes))
                .filter(r -> query.get("dataProduct") == null || Boolean.parseBoolean(query.get("dataProduct"))
                        == r.path("scope").path("dataProduct").asBoolean(false))
                .map(r -> pick(r, "identifier", "revision", "ruleType", "name", "targetType", "target", "action",
                        "scope", "updatedAt", "lastUpdatedBy"))
                .toList();
    }

    private Set<String> ancestors(String region, String domainId, String unitId) {
        Set<String> out = new HashSet<>();
        JsonNode unit = governance.getDomainUnit(region, domainId, unitId);
        while (unit.hasNonNull("parentDomainUnitId") && out.add(unit.get("parentDomainUnitId").asText())) {
            unit = governance.getDomainUnit(region, domainId, unit.get("parentDomainUnitId").asText());
        }
        return out;
    }

    private static boolean scopeMatches(JsonNode scope, String specificMember, List<String> wanted) {
        if (wanted == null || wanted.isEmpty() || scope.isMissingNode()
                || !"SPECIFIC".equals(scope.path("selectionMode").asText())) {
            return true;
        }
        for (JsonNode value : scope.path(specificMember)) {
            if (wanted.contains(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private static void validateScope(JsonNode scope) {
        for (String member : List.of("assetType", "project")) {
            if (scope.hasNonNull(member)) {
                requireEnum(requiredText(scope.get(member), "selectionMode"), "selectionMode", SELECTION_MODES);
            }
        }
    }

    private static String ruleType(JsonNode detail) {
        return RULE_TYPE_BY_DETAIL.get(unionMember(detail, "detail"));
    }

    // ──────────────────────────── Account pools ────────────────────────────

    public synchronized ObjectNode createAccountPool(String region, String domainIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String name = requiredText(request, "name");
        requireEnum(requiredText(request, "resolutionStrategy"), "resolutionStrategy", Set.of("MANUAL"));
        JsonNode source = accountSource(requiredNode(request, "accountSource"));
        requireUniqueName(ACCOUNT_POOL, region, domain.getId(), name, null, "account pool");
        String now = iso(Instant.now());
        ObjectNode pool = JSON.objectNode();
        pool.put("domainId", domain.getId());
        pool.put("name", name);
        pool.put("id", DataZoneService.randomId());
        copy(request, pool, "description");
        pool.put("resolutionStrategy", "MANUAL");
        pool.set("accountSource", source.deepCopy());
        pool.put("createdBy", account());
        pool.put("createdAt", now);
        pool.put("lastUpdatedAt", now);
        pool.put("updatedBy", account());
        pool.put("domainUnitId", domain.getRootDomainUnitId());
        put(ACCOUNT_POOL, region, domain.getId(), pool.path("id").asText(), pool);
        return pool.deepCopy();
    }

    public synchronized ObjectNode getAccountPool(String region, String domainIdentifier, String identifier) {
        return require(ACCOUNT_POOL, region, domainId(region, domainIdentifier), identifier, "Account pool")
                .deepCopy();
    }

    public synchronized ObjectNode updateAccountPool(String region, String domainIdentifier, String identifier,
                                                     JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode pool = require(ACCOUNT_POOL, region, domainId, identifier, "Account pool");
        String name = text(request, "name");
        if (name != null) {
            requireUniqueName(ACCOUNT_POOL, region, domainId, name, identifier, "account pool");
        }
        requireEnum(text(request, "resolutionStrategy"), "resolutionStrategy", Set.of("MANUAL"));
        if (request.hasNonNull("accountSource")) {
            pool.set("accountSource", accountSource(request.get("accountSource")).deepCopy());
        }
        copy(request, pool, "name", "description", "resolutionStrategy");
        pool.put("lastUpdatedAt", iso(Instant.now()));
        pool.put("updatedBy", account());
        put(ACCOUNT_POOL, region, domainId, identifier, pool);
        return pool.deepCopy();
    }

    public synchronized void deleteAccountPool(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        require(ACCOUNT_POOL, region, domainId, identifier, "Account pool");
        delete(ACCOUNT_POOL, region, domainId, identifier);
    }

    public synchronized List<ObjectNode> listAccountPools(String region, String domainIdentifier,
                                                          Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(query.get("sortBy"), "sortBy", Set.of("NAME"));
        return sorted(list(ACCOUNT_POOL, region, domainId), query).stream()
                .filter(p -> query.get("name") == null || p.path("name").asText().contains(query.get("name")))
                .map(p -> pick(p, "domainId", "id", "name", "resolutionStrategy", "domainUnitId", "createdBy",
                        "updatedBy"))
                .toList();
    }

    public synchronized List<ObjectNode> listAccountsInAccountPool(String region, String domainIdentifier,
                                                                   String identifier) {
        ObjectNode pool = getAccountPool(region, domainIdentifier, identifier);
        List<ObjectNode> accounts = new ArrayList<>();
        pool.path("accountSource").path("accounts").forEach(a -> accounts.add((ObjectNode) a));
        return accounts;
    }

    private static JsonNode accountSource(JsonNode source) {
        String kind = unionMember(source, "accountSource");
        if ("accounts".equals(kind)) {
            for (JsonNode account : source.get("accounts")) {
                requiredText(account, "awsAccountId");
                if (!account.path("supportedRegions").isArray()) {
                    throw required("supportedRegions");
                }
            }
        } else if ("customAccountPoolHandler".equals(kind)) {
            requiredText(source.get(kind), "lambdaFunctionArn");
        } else {
            throw SmithyValidationErrors.validationException("Unknown account source " + kind + ".");
        }
        return source;
    }

    // ──────────────────────────── Environment actions ────────────────────────────

    public synchronized ObjectNode createEnvironmentAction(String region, String domainIdentifier,
                                                           String environmentIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        environments.getEnvironment(region, domainId, environmentIdentifier);
        String name = requiredText(request, "name");
        JsonNode parameters = requiredNode(request, "parameters");
        unionMember(parameters, "parameters");
        ObjectNode action = JSON.objectNode();
        action.put("domainId", domainId);
        action.put("environmentId", environmentIdentifier);
        action.put("id", DataZoneService.randomId());
        action.put("name", name);
        action.set("parameters", parameters.deepCopy());
        copy(request, action, "description");
        action.put("createdAt", now());
        put(ENVIRONMENT_ACTION, region, domainId, action.path("id").asText(), action);
        return publicAction(action);
    }

    public synchronized ObjectNode getEnvironmentAction(String region, String domainIdentifier,
                                                        String environmentIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        return publicAction(requireAction(region, domainId, environmentIdentifier, identifier));
    }

    public synchronized ObjectNode updateEnvironmentAction(String region, String domainIdentifier,
                                                           String environmentIdentifier, String identifier,
                                                           JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode action = requireAction(region, domainId, environmentIdentifier, identifier);
        if (request.hasNonNull("parameters")) {
            unionMember(request.get("parameters"), "parameters");
        }
        copy(request, action, "name", "description", "parameters");
        put(ENVIRONMENT_ACTION, region, domainId, identifier, action);
        return publicAction(action);
    }

    public synchronized void deleteEnvironmentAction(String region, String domainIdentifier,
                                                     String environmentIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        requireAction(region, domainId, environmentIdentifier, identifier);
        delete(ENVIRONMENT_ACTION, region, domainId, identifier);
    }

    public synchronized List<ObjectNode> listEnvironmentActions(String region, String domainIdentifier,
                                                                String environmentIdentifier) {
        String domainId = domainId(region, domainIdentifier);
        environments.getEnvironment(region, domainId, environmentIdentifier);
        return list(ENVIRONMENT_ACTION, region, domainId).stream()
                .filter(a -> environmentIdentifier.equals(a.path("environmentId").asText()))
                .map(DataZoneAdministrationService::publicAction)
                .toList();
    }

    private ObjectNode requireAction(String region, String domainId, String environmentId, String identifier) {
        environments.getEnvironment(region, domainId, environmentId);
        ObjectNode action = require(ENVIRONMENT_ACTION, region, domainId, identifier, "Environment action");
        if (!environmentId.equals(action.path("environmentId").asText())) {
            throw notFound("Environment action " + identifier + " not found.");
        }
        return action;
    }

    private static ObjectNode publicAction(ObjectNode action) {
        ObjectNode out = action.deepCopy();
        out.remove("createdAt");
        return out;
    }

    // ──────────────────────────── Environment roles and credentials ────────────────────────────

    public synchronized void associateEnvironmentRole(String region, String domainIdentifier,
                                                      String environmentIdentifier, String roleArn) {
        String domainId = domainId(region, domainIdentifier);
        environments.getEnvironment(region, domainId, environmentIdentifier);
        ObjectNode role = JSON.objectNode();
        role.put("environmentId", environmentIdentifier);
        role.put("environmentRoleArn", roleArn);
        role.put("createdAt", now());
        put(ENVIRONMENT_ROLE, region, domainId, environmentIdentifier + "|" + roleArn, role);
    }

    public synchronized void disassociateEnvironmentRole(String region, String domainIdentifier,
                                                         String environmentIdentifier, String roleArn) {
        String domainId = domainId(region, domainIdentifier);
        environments.getEnvironment(region, domainId, environmentIdentifier);
        String id = environmentIdentifier + "|" + roleArn;
        require(ENVIRONMENT_ROLE, region, domainId, id, "Environment role " + roleArn + " of environment");
        delete(ENVIRONMENT_ROLE, region, domainId, id);
    }

    public synchronized ObjectNode getEnvironmentCredentials(String region, String domainIdentifier,
                                                             String environmentIdentifier) {
        environments.getEnvironment(region, domainId(region, domainIdentifier), environmentIdentifier);
        ObjectNode credentials = JSON.objectNode();
        credentials.put("accessKeyId", "ASIAFLOCIDATAZONEENV");
        credentials.put("secretAccessKey", "floci/datazone/environment/secret/access/key");
        credentials.put("sessionToken", "FwoGZXIvYXdzEFlociDataZoneEnvironmentSessionToken");
        credentials.put("expiration", iso(Instant.now().plus(1, ChronoUnit.HOURS)));
        return credentials;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private String domainUnitId(String region, DataZoneDomain domain, String identifier) {
        if (identifier == null) {
            return domain.getRootDomainUnitId();
        }
        return governance.getDomainUnit(region, domain.getId(), identifier).path("id").asText();
    }

    private void requireUniqueName(String type, String region, String domainId, String name, String selfId,
                                   String label) {
        boolean taken = list(type, region, domainId).stream()
                .anyMatch(r -> name.equals(r.path("name").asText())
                        && !(r.path("id").asText(r.path("identifier").asText())).equals(selfId));
        if (taken) {
            throw conflict("A " + label + " with name " + name + " already exists.");
        }
    }

    /** Oldest first, or by name when {@code sortBy} is NAME. */
    private static List<ObjectNode> sorted(List<ObjectNode> records, Map<String, String> query) {
        requireEnum(query.get("sortOrder"), "sortOrder", SORT_ORDERS);
        Comparator<ObjectNode> order = "NAME".equals(query.get("sortBy"))
                ? Comparator.comparing((ObjectNode r) -> r.path("name").asText())
                : Comparator.comparing((ObjectNode r) -> r.path("createdAt").asText());
        order = order.thenComparing(r -> r.path("id").asText());
        return records.stream()
                .sorted("DESCENDING".equals(query.get("sortOrder")) ? order.reversed() : order)
                .toList();
    }

    private static String iso(Instant instant) {
        return ISO.format(instant);
    }
}
