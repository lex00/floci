package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DataZone lineage: OpenLineage run events and the lineage graph built from them. Each event
 * makes its job and its input and output datasets lineage nodes, with each input upstream of the
 * job and each output downstream of it. A node keeps one snapshot per event time it was seen at.
 */
@ApplicationScoped
public class DataZoneLineageService extends DataZoneRecords {

    static final String JOB_TYPE = "amazon.datazone.JobLineageNodeType";
    static final String DATASET_TYPE = "amazon.datazone.DatasetLineageNodeType";

    private static final String EVENT = "lineage-event";
    private static final String NODE = "lineage-node";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> RUN_STATES = Set.of("START", "RUNNING", "COMPLETE", "ABORT", "FAIL", "OTHER");
    private static final Set<String> PROCESSING_STATUSES = Set.of("REQUESTED", "PROCESSING", "SUCCESS", "FAILED");
    private static final Set<String> SORT_ORDERS = Set.of("ASCENDING", "DESCENDING");

    @Inject
    public DataZoneLineageService(StorageFactory storageFactory, DataZoneService domains,
                                  RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-lineage.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, regionResolver);
    }

    DataZoneLineageService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                           RegionResolver regionResolver) {
        super(records, domains, regionResolver);
    }

    // ──────────────────────────── Events ────────────────────────────

    public synchronized ObjectNode postLineageEvent(String region, String domainIdentifier, byte[] body,
                                                    String clientToken) {
        String domainId = domainId(region, domainIdentifier);
        if (clientToken != null) {
            Optional<ObjectNode> previous = list(EVENT, region, domainId).stream()
                    .filter(e -> clientToken.equals(e.path("clientToken").asText()))
                    .findFirst();
            if (previous.isPresent()) {
                return pick(previous.get(), "id", "domainId");
            }
        }
        JsonNode runEvent = parse(body);
        ObjectNode summary = summarize(runEvent);
        BigDecimal eventTime = seconds(isoInstant(requiredText(runEvent, "eventTime"), "eventTime"));

        ObjectNode event = JSON.objectNode();
        event.put("id", DataZoneService.randomId());
        event.put("domainId", domainId);
        event.put("processingStatus", "SUCCESS");
        event.put("eventTime", eventTime);
        event.putObject("eventSummary").set("openLineageRunEventSummary", summary);
        event.put("createdBy", account());
        event.put("createdAt", now());
        event.put("event", new String(body, StandardCharsets.UTF_8));
        if (clientToken != null) {
            event.put("clientToken", clientToken);
        }
        put(EVENT, region, domainId, event.path("id").asText(), event);
        record(region, domainId, summary, eventTime);
        return pick(event, "id", "domainId");
    }

    public synchronized ObjectNode getLineageEvent(String region, String domainIdentifier, String identifier) {
        return require(EVENT, region, domainId(region, domainIdentifier), identifier, "Lineage event");
    }

    public synchronized List<ObjectNode> listLineageEvents(String region, String domainIdentifier,
                                                           Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        String status = requireEnum(query.get("processingStatus"), "processingStatus", PROCESSING_STATUSES);
        BigDecimal after = queryInstant(query, "timestampAfter");
        BigDecimal before = queryInstant(query, "timestampBefore");
        Comparator<ObjectNode> order = Comparator.comparing(e -> e.path("eventTime").decimalValue());
        return list(EVENT, region, domainId).stream()
                .filter(e -> status == null || status.equals(e.path("processingStatus").asText()))
                .filter(e -> after == null || e.path("eventTime").decimalValue().compareTo(after) > 0)
                .filter(e -> before == null || e.path("eventTime").decimalValue().compareTo(before) < 0)
                .sorted(descending(query) ? order.reversed() : order)
                .map(e -> pick(e, "id", "domainId", "processingStatus", "eventTime", "eventSummary", "createdBy",
                        "createdAt"))
                .toList();
    }

    public synchronized ObjectNode deleteLineageEvent(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode event = require(EVENT, region, domainId, identifier, "Lineage event");
        delete(EVENT, region, domainId, identifier);
        return pick(event, "id", "domainId", "processingStatus");
    }

    private static JsonNode parse(byte[] body) {
        JsonNode runEvent;
        try {
            runEvent = body == null || body.length == 0 ? null : MAPPER.readTree(body);
        } catch (IOException e) {
            throw SmithyValidationErrors.validationException("The lineage event is not valid JSON.");
        }
        if (runEvent == null || !runEvent.isObject()) {
            throw SmithyValidationErrors.validationException("The lineage event must be an OpenLineage run event.");
        }
        return runEvent;
    }

    /** The {@code OpenLineageRunEventSummary} of a run event. */
    private static ObjectNode summarize(JsonNode runEvent) {
        ObjectNode summary = JSON.objectNode();
        String eventType = requireEnum(text(runEvent, "eventType"), "eventType", RUN_STATES);
        if (eventType != null) {
            summary.put("eventType", eventType);
        }
        summary.put("runId", requiredText(requiredNode(runEvent, "run"), "runId"));
        summary.set("job", nameIdentifier(requiredNode(runEvent, "job"), "job"));
        for (String member : List.of("inputs", "outputs")) {
            ArrayNode datasets = summary.putArray(member);
            for (JsonNode dataset : runEvent.path(member)) {
                datasets.add(nameIdentifier(dataset, member));
            }
        }
        return summary;
    }

    private static ObjectNode nameIdentifier(JsonNode node, String member) {
        if (!node.hasNonNull("namespace") || !node.hasNonNull("name")) {
            throw SmithyValidationErrors.validationException(
                    "Each " + member + " entry of the lineage event needs a namespace and a name.");
        }
        return JSON.objectNode().put("name", node.get("name").asText())
                .put("namespace", node.get("namespace").asText());
    }

    // ──────────────────────────── Nodes ────────────────────────────

    /** Adds a snapshot at the event time to the job and dataset nodes the event names. */
    private void record(String region, String domainId, ObjectNode summary, BigDecimal eventTime) {
        ObjectNode job = node(region, domainId, summary.get("job"), JOB_TYPE);
        List<ObjectNode> inputs = new ArrayList<>();
        summary.path("inputs").forEach(d -> inputs.add(node(region, domainId, d, DATASET_TYPE)));
        List<ObjectNode> outputs = new ArrayList<>();
        summary.path("outputs").forEach(d -> outputs.add(node(region, domainId, d, DATASET_TYPE)));

        snapshot(region, domainId, job, eventTime, ids(inputs), ids(outputs));
        for (ObjectNode input : inputs) {
            snapshot(region, domainId, input, eventTime, List.of(), List.of(job.path("id").asText()));
        }
        for (ObjectNode output : outputs) {
            snapshot(region, domainId, output, eventTime, List.of(job.path("id").asText()), List.of());
        }
    }

    private ObjectNode node(String region, String domainId, JsonNode name, String typeName) {
        String sourceIdentifier = name.path("namespace").asText() + "/" + name.path("name").asText();
        return list(NODE, region, domainId).stream()
                .filter(n -> sourceIdentifier.equals(n.path("sourceIdentifier").asText()))
                .findFirst()
                .orElseGet(() -> {
                    ObjectNode node = JSON.objectNode();
                    node.put("id", DataZoneService.randomId());
                    node.put("domainId", domainId);
                    node.put("name", name.path("name").asText());
                    node.put("typeName", typeName);
                    node.put("typeRevision", "1");
                    node.put("sourceIdentifier", sourceIdentifier);
                    node.put("createdAt", now());
                    node.put("createdBy", account());
                    node.putArray("snapshots");
                    put(NODE, region, domainId, node.path("id").asText(), node);
                    return node;
                });
    }

    private void snapshot(String region, String domainId, ObjectNode node, BigDecimal eventTime,
                          List<String> upstream, List<String> downstream) {
        ObjectNode stored = require(NODE, region, domainId, node.path("id").asText(), "Lineage node");
        ArrayNode snapshots = (ArrayNode) stored.get("snapshots");
        ObjectNode base = at(stored, eventTime).orElse(null);
        ObjectNode snapshot = null;
        for (JsonNode candidate : snapshots) {
            if (candidate.path("eventTimestamp").decimalValue().compareTo(eventTime) == 0) {
                snapshot = (ObjectNode) candidate;
            }
        }
        if (snapshot == null) {
            snapshot = snapshots.addObject();
            snapshot.put("eventTimestamp", eventTime);
            snapshot.set("upstream", base == null ? JSON.arrayNode() : base.get("upstream").deepCopy());
            snapshot.set("downstream", base == null ? JSON.arrayNode() : base.get("downstream").deepCopy());
        }
        for (JsonNode candidate : snapshots) {
            if (candidate.path("eventTimestamp").decimalValue().compareTo(eventTime) >= 0) {
                merge((ArrayNode) candidate.get("upstream"), upstream);
                merge((ArrayNode) candidate.get("downstream"), downstream);
            }
        }
        snapshot.put("updatedAt", now());
        snapshot.put("updatedBy", account());
        List<JsonNode> sorted = new ArrayList<>();
        snapshots.forEach(sorted::add);
        sorted.sort(Comparator.comparing(s -> s.path("eventTimestamp").decimalValue()));
        snapshots.removeAll();
        snapshots.addAll(sorted);
        put(NODE, region, domainId, stored.path("id").asText(), stored);
    }

    private static void merge(ArrayNode into, List<String> ids) {
        Set<String> present = new LinkedHashSet<>();
        into.forEach(id -> present.add(id.asText()));
        for (String id : ids) {
            if (present.add(id)) {
                into.add(id);
            }
        }
    }

    public synchronized ObjectNode getLineageNode(String region, String domainIdentifier, String identifier,
                                                  String timestamp) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode node = require(NODE, region, domainId, identifier, "Lineage node");
        BigDecimal time = timestamp == null ? null : seconds(isoInstant(timestamp, "timestamp"));
        ObjectNode snapshot = (time == null ? latest(node) : at(node, time))
                .orElseThrow(() -> notFound("Lineage node " + identifier + " has no snapshot at " + timestamp + "."));
        ObjectNode out = summary(node, snapshot);
        out.putArray("formsOutput");
        out.set("upstreamNodes", references(region, domainId, snapshot.get("upstream"), time));
        out.set("downstreamNodes", references(region, domainId, snapshot.get("downstream"), time));
        return out;
    }

    public synchronized List<ObjectNode> listLineageNodeHistory(String region, String domainIdentifier,
                                                                String identifier, Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode node = require(NODE, region, domainId, identifier, "Lineage node");
        String direction = requireEnum(query.get("direction"), "direction", Set.of("UPSTREAM", "DOWNSTREAM"));
        BigDecimal from = queryInstant(query, "timestampGTE");
        BigDecimal to = queryInstant(query, "timestampLTE");
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode snapshot : node.path("snapshots")) {
            BigDecimal time = snapshot.path("eventTimestamp").decimalValue();
            boolean inRange = (from == null || time.compareTo(from) >= 0) && (to == null || time.compareTo(to) <= 0);
            boolean facing = direction == null
                    || !snapshot.path("UPSTREAM".equals(direction) ? "upstream" : "downstream").isEmpty();
            if (inRange && facing) {
                out.add(summary(node, (ObjectNode) snapshot));
            }
        }
        return descending(query) ? out.reversed() : out;
    }

    private static ObjectNode summary(ObjectNode node, ObjectNode snapshot) {
        ObjectNode out = pick(node, "domainId", "name", "description", "createdAt", "createdBy", "id", "typeName",
                "typeRevision", "sourceIdentifier");
        copy(snapshot, out, "updatedAt", "updatedBy", "eventTimestamp");
        return out;
    }

    /** {@code LineageNodeReference} entries, each at the neighbour's snapshot for the time asked. */
    private ArrayNode references(String region, String domainId, JsonNode ids, BigDecimal time) {
        ArrayNode out = JSON.arrayNode();
        for (JsonNode id : ids) {
            find(NODE, region, domainId, id.asText()).ifPresent(neighbour -> {
                ObjectNode reference = out.addObject().put("id", id.asText());
                (time == null ? latest(neighbour) : at(neighbour, time))
                        .ifPresent(s -> reference.set("eventTimestamp", s.get("eventTimestamp")));
            });
        }
        return out;
    }

    private static Optional<ObjectNode> latest(ObjectNode node) {
        JsonNode snapshots = node.path("snapshots");
        return snapshots.isEmpty() ? Optional.empty() : Optional.of((ObjectNode) snapshots.get(snapshots.size() - 1));
    }

    /** The node's snapshot in force at the time: the latest one at or before it. */
    private static Optional<ObjectNode> at(ObjectNode node, BigDecimal time) {
        ObjectNode found = null;
        for (JsonNode snapshot : node.path("snapshots")) {
            if (snapshot.path("eventTimestamp").decimalValue().compareTo(time) <= 0) {
                found = (ObjectNode) snapshot;
            }
        }
        return Optional.ofNullable(found);
    }

    private static List<String> ids(List<ObjectNode> nodes) {
        return nodes.stream().map(n -> n.path("id").asText()).toList();
    }

    // ──────────────────────────── Graph ────────────────────────────

    /**
     * Walks the match clauses in order: an entity pattern selects lineage nodes by id or source
     * identifier and filters, and a relation pattern follows lineage edges from the current nodes.
     * Returns every node on the matched paths.
     */
    public synchronized List<ObjectNode> queryGraph(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        JsonNode match = requiredNode(request, "match");
        if (!match.isArray() || match.isEmpty()) {
            throw required("match");
        }
        Map<String, ObjectNode> nodes = new LinkedHashMap<>();
        list(NODE, region, domainId).forEach(n -> nodes.put(n.path("id").asText(), n));
        Set<String> current = null;
        Set<String> path = new LinkedHashSet<>();
        for (JsonNode clause : match) {
            String kind = unionMember(clause, "match");
            JsonNode pattern = clause.get(kind);
            if ("entityPattern".equals(kind)) {
                requireEnum(requiredText(pattern, "entityType"), "entityType", Set.of("LINEAGE_NODE"));
                Set<String> selected = new LinkedHashSet<>();
                for (ObjectNode node : nodes.values()) {
                    boolean candidate = current == null || current.contains(node.path("id").asText());
                    if (candidate && entityMatches(node, pattern)) {
                        selected.add(node.path("id").asText());
                    }
                }
                if (current != null) {
                    Set<String> previous = current;
                    path.removeIf(id -> previous.contains(id) && !selected.contains(id));
                }
                current = selected;
                path.addAll(selected);
            } else if ("relationPattern".equals(kind)) {
                requireEnum(requiredText(pattern, "relationType"), "relationType", Set.of("LINEAGE"));
                String direction = requireEnum(requiredText(pattern, "relationDirection"), "relationDirection",
                        Set.of("IN", "OUT"));
                int maxPathLength = pattern.path("maxPathLength").asInt(1);
                Set<String> start = current == null ? nodes.keySet() : current;
                current = walk(nodes, start, "IN".equals(direction) ? "upstream" : "downstream", maxPathLength);
                path.addAll(start);
                path.addAll(current);
            } else {
                throw SmithyValidationErrors.validationException("Unknown match clause " + kind + ".");
            }
        }
        List<String> formNames = new ArrayList<>();
        request.path("additionalAttributes").path("formNames").forEach(f -> formNames.add(f.asText()));
        List<ObjectNode> items = new ArrayList<>();
        for (String id : path) {
            ObjectNode node = nodes.get(id);
            ObjectNode snapshot = latest(node).orElse(JSON.objectNode());
            ObjectNode item = summary(node, snapshot);
            item.putArray("formsOutput");
            item.set("upstreamLineageNodeIds", snapshot.path("upstream").deepCopy());
            item.set("downstreamLineageNodeIds", snapshot.path("downstream").deepCopy());
            items.add(JSON.objectNode().set("lineageNode", item));
        }
        return items;
    }

    private static boolean entityMatches(ObjectNode node, JsonNode pattern) {
        String identifier = text(pattern, "identifier");
        if (identifier != null && !identifier.equals(node.path("id").asText())
                && !identifier.equals(node.path("sourceIdentifier").asText())) {
            return false;
        }
        JsonNode filters = pattern.get("filters");
        if (filters == null || filters.isNull()) {
            return true;
        }
        ObjectNode document = pick(node, "id", "name", "typeName", "sourceIdentifier");
        return !DataZoneSearch.apply(List.of(document), JSON.objectNode().set("filters", filters)).isEmpty();
    }

    private static Set<String> walk(Map<String, ObjectNode> nodes, Set<String> start, String edge, int maxDepth) {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> frontier = new ArrayDeque<>(start);
        for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
            Deque<String> next = new ArrayDeque<>();
            for (String id : frontier) {
                ObjectNode node = nodes.get(id);
                if (node == null) {
                    continue;
                }
                latest(node).ifPresent(s -> s.path(edge).forEach(neighbour -> {
                    if (reached.add(neighbour.asText()) && nodes.containsKey(neighbour.asText())) {
                        next.add(neighbour.asText());
                    }
                }));
            }
            frontier = next;
        }
        reached.retainAll(nodes.keySet());
        return reached;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static boolean descending(Map<String, String> query) {
        return !"ASCENDING".equals(requireEnum(query.get("sortOrder"), "sortOrder", SORT_ORDERS));
    }

    static BigDecimal queryInstant(Map<String, String> query, String member) {
        String value = query.get(member);
        return value == null ? null : seconds(isoInstant(value, member));
    }

    static Instant isoInstant(String value, String member) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw SmithyValidationErrors.validationException("Value '" + value + "' at '" + member
                    + "' is not an ISO-8601 timestamp.");
        }
    }

    static BigDecimal seconds(Instant instant) {
        return BigDecimal.valueOf(instant.toEpochMilli()).movePointLeft(3);
    }
}
