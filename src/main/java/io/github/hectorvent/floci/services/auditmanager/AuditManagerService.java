package io.github.hectorvent.floci.services.auditmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.auditmanager.model.Control;
import io.github.hectorvent.floci.services.auditmanager.model.Framework;
import io.github.hectorvent.floci.services.auditmanager.model.FrameworkControlSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Audit Manager custom assessment frameworks and custom controls.
 *
 * <p>Floci holds no AWS standard frameworks or controls. Listing the {@code Standard} or
 * {@code Core} type returns an empty list. A framework may name a control id that Floci does not hold;
 * the framework keeps the id and reports the control with only its id.
 */
@ApplicationScoped
public class AuditManagerService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(AuditManagerService.class);

    static final String SERVICE = "auditmanager";
    private static final String FRAMEWORK_PREFIX = "assessmentFramework/";
    private static final String CONTROL_PREFIX = "control/";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$");
    private static final Set<String> FRAMEWORK_TYPES = Set.of("Standard", "Custom");
    private static final Set<String> CONTROL_TYPES = Set.of("Standard", "Custom", "Core");
    private static final int MAX_PAGE = 1000;

    private final StorageBackend<String, Framework> frameworks;
    private final StorageBackend<String, Control> controls;
    private final RegionResolver regionResolver;
    private final Instance<AuditManagerAssessmentService> assessments;

    AuditManagerService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory, regionResolver, null);
    }

    /** Assessments resolve lazily, since {@link AuditManagerAssessmentService} depends on this service. */
    @Inject
    public AuditManagerService(StorageFactory storageFactory, RegionResolver regionResolver,
                               Instance<AuditManagerAssessmentService> assessments) {
        this.assessments = assessments;
        this.frameworks = storageFactory.create(SERVICE, "auditmanager-frameworks.json",
                new TypeReference<Map<String, Framework>>() {});
        this.controls = storageFactory.create(SERVICE, "auditmanager-controls.json",
                new TypeReference<Map<String, Control>>() {});
        this.regionResolver = regionResolver;
    }

    // ──────────────────────── Assessment frameworks ────────────────────────

    public Framework createAssessmentFramework(String name, String description, String complianceType,
                                               List<FrameworkControlSet> controlSets,
                                               Map<String, String> tags, String region) {
        validateFramework(name, description, controlSets, false);
        Instant now = Instant.now();
        Framework framework = new Framework();
        framework.setId(UUID.randomUUID().toString());
        framework.setArn(regionResolver.buildArn(SERVICE, region, FRAMEWORK_PREFIX + framework.getId()));
        framework.setName(name);
        framework.setDescription(description);
        framework.setComplianceType(complianceType);
        framework.setControlSets(assignControlSetIds(controlSets));
        framework.setCreatedAt(now);
        framework.setLastUpdatedAt(now);
        framework.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        frameworks.put(key(region, framework.getId()), framework);
        LOG.infov("Created Audit Manager framework {0} ({1})", name, framework.getId());
        return framework;
    }

    public Framework getAssessmentFramework(String frameworkId, String region) {
        requireUuid(frameworkId, "frameworkId");
        return frameworks.get(key(region, frameworkId))
                .orElseThrow(() -> notFound(frameworkId, "ASSESSMENT_FRAMEWORK"));
    }

    public Framework updateAssessmentFramework(String frameworkId, String name, String description,
                                               String complianceType, List<FrameworkControlSet> controlSets,
                                               String region) {
        Framework framework = getAssessmentFramework(frameworkId, region);
        validateFramework(name, description, controlSets, true);
        framework.setName(name);
        framework.setDescription(description);
        framework.setComplianceType(complianceType);
        framework.setControlSets(assignControlSetIds(controlSets));
        framework.setLastUpdatedAt(Instant.now());
        frameworks.put(key(region, frameworkId), framework);
        return framework;
    }

    public void deleteAssessmentFramework(String frameworkId, String region) {
        getAssessmentFramework(frameworkId, region);
        frameworks.delete(key(region, frameworkId));
    }

    public PaginatedResult<Framework> listAssessmentFrameworks(String frameworkType, Integer maxResults,
                                                               String nextToken, String region) {
        requireEnum(frameworkType, "frameworkType", FRAMEWORK_TYPES);
        List<Framework> matching = "Custom".equals(frameworkType)
                ? frameworks.scan(k -> k.startsWith(region + "::"))
                : List.of();
        return Pagination.paginate(matching, Framework::getId, maxResults, nextToken, MAX_PAGE,
                "ValidationException");
    }

    // ──────────────────────── Controls ────────────────────────

    public Control createControl(String name, String description, String testingInformation,
                                 String actionPlanTitle, String actionPlanInstructions,
                                 List<Map<String, Object>> mappingSources, Map<String, String> tags,
                                 String region) {
        validateControl(name, mappingSources);
        Instant now = Instant.now();
        Control control = new Control();
        control.setId(UUID.randomUUID().toString());
        control.setArn(regionResolver.buildArn(SERVICE, region, CONTROL_PREFIX + control.getId()));
        control.setName(name);
        control.setDescription(description);
        control.setTestingInformation(testingInformation);
        control.setActionPlanTitle(actionPlanTitle);
        control.setActionPlanInstructions(actionPlanInstructions);
        control.setControlMappingSources(assignSourceIds(mappingSources));
        control.setCreatedAt(now);
        control.setLastUpdatedAt(now);
        control.setTags(tags != null ? new LinkedHashMap<>(tags) : new LinkedHashMap<>());
        controls.put(key(region, control.getId()), control);
        return control;
    }

    public Control getControl(String controlId, String region) {
        requireUuid(controlId, "controlId");
        return controls.get(key(region, controlId)).orElseThrow(() -> notFound(controlId, "CONTROL"));
    }

    /** The stored control with this id, or empty when Floci does not hold it. */
    public Optional<Control> findControl(String controlId, String region) {
        return controls.get(key(region, controlId));
    }

    public Control updateControl(String controlId, String name, String description, String testingInformation,
                                 String actionPlanTitle, String actionPlanInstructions,
                                 List<Map<String, Object>> mappingSources, String region) {
        Control control = getControl(controlId, region);
        validateControl(name, mappingSources);
        control.setName(name);
        control.setDescription(description);
        control.setTestingInformation(testingInformation);
        control.setActionPlanTitle(actionPlanTitle);
        control.setActionPlanInstructions(actionPlanInstructions);
        control.setControlMappingSources(assignSourceIds(mappingSources));
        control.setLastUpdatedAt(Instant.now());
        controls.put(key(region, controlId), control);
        return control;
    }

    public void deleteControl(String controlId, String region) {
        getControl(controlId, region);
        controls.delete(key(region, controlId));
    }

    public PaginatedResult<Control> listControls(String controlType, Integer maxResults, String nextToken,
                                                 String region) {
        requireEnum(controlType, "controlType", CONTROL_TYPES);
        List<Control> matching = "Custom".equals(controlType)
                ? controls.scan(k -> k.startsWith(region + "::"))
                : List.of();
        return Pagination.paginate(matching, Control::getId, maxResults, nextToken, MAX_PAGE,
                "ValidationException");
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return SERVICE;
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
        AuditManagerAssessmentService owner = assessmentOwner(arn, region);
        if (owner != null) {
            return owner.listTags(arn, AwsArnUtils.regionOrDefault(arn, region));
        }
        return resolveTagged(arn, region).tags();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        AuditManagerAssessmentService owner = assessmentOwner(arn, region);
        if (owner != null) {
            owner.setTags(arn, tags, null, AwsArnUtils.regionOrDefault(arn, region));
            return;
        }
        Tagged tagged = resolveTagged(arn, region);
        tagged.tags().putAll(tags);
        tagged.save().run();
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        AuditManagerAssessmentService owner = assessmentOwner(arn, region);
        if (owner != null) {
            owner.setTags(arn, null, tagKeys, AwsArnUtils.regionOrDefault(arn, region));
            return;
        }
        Tagged tagged = resolveTagged(arn, region);
        if (tagKeys != null) {
            tagKeys.forEach(tagged.tags()::remove);
        }
        tagged.save().run();
    }

    private AuditManagerAssessmentService assessmentOwner(String arn, String region) {
        if (assessments == null || assessments.isUnsatisfied()) {
            return null;
        }
        AuditManagerAssessmentService service = assessments.get();
        return service.ownsAssessment(arn, AwsArnUtils.regionOrDefault(arn, region)) ? service : null;
    }

    private record Tagged(Map<String, String> tags, Runnable save) {}

    private Tagged resolveTagged(String arnText, String region) {
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(arnText);
        } catch (IllegalArgumentException e) {
            throw SmithyValidationErrors.validationException(
                    "Value '" + arnText + "' at 'resourceArn' failed to satisfy constraint: "
                            + "Member must satisfy regular expression pattern: ^arn:.*:auditmanager:.*");
        }
        String arnRegion = arn.region().isEmpty() ? region : arn.region();
        String resource = arn.resource();
        if (resource.startsWith(FRAMEWORK_PREFIX)) {
            String id = resource.substring(FRAMEWORK_PREFIX.length());
            Framework framework = frameworks.get(key(arnRegion, id))
                    .orElseThrow(() -> notFound(arnText, "ASSESSMENT_FRAMEWORK"));
            if (framework.getTags() == null) {
                framework.setTags(new LinkedHashMap<>());
            }
            return new Tagged(framework.getTags(), () -> frameworks.put(key(arnRegion, id), framework));
        }
        if (resource.startsWith(CONTROL_PREFIX)) {
            String id = resource.substring(CONTROL_PREFIX.length());
            Control control = controls.get(key(arnRegion, id))
                    .orElseThrow(() -> notFound(arnText, "CONTROL"));
            if (control.getTags() == null) {
                control.setTags(new LinkedHashMap<>());
            }
            return new Tagged(control.getTags(), () -> controls.put(key(arnRegion, id), control));
        }
        throw notFound(arnText, "RESOURCE");
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private void validateFramework(String name, String description, List<FrameworkControlSet> controlSets,
                                   boolean update) {
        List<String> violations = new ArrayList<>();
        if (name == null || name.isEmpty() || name.length() > 300 || name.contains("\\")) {
            violations.add("Value '" + name + "' at 'name' failed to satisfy constraint: "
                    + "Member must have length between 1 and 300 and contain no backslash");
        }
        if (description != null && (description.isEmpty() || description.length() > 1000)) {
            violations.add("Value at 'description' failed to satisfy constraint: "
                    + "Member must have length between 1 and 1000");
        }
        if (controlSets == null || controlSets.isEmpty()) {
            violations.add("Value at 'controlSets' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1");
        } else {
            for (int i = 0; i < controlSets.size(); i++) {
                FrameworkControlSet set = controlSets.get(i);
                String setName = set.getName();
                if (setName == null || setName.isEmpty() || setName.length() > 300
                        || setName.contains("\\") || setName.contains("_")) {
                    violations.add("Value '" + setName + "' at 'controlSets." + (i + 1)
                            + ".member.name' failed to satisfy constraint: Member must have length between "
                            + "1 and 300 and contain no backslash or underscore");
                }
                if (update && set.getControlIds() == null) {
                    violations.add("Value null at 'controlSets." + (i + 1)
                            + ".member.controls' failed to satisfy constraint: Member must not be null");
                }
                if (set.getControlIds() != null) {
                    for (String controlId : set.getControlIds()) {
                        if (controlId == null || !UUID_PATTERN.matcher(controlId).matches()) {
                            violations.add("Value '" + controlId + "' at 'controlSets." + (i + 1)
                                    + ".member.controls.member.id' failed to satisfy constraint: "
                                    + "Member must satisfy regular expression pattern: " + UUID_PATTERN.pattern());
                        }
                    }
                }
            }
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    private void validateControl(String name, List<Map<String, Object>> mappingSources) {
        List<String> violations = new ArrayList<>();
        if (name == null || name.isEmpty() || name.length() > 300 || name.contains("\\")) {
            violations.add("Value '" + name + "' at 'name' failed to satisfy constraint: "
                    + "Member must have length between 1 and 300 and contain no backslash");
        }
        if (mappingSources == null || mappingSources.isEmpty()) {
            violations.add("Value at 'controlMappingSources' failed to satisfy constraint: "
                    + "Member must have length greater than or equal to 1");
        }
        if (!violations.isEmpty()) {
            throw SmithyValidationErrors.validationException(violations);
        }
    }

    private List<FrameworkControlSet> assignControlSetIds(List<FrameworkControlSet> controlSets) {
        List<FrameworkControlSet> result = new ArrayList<>();
        for (FrameworkControlSet set : controlSets) {
            String id = set.getId() != null && !set.getId().isBlank() ? set.getId() : UUID.randomUUID().toString();
            List<String> ids = set.getControlIds() != null ? List.copyOf(set.getControlIds()) : List.of();
            result.add(new FrameworkControlSet(id, set.getName(), new ArrayList<>(ids)));
        }
        return result;
    }

    private List<Map<String, Object>> assignSourceIds(List<Map<String, Object>> mappingSources) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> source : mappingSources) {
            Map<String, Object> copy = new LinkedHashMap<>(source);
            if (!(copy.get("sourceId") instanceof String id) || id.isBlank()) {
                copy.put("sourceId", UUID.randomUUID().toString());
            }
            result.add(copy);
        }
        return result;
    }

    private void requireUuid(String value, String field) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw SmithyValidationErrors.validationException("Value '" + value + "' at '" + field
                    + "' failed to satisfy constraint: Member must satisfy regular expression pattern: "
                    + UUID_PATTERN.pattern());
        }
    }

    private void requireEnum(String value, String field, Set<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw SmithyValidationErrors.validationException("Value '" + value + "' at '" + field
                    + "' failed to satisfy constraint: Member must satisfy enum value set: "
                    + allowed.stream().sorted().toList());
        }
    }

    private AwsException notFound(String resourceId, String resourceType) {
        return new AwsException("ResourceNotFoundException",
                "The resource " + resourceId + " of type " + resourceType + " was not found.", 404);
    }

    private String key(String region, String id) {
        return region + "::" + id;
    }
}
