package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Metadata DataZone keeps on an asset or listing: time series data points, per-attribute forms,
 * and an asset's governed glossary terms. An asset reports its newest data point of each form in
 * {@code latestTimeSeriesDataPointFormsOutput}.
 */
@ApplicationScoped
public class DataZoneEntityMetadataService extends DataZoneRecords {

    private static final String DATA_POINT = "time-series-data-point";
    private static final String ATTRIBUTE = "attribute-metadata";
    private static final Set<String> ENTITY_TYPES = Set.of("ASSET", "LISTING");

    private final DataZoneCatalogService catalog;

    @Inject
    public DataZoneEntityMetadataService(StorageFactory storageFactory, DataZoneService domains,
                                         DataZoneCatalogService catalog, RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-entity-metadata.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, catalog, regionResolver);
    }

    DataZoneEntityMetadataService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                                  DataZoneCatalogService catalog, RegionResolver regionResolver) {
        super(records, domains, regionResolver);
        this.catalog = catalog;
    }

    // ──────────────────────────── Time series data points ────────────────────────────

    public synchronized ObjectNode postTimeSeriesDataPoints(String region, String domainIdentifier,
                                                            String entityType, String entityIdentifier,
                                                            JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String entityId = requireEntity(region, domainId, entityType, entityIdentifier);
        JsonNode forms = requiredNode(request, "forms");
        if (!forms.isArray() || forms.isEmpty()) {
            throw required("forms");
        }
        ArrayNode output = JSON.arrayNode();
        for (JsonNode form : forms) {
            ObjectNode point = JSON.objectNode();
            point.put("id", DataZoneService.randomId());
            point.put("formName", requiredText(form, "formName"));
            point.put("typeIdentifier", requiredText(form, "typeIdentifier"));
            copy(form, point, "typeRevision");
            point.set("timestamp", requiredNode(form, "timestamp"));
            copy(form, point, "content");
            point.put("entityType", entityType);
            point.put("entityId", entityId);
            point.put("createdAt", now());
            put(DATA_POINT, region, domainId, point.path("id").asText(), point);
            output.add(pick(point, "formName", "typeIdentifier", "typeRevision", "timestamp", "content", "id"));
        }
        refreshLatest(region, domainId, entityType, entityId);
        ObjectNode out = JSON.objectNode();
        out.put("domainId", domainId);
        out.put("entityId", entityId);
        out.put("entityType", entityType);
        out.set("forms", output);
        return out;
    }

    public synchronized ObjectNode getTimeSeriesDataPoint(String region, String domainIdentifier, String entityType,
                                                          String entityIdentifier, String identifier,
                                                          String formName) {
        String domainId = domainId(region, domainIdentifier);
        String entityId = requireEntity(region, domainId, entityType, entityIdentifier);
        if (formName == null) {
            throw required("formName");
        }
        ObjectNode point = find(DATA_POINT, region, domainId, identifier)
                .filter(p -> entityId.equals(p.path("entityId").asText()) && formName.equals(p.path("formName").asText()))
                .orElseThrow(() -> notFound("Time series data point " + identifier + " not found."));
        ObjectNode out = JSON.objectNode();
        out.put("domainId", domainId);
        out.put("entityId", entityId);
        out.put("entityType", entityType);
        out.put("formName", formName);
        out.set("form", pick(point, "formName", "typeIdentifier", "typeRevision", "timestamp", "content", "id"));
        return out;
    }

    public synchronized List<ObjectNode> listTimeSeriesDataPoints(String region, String domainIdentifier,
                                                                  String entityType, String entityIdentifier,
                                                                  Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        String entityId = requireEntity(region, domainId, entityType, entityIdentifier);
        String formName = query.get("formName");
        if (formName == null) {
            throw required("formName");
        }
        BigDecimal startedAt = DataZoneLineageService.queryInstant(query, "startedAt");
        BigDecimal endedAt = DataZoneLineageService.queryInstant(query, "endedAt");
        return points(region, domainId, entityId, formName).stream()
                .filter(p -> startedAt == null || p.path("timestamp").decimalValue().compareTo(startedAt) >= 0)
                .filter(p -> endedAt == null || p.path("timestamp").decimalValue().compareTo(endedAt) <= 0)
                .map(DataZoneEntityMetadataService::summary)
                .toList();
    }

    public synchronized void deleteTimeSeriesDataPoints(String region, String domainIdentifier, String entityType,
                                                        String entityIdentifier, String formName) {
        String domainId = domainId(region, domainIdentifier);
        String entityId = requireEntity(region, domainId, entityType, entityIdentifier);
        if (formName == null) {
            throw required("formName");
        }
        for (ObjectNode point : points(region, domainId, entityId, formName)) {
            delete(DATA_POINT, region, domainId, point.path("id").asText());
        }
        refreshLatest(region, domainId, entityType, entityId);
    }

    /** The entity's data points of one form, oldest timestamp first. */
    private List<ObjectNode> points(String region, String domainId, String entityId, String formName) {
        return list(DATA_POINT, region, domainId).stream()
                .filter(p -> entityId.equals(p.path("entityId").asText()))
                .filter(p -> formName == null || formName.equals(p.path("formName").asText()))
                .sorted(Comparator.comparing(p -> p.path("timestamp").decimalValue()))
                .toList();
    }

    private void refreshLatest(String region, String domainId, String entityType, String entityId) {
        if (!"ASSET".equals(entityType)) {
            return;
        }
        Map<String, ObjectNode> latest = new TreeMap<>();
        for (ObjectNode point : points(region, domainId, entityId, null)) {
            latest.put(point.path("formName").asText(), summary(point));
        }
        ArrayNode forms = JSON.arrayNode();
        latest.values().forEach(forms::add);
        catalog.updateAssetEverywhere(region, domainId, entityId,
                asset -> asset.set("latestTimeSeriesDataPointFormsOutput", forms.deepCopy()));
    }

    private static ObjectNode summary(ObjectNode point) {
        ObjectNode out = pick(point, "formName", "typeIdentifier", "typeRevision", "timestamp", "id");
        copy(point, out, "content");
        if (out.has("content")) {
            out.set("contentSummary", out.remove("content"));
        }
        return out;
    }

    // ──────────────────────────── Attribute metadata ────────────────────────────

    public synchronized ObjectNode batchPutAttributesMetadata(String region, String domainIdentifier,
                                                              String entityType, String entityIdentifier,
                                                              JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String entityId = requireEntity(region, domainId, entityType, entityIdentifier);
        JsonNode attributes = requiredNode(request, "attributes");
        if (!attributes.isArray() || attributes.isEmpty()) {
            throw required("attributes");
        }
        ObjectNode out = JSON.objectNode();
        ArrayNode written = out.putArray("attributes");
        out.putArray("errors");
        for (JsonNode attribute : attributes) {
            String attributeId = requiredText(attribute, "attributeIdentifier");
            JsonNode forms = requiredNode(attribute, "forms");
            ArrayNode formsOutput = JSON.arrayNode();
            for (JsonNode form : forms) {
                ObjectNode formOutput = formsOutput.addObject();
                formOutput.put("formName", requiredText(form, "formName"));
                if (form.hasNonNull("typeIdentifier")) {
                    formOutput.put("typeName", form.get("typeIdentifier").asText());
                }
                copy(form, formOutput, "typeRevision", "content");
            }
            ObjectNode record = JSON.objectNode();
            record.put("id", attributeKey(entityType, entityId, attributeId));
            record.put("attributeIdentifier", attributeId);
            record.set("forms", formsOutput);
            record.put("createdAt", now());
            put(ATTRIBUTE, region, domainId, record.path("id").asText(), record);
            written.addObject().put("attributeIdentifier", attributeId);
        }
        return out;
    }

    public synchronized ObjectNode batchGetAttributesMetadata(String region, String domainIdentifier,
                                                              String entityType, String entityIdentifier,
                                                              List<String> attributeIdentifiers) {
        String domainId = domainId(region, domainIdentifier);
        String entityId = requireEntity(region, domainId, entityType, entityIdentifier);
        if (attributeIdentifiers == null || attributeIdentifiers.isEmpty()) {
            throw required("attributeIdentifiers");
        }
        ObjectNode out = JSON.objectNode();
        ArrayNode attributes = out.putArray("attributes");
        ArrayNode errors = out.putArray("errors");
        for (String attributeId : attributeIdentifiers) {
            find(ATTRIBUTE, region, domainId, attributeKey(entityType, entityId, attributeId)).ifPresentOrElse(
                    record -> attributes.add(pick(record, "attributeIdentifier", "forms")),
                    () -> errors.addObject().put("attributeIdentifier", attributeId)
                            .put("code", "ResourceNotFoundException")
                            .put("message", "Attribute " + attributeId + " has no metadata."));
        }
        return out;
    }

    private static String attributeKey(String entityType, String entityId, String attributeId) {
        return entityType + ":" + entityId + ":" + attributeId;
    }

    // ──────────────────────────── Governed terms ────────────────────────────

    public synchronized void associateGovernedTerms(String region, String domainIdentifier, String entityType,
                                                    String entityIdentifier, JsonNode request) {
        changeGovernedTerms(region, domainIdentifier, entityType, entityIdentifier, request, true);
    }

    public synchronized void disassociateGovernedTerms(String region, String domainIdentifier, String entityType,
                                                       String entityIdentifier, JsonNode request) {
        changeGovernedTerms(region, domainIdentifier, entityType, entityIdentifier, request, false);
    }

    private void changeGovernedTerms(String region, String domainIdentifier, String entityType,
                                     String entityIdentifier, JsonNode request, boolean associate) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(entityType, "entityType", Set.of("ASSET"));
        catalog.findAsset(region, domainId, entityIdentifier)
                .orElseThrow(() -> notFound("Asset " + entityIdentifier + " not found."));
        JsonNode terms = requiredNode(request, "governedGlossaryTerms");
        if (!terms.isArray() || terms.isEmpty()) {
            throw required("governedGlossaryTerms");
        }
        Set<String> termIds = new LinkedHashSet<>();
        for (JsonNode term : terms) {
            termIds.add(domains.getGlossaryTerm(region, domainId, term.asText()).getId());
        }
        catalog.updateAssetEverywhere(region, domainId, entityIdentifier, asset -> {
            Set<String> current = new LinkedHashSet<>();
            asset.path("governedGlossaryTerms").forEach(t -> current.add(t.asText()));
            if (associate) {
                current.addAll(termIds);
            } else {
                current.removeAll(termIds);
            }
            ArrayNode updated = asset.putArray("governedGlossaryTerms");
            current.forEach(updated::add);
        });
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /** The id of the asset or listing the path names, or ResourceNotFoundException. */
    private String requireEntity(String region, String domainId, String entityType, String entityIdentifier) {
        requireEnum(entityType, "entityType", ENTITY_TYPES);
        boolean asset = "ASSET".equals(entityType);
        boolean exists = asset ? catalog.findAsset(region, domainId, entityIdentifier).isPresent()
                : catalog.findListing(region, domainId, entityIdentifier).isPresent();
        if (!exists) {
            throw notFound((asset ? "Asset " : "Listing ") + entityIdentifier + " not found.");
        }
        return entityIdentifier;
    }
}
