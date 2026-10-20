package io.github.hectorvent.floci.services.auditmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ServicePrincipals;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.auditmanager.model.Control;
import io.github.hectorvent.floci.services.auditmanager.model.Framework;
import io.github.hectorvent.floci.services.auditmanager.model.FrameworkControlSet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Audit Manager assessments built from a custom framework, with their evidence folders, manual
 * evidence, delegations and assessment reports, plus the account registration. No evidence is
 * collected automatically: every folder holds the manual evidence imported into it. A report is
 * {@code COMPLETE} as soon as it is created. Records are keyed by type, region and id.
 */
@ApplicationScoped
public class AuditManagerAssessmentService {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String SERVICE = "auditmanager";
    private static final String ASSESSMENT = "assessment";
    private static final String FOLDER = "folder";
    private static final String EVIDENCE = "evidence";
    private static final String REPORT = "report";
    private static final String ACCOUNT = "account";
    private static final Set<String> ROLE_TYPES = Set.of("PROCESS_OWNER", "RESOURCE_OWNER");

    private final StorageBackend<String, ObjectNode> records;
    private final AuditManagerService frameworks;
    private final RegionResolver regionResolver;

    @Inject
    public AuditManagerAssessmentService(StorageFactory storageFactory, AuditManagerService frameworks,
                                         RegionResolver regionResolver) {
        this(storageFactory.create(SERVICE, "auditmanager-assessments.json",
                new TypeReference<Map<String, ObjectNode>>() {}), frameworks, regionResolver);
    }

    AuditManagerAssessmentService(StorageBackend<String, ObjectNode> records, AuditManagerService frameworks,
                                  RegionResolver regionResolver) {
        this.records = records;
        this.frameworks = frameworks;
        this.regionResolver = regionResolver;
    }

    // ──────────────────────────── Assessments ────────────────────────────

