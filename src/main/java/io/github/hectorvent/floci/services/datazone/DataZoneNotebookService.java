package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * DataZone notebooks with their runs, exports, imports and syncs, and job runs. Runs, exports,
 * imports and syncs finish as soon as they start and execute nothing. Floci runs no jobs, so there
 * are no job runs.
 */
@ApplicationScoped
public class DataZoneNotebookService extends DataZoneRecords {

    private static final String NOTEBOOK = "notebook";
    private static final String RUN = "notebook-run";
    private static final String EXPORT = "notebook-export";

    private static final Set<String> NOTEBOOK_TYPES = Set.of("DATA", "SQL");
    private static final Set<String> NOTEBOOK_STATUSES = Set.of("ACTIVE", "ARCHIVED", "SYNC_IN_PROGRESS",
            "SYNC_FAILED");
    private static final Set<String> RUN_STATUSES = Set.of("QUEUED", "STARTING", "RUNNING", "STOPPING", "STOPPED",
            "SUCCEEDED", "FAILED");
    private static final Set<String> FINISHED_RUN_STATUSES = Set.of("STOPPED", "SUCCEEDED", "FAILED");
    private static final Set<String> FILE_FORMATS = Set.of("PDF", "IPYNB");
    private static final Set<String> JOB_RUN_STATUSES = Set.of("SCHEDULED", "IN_PROGRESS", "SUCCESS",
            "PARTIALLY_SUCCEEDED", "FAILED", "ABORTED", "TIMED_OUT", "CANCELED");
    private static final Set<String> SORT_KEYS = Set.of("CREATED_AT", "UPDATED_AT");
    private static final Set<String> SORT_ORDERS = Set.of("ASCENDING", "DESCENDING");

