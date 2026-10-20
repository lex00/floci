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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * DataZone domain units and their owners, project memberships and managed policy grants. A
 * domain's root unit is created on first use, named after the domain.
 */
@ApplicationScoped
public class DataZoneGovernanceService extends DataZoneRecords {

    private static final String DOMAIN_UNIT = "domain-unit";
    private static final String MEMBERSHIP = "membership";
    private static final String POLICY_GRANT = "policy-grant";

    private static final Set<String> DESIGNATIONS = Set.of("PROJECT_OWNER", "PROJECT_CONTRIBUTOR",
            "PROJECT_CATALOG_VIEWER", "PROJECT_CATALOG_CONSUMER", "PROJECT_CATALOG_STEWARD");
    private static final Set<String> TARGET_ENTITY_TYPES = Set.of("DOMAIN_UNIT",
            "ENVIRONMENT_BLUEPRINT_CONFIGURATION", "ENVIRONMENT_PROFILE", "ASSET_TYPE");
    private static final Set<String> POLICY_TYPES = Set.of("CREATE_DOMAIN_UNIT", "OVERRIDE_DOMAIN_UNIT_OWNERS",
            "ADD_TO_PROJECT_MEMBER_POOL", "OVERRIDE_PROJECT_OWNERS", "CREATE_GLOSSARY", "CREATE_FORM_TYPE",
            "CREATE_ASSET_TYPE", "CREATE_PROJECT", "CREATE_ENVIRONMENT_PROFILE",
            "DELEGATE_CREATE_ENVIRONMENT_PROFILE", "CREATE_ENVIRONMENT", "CREATE_ENVIRONMENT_FROM_BLUEPRINT",
            "CREATE_PROJECT_FROM_PROJECT_PROFILE", "USE_ASSET_TYPE");
    private static final String GRANT_ID_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DataZoneEnvironmentService environments;