    public synchronized ObjectNode createAssessment(JsonNode request, String region) {
        String name = requiredText(request, "name");
        if (!request.path("assessmentReportsDestination").isObject()) {
            throw required("assessmentReportsDestination");
        }
        if (!request.path("scope").isObject()) {
            throw required("scope");
        }
        JsonNode roles = request.path("roles");
        if (!roles.isArray()) {
            throw required("roles");
        }
        validateRoles(roles);
        Framework framework = frameworks.getAssessmentFramework(requiredText(request, "frameworkId"), region);
        String id = UUID.randomUUID().toString();
        double now = now();
        String account = regionResolver.getAccountId();

        ObjectNode assessment = JSON.objectNode();
        assessment.put("arn", regionResolver.buildArn(SERVICE, region, "assessment/" + id));
        assessment.putObject("awsAccount").put("id", account);
        ObjectNode metadata = assessment.putObject("metadata");
        metadata.put("name", name);
        metadata.put("id", id);
        if (request.hasNonNull("description")) {
            metadata.put("description", request.get("description").asText());
        }
        if (framework.getComplianceType() != null) {
            metadata.put("complianceType", framework.getComplianceType());
        }
        metadata.put("status", "ACTIVE");
        metadata.set("assessmentReportsDestination", request.get("assessmentReportsDestination").deepCopy());
        metadata.set("scope", request.get("scope").deepCopy());
        metadata.set("roles", roles.deepCopy());
        metadata.putArray("delegations");
        metadata.put("creationTime", now);
        metadata.put("lastUpdated", now);

        ObjectNode assessmentFramework = assessment.putObject("framework");
        assessmentFramework.put("id", framework.getId());
        assessmentFramework.put("arn", framework.getArn());
        ObjectNode frameworkMetadata = assessmentFramework.putObject("metadata");
        frameworkMetadata.put("name", framework.getName());
        if (framework.getDescription() != null) {
            frameworkMetadata.put("description", framework.getDescription());
        }
        if (framework.getComplianceType() != null) {
            frameworkMetadata.put("complianceType", framework.getComplianceType());
        }
        ArrayNode controlSets = assessmentFramework.putArray("controlSets");
        for (FrameworkControlSet set : framework.getControlSets()) {
            ObjectNode controlSet = controlSets.addObject();
            controlSet.put("id", set.getName());
            controlSet.put("description", set.getName());
            controlSet.put("status", "ACTIVE");
            controlSet.set("roles", roles.deepCopy());
            ArrayNode controls = controlSet.putArray("controls");
            for (String controlId : set.getControlIds()) {
                Control control = frameworks.findControl(controlId, region).orElse(null);
                ObjectNode assessmentControl = controls.addObject();
                assessmentControl.put("id", controlId);
                if (control != null) {
                    assessmentControl.put("name", control.getName());
                    if (control.getDescription() != null) {
                        assessmentControl.put("description", control.getDescription());
                    }
                }
                assessmentControl.put("status", "UNDER_REVIEW");
                assessmentControl.put("response", "MANUAL");
                assessmentControl.putArray("comments");
                assessmentControl.putArray("evidenceSources").add("Manual");
                assessmentControl.put("evidenceCount", 0);
                assessmentControl.put("assessmentReportEvidenceCount", 0);
            }
            controlSet.putArray("delegations");
            controlSet.put("systemEvidenceCount", 0);
            controlSet.put("manualEvidenceCount", 0);
        }
        ObjectNode tags = assessment.putObject("tags");
        if (request.get("tags") instanceof ObjectNode requested) {
            requested.properties().forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        records.put(key(ASSESSMENT, region, id), assessment);
        return assessment.deepCopy();
    }

    public ObjectNode getAssessment(String assessmentId, String region) {
        return requireAssessment(assessmentId, region).deepCopy();
    }

    public synchronized ObjectNode updateAssessment(String assessmentId, JsonNode request, String region) {
        ObjectNode assessment = requireAssessment(assessmentId, region);
        if (!request.path("scope").isObject()) {
            throw required("scope");
        }
        ObjectNode metadata = (ObjectNode) assessment.get("metadata");
        if (request.hasNonNull("assessmentName")) {
            metadata.put("name", request.get("assessmentName").asText());
        }
        if (request.hasNonNull("assessmentDescription")) {
            metadata.put("description", request.get("assessmentDescription").asText());
        }
        metadata.set("scope", request.get("scope").deepCopy());
        if (request.get("assessmentReportsDestination") instanceof ObjectNode destination) {
            metadata.set("assessmentReportsDestination", destination.deepCopy());
        }
        if (request.get("roles") instanceof ArrayNode roles) {
            validateRoles(roles);
            metadata.set("roles", roles.deepCopy());
        }
        return save(assessment, region);
    }

    public synchronized ObjectNode updateAssessmentStatus(String assessmentId, String status, String region) {
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw SmithyValidationErrors.validationException("Value '" + status
                    + "' at 'status' failed to satisfy constraint: Member must satisfy enum value set: [ACTIVE, INACTIVE]");
        }
        ObjectNode assessment = requireAssessment(assessmentId, region);
        ((ObjectNode) assessment.get("metadata")).put("status", status);
        return save(assessment, region);
    }

    public synchronized void deleteAssessment(String assessmentId, String region) {
        requireAssessment(assessmentId, region);
        for (String key : List.copyOf(records.keys())) {
            if (key.equals(key(ASSESSMENT, region, assessmentId))) {
                records.delete(key);
            } else if (!key.startsWith(ACCOUNT)) {
                records.get(key).filter(r -> assessmentId.equals(r.path("assessmentId").asText()))
                        .ifPresent(r -> records.delete(key));
            }
        }
    }