    @Inject
    public DataZoneNotebookService(StorageFactory storageFactory, DataZoneService domains,
                                   RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-notebooks.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, regionResolver);
    }

    DataZoneNotebookService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                            RegionResolver regionResolver) {
        super(records, domains, regionResolver);
    }

    // ──────────────────────────── Notebooks ────────────────────────────

    public synchronized ObjectNode createNotebook(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String projectId = projectId(region, domainId, requiredText(request, "owningProjectIdentifier"));
        String name = requiredText(request, "name");
        String type = requireEnum(text(request, "type"), "type", NOTEBOOK_TYPES);
        ObjectNode notebook = newNotebook(domainId, projectId, name, text(request, "description"), type);
        copy(request, notebook, "metadata", "parameters");
        put(NOTEBOOK, region, domainId, notebook.path("id").asText(), notebook);
        return notebook.deepCopy();
    }

    public synchronized ObjectNode getNotebook(String region, String domainIdentifier, String identifier) {
        return requireNotebook(region, domainId(region, domainIdentifier), identifier).deepCopy();
    }

    public synchronized ObjectNode updateNotebook(String region, String domainIdentifier, String identifier,
                                                  JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode notebook = requireNotebook(region, domainId, identifier);
        requireEnum(text(request, "status"), "status", NOTEBOOK_STATUSES);
        requireEnum(text(request, "type"), "type", NOTEBOOK_TYPES);
        copy(request, notebook, "name", "description", "status", "type", "cellOrder", "metadata", "parameters",
                "environmentConfiguration");
        touch(notebook);
        put(NOTEBOOK, region, domainId, identifier, notebook);
        return notebook.deepCopy();
    }

    public synchronized void deleteNotebook(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        requireNotebook(region, domainId, identifier);
        delete(NOTEBOOK, region, domainId, identifier);
    }

    public synchronized List<ObjectNode> listNotebooks(String region, String domainIdentifier,
                                                       Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(query.get("status"), "status", NOTEBOOK_STATUSES);
        requireEnum(query.get("type"), "type", NOTEBOOK_TYPES);
        String projectId = query.get("owningProjectIdentifier");
        return sorted(list(NOTEBOOK, region, domainId).stream()
                .filter(n -> projectId == null || projectId.equals(n.path("owningProjectId").asText()))
                .filter(n -> matches(query.get("status"), n.path("status")))
                .filter(n -> matches(query.get("type"), n.path("type")))
                .map(n -> pick(n, "id", "name", "owningProjectId", "domainId", "status", "type", "description",
                        "createdAt", "createdBy", "updatedAt", "updatedBy"))
                .toList(), query.get("sortBy"), query.get("sortOrder"));
    }

    private ObjectNode newNotebook(String domainId, String projectId, String name, String description, String type) {
        BigDecimal now = now();
        ObjectNode notebook = JSON.objectNode();
        notebook.put("id", DataZoneService.randomId());
        notebook.put("name", name);
        notebook.put("owningProjectId", projectId);
        notebook.put("domainId", domainId);
        notebook.putArray("cellOrder");
        notebook.put("status", "ACTIVE");
        if (type != null) {
            notebook.put("type", type);
        }
        if (description != null) {
            notebook.put("description", description);
        }
        notebook.put("createdAt", now);
        notebook.put("createdBy", account());
        notebook.put("updatedAt", now);
        notebook.put("updatedBy", account());
        return notebook;
    }

    private ObjectNode requireNotebook(String region, String domainId, String identifier) {
        return require(NOTEBOOK, region, domainId, identifier, "Notebook");
    }

    // ──────────────────────────── Notebook runs ────────────────────────────

    public synchronized ObjectNode startNotebookRun(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode notebook = requireNotebook(region, domainId, requiredText(request, "notebookIdentifier"));
        String projectId = projectId(region, domainId, text(request, "owningProjectIdentifier") != null
                ? text(request, "owningProjectIdentifier") : notebook.path("owningProjectId").asText());
        BigDecimal now = now();
        ObjectNode run = JSON.objectNode();
        run.put("id", DataZoneService.randomId());
        run.put("domainId", domainId);
        run.put("owningProjectId", projectId);
        run.put("notebookId", notebook.path("id").asText());
        if (request.hasNonNull("scheduleIdentifier")) {
            run.put("scheduleId", request.get("scheduleIdentifier").asText());
        }
        run.put("status", "SUCCEEDED");
        run.set("cellOrder", notebook.path("cellOrder").deepCopy());
        run.set("metadata", request.hasNonNull("metadata") ? request.get("metadata").deepCopy()
                : notebook.path("metadata").isObject() ? notebook.get("metadata").deepCopy() : JSON.objectNode());
        run.set("parameters", request.hasNonNull("parameters") ? request.get("parameters").deepCopy()
                : notebook.path("parameters").isObject() ? notebook.get("parameters").deepCopy() : JSON.objectNode());
        copy(request, run, "computeConfiguration", "networkConfiguration", "timeoutConfiguration",
                "notificationConfiguration");
        copy(notebook, run, "environmentConfiguration");
        run.set("triggerSource", request.hasNonNull("triggerSource") ? request.get("triggerSource").deepCopy()
                : JSON.objectNode().put("type", "MANUAL"));
        run.put("createdAt", now);
        run.put("createdBy", account());
        run.put("updatedAt", now);
        run.put("updatedBy", account());
        run.put("startedAt", now);
        run.put("completedAt", now);
        put(RUN, region, domainId, run.path("id").asText(), run);
        return run.deepCopy();
    }

    public synchronized ObjectNode getNotebookRun(String region, String domainIdentifier, String identifier) {
        return require(RUN, region, domainId(region, domainIdentifier), identifier, "Notebook run").deepCopy();
    }

    public synchronized ObjectNode stopNotebookRun(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode run = require(RUN, region, domainId, identifier, "Notebook run");
        if (FINISHED_RUN_STATUSES.contains(run.path("status").asText())) {
            throw conflict("Notebook run " + identifier + " is " + run.path("status").asText() + ".");
        }
        run.put("status", "STOPPED");
        run.put("completedAt", now());
        touch(run);
        put(RUN, region, domainId, identifier, run);
        return pick(run, "id", "domainId", "owningProjectId", "status");
    }

    public synchronized List<ObjectNode> listNotebookRuns(String region, String domainIdentifier,
                                                          Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(query.get("status"), "status", RUN_STATUSES);
        String projectId = query.get("owningProjectIdentifier");
        return sorted(list(RUN, region, domainId).stream()
                .filter(r -> projectId == null || projectId.equals(r.path("owningProjectId").asText()))
                .filter(r -> matches(query.get("notebookIdentifier"), r.path("notebookId")))
                .filter(r -> matches(query.get("status"), r.path("status")))
                .filter(r -> matches(query.get("scheduleIdentifier"), r.path("scheduleId")))
                .map(r -> pick(r, "id", "domainId", "owningProjectId", "notebookId", "scheduleId", "status",
                        "triggerSource", "createdAt", "createdBy", "updatedAt", "updatedBy", "startedAt",
                        "completedAt"))
                .toList(), null, query.get("sortOrder"));
    }

    // ──────────────────────────── Notebook exports, imports and syncs ────────────────────────────

    public synchronized ObjectNode startNotebookExport(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode notebook = requireNotebook(region, domainId, requiredText(request, "notebookIdentifier"));
        String projectId = projectId(region, domainId, requiredText(request, "owningProjectIdentifier"));
        String fileFormat = requireEnum(requiredText(request, "fileFormat"), "fileFormat", FILE_FORMATS);
        BigDecimal now = now();
        ObjectNode export = JSON.objectNode();
        String id = DataZoneService.randomId();
        export.put("id", id);
        export.put("domainId", domainId);
        export.put("owningProjectId", projectId);
        export.put("notebookId", notebook.path("id").asText());
        export.put("fileFormat", fileFormat);
        export.put("status", "SUCCEEDED");
        export.putObject("outputLocation").putObject("s3").put("uri", "s3://amazon-sagemaker-" + account() + "-"
                + region + "/" + domainId + "/" + projectId + "/notebook-exports/" + id + "."
                + fileFormat.toLowerCase(Locale.ROOT));
        export.put("completedAt", now);
        export.put("createdAt", now);
        export.put("createdBy", account());
        put(EXPORT, region, domainId, id, export);
        return pick(export, "id", "domainId", "owningProjectId", "notebookId", "fileFormat", "status", "createdAt",
                "createdBy");
    }

    public synchronized ObjectNode getNotebookExport(String region, String domainIdentifier, String identifier) {
        return require(EXPORT, region, domainId(region, domainIdentifier), identifier, "Notebook export")
                .deepCopy();
    }

    public synchronized ObjectNode startNotebookImport(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String projectId = projectId(region, domainId, requiredText(request, "owningProjectIdentifier"));
        JsonNode source = requiredNode(request, "sourceLocation");
        unionMember(source, "sourceLocation");
        String name = requiredText(request, "name");
        String type = requireEnum(text(request, "type"), "type", NOTEBOOK_TYPES);
        ObjectNode notebook = newNotebook(domainId, projectId, name, text(request, "description"), type);
        put(NOTEBOOK, region, domainId, notebook.path("id").asText(), notebook);
        ObjectNode out = JSON.objectNode();
        out.put("notebookId", notebook.path("id").asText());
        copy(notebook, out, "status", "domainId", "owningProjectId", "name", "description", "type");
        out.set("sourceLocation", source.deepCopy());
        copy(notebook, out, "createdAt", "createdBy");
        return out;
    }

    public synchronized ObjectNode startNotebookSync(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String projectId = projectId(region, domainId, requiredText(request, "owningProjectIdentifier"));
        JsonNode source = request.get("sourceLocation");
        if (source != null && !source.isNull()) {
            unionMember(source, "sourceLocation");
        }
        String notebookId = text(request, "notebookId");
        ObjectNode notebook;
        if (notebookId != null) {
            notebook = requireNotebook(region, domainId, notebookId);
            copy(request, notebook, "name", "description");
            touch(notebook);
        } else {
            notebook = newNotebook(domainId, projectId, requiredText(request, "name"), text(request, "description"),
                    null);
        }
        copy(request, notebook, "gitMetadata");
        notebook.put("status", "ACTIVE");
        put(NOTEBOOK, region, domainId, notebook.path("id").asText(), notebook);
        ObjectNode out = JSON.objectNode();
        out.put("notebookId", notebook.path("id").asText());
        copy(notebook, out, "status", "domainId", "owningProjectId");
        copy(request, out, "sourceLocation", "gitMetadata");
        copy(notebook, out, "name", "description", "createdAt", "createdBy");
        return out;
    }

    // ──────────────────────────── Job runs ────────────────────────────

    public ObjectNode getJobRun(String region, String domainIdentifier, String identifier) {
        domainId(region, domainIdentifier);
        throw notFound("Job run " + identifier + " not found.");
    }

    public List<ObjectNode> listJobRuns(String region, String domainIdentifier, String jobIdentifier,
                                        Map<String, String> query) {
        domainId(region, domainIdentifier);
        requireEnum(query.get("status"), "status", JOB_RUN_STATUSES);
        requireEnum(query.get("sortOrder"), "sortOrder", SORT_ORDERS);
        return List.of();
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private String projectId(String region, String domainId, String identifier) {
        return domains.getProject(region, domainId, identifier).getId();
    }

    private void touch(ObjectNode record) {
        record.put("updatedAt", now());
        record.put("updatedBy", account());
    }

    private static boolean matches(String expected, JsonNode actual) {
        return expected == null || expected.equals(actual.asText());
    }

    private static List<ObjectNode> sorted(List<ObjectNode> records, String sortBy, String sortOrder) {
        requireEnum(sortBy, "sortBy", SORT_KEYS);
        requireEnum(sortOrder, "sortOrder", SORT_ORDERS);
        String member = "UPDATED_AT".equals(sortBy) ? "updatedAt" : "createdAt";
        Comparator<ObjectNode> order = Comparator.comparing(r -> r.path(member).decimalValue());
        return records.stream()
                .sorted("ASCENDING".equals(sortOrder) ? order : order.reversed())
                .toList();
    }
}
