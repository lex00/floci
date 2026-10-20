package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import io.github.hectorvent.floci.services.datazone.model.DataZoneGlossary;
import io.github.hectorvent.floci.services.datazone.model.DataZoneGlossaryTerm;
import io.github.hectorvent.floci.services.datazone.model.DataZoneProject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Amazon DataZone business glossaries and the domains and projects that hold them.
 *
 * <p>A domain is {@code AVAILABLE} as soon as CreateDomain returns, so SDK and Terraform waiters
 * finish on their first poll. DeleteDomain answers {@code DELETING} and removes the domain with
 * everything inside it.
 */
@ApplicationScoped
public class DataZoneService implements TagHandler, Resettable {

    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ID_LENGTH = 14;
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern DOMAIN_ID = Pattern.compile("dzd[-_][a-zA-Z0-9_-]{1,36}");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-zA-Z0-9_-]{1,36}");
    private static final Pattern ROLE_ARN =
            Pattern.compile("arn:" + AwsArnUtils.PARTITION_REGEX
                    + ":iam::\\d{12}:role(/[a-zA-Z0-9+=,.@_-]+)*/[a-zA-Z0-9+=,.@_-]+");
    private static final Pattern PROJECT_NAME = Pattern.compile("[\\w -]+");
    private static final Set<String> DOMAIN_STATUSES = Set.of(
            "CREATING", "AVAILABLE", "CREATION_FAILED", "DELETING", "DELETED", "DELETION_FAILED");
    private static final Set<String> ENABLEMENT = Set.of("ENABLED", "DISABLED");
    private static final Set<String> DOMAIN_VERSIONS = Set.of("V1", "V2");

    private final StorageBackend<String, DataZoneDomain> domains;
    private final StorageBackend<String, DataZoneProject> projects;
    private final StorageBackend<String, DataZoneGlossary> glossaries;
    private final StorageBackend<String, DataZoneGlossaryTerm> terms;
    private final RegionResolver regionResolver;

    @Inject
    public DataZoneService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-domains.json",
                        new TypeReference<Map<String, DataZoneDomain>>() {}),
                storageFactory.create("datazone", "datazone-projects.json",
                        new TypeReference<Map<String, DataZoneProject>>() {}),
                storageFactory.create("datazone", "datazone-glossaries.json",
                        new TypeReference<Map<String, DataZoneGlossary>>() {}),
                storageFactory.create("datazone", "datazone-glossary-terms.json",
                        new TypeReference<Map<String, DataZoneGlossaryTerm>>() {}),
                regionResolver);
    }

    DataZoneService(StorageBackend<String, DataZoneDomain> domains,
                    StorageBackend<String, DataZoneProject> projects,
                    StorageBackend<String, DataZoneGlossary> glossaries,
                    StorageBackend<String, DataZoneGlossaryTerm> terms,
                    RegionResolver regionResolver) {
        this.domains = domains;
        this.projects = projects;
        this.glossaries = glossaries;
        this.terms = terms;
        this.regionResolver = regionResolver;
    }

    // Domains

    public DataZoneDomain createDomain(String region, String name, String description, String domainExecutionRole,
                                       String serviceRole, String kmsKeyIdentifier, String domainVersion,
                                       JsonNode singleSignOn, Map<String, String> tags) {
        require(name, "name");
        if (domainExecutionRole != null) {
            validateRole(domainExecutionRole, "domainExecutionRole");
        }
        if (serviceRole != null) {
            validateRole(serviceRole, "serviceRole");
        }
        if (domainVersion != null && !DOMAIN_VERSIONS.contains(domainVersion)) {
            throw enumViolation(domainVersion, "domainVersion", DOMAIN_VERSIONS);
        }
        boolean nameTaken = listDomains(region, null).stream().anyMatch(d -> d.getName().equals(name));
        if (nameTaken) {
            throw conflict("A domain with name " + name + " already exists.");
        }
        String id = "dzd_" + randomId();
        String account = regionResolver.getAccountId();
        long now = Instant.now().toEpochMilli();
        DataZoneDomain domain = new DataZoneDomain();
        domain.setId(id);
        domain.setRegion(region);
        domain.setArn(AwsArnUtils.Arn.of("datazone", region, account, "domain/" + id).toString());
        domain.setName(name);
        domain.setDescription(description);
        domain.setDomainExecutionRole(domainExecutionRole);
        domain.setServiceRole(serviceRole);
        domain.setKmsKeyIdentifier(kmsKeyIdentifier);
        domain.setDomainVersion(domainVersion != null ? domainVersion : "V1");
        domain.setSingleSignOn(singleSignOn);
        domain.setStatus("AVAILABLE");
        domain.setPortalUrl("https://" + id + ".datazone." + region + ".on.aws");
        domain.setRootDomainUnitId(randomId());
        domain.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        domain.setCreatedAt(now);
        domain.setUpdatedAt(now);
        domain.setCreatedBy(account);
        domain.setUpdatedBy(account);
        domains.put(domainKey(region, id), domain);
        return domain;
    }

    public DataZoneDomain getDomain(String region, String identifier) {
        validateDomainId(identifier);
        return domains.get(domainKey(region, identifier))
                .orElseThrow(() -> notFound("Domain " + identifier + " not found."));
    }

    public List<DataZoneDomain> listDomains(String region, String status) {
        if (status != null && !DOMAIN_STATUSES.contains(status)) {
            throw enumViolation(status, "status", DOMAIN_STATUSES);
        }
        return domains.scan(k -> k.startsWith(region + ":")).stream()
                .filter(d -> status == null || status.equals(d.getStatus()))
                .sorted(Comparator.comparingLong(DataZoneDomain::getCreatedAt).thenComparing(DataZoneDomain::getId))
                .toList();
    }

    /** Removes the domain and everything in it; the response reports the domain as {@code DELETING}. */
    public void deleteDomain(String region, String identifier) {
        DataZoneDomain domain = getDomain(region, identifier);
        String childPrefix = childPrefix(region, domain.getId());
        terms.keys().stream().filter(k -> k.startsWith(childPrefix)).toList().forEach(terms::delete);
        glossaries.keys().stream().filter(k -> k.startsWith(childPrefix)).toList().forEach(glossaries::delete);
        projects.keys().stream().filter(k -> k.startsWith(childPrefix)).toList().forEach(projects::delete);
        domains.delete(domainKey(region, domain.getId()));
    }

    public DataZoneDomain updateDomain(String region, String identifier, String name, String description,
                                       String domainExecutionRole, String serviceRole, JsonNode singleSignOn) {
        DataZoneDomain domain = getDomain(region, identifier);
        if (domainExecutionRole != null) {
            validateRole(domainExecutionRole, "domainExecutionRole");
            domain.setDomainExecutionRole(domainExecutionRole);
        }
        if (serviceRole != null) {
            validateRole(serviceRole, "serviceRole");
            domain.setServiceRole(serviceRole);
        }
        if (name != null && !name.equals(domain.getName())) {
            boolean nameTaken = listDomains(region, null).stream().anyMatch(d -> d.getName().equals(name));
            if (nameTaken) {
                throw conflict("A domain with name " + name + " already exists.");
            }
            domain.setName(name);
        }
        if (description != null) {
            domain.setDescription(description);
        }
        if (singleSignOn != null) {
            domain.setSingleSignOn(singleSignOn);
        }
        domain.setUpdatedAt(Instant.now().toEpochMilli());
        domain.setUpdatedBy(regionResolver.getAccountId());
        domains.put(domainKey(region, domain.getId()), domain);
        return domain;
    }

    // Projects

    public DataZoneProject createProject(String region, String domainIdentifier, String name, String description,
                                         String domainUnitId, List<String> glossaryTerms) {
        DataZoneDomain domain = getDomain(region, domainIdentifier);
        require(name, "name");
        if (!PROJECT_NAME.matcher(name).matches() || name.length() > 64) {
            throw SmithyValidationErrors.validationException("Value '" + name
                    + "' at 'name' failed to satisfy constraint: Member must satisfy regular expression pattern: [\\w -]+");
        }
        boolean nameTaken = listProjects(region, domain.getId(), null).stream()
                .anyMatch(p -> p.getName().equals(name));
        if (nameTaken) {
            throw conflict("A project with name " + name + " already exists in domain " + domain.getId() + ".");
        }
        String account = regionResolver.getAccountId();
        long now = Instant.now().toEpochMilli();
        DataZoneProject project = new DataZoneProject();
        project.setId(randomId());
        project.setDomainId(domain.getId());
        project.setRegion(region);
        project.setName(name);
        project.setDescription(description);
        project.setDomainUnitId(domainUnitId != null ? domainUnitId : domain.getRootDomainUnitId());
        project.setGlossaryTerms(glossaryTerms != null ? List.copyOf(glossaryTerms) : List.of());
        project.setCreatedAt(now);
        project.setUpdatedAt(now);
        project.setCreatedBy(account);
        project.setUpdatedBy(account);
        projects.put(childKey(region, domain.getId(), project.getId()), project);
        return project;
    }

    public DataZoneProject getProject(String region, String domainIdentifier, String identifier) {
        DataZoneDomain domain = getDomain(region, domainIdentifier);
        validateResourceId(identifier, "identifier");
        return projects.get(childKey(region, domain.getId(), identifier))
                .orElseThrow(() -> notFound("Project " + identifier + " not found."));
    }

    public List<DataZoneProject> listProjects(String region, String domainIdentifier, String name) {
        DataZoneDomain domain = getDomain(region, domainIdentifier);
        return projects.scan(k -> k.startsWith(childPrefix(region, domain.getId()))).stream()
                .filter(p -> name == null || p.getName().contains(name))
                .sorted(Comparator.comparingLong(DataZoneProject::getCreatedAt).thenComparing(DataZoneProject::getId))
                .toList();
    }

    public DataZoneProject updateProject(String region, String domainIdentifier, String identifier, String name,
                                         String description, String domainUnitId, List<String> glossaryTerms) {
        DataZoneProject project = getProject(region, domainIdentifier, identifier);
        if (name != null && !name.equals(project.getName())) {
            if (!PROJECT_NAME.matcher(name).matches() || name.length() > 64) {
                throw SmithyValidationErrors.validationException("Value '" + name
                        + "' at 'name' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                        + "[\\w -]+");
            }
            boolean nameTaken = listProjects(region, project.getDomainId(), null).stream()
                    .anyMatch(p -> p.getName().equals(name));
            if (nameTaken) {
                throw conflict("A project with name " + name + " already exists in domain "
                        + project.getDomainId() + ".");
            }
            project.setName(name);
        }
        if (description != null) {
            project.setDescription(description);
        }
        if (domainUnitId != null) {
            project.setDomainUnitId(domainUnitId);
        }
        if (glossaryTerms != null) {
            project.setGlossaryTerms(List.copyOf(glossaryTerms));
        }
        project.setUpdatedAt(Instant.now().toEpochMilli());
        project.setUpdatedBy(regionResolver.getAccountId());
        projects.put(childKey(region, project.getDomainId(), project.getId()), project);
        return project;
    }

    /** Refuses a project that still owns glossaries unless {@code skipDeletionCheck} is set. */
    public void deleteProject(String region, String domainIdentifier, String identifier, boolean skipDeletionCheck) {
        DataZoneProject project = getProject(region, domainIdentifier, identifier);
        String childPrefix = childPrefix(region, project.getDomainId());
        List<DataZoneGlossary> owned = glossaries.scan(k -> k.startsWith(childPrefix)).stream()
                .filter(g -> project.getId().equals(g.getOwningProjectId()))
                .toList();
        if (!owned.isEmpty() && !skipDeletionCheck) {
            throw conflict("Project " + project.getId() + " still owns glossaries.");
        }
        for (DataZoneGlossary glossary : owned) {
            terms.scan(k -> k.startsWith(childPrefix)).stream()
                    .filter(t -> glossary.getId().equals(t.getGlossaryId()))
                    .forEach(t -> terms.delete(childKey(region, t.getDomainId(), t.getId())));
            glossaries.delete(childKey(region, glossary.getDomainId(), glossary.getId()));
        }
        projects.delete(childKey(region, project.getDomainId(), project.getId()));
    }

    // Glossaries

    public DataZoneGlossary createGlossary(String region, String domainIdentifier, String name,
                                           String owningProjectIdentifier, String description, String status,
                                           List<String> usageRestrictions) {
        DataZoneDomain domain = getDomain(region, domainIdentifier);
        require(name, "name");
        require(owningProjectIdentifier, "owningProjectIdentifier");
        validateEnablement(status);
        DataZoneProject project = getProject(region, domain.getId(), owningProjectIdentifier);
        if (findGlossaryByName(region, domain.getId(), name) != null) {
            throw conflict("A glossary with name " + name + " already exists in domain " + domain.getId() + ".");
        }
        String account = regionResolver.getAccountId();
        long now = Instant.now().toEpochMilli();
        DataZoneGlossary glossary = new DataZoneGlossary();
        glossary.setId(randomId());
        glossary.setDomainId(domain.getId());
        glossary.setRegion(region);
        glossary.setName(name);
        glossary.setOwningProjectId(project.getId());
        glossary.setDescription(description);
        glossary.setStatus(status != null ? status : "ENABLED");
        glossary.setUsageRestrictions(usageRestrictions);
        glossary.setCreatedAt(now);
        glossary.setUpdatedAt(now);
        glossary.setCreatedBy(account);
        glossary.setUpdatedBy(account);
        glossaries.put(childKey(region, domain.getId(), glossary.getId()), glossary);
        return glossary;
    }

    public DataZoneGlossary getGlossary(String region, String domainIdentifier, String identifier) {
        DataZoneDomain domain = getDomain(region, domainIdentifier);
        validateResourceId(identifier, "identifier");
        return glossaries.get(childKey(region, domain.getId(), identifier))
                .orElseThrow(() -> notFound("Glossary " + identifier + " not found."));
    }

    public DataZoneGlossary updateGlossary(String region, String domainIdentifier, String identifier,
                                           String name, String description, String status) {
        DataZoneGlossary glossary = getGlossary(region, domainIdentifier, identifier);
        validateEnablement(status);
        if (name != null && !name.equals(glossary.getName())) {
            if (findGlossaryByName(region, glossary.getDomainId(), name) != null) {
                throw conflict("A glossary with name " + name + " already exists in domain "
                        + glossary.getDomainId() + ".");
            }
            glossary.setName(name);
        }
        if (description != null) {
            glossary.setDescription(description);
        }
        if (status != null) {
            glossary.setStatus(status);
        }
        touch(glossary);
        glossaries.put(childKey(region, glossary.getDomainId(), glossary.getId()), glossary);
        return glossary;
    }

    /** A glossary must be {@code DISABLED} and hold no terms before it can be deleted. */
    public void deleteGlossary(String region, String domainIdentifier, String identifier) {
        DataZoneGlossary glossary = getGlossary(region, domainIdentifier, identifier);
        if (!"DISABLED".equals(glossary.getStatus())) {
            throw conflict("Glossary " + glossary.getId() + " must be DISABLED before it can be deleted.");
        }
        boolean hasTerms = terms.scan(k -> k.startsWith(childPrefix(region, glossary.getDomainId()))).stream()
                .anyMatch(t -> glossary.getId().equals(t.getGlossaryId()));
        if (hasTerms) {
            throw conflict("Glossary " + glossary.getId() + " still contains glossary terms.");
        }
        glossaries.delete(childKey(region, glossary.getDomainId(), glossary.getId()));
    }

    // Glossary terms

    public DataZoneGlossaryTerm createGlossaryTerm(String region, String domainIdentifier, String glossaryIdentifier,
                                                   String name, String status, String shortDescription,
                                                   String longDescription, JsonNode termRelations) {
        DataZoneDomain domain = getDomain(region, domainIdentifier);
        require(glossaryIdentifier, "glossaryIdentifier");
        require(name, "name");
        validateEnablement(status);
        DataZoneGlossary glossary = getGlossary(region, domain.getId(), glossaryIdentifier);
        if (findTermByName(region, domain.getId(), glossary.getId(), name) != null) {
            throw conflict("A glossary term with name " + name + " already exists in glossary "
                    + glossary.getId() + ".");
        }
        String account = regionResolver.getAccountId();
        long now = Instant.now().toEpochMilli();
        DataZoneGlossaryTerm term = new DataZoneGlossaryTerm();
        term.setId(randomId());
        term.setDomainId(domain.getId());
        term.setGlossaryId(glossary.getId());
        term.setRegion(region);
        term.setName(name);
        term.setStatus(status != null ? status : "ENABLED");
        term.setShortDescription(shortDescription);
        term.setLongDescription(longDescription);
        term.setTermRelations(termRelations);
        term.setCreatedAt(now);
        term.setUpdatedAt(now);
        term.setCreatedBy(account);
        term.setUpdatedBy(account);
        terms.put(childKey(region, domain.getId(), term.getId()), term);
        return term;
    }

    public DataZoneGlossaryTerm getGlossaryTerm(String region, String domainIdentifier, String identifier) {
        DataZoneDomain domain = getDomain(region, domainIdentifier);
        validateResourceId(identifier, "identifier");
        return terms.get(childKey(region, domain.getId(), identifier))
                .orElseThrow(() -> notFound("Glossary term " + identifier + " not found."));
    }

    public DataZoneGlossaryTerm updateGlossaryTerm(String region, String domainIdentifier, String identifier,
                                                   String glossaryIdentifier, String name, String status,
                                                   String shortDescription, String longDescription,
                                                   JsonNode termRelations) {
        DataZoneGlossaryTerm term = getGlossaryTerm(region, domainIdentifier, identifier);
        validateEnablement(status);
        if (glossaryIdentifier != null && !glossaryIdentifier.equals(term.getGlossaryId())) {
            term.setGlossaryId(getGlossary(region, term.getDomainId(), glossaryIdentifier).getId());
        }
        if (name != null && !name.equals(term.getName())) {
            DataZoneGlossaryTerm existing = findTermByName(region, term.getDomainId(), term.getGlossaryId(), name);
            if (existing != null && !existing.getId().equals(term.getId())) {
                throw conflict("A glossary term with name " + name + " already exists in glossary "
                        + term.getGlossaryId() + ".");
            }
            term.setName(name);
        }
        if (status != null) {
            term.setStatus(status);
        }
        if (shortDescription != null) {
            term.setShortDescription(shortDescription);
        }
        if (longDescription != null) {
            term.setLongDescription(longDescription);
        }
        if (termRelations != null) {
            term.setTermRelations(termRelations);
        }
        touch(term);
        terms.put(childKey(region, term.getDomainId(), term.getId()), term);
        return term;
    }

    /** A glossary term must be {@code DISABLED} before it can be deleted. */
    public void deleteGlossaryTerm(String region, String domainIdentifier, String identifier) {
        DataZoneGlossaryTerm term = getGlossaryTerm(region, domainIdentifier, identifier);
        if (!"DISABLED".equals(term.getStatus())) {
            throw conflict("Glossary term " + term.getId() + " must be DISABLED before it can be deleted.");
        }
        terms.delete(childKey(region, term.getDomainId(), term.getId()));
    }

    // Tagging, served on the shared /tags/{resourceArn} path

    @Override
    public String serviceKey() {
        return "datazone";
    }

    @Override
    public int tagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public int untagResourceSuccessStatus() {
        return 200;
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(domainForArn(arn).getTags());
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        DataZoneDomain domain = domainForArn(arn);
        domain.getTags().putAll(tags);
        domains.put(domainKey(domain.getRegion(), domain.getId()), domain);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        DataZoneDomain domain = domainForArn(arn);
        if (tagKeys != null) {
            tagKeys.forEach(domain.getTags()::remove);
        }
        domains.put(domainKey(domain.getRegion(), domain.getId()), domain);
    }

    @Override
    public void clear() {
        terms.clear();
        glossaries.clear();
        projects.clear();
        domains.clear();
    }

    private DataZoneDomain domainForArn(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw notFound("Resource " + arn + " not found.");
        }
        if (parsed.resource() == null || !parsed.resource().startsWith("domain/")) {
            throw notFound("Resource " + arn + " not found.");
        }
        String id = parsed.resource().substring("domain/".length());
        return domains.get(domainKey(parsed.region(), id))
                .filter(d -> arn.equals(d.getArn()))
                .orElseThrow(() -> notFound("Resource " + arn + " not found."));
    }

    private DataZoneGlossary findGlossaryByName(String region, String domainId, String name) {
        return glossaries.scan(k -> k.startsWith(childPrefix(region, domainId))).stream()
                .filter(g -> g.getName().equals(name))
                .findFirst().orElse(null);
    }

    private DataZoneGlossaryTerm findTermByName(String region, String domainId, String glossaryId, String name) {
        return terms.scan(k -> k.startsWith(childPrefix(region, domainId))).stream()
                .filter(t -> glossaryId.equals(t.getGlossaryId()) && t.getName().equals(name))
                .findFirst().orElse(null);
    }

    private void touch(DataZoneGlossary glossary) {
        glossary.setUpdatedAt(Instant.now().toEpochMilli());
        glossary.setUpdatedBy(regionResolver.getAccountId());
    }

    private void touch(DataZoneGlossaryTerm term) {
        term.setUpdatedAt(Instant.now().toEpochMilli());
        term.setUpdatedBy(regionResolver.getAccountId());
    }

    private static String domainKey(String region, String id) {
        return region + ":" + id;
    }

    private static String childPrefix(String region, String domainId) {
        return region + ":" + domainId + ":";
    }

    private static String childKey(String region, String domainId, String id) {
        return childPrefix(region, domainId) + id;
    }

    static String randomId() {
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static void require(String value, String member) {
        if (value == null || value.isEmpty()) {
            throw SmithyValidationErrors.validationException(
                    "Value null at '" + member + "' failed to satisfy constraint: Member must not be null");
        }
    }

    private static void validateRole(String arn, String member) {
        if (!ROLE_ARN.matcher(arn).matches()) {
            throw SmithyValidationErrors.validationException("Value '" + arn + "' at '" + member
                    + "' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + ROLE_ARN.pattern());
        }
    }

    private static void validateDomainId(String identifier) {
        if (identifier == null || !DOMAIN_ID.matcher(identifier).matches()) {
            throw SmithyValidationErrors.validationException("Value '" + identifier
                    + "' at 'identifier' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + DOMAIN_ID.pattern());
        }
    }

    private static void validateResourceId(String identifier, String member) {
        if (identifier == null || !RESOURCE_ID.matcher(identifier).matches()) {
            throw SmithyValidationErrors.validationException("Value '" + identifier + "' at '" + member
                    + "' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + RESOURCE_ID.pattern());
        }
    }

    private static void validateEnablement(String status) {
        if (status != null && !ENABLEMENT.contains(status)) {
            throw enumViolation(status, "status", ENABLEMENT);
        }
    }

    private static AwsException enumViolation(String value, String member, Set<String> allowed) {
        List<String> sorted = new ArrayList<>(allowed);
        sorted.sort(null);
        return SmithyValidationErrors.validationException("Value '" + value + "' at '" + member
                + "' failed to satisfy constraint: Member must satisfy enum value set: " + sorted);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