    public List<ObjectNode> listAssessments(String status, String region) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode assessment : scan(ASSESSMENT, region)) {
            JsonNode metadata = assessment.get("metadata");
            if (status != null && !status.equals(metadata.path("status").asText())) {
                continue;
            }
            ObjectNode item = JSON.objectNode();
            for (String member : List.of("name", "id", "complianceType", "status", "roles", "delegations",
                    "creationTime", "lastUpdated")) {
                if (metadata.has(member)) {
                    item.set(member, metadata.get(member).deepCopy());
                }
            }
            result.add(item);
        }
        result.sort(Comparator.comparing(i -> i.path("id").asText()));
        return result;
    }

    public synchronized ObjectNode updateAssessmentControlSetStatus(String assessmentId, String controlSetId,
                                                                    JsonNode request, String region) {
        String status = requiredText(request, "status");
        if (!Set.of("ACTIVE", "UNDER_REVIEW", "REVIEWED").contains(status)) {
            throw SmithyValidationErrors.validationException("Value '" + status
                    + "' at 'status' failed to satisfy constraint: Member must satisfy enum value set: "
                    + "[ACTIVE, UNDER_REVIEW, REVIEWED]");
        }
        ObjectNode assessment = requireAssessment(assessmentId, region);
        ObjectNode controlSet = controlSet(assessment, controlSetId);
        controlSet.put("status", status);
        save(assessment, region);
        return controlSet.deepCopy();
    }

    public synchronized ObjectNode updateAssessmentControl(String assessmentId, String controlSetId, String controlId,
                                                           JsonNode request, String region) {
        ObjectNode assessment = requireAssessment(assessmentId, region);
        ObjectNode control = control(controlSet(assessment, controlSetId), controlId);
        if (request.hasNonNull("controlStatus")) {
            control.put("status", request.get("controlStatus").asText());
        }
        if (request.hasNonNull("commentBody")) {
            ((ArrayNode) control.get("comments")).addObject()
                    .put("authorName", regionResolver.getAccountId())
                    .put("commentBody", request.get("commentBody").asText())
                    .put("postedDate", now());
        }
        save(assessment, region);
        return control.deepCopy();
    }

    // ──────────────────────────── Evidence ────────────────────────────

    public synchronized List<ObjectNode> batchImportEvidence(String assessmentId, String controlSetId, String controlId,
                                                             JsonNode manualEvidence, String region) {
        ObjectNode assessment = requireAssessment(assessmentId, region);
        ObjectNode controlSet = controlSet(assessment, controlSetId);
        ObjectNode control = control(controlSet, controlId);
        if (!manualEvidence.isArray() || manualEvidence.isEmpty() || manualEvidence.size() > 50) {
            throw SmithyValidationErrors.validationException("manualEvidence must hold 1 to 50 items.");
        }
        ObjectNode folder = todaysFolder(assessmentId, controlSetId, control, region);
        List<ObjectNode> errors = new ArrayList<>();
        int imported = 0;
        for (JsonNode item : manualEvidence) {
            if (!item.hasNonNull("s3ResourcePath") && !item.hasNonNull("textResponse")
                    && !item.hasNonNull("evidenceFileName")) {
                ObjectNode error = JSON.objectNode();
                error.set("manualEvidence", item.deepCopy());
                error.put("errorCode", "400");
                error.put("errorMessage", "Manual evidence needs an s3ResourcePath, textResponse or evidenceFileName.");
                errors.add(error);
                continue;
            }
            String evidenceId = UUID.randomUUID().toString();
            ObjectNode evidence = JSON.objectNode();
            evidence.put("dataSource", "Manual");
            evidence.put("evidenceAwsAccountId", regionResolver.getAccountId());
            evidence.put("time", now());
            evidence.put("eventSource", ServicePrincipals.of(SERVICE));
            evidence.put("eventName", "BatchImportEvidenceToAssessmentControl");
            evidence.put("evidenceByType", "Manual");
            ArrayNode resources = evidence.putArray("resourcesIncluded");
            if (item.hasNonNull("s3ResourcePath")) {
                resources.addObject().put("value", item.get("s3ResourcePath").asText());
            }
            ObjectNode attributes = evidence.putObject("attributes");
            if (item.hasNonNull("textResponse")) {
                attributes.put("textResponse", item.get("textResponse").asText());
            }
            if (item.hasNonNull("evidenceFileName")) {
                attributes.put("evidenceFileName", item.get("evidenceFileName").asText());
            }
            evidence.put("awsAccountId", regionResolver.getAccountId());
            evidence.put("evidenceFolderId", folder.path("id").asText());
            evidence.put("id", evidenceId);
            evidence.put("assessmentReportSelection", "No");
            evidence.put("assessmentId", assessmentId);
            records.put(key(EVIDENCE, region, evidenceId), evidence);
            imported++;
        }
        folder.put("totalEvidence", folder.path("totalEvidence").asInt() + imported);
        folder.put("evidenceByTypeManualCount", folder.path("evidenceByTypeManualCount").asInt() + imported);
        folder.put("evidenceResourcesIncludedCount", folder.path("totalEvidence").asInt());
        records.put(key(FOLDER, region, folder.path("id").asText()), folder);
        control.put("evidenceCount", control.path("evidenceCount").asInt() + imported);
        controlSet.put("manualEvidenceCount", controlSet.path("manualEvidenceCount").asInt() + imported);
        save(assessment, region);
        return errors;
    }

    public List<ObjectNode> evidenceFolders(String assessmentId, String controlSetId, String controlId, String region) {
        requireAssessment(assessmentId, region);
        return scan(FOLDER, region).stream()
                .filter(f -> assessmentId.equals(f.path("assessmentId").asText()))
                .filter(f -> controlSetId == null || controlSetId.equals(f.path("controlSetId").asText()))
                .filter(f -> controlId == null || controlId.equals(f.path("controlId").asText()))
                .sorted(Comparator.comparing(f -> f.path("id").asText()))
                .toList();
    }

    public ObjectNode getEvidenceFolder(String assessmentId, String controlSetId, String folderId, String region) {
        return requireFolder(assessmentId, controlSetId, folderId, region).deepCopy();
    }

    public List<ObjectNode> evidenceByFolder(String assessmentId, String controlSetId, String folderId, String region) {
        requireFolder(assessmentId, controlSetId, folderId, region);
        return scan(EVIDENCE, region).stream()
                .filter(e -> folderId.equals(e.path("evidenceFolderId").asText()))
                .map(AuditManagerAssessmentService::publicEvidence)
                .sorted(Comparator.comparing(e -> e.path("id").asText()))
                .toList();
    }

    public ObjectNode getEvidence(String assessmentId, String controlSetId, String folderId, String evidenceId,
                                  String region) {
        requireFolder(assessmentId, controlSetId, folderId, region);
        return records.get(key(EVIDENCE, region, evidenceId))
                .filter(e -> folderId.equals(e.path("evidenceFolderId").asText()))
                .map(AuditManagerAssessmentService::publicEvidence)
                .orElseThrow(() -> notFound(evidenceId, "EVIDENCE"));
    }

    /** Marks evidence for the assessment report; returns the ids it could not find. */
    public synchronized List<ObjectNode> setReportSelection(String assessmentId, String folderId, JsonNode evidenceIds,
                                                            boolean selected, String region) {
        requireAssessment(assessmentId, region);
        ObjectNode folder = records.get(key(FOLDER, region, folderId))
                .filter(f -> assessmentId.equals(f.path("assessmentId").asText()))
                .orElseThrow(() -> notFound(folderId, "EVIDENCE_FOLDER"));
        List<String> ids = new ArrayList<>();
        if (evidenceIds == null) {
            scan(EVIDENCE, region).stream().filter(e -> folderId.equals(e.path("evidenceFolderId").asText()))
                    .forEach(e -> ids.add(e.path("id").asText()));
        } else {
            evidenceIds.forEach(id -> ids.add(id.asText()));
        }
        List<ObjectNode> errors = new ArrayList<>();
        for (String id : ids) {
            ObjectNode evidence = records.get(key(EVIDENCE, region, id))
                    .filter(e -> folderId.equals(e.path("evidenceFolderId").asText())).orElse(null);
            if (evidence == null) {
                errors.add(JSON.objectNode().put("evidenceId", id).put("errorCode", "404")
                        .put("errorMessage", "Evidence " + id + " not found."));
                continue;
            }
            evidence.put("assessmentReportSelection", selected ? "Yes" : "No");
            records.put(key(EVIDENCE, region, id), evidence);
        }
        long selectedCount = scan(EVIDENCE, region).stream()
                .filter(e -> folderId.equals(e.path("evidenceFolderId").asText()))
                .filter(e -> "Yes".equals(e.path("assessmentReportSelection").asText()))
                .count();
        folder.put("assessmentReportSelectionCount", selectedCount);
        records.put(key(FOLDER, region, folderId), folder);
        return errors;
    }

    private ObjectNode todaysFolder(String assessmentId, String controlSetId, ObjectNode control, String region) {
        String controlId = control.path("id").asText();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        double date = today.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        for (ObjectNode folder : scan(FOLDER, region)) {
            if (assessmentId.equals(folder.path("assessmentId").asText())
                    && controlSetId.equals(folder.path("controlSetId").asText())
                    && controlId.equals(folder.path("controlId").asText())
                    && folder.path("date").asDouble() == date) {
                return folder;
            }
        }
        ObjectNode folder = JSON.objectNode();
        folder.put("name", today.toString());
        folder.put("date", date);
        folder.put("assessmentId", assessmentId);
        folder.put("controlSetId", controlSetId);
        folder.put("controlId", controlId);
        folder.put("id", UUID.randomUUID().toString());
        folder.put("dataSource", "Manual");
        folder.put("author", regionResolver.getAccountId());
        folder.put("totalEvidence", 0);
        folder.put("assessmentReportSelectionCount", 0);
        folder.put("controlName", control.path("name").asText(controlId));
        folder.put("evidenceResourcesIncludedCount", 0);
        folder.put("evidenceByTypeConfigurationDataCount", 0);
        folder.put("evidenceByTypeManualCount", 0);
        folder.put("evidenceByTypeComplianceCheckCount", 0);
        folder.put("evidenceByTypeComplianceCheckIssuesCount", 0);
        folder.put("evidenceByTypeUserActivityCount", 0);
        folder.put("evidenceAwsServiceSourceCount", 0);
        return folder;
    }

    private ObjectNode requireFolder(String assessmentId, String controlSetId, String folderId, String region) {
        requireAssessment(assessmentId, region);
        return records.get(key(FOLDER, region, folderId))
                .filter(f -> assessmentId.equals(f.path("assessmentId").asText())
                        && controlSetId.equals(f.path("controlSetId").asText()))
                .orElseThrow(() -> notFound(folderId, "EVIDENCE_FOLDER"));
    }

    private static ObjectNode publicEvidence(ObjectNode evidence) {
        ObjectNode copy = evidence.deepCopy();
        copy.remove("assessmentId");
        return copy;
    }

    // ──────────────────────────── Delegations ────────────────────────────

    public synchronized ObjectNode batchCreateDelegations(String assessmentId, JsonNode requests, String region) {
        ObjectNode assessment = requireAssessment(assessmentId, region);
        ObjectNode metadata = (ObjectNode) assessment.get("metadata");
        ObjectNode response = JSON.objectNode();
        ArrayNode created = response.putArray("delegations");
        ArrayNode errors = response.putArray("errors");
        for (JsonNode request : requests) {
            String controlSetId = request.path("controlSetId").asText("");
            ObjectNode controlSet = findControlSet(assessment, controlSetId);
            if (controlSet == null || !ROLE_TYPES.contains(request.path("roleType").asText())
                    || request.path("roleArn").asText("").isBlank()) {
                ObjectNode error = errors.addObject();
                error.set("createDelegationRequest", request.deepCopy());
                error.put("errorCode", controlSet == null ? "404" : "400");
                error.put("errorMessage", controlSet == null ? "Control set " + controlSetId + " not found."
                        : "roleArn and roleType are required.");
                continue;
            }
            double now = now();
            ObjectNode delegation = JSON.objectNode();
            delegation.put("id", UUID.randomUUID().toString());
            delegation.put("assessmentName", metadata.path("name").asText());
            delegation.put("assessmentId", assessmentId);
            delegation.put("status", "IN_PROGRESS");
            delegation.put("roleArn", request.get("roleArn").asText());
            delegation.put("roleType", request.get("roleType").asText());
            delegation.put("creationTime", now);
            delegation.put("lastUpdated", now);
            delegation.put("controlSetId", controlSetId);
            if (request.hasNonNull("comment")) {
                delegation.put("comment", request.get("comment").asText());
            }
            delegation.put("createdBy", regionResolver.getAccountId());
            ((ArrayNode) metadata.get("delegations")).add(delegation.deepCopy());
            ((ArrayNode) controlSet.get("delegations")).add(delegation.deepCopy());
            created.add(delegation);
        }
        save(assessment, region);
        return response;
    }

    public synchronized List<ObjectNode> batchDeleteDelegations(String assessmentId, JsonNode delegationIds,
                                                                String region) {
        ObjectNode assessment = requireAssessment(assessmentId, region);
        ObjectNode metadata = (ObjectNode) assessment.get("metadata");
        List<ObjectNode> errors = new ArrayList<>();
        for (JsonNode id : delegationIds) {
            boolean removed = removeById((ArrayNode) metadata.get("delegations"), id.asText());
            for (JsonNode controlSet : assessment.path("framework").path("controlSets")) {
                removeById((ArrayNode) controlSet.get("delegations"), id.asText());
            }
            if (!removed) {
                errors.add(JSON.objectNode().put("delegationId", id.asText()).put("errorCode", "404")
                        .put("errorMessage", "Delegation " + id.asText() + " not found."));
            }
        }
        save(assessment, region);
        return errors;
    }

    public List<ObjectNode> getDelegations(String region) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode assessment : scan(ASSESSMENT, region)) {
            for (JsonNode delegation : assessment.path("metadata").path("delegations")) {
                ObjectNode item = JSON.objectNode();
                for (String member : List.of("id", "assessmentName", "assessmentId", "status", "roleArn",
                        "creationTime")) {
                    item.set(member, delegation.get(member));
                }
                item.put("controlSetName", delegation.path("controlSetId").asText());
                result.add(item);
            }
        }
        return result;
    }

    private static boolean removeById(ArrayNode list, String id) {
        for (int i = 0; i < list.size(); i++) {
            if (id.equals(list.get(i).path("id").asText())) {
                list.remove(i);
                return true;
            }
        }
        return false;
    }

    // ──────────────────────────── Assessment reports ────────────────────────────

    public synchronized ObjectNode createAssessmentReport(String assessmentId, JsonNode request, String region) {
        ObjectNode assessment = requireAssessment(assessmentId, region);
        String name = requiredText(request, "name");
        ObjectNode report = JSON.objectNode();
        String id = UUID.randomUUID().toString();
        report.put("id", id);
        report.put("name", name);
        if (request.hasNonNull("description")) {
            report.put("description", request.get("description").asText());
        }
        report.put("awsAccountId", regionResolver.getAccountId());
        report.put("assessmentId", assessmentId);
        report.put("assessmentName", assessment.path("metadata").path("name").asText());
        report.put("author", regionResolver.getAccountId());
        report.put("status", "COMPLETE");
        report.put("creationTime", now());
        records.put(key(REPORT, region, id), report);
        return report.deepCopy();
    }

    public synchronized void deleteAssessmentReport(String assessmentId, String reportId, String region) {
        requireReport(assessmentId, reportId, region);
        records.delete(key(REPORT, region, reportId));
    }

    public List<ObjectNode> listAssessmentReports(String region) {
        return scan(REPORT, region).stream()
                .map(r -> {
                    ObjectNode copy = r.deepCopy();
                    copy.remove("awsAccountId");
                    return copy;
                })
                .sorted(Comparator.comparing(r -> r.path("id").asText()))
                .toList();
    }

    /** The report's location in the assessment's report destination. */
    public ObjectNode getAssessmentReportUrl(String assessmentId, String reportId, String region) {
        ObjectNode report = requireReport(assessmentId, reportId, region);
        String destination = requireAssessment(assessmentId, region).path("metadata")
                .path("assessmentReportsDestination").path("destination").asText("");
        String bucket = destination.replaceFirst("^s3://", "");
        int slash = bucket.indexOf('/');
        String prefix = slash < 0 ? "" : bucket.substring(slash + 1).replaceAll("/+$", "") + "/";
        bucket = slash < 0 ? bucket : bucket.substring(0, slash);
        String file = report.path("name").asText() + ".zip";
        return JSON.objectNode()
                .put("hyperlinkName", file)
                .put("link", "https://" + bucket + ".s3." + region + "." + AwsRegions.dnsSuffixFor(region) + "/"
                        + prefix + reportId + "/" + file);
    }

    private ObjectNode requireReport(String assessmentId, String reportId, String region) {
        requireAssessment(assessmentId, region);
        return records.get(key(REPORT, region, reportId))
                .filter(r -> assessmentId.equals(r.path("assessmentId").asText()))
                .orElseThrow(() -> notFound(reportId, "ASSESSMENT_REPORT"));
    }

    // ──────────────────────────── Account registration ────────────────────────────

    public synchronized String registerAccount(JsonNode request) {
        ObjectNode account = JSON.objectNode().put("status", "ACTIVE");
        if (request.hasNonNull("kmsKey")) {
            account.put("kmsKey", request.get("kmsKey").asText());
        }
        if (request.hasNonNull("delegatedAdminAccount")) {
            account.put("delegatedAdminAccount", request.get("delegatedAdminAccount").asText());
        }
        records.put(accountKey(), account);
        return "ACTIVE";
    }

    public synchronized String deregisterAccount() {
        records.delete(accountKey());
        return "INACTIVE";
    }

    public String accountStatus() {
        return records.get(accountKey()).map(a -> a.path("status").asText()).orElse("INACTIVE");
    }

    // ──────────────────────────── Tags ────────────────────────────

    public boolean ownsAssessment(String arn, String region) {
        return arn != null && arn.contains(":assessment/")
                && records.get(key(ASSESSMENT, region, arn.substring(arn.lastIndexOf('/') + 1))).isPresent();
    }

    public Map<String, String> listTags(String arn, String region) {
        Map<String, String> tags = new LinkedHashMap<>();
        requireAssessment(arn.substring(arn.lastIndexOf('/') + 1), region).path("tags").properties()
                .forEach(e -> tags.put(e.getKey(), e.getValue().asText()));
        return tags;
    }

    public synchronized void setTags(String arn, Map<String, String> tags, List<String> removed, String region) {
        String id = arn.substring(arn.lastIndexOf('/') + 1);
        ObjectNode assessment = requireAssessment(id, region);
        ObjectNode current = (ObjectNode) assessment.get("tags");
        if (tags != null) {
            tags.forEach(current::put);
        }
        if (removed != null) {
            removed.forEach(current::remove);
        }
        records.put(key(ASSESSMENT, region, id), assessment);
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private ObjectNode save(ObjectNode assessment, String region) {
        ((ObjectNode) assessment.get("metadata")).put("lastUpdated", now());
        records.put(key(ASSESSMENT, region, assessment.path("metadata").path("id").asText()), assessment);
        return assessment.deepCopy();
    }

    private ObjectNode requireAssessment(String assessmentId, String region) {
        return records.get(key(ASSESSMENT, region, assessmentId == null ? "" : assessmentId))
                .orElseThrow(() -> notFound(assessmentId, "ASSESSMENT"));
    }

    private static ObjectNode findControlSet(ObjectNode assessment, String controlSetId) {
        for (JsonNode controlSet : assessment.path("framework").path("controlSets")) {
            if (controlSetId.equals(controlSet.path("id").asText())) {
                return (ObjectNode) controlSet;
            }
        }
        return null;
    }

    private static ObjectNode controlSet(ObjectNode assessment, String controlSetId) {
        ObjectNode controlSet = findControlSet(assessment, controlSetId);
        if (controlSet == null) {
            throw notFound(controlSetId, "ASSESSMENT_CONTROL_SET");
        }
        return controlSet;
    }

    private static ObjectNode control(ObjectNode controlSet, String controlId) {
        for (JsonNode control : controlSet.path("controls")) {
            if (controlId.equals(control.path("id").asText())) {
                return (ObjectNode) control;
            }
        }
        throw notFound(controlId, "ASSESSMENT_CONTROL");
    }

    private static void validateRoles(JsonNode roles) {
        for (JsonNode role : roles) {
            if (!ROLE_TYPES.contains(role.path("roleType").asText()) || role.path("roleArn").asText("").isBlank()) {
                throw SmithyValidationErrors.validationException(
                        "Each role needs a roleArn and a roleType of PROCESS_OWNER or RESOURCE_OWNER.");
            }
        }
    }

    private List<ObjectNode> scan(String type, String region) {
        String prefix = type + "::" + region + "::";
        return records.scan(k -> k.startsWith(prefix));
    }

    private String accountKey() {
        return ACCOUNT + "::" + regionResolver.getAccountId();
    }

    private static String key(String type, String region, String id) {
        return type + "::" + region + "::" + id;
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static String requiredText(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            throw required(member);
        }
        return value.asText();
    }

    private static AwsException required(String member) {
        return SmithyValidationErrors.validationException(
                "Value null at '" + member + "' failed to satisfy constraint: Member must not be null");
    }

    private static AwsException notFound(String resourceId, String resourceType) {
        return new AwsException("ResourceNotFoundException",
                "The resource " + resourceId + " of type " + resourceType + " was not found.", 404);
    }
}