    @Inject
    public DataZoneGovernanceService(StorageFactory storageFactory, DataZoneService domains,
                                     DataZoneEnvironmentService environments, RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-governance.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, environments, regionResolver);
    }

    DataZoneGovernanceService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                              DataZoneEnvironmentService environments, RegionResolver regionResolver) {
        super(records, domains, regionResolver);
        this.environments = environments;
    }

    // ──────────────────────────── Domain units ────────────────────────────

    public synchronized ObjectNode createDomainUnit(String region, String domainIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String name = requiredText(request, "name");
        ObjectNode parent = requireDomainUnit(region, domain, requiredText(request, "parentDomainUnitIdentifier"));
        String parentId = parent.path("id").asText();
        boolean taken = list(DOMAIN_UNIT, region, domain.getId()).stream()
                .anyMatch(u -> parentId.equals(u.path("parentDomainUnitId").asText())
                        && name.equals(u.path("name").asText()));
        if (taken) {
            throw conflict("A domain unit with name " + name + " already exists under " + parentId + ".");
        }
        ArrayNode ancestors = JSON.arrayNode();
        parent.path("ancestorDomainUnitIds").forEach(ancestors::add);
        ancestors.add(parentId);
        ObjectNode unit = JSON.objectNode();
        unit.put("id", DataZoneService.randomId());
        unit.put("domainId", domain.getId());
        unit.put("name", name);
        unit.put("parentDomainUnitId", parentId);
        if (request.hasNonNull("description")) {
            unit.put("description", request.get("description").asText());
        }
        unit.putArray("owners");
        unit.set("ancestorDomainUnitIds", ancestors);
        unit.put("createdAt", now());
        unit.put("createdBy", account());
        unit.put("lastUpdatedAt", unit.get("createdAt").decimalValue());
        unit.put("lastUpdatedBy", account());
        put(DOMAIN_UNIT, region, domain.getId(), unit.path("id").asText(), unit);
        ObjectNode response = unit.deepCopy();
        response.remove(List.of("lastUpdatedAt", "lastUpdatedBy"));
        return response;
    }

    public synchronized ObjectNode getDomainUnit(String region, String domainIdentifier, String identifier) {
        ObjectNode unit = requireDomainUnit(region, domains.getDomain(region, domainIdentifier), identifier).deepCopy();
        unit.remove("ancestorDomainUnitIds");
        return unit;
    }

    public synchronized ObjectNode updateDomainUnit(String region, String domainIdentifier, String identifier,
                                                    JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        ObjectNode unit = requireDomainUnit(region, domain, identifier);
        String name = text(request, "name");
        if (name != null && !name.equals(unit.path("name").asText()) && unit.hasNonNull("parentDomainUnitId")) {
            String parentId = unit.get("parentDomainUnitId").asText();
            boolean taken = list(DOMAIN_UNIT, region, domain.getId()).stream()
                    .anyMatch(u -> parentId.equals(u.path("parentDomainUnitId").asText())
                            && name.equals(u.path("name").asText()));
            if (taken) {
                throw conflict("A domain unit with name " + name + " already exists under " + parentId + ".");
            }
        }
        copy(request, unit, "name", "description");
        unit.put("lastUpdatedAt", now());
        unit.put("lastUpdatedBy", account());
        put(DOMAIN_UNIT, region, domain.getId(), identifier, unit);
        ObjectNode response = unit.deepCopy();
        response.remove("ancestorDomainUnitIds");
        return response;
    }

    public synchronized void deleteDomainUnit(String region, String domainIdentifier, String identifier) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        ObjectNode unit = requireDomainUnit(region, domain, identifier);
        if (!unit.hasNonNull("parentDomainUnitId")) {
            throw conflict("The root domain unit of a domain cannot be deleted.");
        }
        boolean hasChildren = list(DOMAIN_UNIT, region, domain.getId()).stream()
                .anyMatch(u -> identifier.equals(u.path("parentDomainUnitId").asText()));
        boolean hasProjects = domains.listProjects(region, domain.getId(), null).stream()
                .anyMatch(p -> identifier.equals(p.getDomainUnitId()));
        if (hasChildren || hasProjects) {
            throw conflict("Domain unit " + identifier + " still has child domain units or projects.");
        }
        delete(DOMAIN_UNIT, region, domain.getId(), identifier);
    }

    public synchronized List<ObjectNode> listDomainUnitsForParent(String region, String domainIdentifier,
                                                                  String parentIdentifier) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        if (parentIdentifier == null || parentIdentifier.isEmpty()) {
            throw required("parentDomainUnitIdentifier");
        }
        String parentId = requireDomainUnit(region, domain, parentIdentifier).path("id").asText();
        return list(DOMAIN_UNIT, region, domain.getId()).stream()
                .filter(u -> parentId.equals(u.path("parentDomainUnitId").asText()))
                .map(u -> pick(u, "name", "id"))
                .toList();
    }

    private ObjectNode requireDomainUnit(String region, DataZoneDomain domain, String identifier) {
        ObjectNode unit = find(DOMAIN_UNIT, region, domain.getId(), identifier).orElse(null);
        if (unit != null) {
            return unit;
        }
        if (identifier.equals(domain.getRootDomainUnitId())) {
            ObjectNode root = JSON.objectNode();
            root.put("id", identifier);
            root.put("domainId", domain.getId());
            root.put("name", domain.getName());
            root.putArray("owners");
            root.putArray("ancestorDomainUnitIds");
            root.put("createdAt", BigDecimal.valueOf(domain.getCreatedAt()).movePointLeft(3));
            root.put("createdBy", domain.getCreatedBy());
            root.put("lastUpdatedAt", root.get("createdAt").decimalValue());
            root.put("lastUpdatedBy", domain.getCreatedBy());
            put(DOMAIN_UNIT, region, domain.getId(), identifier, root);
            return root;
        }
        throw notFound("Domain unit " + identifier + " not found.");
    }

    // ──────────────────────────── Entity owners ────────────────────────────

    public synchronized void addEntityOwner(String region, String domainIdentifier, String entityType,
                                            String entityIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        ObjectNode unit = requireOwnedEntity(region, domain, entityType, entityIdentifier);
        ObjectNode owner = ownerOutput(region, domain, requiredNode(request, "owner"));
        ArrayNode owners = (ArrayNode) unit.withArray("owners");
        for (JsonNode existing : owners) {
            if (existing.equals(owner)) {
                throw conflict("The owner is already an owner of " + entityIdentifier + ".");
            }
        }
        owners.add(owner);
        put(DOMAIN_UNIT, region, domain.getId(), unit.path("id").asText(), unit);
    }

    public synchronized void removeEntityOwner(String region, String domainIdentifier, String entityType,
                                               String entityIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        ObjectNode unit = requireOwnedEntity(region, domain, entityType, entityIdentifier);
        ObjectNode owner = ownerOutput(region, domain, requiredNode(request, "owner"));
        ArrayNode owners = (ArrayNode) unit.withArray("owners");
        for (int i = 0; i < owners.size(); i++) {
            if (owners.get(i).equals(owner)) {
                owners.remove(i);
                put(DOMAIN_UNIT, region, domain.getId(), unit.path("id").asText(), unit);
                return;
            }
        }
        throw notFound("The owner is not an owner of " + entityIdentifier + ".");
    }

    public synchronized List<ObjectNode> listEntityOwners(String region, String domainIdentifier,
                                                          String entityType, String entityIdentifier) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        List<ObjectNode> owners = new ArrayList<>();
        requireOwnedEntity(region, domain, entityType, entityIdentifier).path("owners")
                .forEach(o -> owners.add((ObjectNode) o.deepCopy()));
        return owners;
    }

    public synchronized void updateRootDomainUnitOwner(String region, String domainIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String current = environments.userId(region, domain.getId(), requiredText(request, "currentOwner"));
        String replacement = environments.userId(region, domain.getId(), requiredText(request, "newOwner"));
        ObjectNode root = requireDomainUnit(region, domain, domain.getRootDomainUnitId());
        ArrayNode owners = (ArrayNode) root.withArray("owners");
        for (int i = 0; i < owners.size(); i++) {
            if (current.equals(owners.get(i).path("user").path("userId").asText())) {
                owners.remove(i);
                break;
            }
        }
        ObjectNode owner = JSON.objectNode();
        owner.putObject("user").put("userId", replacement);
        if (!containsNode(owners, owner)) {
            owners.add(owner);
        }
        put(DOMAIN_UNIT, region, domain.getId(), root.path("id").asText(), root);
    }

    private ObjectNode requireOwnedEntity(String region, DataZoneDomain domain, String entityType,
                                          String entityIdentifier) {
        requireEnum(entityType, "entityType", Set.of("DOMAIN_UNIT"));
        return requireDomainUnit(region, domain, entityIdentifier);
    }

    /** Turns {@code OwnerProperties} input into {@code OwnerPropertiesOutput}. */
    private ObjectNode ownerOutput(String region, DataZoneDomain domain, JsonNode owner) {
        String kind = unionMember(owner, "owner");
        ObjectNode out = JSON.objectNode();
        if ("user".equals(kind)) {
            String user = requiredText(owner.get("user"), "userIdentifier");
            out.putObject("user").put("userId", environments.userId(region, domain.getId(), user));
        } else if ("group".equals(kind)) {
            out.putObject("group").put("groupId", requiredText(owner.get("group"), "groupIdentifier"));
        } else {
            throw SmithyValidationErrors.validationException("Unknown owner type " + kind + ".");
        }
        return out;
    }

    private static boolean containsNode(ArrayNode array, JsonNode node) {
        for (JsonNode item : array) {
            if (item.equals(node)) {
                return true;
            }
        }
        return false;
    }

    // ──────────────────────────── Project memberships ────────────────────────────

    public synchronized void createProjectMembership(String region, String domainIdentifier, String projectIdentifier,
                                                     JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String projectId = domains.getProject(region, domain.getId(), projectIdentifier).getId();
        String designation = requireEnum(requiredText(request, "designation"), "designation", DESIGNATIONS);
        ObjectNode details = memberDetails(region, domain, requiredNode(request, "member"));
        ObjectNode membership = JSON.objectNode();
        membership.put("projectId", projectId);
        membership.set("memberDetails", details);
        membership.put("designation", designation);
        membership.put("createdAt", now());
        put(MEMBERSHIP, region, domain.getId(), membershipId(projectId, details), membership);
    }

    public synchronized void deleteProjectMembership(String region, String domainIdentifier, String projectIdentifier,
                                                     JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String projectId = domains.getProject(region, domain.getId(), projectIdentifier).getId();
        String id = membershipId(projectId, memberDetails(region, domain, requiredNode(request, "member")));
        require(MEMBERSHIP, region, domain.getId(), id, "Project membership");
        delete(MEMBERSHIP, region, domain.getId(), id);
    }

    public synchronized List<ObjectNode> listProjectMemberships(String region, String domainIdentifier,
                                                                String projectIdentifier, String sortBy,
                                                                String sortOrder) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String projectId = domains.getProject(region, domain.getId(), projectIdentifier).getId();
        requireEnum(sortBy, "sortBy", Set.of("NAME"));
        requireEnum(sortOrder, "sortOrder", Set.of("ASCENDING", "DESCENDING"));
        Comparator<ObjectNode> byName = Comparator.comparing(DataZoneGovernanceService::memberName);
        return list(MEMBERSHIP, region, domain.getId()).stream()
                .filter(m -> projectId.equals(m.path("projectId").asText()))
                .sorted("DESCENDING".equals(sortOrder) ? byName.reversed() : byName)
                .map(m -> pick(m, "memberDetails", "designation"))
                .toList();
    }

    public synchronized void deleteProjectResources(String region, String domainId, String projectId) {
        for (ObjectNode membership : list(MEMBERSHIP, region, domainId)) {
            if (projectId.equals(membership.path("projectId").asText())) {
                delete(MEMBERSHIP, region, domainId, membershipId(projectId, membership.get("memberDetails")));
            }
        }
    }

    /** Turns {@code Member} input into {@code MemberDetails}. */
    private ObjectNode memberDetails(String region, DataZoneDomain domain, JsonNode member) {
        String kind = unionMember(member, "member");
        ObjectNode out = JSON.objectNode();
        if ("userIdentifier".equals(kind)) {
            out.putObject("user").put("userId", environments.userId(region, domain.getId(),
                    member.get(kind).asText()));
        } else if ("groupIdentifier".equals(kind)) {
            out.putObject("group").put("groupId", member.get(kind).asText());
        } else {
            throw SmithyValidationErrors.validationException("Unknown member type " + kind + ".");
        }
        return out;
    }

    private static String memberName(ObjectNode membership) {
        JsonNode details = membership.path("memberDetails");
        return details.has("user") ? details.path("user").path("userId").asText()
                : details.path("group").path("groupId").asText();
    }

    private static String membershipId(String projectId, JsonNode details) {
        return projectId + "/" + (details.has("user") ? "user:" + details.path("user").path("userId").asText()
                : "group:" + details.path("group").path("groupId").asText());
    }

    // ──────────────────────────── Policy grants ────────────────────────────

    public synchronized String addPolicyGrant(String region, String domainIdentifier, String entityType,
                                              String entityIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(entityType, "entityType", TARGET_ENTITY_TYPES);
        String policyType = requireEnum(requiredText(request, "policyType"), "policyType", POLICY_TYPES);
        JsonNode principal = requiredNode(request, "principal");
        unionMember(principal, "principal");
        JsonNode detail = requiredNode(request, "detail");
        String detailKind = unionMember(detail, "detail");
        if (!detailKind.equals(detailMember(policyType))) {
            throw SmithyValidationErrors.validationException("The detail " + detailKind
                    + " does not match the policy type " + policyType + ".");
        }
        String grantId = grantId();
        ObjectNode grant = JSON.objectNode();
        grant.put("entityType", entityType);
        grant.put("entityIdentifier", entityIdentifier);
        grant.put("policyType", policyType);
        grant.set("principal", principal.deepCopy());
        grant.set("detail", detail.deepCopy());
        grant.put("createdAt", now());
        grant.put("createdBy", account());
        grant.put("grantId", grantId);
        put(POLICY_GRANT, region, domainId, grantId, grant);
        return grantId;
    }

    public synchronized void removePolicyGrant(String region, String domainIdentifier, String entityType,
                                               String entityIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(entityType, "entityType", TARGET_ENTITY_TYPES);
        String policyType = requireEnum(requiredText(request, "policyType"), "policyType", POLICY_TYPES);
        JsonNode principal = requiredNode(request, "principal");
        String grantId = text(request, "grantIdentifier");
        for (ObjectNode grant : grants(region, domainId, entityType, entityIdentifier, policyType)) {
            boolean matches = grantId != null ? grantId.equals(grant.path("grantId").asText())
                    : principal.equals(grant.get("principal"));
            if (matches) {
                delete(POLICY_GRANT, region, domainId, grant.path("grantId").asText());
            }
        }
    }

    public synchronized List<ObjectNode> listPolicyGrants(String region, String domainIdentifier, String entityType,
                                                          String entityIdentifier, String policyType) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(entityType, "entityType", TARGET_ENTITY_TYPES);
        if (policyType == null || policyType.isEmpty()) {
            throw required("policyType");
        }
        requireEnum(policyType, "policyType", POLICY_TYPES);
        return grants(region, domainId, entityType, entityIdentifier, policyType).stream()
                .map(g -> pick(g, "principal", "detail", "createdAt", "createdBy", "grantId"))
                .toList();
    }

    private List<ObjectNode> grants(String region, String domainId, String entityType, String entityIdentifier,
                                    String policyType) {
        return list(POLICY_GRANT, region, domainId).stream()
                .filter(g -> entityType.equals(g.path("entityType").asText())
                        && entityIdentifier.equals(g.path("entityIdentifier").asText())
                        && policyType.equals(g.path("policyType").asText()))
                .toList();
    }

    /** The {@code PolicyGrantDetail} member for a policy type, e.g. CREATE_DOMAIN_UNIT to createDomainUnit. */
    static String detailMember(String policyType) {
        StringBuilder out = new StringBuilder();
        boolean upper = false;
        for (char c : policyType.toLowerCase().toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                out.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return out.toString();
    }

    private static String grantId() {
        StringBuilder id = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            id.append(GRANT_ID_ALPHABET.charAt(RANDOM.nextInt(GRANT_ID_ALPHABET.length())));
        }
        return id.toString();
    }
}
