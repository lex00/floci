package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import io.github.hectorvent.floci.services.datazone.model.DataZoneGlossary;
import io.github.hectorvent.floci.services.datazone.model.DataZoneGlossaryTerm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The DataZone catalog: assets with their revisions and filters, data products, and the listings
 * that publish them. Each asset and data product keeps every revision; a listing is
 * {@code ACTIVE} as soon as it is published.
 */
@ApplicationScoped
public class DataZoneCatalogService extends DataZoneRecords {

    static final String ASSET = "asset";
    static final String DATA_PRODUCT = "data-product";
    static final String LISTING = "listing";
    private static final String ASSET_FILTER = "asset-filter";
    private static final String MANAGED_TYPE_PREFIX = "amazon.datazone.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String[] ASSET_MEMBERS = {"id", "name", "typeIdentifier", "typeRevision",
            "externalIdentifier", "revision", "description", "createdAt", "createdBy", "firstRevisionCreatedAt",
            "firstRevisionCreatedBy", "glossaryTerms", "governedGlossaryTerms", "owningProjectId", "domainId",
            "listing", "formsOutput", "readOnlyFormsOutput", "latestTimeSeriesDataPointFormsOutput",
            "predictionConfiguration"};
    private static final String[] DATA_PRODUCT_MEMBERS = {"domainId", "id", "revision", "owningProjectId", "name",
            "status", "description", "glossaryTerms", "items", "formsOutput", "createdAt", "createdBy",
            "firstRevisionCreatedAt", "firstRevisionCreatedBy"};
    private static final String[] FILTER_MEMBERS = {"id", "domainId", "assetId", "name", "description", "status",
            "configuration", "createdAt", "errorMessage", "effectiveColumnNames", "effectiveRowFilter"};

    private final DataZoneEnvironmentService environments;

    @Inject
    public DataZoneCatalogService(StorageFactory storageFactory, DataZoneService domains,
                                  DataZoneEnvironmentService environments, RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-catalog.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, environments, regionResolver);
    }

    DataZoneCatalogService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                           DataZoneEnvironmentService environments, RegionResolver regionResolver) {
        super(records, domains, regionResolver);
        this.environments = environments;
    }

    // ──────────────────────────── Assets ────────────────────────────

    public synchronized ObjectNode createAsset(String region, String domainIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String name = requiredText(request, "name");
        String typeIdentifier = requiredText(request, "typeIdentifier");
        String projectId = domains.getProject(region, domain.getId(),
                requiredText(request, "owningProjectIdentifier")).getId();
        ObjectNode assetType = assetType(region, domain.getId(), typeIdentifier);
        BigDecimal now = now();
        ObjectNode asset = JSON.objectNode();
        asset.put("id", DataZoneService.randomId());
        asset.put("name", name);
        asset.put("typeIdentifier", typeIdentifier);
        asset.put("typeRevision", request.path("typeRevision").asText(assetType.path("revision").asText("1")));
        copy(request, asset, "externalIdentifier");
        asset.put("revision", "1");
        copy(request, asset, "description");
        asset.put("createdAt", now);
        asset.put("createdBy", account());
        asset.put("firstRevisionCreatedAt", now);
        asset.put("firstRevisionCreatedBy", account());
        asset.set("glossaryTerms", arrayOrEmpty(request.get("glossaryTerms")));
        asset.put("owningProjectId", projectId);
        asset.put("domainId", domain.getId());
        asset.set("formsOutput", formsOutput(request.path("formsInput"), assetType));
        asset.putArray("readOnlyFormsOutput");
        asset.putArray("latestTimeSeriesDataPointFormsOutput");
        copy(request, asset, "predictionConfiguration");
        asset.putArray("revisions").add(revisionSnapshot(asset));
        put(ASSET, region, domain.getId(), asset.path("id").asText(), asset);
        return pick(asset, ASSET_MEMBERS);
    }

    public synchronized ObjectNode createAssetRevision(String region, String domainIdentifier, String identifier,
                                                       JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode asset = require(ASSET, region, domainId, identifier, "Asset");
        asset.put("name", requiredText(request, "name"));
        copy(request, asset, "description", "typeRevision", "predictionConfiguration");
        if (request.path("glossaryTerms").isArray()) {
            asset.set("glossaryTerms", request.get("glossaryTerms").deepCopy());
        }
        if (request.path("formsInput").isArray()) {
            ObjectNode assetType = assetType(region, domainId, asset.path("typeIdentifier").asText());
            asset.set("formsOutput", formsOutput(request.get("formsInput"), assetType));
        }
        asset.put("revision", Integer.toString(asset.path("revision").asInt() + 1));
        asset.put("createdAt", now());
        asset.put("createdBy", account());
        ((ArrayNode) asset.get("revisions")).add(revisionSnapshot(asset));
        put(ASSET, region, domainId, identifier, asset);
        return pick(asset, ASSET_MEMBERS);
    }

    public synchronized ObjectNode getAsset(String region, String domainIdentifier, String identifier,
                                            String revision) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode asset = require(ASSET, region, domainId, identifier, "Asset");
        ObjectNode snapshot = revision(asset, revision, "Asset");
        ObjectNode out = pick(snapshot, ASSET_MEMBERS);
        copy(asset, out, "listing");
        out.remove("predictionConfiguration");
        return out;
    }

    public synchronized List<ObjectNode> listAssetRevisions(String region, String domainIdentifier,
                                                            String identifier) {
        String domainId = domainId(region, domainIdentifier);
        return revisionSummaries(require(ASSET, region, domainId, identifier, "Asset"));
    }

    public synchronized void deleteAsset(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode asset = require(ASSET, region, domainId, identifier, "Asset");
        boolean inProduct = list(DATA_PRODUCT, region, domainId).stream()
                .anyMatch(p -> p.path("items").findValuesAsText("identifier").contains(identifier));
        if (inProduct) {
            throw conflict("Asset " + identifier + " is an item of a data product.");
        }
        String listingId = asset.path("listing").path("listingId").asText(null);
        if (listingId != null) {
            delete(LISTING, region, domainId, listingId);
        }
        for (ObjectNode filter : list(ASSET_FILTER, region, domainId)) {
            if (identifier.equals(filter.path("assetId").asText())) {
                delete(ASSET_FILTER, region, domainId, filter.path("id").asText());
            }
        }
        delete(ASSET, region, domainId, identifier);
    }

    public synchronized ObjectNode acceptPredictions(String region, String domainIdentifier, String identifier,
                                                     String revision) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode snapshot = revision(require(ASSET, region, domainId, identifier, "Asset"), revision, "Asset");
        ObjectNode out = JSON.objectNode();
        out.put("domainId", domainId);
        out.put("assetId", identifier);
        out.put("revision", snapshot.path("revision").asText());
        return out;
    }

    public synchronized ObjectNode rejectPredictions(String region, String domainIdentifier, String identifier,
                                                     String revision) {
        ObjectNode out = acceptPredictions(region, domainIdentifier, identifier, revision);
        out.set("assetRevision", out.remove("revision"));
        return out;
    }

    private ObjectNode assetType(String region, String domainId, String typeIdentifier) {
        if (typeIdentifier.startsWith(MANAGED_TYPE_PREFIX)) {
            return JSON.objectNode().put("name", typeIdentifier).put("revision", "1");
        }
        return environments.getAssetType(region, domainId, typeIdentifier);
    }

    /** Turns {@code FormInput} entries into {@code FormOutput}, taking missing types from the asset type. */
    private static ArrayNode formsOutput(JsonNode formsInput, ObjectNode assetType) {
        ArrayNode out = JSON.arrayNode();
        for (JsonNode form : formsInput) {
            String formName = requiredText(form, "formName");
            JsonNode declared = assetType.path("formsOutput").path(formName);
            ObjectNode output = out.addObject();
            output.put("formName", formName);
            String typeName = form.path("typeIdentifier").asText(declared.path("typeName").asText(null));
            if (typeName != null) {
                output.put("typeName", typeName);
            }
            String typeRevision = form.path("typeRevision").asText(declared.path("typeRevision").asText(null));
            if (typeRevision != null) {
                output.put("typeRevision", typeRevision);
            }
            copy(form, output, "content");
        }
        return out;
    }

    // ──────────────────────────── Asset filters ────────────────────────────

    public synchronized ObjectNode createAssetFilter(String region, String domainIdentifier, String assetIdentifier,
                                                     JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        require(ASSET, region, domainId, assetIdentifier, "Asset");
        String name = requiredText(request, "name");
        JsonNode configuration = requiredNode(request, "configuration");
        unionMember(configuration, "configuration");
        if (filters(region, domainId, assetIdentifier).stream().anyMatch(f -> name.equals(f.path("name").asText()))) {
            throw conflict("An asset filter with name " + name + " already exists.");
        }
        ObjectNode filter = JSON.objectNode();
        filter.put("id", DataZoneService.randomId());
        filter.put("domainId", domainId);
        filter.put("assetId", assetIdentifier);
        filter.put("name", name);
        copy(request, filter, "description");
        filter.put("status", "VALID");
        filter.set("configuration", configuration.deepCopy());
        filter.put("createdAt", now());
        applyEffective(filter);
        put(ASSET_FILTER, region, domainId, filter.path("id").asText(), filter);
        return filter.deepCopy();
    }

    public synchronized ObjectNode getAssetFilter(String region, String domainIdentifier, String assetIdentifier,
                                                  String identifier) {
        return requireFilter(region, domainId(region, domainIdentifier), assetIdentifier, identifier).deepCopy();
    }

    public synchronized ObjectNode updateAssetFilter(String region, String domainIdentifier, String assetIdentifier,
                                                     String identifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode filter = requireFilter(region, domainId, assetIdentifier, identifier);
        String name = text(request, "name");
        if (name != null && !name.equals(filter.path("name").asText()) && filters(region, domainId, assetIdentifier)
                .stream().anyMatch(f -> name.equals(f.path("name").asText()))) {
            throw conflict("An asset filter with name " + name + " already exists.");
        }
        copy(request, filter, "name", "description");
        if (request.hasNonNull("configuration")) {
            unionMember(request.get("configuration"), "configuration");
            filter.set("configuration", request.get("configuration").deepCopy());
            applyEffective(filter);
        }
        put(ASSET_FILTER, region, domainId, identifier, filter);
        return filter.deepCopy();
    }

    public synchronized void deleteAssetFilter(String region, String domainIdentifier, String assetIdentifier,
                                               String identifier) {
        String domainId = domainId(region, domainIdentifier);
        requireFilter(region, domainId, assetIdentifier, identifier);
        delete(ASSET_FILTER, region, domainId, identifier);
    }

    public synchronized List<ObjectNode> listAssetFilters(String region, String domainIdentifier,
                                                          String assetIdentifier, String status) {
        String domainId = domainId(region, domainIdentifier);
        require(ASSET, region, domainId, assetIdentifier, "Asset");
        requireEnum(status, "status", Set.of("VALID", "INVALID"));
        return filters(region, domainId, assetIdentifier).stream()
                .filter(f -> status == null || status.equals(f.path("status").asText()))
                .map(f -> {
                    f.remove("configuration");
                    return f;
                })
                .toList();
    }

    private List<ObjectNode> filters(String region, String domainId, String assetId) {
        return list(ASSET_FILTER, region, domainId).stream()
                .filter(f -> assetId.equals(f.path("assetId").asText()))
                .toList();
    }

    private ObjectNode requireFilter(String region, String domainId, String assetId, String identifier) {
        require(ASSET, region, domainId, assetId, "Asset");
        ObjectNode filter = require(ASSET_FILTER, region, domainId, identifier, "Asset filter");
        if (!assetId.equals(filter.path("assetId").asText())) {
            throw notFound("Asset filter " + identifier + " not found.");
        }
        return filter;
    }

    private static void applyEffective(ObjectNode filter) {
        filter.remove(List.of("effectiveColumnNames", "effectiveRowFilter"));
        JsonNode columns = filter.path("configuration").path("columnConfiguration").path("includedColumnNames");
        if (columns.isArray()) {
            filter.set("effectiveColumnNames", columns.deepCopy());
        }
        JsonNode rows = filter.path("configuration").path("rowConfiguration").path("rowFilter");
        if (!rows.isMissingNode()) {
            filter.put("effectiveRowFilter", rows.toString());
        }
    }

    // ──────────────────────────── Data products ────────────────────────────

    public synchronized ObjectNode createDataProduct(String region, String domainIdentifier, JsonNode request) {
        DataZoneDomain domain = domains.getDomain(region, domainIdentifier);
        String name = requiredText(request, "name");
        String projectId = domains.getProject(region, domain.getId(),
                requiredText(request, "owningProjectIdentifier")).getId();
        BigDecimal now = now();
        ObjectNode product = JSON.objectNode();
        product.put("domainId", domain.getId());
        product.put("id", DataZoneService.randomId());
        product.put("revision", "1");
        product.put("owningProjectId", projectId);
        product.put("name", name);
        product.put("status", "CREATED");
        copy(request, product, "description");
        product.set("glossaryTerms", arrayOrEmpty(request.get("glossaryTerms")));
        product.set("items", productItems(region, domain.getId(), request.path("items")));
        product.set("formsOutput", formsOutput(request.path("formsInput"), JSON.objectNode()));
        product.put("createdAt", now);
        product.put("createdBy", account());
        product.put("firstRevisionCreatedAt", now);
        product.put("firstRevisionCreatedBy", account());
        product.putArray("revisions").add(revisionSnapshot(product));
        put(DATA_PRODUCT, region, domain.getId(), product.path("id").asText(), product);
        return pick(product, DATA_PRODUCT_MEMBERS);
    }

    public synchronized ObjectNode createDataProductRevision(String region, String domainIdentifier,
                                                             String identifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode product = require(DATA_PRODUCT, region, domainId, identifier, "Data product");
        product.put("name", requiredText(request, "name"));
        copy(request, product, "description");
        if (request.path("glossaryTerms").isArray()) {
            product.set("glossaryTerms", request.get("glossaryTerms").deepCopy());
        }
        if (request.path("items").isArray()) {
            product.set("items", productItems(region, domainId, request.get("items")));
        }
        if (request.path("formsInput").isArray()) {
            product.set("formsOutput", formsOutput(request.get("formsInput"), JSON.objectNode()));
        }
        product.put("revision", Integer.toString(product.path("revision").asInt() + 1));
        product.put("createdAt", now());
        product.put("createdBy", account());
        ((ArrayNode) product.get("revisions")).add(revisionSnapshot(product));
        put(DATA_PRODUCT, region, domainId, identifier, product);
        return pick(product, DATA_PRODUCT_MEMBERS);
    }

    public synchronized ObjectNode getDataProduct(String region, String domainIdentifier, String identifier,
                                                  String revision) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode product = require(DATA_PRODUCT, region, domainId, identifier, "Data product");
        return pick(revision(product, revision, "Data product"), DATA_PRODUCT_MEMBERS);
    }

    public synchronized List<ObjectNode> listDataProductRevisions(String region, String domainIdentifier,
                                                                  String identifier) {
        String domainId = domainId(region, domainIdentifier);
        return revisionSummaries(require(DATA_PRODUCT, region, domainId, identifier, "Data product"));
    }

    public synchronized void deleteDataProduct(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode product = require(DATA_PRODUCT, region, domainId, identifier, "Data product");
        String listingId = product.path("listing").path("listingId").asText(null);
        if (listingId != null) {
            delete(LISTING, region, domainId, listingId);
        }
        delete(DATA_PRODUCT, region, domainId, identifier);
    }

    private ArrayNode productItems(String region, String domainId, JsonNode items) {
        ArrayNode out = JSON.arrayNode();
        for (JsonNode item : items) {
            requireEnum(requiredText(item, "itemType"), "itemType", Set.of("ASSET"));
            String assetId = requiredText(item, "identifier");
            ObjectNode asset = require(ASSET, region, domainId, assetId, "Asset");
            ObjectNode output = out.addObject();
            output.put("itemType", "ASSET");
            output.put("identifier", assetId);
            output.put("revision", item.path("revision").asText(asset.path("revision").asText()));
            if (item.path("glossaryTerms").isArray()) {
                output.set("glossaryTerms", item.get("glossaryTerms").deepCopy());
            }
        }
        return out;
    }

    // ──────────────────────────── Listings ────────────────────────────

    public synchronized ObjectNode createListingChangeSet(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String entityType = requireEnum(requiredText(request, "entityType"), "entityType",
                Set.of("ASSET", "DATA_PRODUCT"));
        String action = requireEnum(requiredText(request, "action"), "action", Set.of("PUBLISH", "UNPUBLISH"));
        String entityId = requiredText(request, "entityIdentifier");
        String recordType = "ASSET".equals(entityType) ? ASSET : DATA_PRODUCT;
        ObjectNode entity = require(recordType, region, domainId, entityId,
                "ASSET".equals(entityType) ? "Asset" : "Data product");
        ObjectNode listing = "PUBLISH".equals(action)
                ? publish(region, domainId, entityType, entity, text(request, "entityRevision"))
                : unpublish(region, domainId, recordType, entity);
        ObjectNode out = JSON.objectNode();
        out.put("listingId", listing.path("id").asText());
        out.put("listingRevision", listing.path("listingRevision").asText());
        out.put("status", listing.path("status").asText());
        return out;
    }

    private ObjectNode publish(String region, String domainId, String entityType, ObjectNode entity,
                               String entityRevision) {
        String recordType = "ASSET".equals(entityType) ? ASSET : DATA_PRODUCT;
        ObjectNode snapshot = revision(entity, entityRevision, "ASSET".equals(entityType) ? "Asset" : "Data product");
        String listingId = entity.path("listing").path("listingId").asText(null);
        ObjectNode listing = listingId == null ? null : find(LISTING, region, domainId, listingId).orElse(null);
        BigDecimal now = now();
        if (listing == null) {
            listing = JSON.objectNode();
            listing.put("id", DataZoneService.randomId());
            listing.put("domainId", domainId);
            listing.put("listingRevision", "0");
            listing.put("createdAt", now);
            listing.put("createdBy", account());
            listing.putArray("history");
        }
        List<ObjectNode> productItems = new ArrayList<>();
        if (DATA_PRODUCT.equals(recordType)) {
            for (JsonNode item : snapshot.path("items")) {
                ObjectNode asset = require(ASSET, region, domainId, item.path("identifier").asText(), "Asset");
                productItems.add(publish(region, domainId, "ASSET", asset, item.path("revision").asText(null)));
            }
        }
        listing.put("listingRevision", Integer.toString(listing.path("listingRevision").asInt() + 1));
        listing.put("entityType", entityType);
        listing.put("entityId", entity.path("id").asText());
        listing.put("entityRevision", snapshot.path("revision").asText());
        listing.put("name", snapshot.path("name").asText());
        listing.put("description", snapshot.path("description").asText(""));
        listing.put("owningProjectId", snapshot.path("owningProjectId").asText());
        listing.put("status", "ACTIVE");
        listing.put("updatedAt", now);
        listing.put("updatedBy", account());
        listing.set("item", listingItem(region, domainId, entityType, snapshot, productItems));
        ArrayNode history = (ArrayNode) listing.get("history");
        history.add(listingOutput(listing));
        put(LISTING, region, domainId, listing.path("id").asText(), listing);
        entity.putObject("listing").put("listingId", listing.path("id").asText()).put("listingStatus", "ACTIVE");
        put(recordType, region, domainId, entity.path("id").asText(), entity);
        return listing;
    }

    private ObjectNode unpublish(String region, String domainId, String recordType, ObjectNode entity) {
        String listingId = entity.path("listing").path("listingId").asText(null);
        ObjectNode listing = listingId == null ? null : find(LISTING, region, domainId, listingId).orElse(null);
        if (listing == null) {
            throw notFound("Entity " + entity.path("id").asText() + " has no listing.");
        }
        listing.put("status", "INACTIVE");
        listing.put("updatedAt", now());
        listing.put("updatedBy", account());
        put(LISTING, region, domainId, listingId, listing);
        ((ObjectNode) entity.get("listing")).put("listingStatus", "INACTIVE");
        put(recordType, region, domainId, entity.path("id").asText(), entity);
        return listing;
    }

    private ObjectNode listingItem(String region, String domainId, String entityType, ObjectNode snapshot,
                                   List<ObjectNode> productItems) {
        ObjectNode item = JSON.objectNode();
        if ("ASSET".equals(entityType)) {
            ObjectNode asset = item.putObject("assetListing");
            asset.put("assetId", snapshot.path("id").asText());
            asset.put("assetRevision", snapshot.path("revision").asText());
            asset.put("assetType", snapshot.path("typeIdentifier").asText());
            asset.set("createdAt", snapshot.get("createdAt"));
            asset.put("forms", forms(snapshot.path("formsOutput")));
            asset.set("glossaryTerms", detailedTerms(region, domainId, snapshot.path("glossaryTerms")));
            asset.put("owningProjectId", snapshot.path("owningProjectId").asText());
        } else {
            ObjectNode product = item.putObject("dataProductListing");
            product.put("dataProductId", snapshot.path("id").asText());
            product.put("dataProductRevision", snapshot.path("revision").asText());
            product.set("createdAt", snapshot.get("createdAt"));
            product.put("forms", forms(snapshot.path("formsOutput")));
            product.set("glossaryTerms", detailedTerms(region, domainId, snapshot.path("glossaryTerms")));
            product.put("owningProjectId", snapshot.path("owningProjectId").asText());
            ArrayNode items = product.putArray("items");
            for (ObjectNode listing : productItems) {
                ObjectNode summary = items.addObject();
                summary.put("listingId", listing.path("id").asText());
                summary.put("listingRevision", listing.path("listingRevision").asText());
                summary.set("glossaryTerms", listing.path("item").path("assetListing").path("glossaryTerms"));
            }
        }
        return item;
    }

    public synchronized ObjectNode getListing(String region, String domainIdentifier, String identifier,
                                              String listingRevision) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode listing = require(LISTING, region, domainId, identifier, "Listing");
        if (listingRevision == null || listingRevision.equals(listing.path("listingRevision").asText())) {
            return listingOutput(listing);
        }
        for (JsonNode past : listing.path("history")) {
            if (listingRevision.equals(past.path("listingRevision").asText())) {
                return (ObjectNode) past.deepCopy();
            }
        }
        throw notFound("Listing " + identifier + " has no revision " + listingRevision + ".");
    }

    public synchronized void deleteListing(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode listing = require(LISTING, region, domainId, identifier, "Listing");
        String recordType = "ASSET".equals(listing.path("entityType").asText()) ? ASSET : DATA_PRODUCT;
        find(recordType, region, domainId, listing.path("entityId").asText()).ifPresent(entity -> {
            entity.remove("listing");
            put(recordType, region, domainId, entity.path("id").asText(), entity);
        });
        delete(LISTING, region, domainId, identifier);
    }

    public synchronized ObjectNode searchListings(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        boolean withForms = request.path("additionalAttributes").toString().contains("FORMS");
        List<ObjectNode> documents = new ArrayList<>();
        Map<ObjectNode, ObjectNode> results = new IdentityHashMap<>();
        for (ObjectNode listing : list(LISTING, region, domainId)) {
            if (!"ACTIVE".equals(listing.path("status").asText())) {
                continue;
            }
            ObjectNode document = searchDocument(listing);
            documents.add(document);
            results.put(document, searchListingItem(listing, withForms));
        }
        List<ObjectNode> matches = DataZoneSearch.apply(documents, request);
        return searchPage(matches.stream().map(results::get).toList(), request);
    }

    Optional<ObjectNode> findListing(String region, String domainId, String identifier) {
        return find(LISTING, region, domainId, identifier);
    }

    Optional<ObjectNode> findAsset(String region, String domainId, String identifier) {
        return find(ASSET, region, domainId, identifier);
    }

    /** Changes an asset member that every revision shares, such as its governed glossary terms. */
    synchronized void updateAssetEverywhere(String region, String domainId, String identifier,
                                            Consumer<ObjectNode> change) {
        ObjectNode asset = require(ASSET, region, domainId, identifier, "Asset");
        change.accept(asset);
        asset.path("revisions").forEach(snapshot -> change.accept((ObjectNode) snapshot));
        put(ASSET, region, domainId, identifier, asset);
    }

    private static ObjectNode searchDocument(ObjectNode listing) {
        ObjectNode document = pick(listing, "name", "description", "owningProjectId", "entityId", "entityType",
                "createdAt", "updatedAt");
        document.put("id", listing.path("id").asText());
        document.set("glossaryTerms", listing.path("item").findPath("glossaryTerms").deepCopy());
        document.put("typeIdentifier", listing.path("item").path("assetListing").path("assetType").asText(null));
        return document;
    }

    private static ObjectNode searchListingItem(ObjectNode listing, boolean withForms) {
        boolean asset = "ASSET".equals(listing.path("entityType").asText());
        JsonNode source = listing.path("item").path(asset ? "assetListing" : "dataProductListing");
        ObjectNode item = JSON.objectNode();
        item.put("listingId", listing.path("id").asText());
        item.put("listingRevision", listing.path("listingRevision").asText());
        item.put("name", listing.path("name").asText());
        item.put("entityId", listing.path("entityId").asText());
        item.put("entityRevision", listing.path("entityRevision").asText());
        if (asset) {
            item.put("entityType", source.path("assetType").asText());
        }
        item.put("description", listing.path("description").asText());
        item.set("createdAt", listing.get("createdAt"));
        item.put("listingCreatedBy", listing.path("createdBy").asText());
        item.put("listingUpdatedBy", listing.path("updatedBy").asText());
        item.set("glossaryTerms", source.path("glossaryTerms").deepCopy());
        item.put("owningProjectId", listing.path("owningProjectId").asText());
        if (withForms) {
            item.putObject("additionalAttributes").put("forms", source.path("forms").asText());
        }
        if (!asset) {
            item.set("items", source.path("items").deepCopy());
        }
        ObjectNode result = JSON.objectNode();
        result.set(asset ? "assetListing" : "dataProductListing", item);
        return result;
    }

    private static ObjectNode listingOutput(ObjectNode listing) {
        return pick(listing, "domainId", "id", "listingRevision", "createdAt", "updatedAt", "createdBy", "updatedBy",
                "item", "name", "description", "status");
    }

    // ──────────────────────────── Search ────────────────────────────

    public synchronized ObjectNode search(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String scope = requireEnum(requiredText(request, "searchScope"), "searchScope",
                Set.of("ASSET", "GLOSSARY", "GLOSSARY_TERM", "DATA_PRODUCT"));
        String projectId = text(request, "owningProjectIdentifier");
        boolean withForms = request.path("additionalAttributes").toString().contains("FORMS");
        List<ObjectNode> documents = new ArrayList<>();
        Map<ObjectNode, ObjectNode> results = new IdentityHashMap<>();
        switch (scope) {
            case "ASSET" -> list(ASSET, region, domainId).forEach(asset -> {
                ObjectNode item = pick(asset, "domainId", "name", "typeIdentifier", "typeRevision",
                        "externalIdentifier", "description", "createdAt", "createdBy", "firstRevisionCreatedAt",
                        "firstRevisionCreatedBy", "glossaryTerms", "owningProjectId", "governedGlossaryTerms");
                item.put("identifier", asset.path("id").asText());
                if (withForms) {
                    ObjectNode attributes = item.putObject("additionalAttributes");
                    copy(asset, attributes, "formsOutput", "readOnlyFormsOutput");
                }
                add(documents, results, asset, "assetItem", item);
            });
            case "DATA_PRODUCT" -> list(DATA_PRODUCT, region, domainId).forEach(product -> add(documents, results,
                    product, "dataProductItem", pick(product, "domainId", "id", "name", "owningProjectId",
                            "description", "glossaryTerms", "createdAt", "createdBy", "firstRevisionCreatedAt",
                            "firstRevisionCreatedBy")));
            case "GLOSSARY" -> domains.listGlossaries(region, domainId).forEach(glossary -> {
                ObjectNode item = glossaryItem(glossary);
                add(documents, results, item.deepCopy(), "glossaryItem", item);
            });
            default -> {
                Map<String, String> owners = new HashMap<>();
                domains.listGlossaries(region, domainId).forEach(g -> owners.put(g.getId(), g.getOwningProjectId()));
                domains.listGlossaryTerms(region, domainId).forEach(term -> {
                    ObjectNode item = glossaryTermItem(term);
                    ObjectNode document = item.deepCopy();
                    document.put("owningProjectId", owners.get(term.getGlossaryId()));
                    add(documents, results, document, "glossaryTermItem", item);
                });
            }
        }
        List<ObjectNode> scoped = documents.stream()
                .filter(d -> projectId == null || projectId.equals(d.path("owningProjectId").asText()))
                .toList();
        List<ObjectNode> matches = DataZoneSearch.apply(scoped, request);
        return searchPage(matches.stream().map(results::get).toList(), request);
    }

    private static void add(List<ObjectNode> documents, Map<ObjectNode, ObjectNode> results, ObjectNode source,
                            String member, ObjectNode item) {
        ObjectNode document = source.deepCopy();
        documents.add(document);
        ObjectNode result = JSON.objectNode();
        result.set(member, item);
        results.put(document, result);
    }

    private static ObjectNode glossaryItem(DataZoneGlossary glossary) {
        ObjectNode item = JSON.objectNode();
        item.put("domainId", glossary.getDomainId());
        item.put("id", glossary.getId());
        item.put("name", glossary.getName());
        item.put("owningProjectId", glossary.getOwningProjectId());
        if (glossary.getDescription() != null) {
            item.put("description", glossary.getDescription());
        }
        item.put("status", glossary.getStatus());
        if (glossary.getUsageRestrictions() != null) {
            glossary.getUsageRestrictions().forEach(item.putArray("usageRestrictions")::add);
        }
        item.put("createdAt", seconds(glossary.getCreatedAt()));
        item.put("createdBy", glossary.getCreatedBy());
        item.put("updatedAt", seconds(glossary.getUpdatedAt()));
        item.put("updatedBy", glossary.getUpdatedBy());
        return item;
    }

    private static ObjectNode glossaryTermItem(DataZoneGlossaryTerm term) {
        ObjectNode item = JSON.objectNode();
        item.put("domainId", term.getDomainId());
        item.put("glossaryId", term.getGlossaryId());
        item.put("id", term.getId());
        item.put("name", term.getName());
        if (term.getShortDescription() != null) {
            item.put("shortDescription", term.getShortDescription());
        }
        if (term.getLongDescription() != null) {
            item.put("longDescription", term.getLongDescription());
        }
        if (term.getTermRelations() != null) {
            item.set("termRelations", term.getTermRelations().deepCopy());
        }
        item.put("status", term.getStatus());
        item.put("createdAt", seconds(term.getCreatedAt()));
        item.put("createdBy", term.getCreatedBy());
        item.put("updatedAt", seconds(term.getUpdatedAt()));
        item.put("updatedBy", term.getUpdatedBy());
        return item;
    }

    public synchronized ObjectNode searchTypes(String region, String domainIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String scope = requireEnum(requiredText(request, "searchScope"), "searchScope",
                Set.of("ASSET_TYPE", "FORM_TYPE", "LINEAGE_NODE_TYPE"));
        if (!request.path("managed").isBoolean()) {
            throw required("managed");
        }
        List<ObjectNode> documents = new ArrayList<>();
        Map<ObjectNode, ObjectNode> results = new IdentityHashMap<>();
        if (!request.get("managed").asBoolean()) {
            if ("ASSET_TYPE".equals(scope)) {
                environments.listAssetTypes(region, domainId)
                        .forEach(type -> add(documents, results, type, "assetTypeItem", type.deepCopy()));
            } else if ("FORM_TYPE".equals(scope)) {
                environments.listFormTypes(region, domainId)
                        .forEach(type -> add(documents, results, type, "formTypeItem", type.deepCopy()));
            }
        }
        List<ObjectNode> matches = DataZoneSearch.apply(documents, request);
        return searchPage(matches.stream().map(results::get).toList(), request);
    }

    private static ObjectNode searchPage(List<ObjectNode> items, JsonNode request) {
        Integer maxResults = request.hasNonNull("maxResults") ? request.get("maxResults").asInt() : null;
        ObjectNode page = DataZonePages.page(items, maxResults, 50, text(request, "nextToken"), "items");
        page.put("totalMatchCount", items.size());
        return page;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    /** The entity's latest revision, or the one asked for. */
    static ObjectNode revision(ObjectNode entity, String revision, String label) {
        JsonNode revisions = entity.path("revisions");
        if (revision == null) {
            return (ObjectNode) revisions.get(revisions.size() - 1);
        }
        for (JsonNode snapshot : revisions) {
            if (revision.equals(snapshot.path("revision").asText())) {
                return (ObjectNode) snapshot;
            }
        }
        throw notFound(label + " " + entity.path("id").asText() + " has no revision " + revision + ".");
    }

    private static ObjectNode revisionSnapshot(ObjectNode entity) {
        ObjectNode snapshot = entity.deepCopy();
        snapshot.remove(List.of("revisions", "listing"));
        return snapshot;
    }

    private static List<ObjectNode> revisionSummaries(ObjectNode entity) {
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode snapshot : entity.path("revisions")) {
            out.addFirst(pick((ObjectNode) snapshot, "domainId", "id", "revision", "createdBy", "createdAt"));
        }
        return out;
    }

    /** {@code DetailedGlossaryTerm} entries for the glossary term ids that exist. */
    private ArrayNode detailedTerms(String region, String domainId, JsonNode termIds) {
        ArrayNode out = JSON.arrayNode();
        if (!termIds.isArray() || termIds.isEmpty()) {
            return out;
        }
        Map<String, DataZoneGlossaryTerm> byId = new HashMap<>();
        domains.listGlossaryTerms(region, domainId).forEach(t -> byId.put(t.getId(), t));
        for (JsonNode id : termIds) {
            DataZoneGlossaryTerm term = byId.get(id.asText());
            if (term != null) {
                ObjectNode detailed = out.addObject().put("name", term.getName());
                if (term.getShortDescription() != null) {
                    detailed.put("shortDescription", term.getShortDescription());
                }
            }
        }
        return out;
    }

    /** The forms as the JSON document string a listing carries, keyed by form name. */
    static String forms(JsonNode formsOutput) {
        ObjectNode forms = JSON.objectNode();
        for (JsonNode form : formsOutput) {
            String content = form.path("content").asText("{}");
            try {
                forms.set(form.path("formName").asText(), MAPPER.readTree(content));
            } catch (JsonProcessingException e) {
                forms.put(form.path("formName").asText(), content);
            }
        }
        return forms.toString();
    }

    private static ArrayNode arrayOrEmpty(JsonNode node) {
        return node != null && node.isArray() ? node.deepCopy() : JSON.arrayNode();
    }

    private static BigDecimal seconds(long millis) {
        return BigDecimal.valueOf(millis).movePointLeft(3);
    }
}
