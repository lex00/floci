package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.datazone.model.DataZoneProject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * DataZone subscriptions: requests for listings, the subscriptions an accepted request creates,
 * the subscription targets of environments and the grants that fulfil a subscription. Accepting a
 * request grants each new subscription to the automatic subscription targets of the subscribing
 * project's environments.
 */
@ApplicationScoped
public class DataZoneSubscriptionService extends DataZoneRecords {

    private static final String REQUEST = "subscription-request";
    private static final String SUBSCRIPTION = "subscription";
    private static final String TARGET = "subscription-target";
    private static final String GRANT = "subscription-grant";
    private static final String PROVIDER = "Amazon DataZone";

    private static final Set<String> GRANT_STATUSES = Set.of("GRANT_PENDING", "REVOKE_PENDING", "GRANT_IN_PROGRESS",
            "REVOKE_IN_PROGRESS", "GRANTED", "REVOKED", "GRANT_FAILED", "REVOKE_FAILED");
    private static final Set<String> SORT_KEYS = Set.of("CREATED_AT", "UPDATED_AT");
    private static final Set<String> SORT_ORDERS = Set.of("ASCENDING", "DESCENDING");

    private final DataZoneCatalogService catalog;
    private final DataZoneEnvironmentService environments;

    @Inject
    public DataZoneSubscriptionService(StorageFactory storageFactory, DataZoneService domains,
                                       DataZoneCatalogService catalog, DataZoneEnvironmentService environments,
                                       RegionResolver regionResolver) {
        this(storageFactory.create("datazone", "datazone-subscriptions.json",
                new TypeReference<Map<String, ObjectNode>>() {}), domains, catalog, environments, regionResolver);
    }

    DataZoneSubscriptionService(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                                DataZoneCatalogService catalog, DataZoneEnvironmentService environments,
                                RegionResolver regionResolver) {
        super(records, domains, regionResolver);
        this.catalog = catalog;
        this.environments = environments;
    }

    // ──────────────────────────── Subscription requests ────────────────────────────

    public synchronized ObjectNode createSubscriptionRequest(String region, String domainIdentifier,
                                                             JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String reason = requiredText(request, "requestReason");
        JsonNode principalsInput = requiredNode(request, "subscribedPrincipals");
        JsonNode listingsInput = requiredNode(request, "subscribedListings");
        if (principalsInput.isEmpty() || listingsInput.isEmpty()) {
            throw SmithyValidationErrors.validationException(
                    "subscribedPrincipals and subscribedListings must each hold one entry.");
        }
        ArrayNode principals = JSON.arrayNode();
        for (JsonNode principal : principalsInput) {
            principals.add(subscribedPrincipal(region, domainId, principal));
        }
        ArrayNode listings = JSON.arrayNode();
        for (JsonNode listing : listingsInput) {
            listings.add(subscribedListing(region, domainId, requiredText(listing, "identifier")));
        }
        for (JsonNode principal : principals) {
            for (JsonNode listing : listings) {
                boolean subscribed = list(SUBSCRIPTION, region, domainId).stream()
                        .anyMatch(s -> "APPROVED".equals(s.path("status").asText())
                                && principal.equals(s.get("subscribedPrincipal"))
                                && listing.path("id").asText().equals(s.path("subscribedListing").path("id").asText()));
                if (subscribed) {
                    throw conflict("The principal is already subscribed to listing " + listing.path("id").asText()
                            + ".");
                }
            }
        }
        BigDecimal now = now();
        ObjectNode subscriptionRequest = JSON.objectNode();
        subscriptionRequest.put("id", DataZoneService.randomId());
        subscriptionRequest.put("createdBy", account());
        subscriptionRequest.put("updatedBy", account());
        subscriptionRequest.put("domainId", domainId);
        subscriptionRequest.put("status", "PENDING");
        subscriptionRequest.put("createdAt", now);
        subscriptionRequest.put("updatedAt", now);
        subscriptionRequest.put("requestReason", reason);
        subscriptionRequest.set("subscribedPrincipals", principals);
        subscriptionRequest.set("subscribedListings", listings);
        ArrayNode forms = subscriptionRequest.putArray("metadataForms");
        for (JsonNode form : request.path("metadataForms")) {
            ObjectNode output = forms.addObject();
            output.put("formName", requiredText(form, "formName"));
            copy(form, output, "content", "typeRevision");
            if (form.hasNonNull("typeIdentifier")) {
                output.put("typeName", form.get("typeIdentifier").asText());
            }
        }
        put(REQUEST, region, domainId, subscriptionRequest.path("id").asText(), subscriptionRequest);
        return subscriptionRequest.deepCopy();
    }

    public synchronized ObjectNode getSubscriptionRequestDetails(String region, String domainIdentifier,
                                                                 String identifier) {
        return require(REQUEST, region, domainId(region, domainIdentifier), identifier, "Subscription request")
                .deepCopy();
    }

    public synchronized ObjectNode updateSubscriptionRequest(String region, String domainIdentifier,
                                                             String identifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode subscriptionRequest = requirePending(region, domainId, identifier);
        subscriptionRequest.put("requestReason", requiredText(request, "requestReason"));
        touch(subscriptionRequest);
        put(REQUEST, region, domainId, identifier, subscriptionRequest);
        return subscriptionRequest.deepCopy();
    }

    public synchronized void deleteSubscriptionRequest(String region, String domainIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        require(REQUEST, region, domainId, identifier, "Subscription request");
        delete(REQUEST, region, domainId, identifier);
    }

    public synchronized ObjectNode acceptSubscriptionRequest(String region, String domainIdentifier,
                                                             String identifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode subscriptionRequest = requirePending(region, domainId, identifier);
        decide(subscriptionRequest, "ACCEPTED", request);
        put(REQUEST, region, domainId, identifier, subscriptionRequest);
        for (JsonNode principal : subscriptionRequest.path("subscribedPrincipals")) {
            for (JsonNode listing : subscriptionRequest.path("subscribedListings")) {
                ObjectNode subscription = createSubscription(region, domainId, identifier, principal, listing);
                String projectId = principal.path("project").path("id").asText(null);
                if (projectId != null) {
                    grantToAutomaticTargets(region, domainId, projectId, subscription);
                }
            }
        }
        return subscriptionRequest.deepCopy();
    }

    public synchronized ObjectNode rejectSubscriptionRequest(String region, String domainIdentifier,
                                                             String identifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode subscriptionRequest = requirePending(region, domainId, identifier);
        decide(subscriptionRequest, "REJECTED", request);
        put(REQUEST, region, domainId, identifier, subscriptionRequest);
        return subscriptionRequest.deepCopy();
    }

    public synchronized List<ObjectNode> listSubscriptionRequests(String region, String domainIdentifier,
                                                                  Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(query.get("status"), "status", Set.of("PENDING", "ACCEPTED", "REJECTED"));
        return sorted(list(REQUEST, region, domainId).stream()
                .filter(r -> matches(query.get("status"), r.path("status")))
                .filter(r -> query.get("subscribedListingId") == null
                        || r.path("subscribedListings").findValuesAsText("id").contains(query.get("subscribedListingId")))
                .filter(r -> query.get("approverProjectId") == null || r.path("subscribedListings")
                        .findValuesAsText("ownerProjectId").contains(query.get("approverProjectId")))
                .filter(r -> principalMatches(r.path("subscribedPrincipals"), query))
                .map(r -> {
                    ArrayNode summary = JSON.arrayNode();
                    for (JsonNode form : r.path("metadataForms")) {
                        summary.add(pick((ObjectNode) form, "formName", "typeName", "typeRevision"));
                    }
                    r.remove("metadataForms");
                    r.set("metadataFormsSummary", summary);
                    return r;
                })
                .toList(), query);
    }

    private ObjectNode requirePending(String region, String domainId, String identifier) {
        ObjectNode subscriptionRequest = require(REQUEST, region, domainId, identifier, "Subscription request");
        if (!"PENDING".equals(subscriptionRequest.path("status").asText())) {
            throw conflict("Subscription request " + identifier + " is "
                    + subscriptionRequest.path("status").asText() + ".");
        }
        return subscriptionRequest;
    }

    private void decide(ObjectNode subscriptionRequest, String status, JsonNode request) {
        subscriptionRequest.put("status", status);
        subscriptionRequest.put("reviewerId", account());
        copy(request, subscriptionRequest, "decisionComment");
        touch(subscriptionRequest);
    }

    /** Turns {@code SubscribedPrincipalInput} into {@code SubscribedPrincipal}. */
    private ObjectNode subscribedPrincipal(String region, String domainId, JsonNode principal) {
        String kind = unionMember(principal, "subscribedPrincipals");
        String identifier = requiredText(principal.get(kind), "identifier");
        ObjectNode out = JSON.objectNode();
        switch (kind) {
            case "project" -> {
                DataZoneProject project = domains.getProject(region, domainId, identifier);
                out.putObject("project").put("id", project.getId()).put("name", project.getName());
            }
            case "user" -> out.putObject("user").put("id", environments.userId(region, domainId, identifier));
            case "group" -> out.putObject("group").put("id", identifier).put("name", identifier);
            case "iam" -> out.putObject("iam").put("principalArn", identifier);
            default -> throw SmithyValidationErrors.validationException("Unknown principal type " + kind + ".");
        }
        return out;
    }

    /** The {@code SubscribedListing} for an active listing. */
    private ObjectNode subscribedListing(String region, String domainId, String listingId) {
        ObjectNode listing = catalog.findListing(region, domainId, listingId)
                .orElseThrow(() -> notFound("Listing " + listingId + " not found."));
        if (!"ACTIVE".equals(listing.path("status").asText())) {
            throw conflict("Listing " + listingId + " is not active.");
        }
        ObjectNode out = JSON.objectNode();
        out.put("id", listingId);
        out.put("revision", listing.path("listingRevision").asText());
        out.put("name", listing.path("name").asText());
        out.put("description", listing.path("description").asText(""));
        ObjectNode item = out.putObject("item");
        JsonNode source = listing.path("item");
        if (source.has("assetListing")) {
            ObjectNode asset = item.putObject("assetListing");
            asset.put("entityId", listing.path("entityId").asText());
            asset.put("entityRevision", listing.path("entityRevision").asText());
            asset.put("entityType", source.path("assetListing").path("assetType").asText());
            asset.put("forms", source.path("assetListing").path("forms").asText());
            asset.set("glossaryTerms", source.path("assetListing").path("glossaryTerms").deepCopy());
        } else {
            JsonNode productSource = source.path("dataProductListing");
            ObjectNode product = item.putObject("productListing");
            product.put("entityId", listing.path("entityId").asText());
            product.put("entityRevision", listing.path("entityRevision").asText());
            product.set("glossaryTerms", productSource.path("glossaryTerms").deepCopy());
            product.put("name", listing.path("name").asText());
            product.put("description", listing.path("description").asText(""));
            ArrayNode assets = product.putArray("assetListings");
            for (JsonNode summary : productSource.path("items")) {
                catalog.findListing(region, domainId, summary.path("listingId").asText()).ifPresent(assetListing ->
                        assets.addObject()
                                .put("entityId", assetListing.path("entityId").asText())
                                .put("entityRevision", assetListing.path("entityRevision").asText())
                                .put("entityType", assetListing.path("item").path("assetListing")
                                        .path("assetType").asText()));
            }
        }
        String ownerProjectId = listing.path("owningProjectId").asText();
        out.put("ownerProjectId", ownerProjectId);
        domains.listProjects(region, domainId, null).stream()
                .filter(p -> ownerProjectId.equals(p.getId()))
                .findFirst()
                .ifPresent(p -> out.put("ownerProjectName", p.getName()));
        return out;
    }

    // ──────────────────────────── Subscriptions ────────────────────────────

    private ObjectNode createSubscription(String region, String domainId, String requestId, JsonNode principal,
                                          JsonNode listing) {
        BigDecimal now = now();
        ObjectNode subscription = JSON.objectNode();
        subscription.put("id", DataZoneService.randomId());
        subscription.put("createdBy", account());
        subscription.put("updatedBy", account());
        subscription.put("domainId", domainId);
        subscription.put("status", "APPROVED");
        subscription.put("createdAt", now);
        subscription.put("updatedAt", now);
        subscription.set("subscribedPrincipal", principal.deepCopy());
        subscription.set("subscribedListing", listing.deepCopy());
        subscription.put("subscriptionRequestId", requestId);
        subscription.put("retainPermissions", false);
        put(SUBSCRIPTION, region, domainId, subscription.path("id").asText(), subscription);
        return subscription;
    }

    public synchronized ObjectNode getSubscription(String region, String domainIdentifier, String identifier) {
        return require(SUBSCRIPTION, region, domainId(region, domainIdentifier), identifier, "Subscription")
                .deepCopy();
    }

    public synchronized ObjectNode cancelSubscription(String region, String domainIdentifier, String identifier) {
        return end(region, domainId(region, domainIdentifier), identifier, "CANCELLED", false);
    }

    public synchronized ObjectNode revokeSubscription(String region, String domainIdentifier, String identifier,
                                                      JsonNode request) {
        return end(region, domainId(region, domainIdentifier), identifier, "REVOKED",
                request.path("retainPermissions").asBoolean(false));
    }

    private ObjectNode end(String region, String domainId, String identifier, String status,
                           boolean retainPermissions) {
        ObjectNode subscription = require(SUBSCRIPTION, region, domainId, identifier, "Subscription");
        if (!"APPROVED".equals(subscription.path("status").asText())) {
            throw conflict("Subscription " + identifier + " is " + subscription.path("status").asText() + ".");
        }
        subscription.put("status", status);
        subscription.put("retainPermissions", retainPermissions);
        touch(subscription);
        put(SUBSCRIPTION, region, domainId, identifier, subscription);
        if (!retainPermissions) {
            for (ObjectNode grant : list(GRANT, region, domainId)) {
                if (identifier.equals(grant.path("subscriptionId").asText())) {
                    grant.withArray("assets").forEach(asset -> ((ObjectNode) asset).put("status", "REVOKED"));
                    touch(grant);
                    put(GRANT, region, domainId, grant.path("id").asText(), grant);
                }
            }
        }
        return subscription.deepCopy();
    }

    public synchronized List<ObjectNode> listSubscriptions(String region, String domainIdentifier,
                                                           Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        requireEnum(query.get("status"), "status", Set.of("APPROVED", "REVOKED", "CANCELLED"));
        return sorted(list(SUBSCRIPTION, region, domainId).stream()
                .filter(s -> matches(query.get("status"), s.path("status")))
                .filter(s -> matches(query.get("subscriptionRequestIdentifier"), s.path("subscriptionRequestId")))
                .filter(s -> matches(query.get("subscribedListingId"), s.path("subscribedListing").path("id")))
                .filter(s -> matches(query.get("approverProjectId"),
                        s.path("subscribedListing").path("ownerProjectId")))
                .filter(s -> principalMatches(JSON.arrayNode().add(s.path("subscribedPrincipal")), query))
                .toList(), query);
    }

    // ──────────────────────────── Subscription targets ────────────────────────────

    public synchronized ObjectNode createSubscriptionTarget(String region, String domainIdentifier,
                                                            String environmentIdentifier, JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode environment = environments.getEnvironment(region, domainId, environmentIdentifier);
        String name = requiredText(request, "name");
        String type = requiredText(request, "type");
        for (String member : List.of("subscriptionTargetConfig", "authorizedPrincipals", "applicableAssetTypes")) {
            if (!request.path(member).isArray()) {
                throw required(member);
            }
        }
        String role = requiredText(request, "manageAccessRole");
        requireEnum(text(request, "subscriptionGrantCreationMode"), "subscriptionGrantCreationMode",
                Set.of("AUTOMATIC", "MANUAL"));
        boolean taken = targets(region, domainId, environmentIdentifier).stream()
                .anyMatch(t -> name.equals(t.path("name").asText()));
        if (taken) {
            throw conflict("A subscription target with name " + name + " already exists.");
        }
        BigDecimal now = now();
        ObjectNode target = JSON.objectNode();
        target.put("id", DataZoneService.randomId());
        target.set("authorizedPrincipals", request.get("authorizedPrincipals").deepCopy());
        target.put("domainId", domainId);
        target.put("projectId", environment.path("projectId").asText());
        target.put("environmentId", environmentIdentifier);
        target.put("name", name);
        target.put("type", type);
        target.put("createdBy", account());
        target.put("updatedBy", account());
        target.put("createdAt", now);
        target.put("updatedAt", now);
        target.put("manageAccessRole", role);
        target.set("applicableAssetTypes", request.get("applicableAssetTypes").deepCopy());
        target.set("subscriptionTargetConfig", request.get("subscriptionTargetConfig").deepCopy());
        target.put("provider", request.path("provider").asText(PROVIDER));
        target.put("subscriptionGrantCreationMode", request.path("subscriptionGrantCreationMode").asText("AUTOMATIC"));
        put(TARGET, region, domainId, target.path("id").asText(), target);
        return target.deepCopy();
    }

    public synchronized ObjectNode getSubscriptionTarget(String region, String domainIdentifier,
                                                         String environmentIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        return requireTarget(region, domainId, environmentIdentifier, identifier).deepCopy();
    }

    public synchronized ObjectNode updateSubscriptionTarget(String region, String domainIdentifier,
                                                            String environmentIdentifier, String identifier,
                                                            JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode target = requireTarget(region, domainId, environmentIdentifier, identifier);
        String name = text(request, "name");
        if (name != null && !name.equals(target.path("name").asText()) && targets(region, domainId,
                environmentIdentifier).stream().anyMatch(t -> name.equals(t.path("name").asText()))) {
            throw conflict("A subscription target with name " + name + " already exists.");
        }
        requireEnum(text(request, "subscriptionGrantCreationMode"), "subscriptionGrantCreationMode",
                Set.of("AUTOMATIC", "MANUAL"));
        copy(request, target, "name", "authorizedPrincipals", "applicableAssetTypes", "subscriptionTargetConfig",
                "manageAccessRole", "provider", "subscriptionGrantCreationMode");
        touch(target);
        put(TARGET, region, domainId, identifier, target);
        return target.deepCopy();
    }

    public synchronized void deleteSubscriptionTarget(String region, String domainIdentifier,
                                                      String environmentIdentifier, String identifier) {
        String domainId = domainId(region, domainIdentifier);
        requireTarget(region, domainId, environmentIdentifier, identifier);
        boolean inUse = list(GRANT, region, domainId).stream()
                .anyMatch(g -> identifier.equals(g.path("subscriptionTargetId").asText()));
        if (inUse) {
            throw conflict("Subscription target " + identifier + " still has subscription grants.");
        }
        delete(TARGET, region, domainId, identifier);
    }

    public synchronized List<ObjectNode> listSubscriptionTargets(String region, String domainIdentifier,
                                                                 String environmentIdentifier,
                                                                 Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        environments.getEnvironment(region, domainId, environmentIdentifier);
        return sorted(targets(region, domainId, environmentIdentifier), query);
    }

    private List<ObjectNode> targets(String region, String domainId, String environmentId) {
        return list(TARGET, region, domainId).stream()
                .filter(t -> environmentId.equals(t.path("environmentId").asText()))
                .toList();
    }

    private ObjectNode requireTarget(String region, String domainId, String environmentId, String identifier) {
        environments.getEnvironment(region, domainId, environmentId);
        ObjectNode target = require(TARGET, region, domainId, identifier, "Subscription target");
        if (!environmentId.equals(target.path("environmentId").asText())) {
            throw notFound("Subscription target " + identifier + " not found.");
        }
        return target;
    }

    // ──────────────────────────── Subscription grants ────────────────────────────

    public synchronized ObjectNode createSubscriptionGrant(String region, String domainIdentifier,
                                                           JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        String environmentId = requiredText(request, "environmentIdentifier");
        ObjectNode environment = environments.getEnvironment(region, domainId, environmentId);
        JsonNode granted = requiredNode(request, "grantedEntity");
        if (!"listing".equals(unionMember(granted, "grantedEntity"))) {
            throw SmithyValidationErrors.validationException("grantedEntity must name a listing.");
        }
        String listingId = requiredText(granted.get("listing"), "identifier");
        String listingRevision = requiredText(granted.get("listing"), "revision");
        String targetId = text(request, "subscriptionTargetIdentifier");
        ObjectNode target = targetId != null ? requireTarget(region, domainId, environmentId, targetId)
                : targets(region, domainId, environmentId).stream().findFirst()
                        .orElseThrow(() -> notFound("Environment " + environmentId + " has no subscription target."));
        String projectId = environment.path("projectId").asText();
        String subscriptionId = list(SUBSCRIPTION, region, domainId).stream()
                .filter(s -> "APPROVED".equals(s.path("status").asText())
                        && listingId.equals(s.path("subscribedListing").path("id").asText())
                        && projectId.equals(s.path("subscribedPrincipal").path("project").path("id").asText()))
                .map(s -> s.path("id").asText())
                .findFirst().orElse(null);
        ObjectNode grant = grant(region, domainId, environmentId, target, listingId, listingRevision,
                subscriptionId, request.path("assetTargetNames"));
        return grant.deepCopy();
    }

    private ObjectNode grant(String region, String domainId, String environmentId, ObjectNode target,
                             String listingId, String listingRevision, String subscriptionId,
                             JsonNode assetTargetNames) {
        ObjectNode listing = catalog.findListing(region, domainId, listingId)
                .orElseThrow(() -> notFound("Listing " + listingId + " not found."));
        BigDecimal now = now();
        ArrayNode assets = JSON.arrayNode();
        for (ObjectNode assetListing : assetListings(region, domainId, listing)) {
            String assetId = assetListing.path("entityId").asText();
            ObjectNode asset = assets.addObject();
            asset.put("assetId", assetId);
            asset.put("assetRevision", assetListing.path("entityRevision").asText());
            asset.put("status", "GRANTED");
            for (JsonNode name : assetTargetNames) {
                if (assetId.equals(name.path("assetId").asText())) {
                    asset.put("targetName", name.path("targetName").asText());
                }
            }
            asset.put("grantedTimestamp", now);
        }
        ObjectNode grant = JSON.objectNode();
        grant.put("id", DataZoneService.randomId());
        grant.put("createdBy", account());
        grant.put("updatedBy", account());
        grant.put("domainId", domainId);
        grant.put("createdAt", now);
        grant.put("updatedAt", now);
        grant.put("environmentId", environmentId);
        grant.put("subscriptionTargetId", target.path("id").asText());
        grant.putObject("grantedEntity").putObject("listing").put("id", listingId).put("revision", listingRevision);
        grant.put("status", "COMPLETED");
        grant.set("assets", assets);
        if (subscriptionId != null) {
            grant.put("subscriptionId", subscriptionId);
        }
        grant.put("projectId", target.path("projectId").asText());
        put(GRANT, region, domainId, grant.path("id").asText(), grant);
        return publicGrant(grant);
    }

    private List<ObjectNode> assetListings(String region, String domainId, ObjectNode listing) {
        if ("ASSET".equals(listing.path("entityType").asText())) {
            return List.of(listing);
        }
        List<ObjectNode> out = new ArrayList<>();
        for (JsonNode item : listing.path("item").path("dataProductListing").path("items")) {
            catalog.findListing(region, domainId, item.path("listingId").asText()).ifPresent(out::add);
        }
        return out;
    }

    private void grantToAutomaticTargets(String region, String domainId, String projectId, ObjectNode subscription) {
        String listingId = subscription.path("subscribedListing").path("id").asText();
        ObjectNode listing = catalog.findListing(region, domainId, listingId).orElse(null);
        if (listing == null) {
            return;
        }
        List<String> assetTypes = assetListings(region, domainId, listing).stream()
                .map(l -> l.path("item").path("assetListing").path("assetType").asText())
                .toList();
        for (ObjectNode target : list(TARGET, region, domainId)) {
            boolean applies = projectId.equals(target.path("projectId").asText())
                    && !"MANUAL".equals(target.path("subscriptionGrantCreationMode").asText())
                    && applicable(target, assetTypes);
            if (applies) {
                grant(region, domainId, target.path("environmentId").asText(), target, listingId,
                        subscription.path("subscribedListing").path("revision").asText(),
                        subscription.path("id").asText(), JSON.arrayNode());
            }
        }
    }

    private static boolean applicable(ObjectNode target, List<String> assetTypes) {
        List<String> applicable = new ArrayList<>();
        target.path("applicableAssetTypes").forEach(t -> applicable.add(t.asText()));
        return assetTypes.stream().anyMatch(applicable::contains);
    }

    public synchronized ObjectNode getSubscriptionGrant(String region, String domainIdentifier, String identifier) {
        return publicGrant(require(GRANT, region, domainId(region, domainIdentifier), identifier,
                "Subscription grant"));
    }

    public synchronized ObjectNode deleteSubscriptionGrant(String region, String domainIdentifier,
                                                           String identifier) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode grant = require(GRANT, region, domainId, identifier, "Subscription grant");
        delete(GRANT, region, domainId, identifier);
        return publicGrant(grant);
    }

    public synchronized ObjectNode updateSubscriptionGrantStatus(String region, String domainIdentifier,
                                                                 String identifier, String assetIdentifier,
                                                                 JsonNode request) {
        String domainId = domainId(region, domainIdentifier);
        ObjectNode grant = require(GRANT, region, domainId, identifier, "Subscription grant");
        String status = requireEnum(requiredText(request, "status"), "status", GRANT_STATUSES);
        ObjectNode asset = null;
        for (JsonNode candidate : grant.path("assets")) {
            if (assetIdentifier.equals(candidate.path("assetId").asText())) {
                asset = (ObjectNode) candidate;
            }
        }
        if (asset == null) {
            throw notFound("Asset " + assetIdentifier + " is not part of subscription grant " + identifier + ".");
        }
        asset.put("status", status);
        copy(request, asset, "failureCause", "targetName");
        if (status.endsWith("_FAILED")) {
            asset.put("failureTimestamp", now());
        } else if ("GRANTED".equals(status)) {
            asset.put("grantedTimestamp", now());
        }
        grant.put("status", overallStatus(grant.path("assets")));
        touch(grant);
        put(GRANT, region, domainId, identifier, grant);
        return publicGrant(grant);
    }

    public synchronized List<ObjectNode> listSubscriptionGrants(String region, String domainIdentifier,
                                                                Map<String, String> query) {
        String domainId = domainId(region, domainIdentifier);
        return sorted(list(GRANT, region, domainId).stream()
                .filter(g -> matches(query.get("environmentId"), g.path("environmentId")))
                .filter(g -> matches(query.get("subscriptionTargetId"), g.path("subscriptionTargetId")))
                .filter(g -> matches(query.get("subscribedListingId"), g.path("grantedEntity").path("listing")
                        .path("id")))
                .filter(g -> matches(query.get("subscriptionId"), g.path("subscriptionId")))
                .filter(g -> matches(query.get("owningProjectId"), g.path("projectId")))
                .map(DataZoneSubscriptionService::publicGrant)
                .toList(), query);
    }

    static String overallStatus(JsonNode assets) {
        boolean grantFailed = false;
        boolean revokeFailed = false;
        boolean busy = false;
        for (JsonNode asset : assets) {
            String status = asset.path("status").asText();
            grantFailed |= "GRANT_FAILED".equals(status);
            revokeFailed |= "REVOKE_FAILED".equals(status);
            busy |= status.endsWith("_PENDING") || status.endsWith("_IN_PROGRESS");
        }
        if (grantFailed && revokeFailed) {
            return "GRANT_AND_REVOKE_FAILED";
        }
        if (grantFailed) {
            return "GRANT_FAILED";
        }
        if (revokeFailed) {
            return "REVOKE_FAILED";
        }
        return busy ? "IN_PROGRESS" : "COMPLETED";
    }

    private static ObjectNode publicGrant(ObjectNode grant) {
        ObjectNode out = grant.deepCopy();
        out.remove("projectId");
        return out;
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private void touch(ObjectNode record) {
        record.put("updatedAt", now());
        record.put("updatedBy", account());
    }

    private static boolean matches(String expected, JsonNode actual) {
        return expected == null || expected.equals(actual.asText());
    }

    private static boolean principalMatches(JsonNode principals, Map<String, String> query) {
        List<Predicate<JsonNode>> checks = new ArrayList<>();
        if (query.get("owningProjectId") != null) {
            checks.add(p -> query.get("owningProjectId").equals(p.path("project").path("id").asText()));
        }
        if (query.get("owningUserId") != null) {
            checks.add(p -> query.get("owningUserId").equals(p.path("user").path("id").asText()));
        }
        if (query.get("owningGroupId") != null) {
            checks.add(p -> query.get("owningGroupId").equals(p.path("group").path("id").asText()));
        }
        if (query.get("owningIamPrincipalArn") != null) {
            checks.add(p -> query.get("owningIamPrincipalArn").equals(p.path("iam").path("principalArn").asText()));
        }
        for (Predicate<JsonNode> check : checks) {
            boolean any = false;
            for (JsonNode principal : principals) {
                any |= check.test(principal);
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static List<ObjectNode> sorted(List<ObjectNode> records, Map<String, String> query) {
        requireEnum(query.get("sortBy"), "sortBy", SORT_KEYS);
        requireEnum(query.get("sortOrder"), "sortOrder", SORT_ORDERS);
        String member = "UPDATED_AT".equals(query.get("sortBy")) ? "updatedAt" : "createdAt";
        Comparator<ObjectNode> order = Comparator.comparing(r -> r.path(member).decimalValue());
        return records.stream()
                .sorted("ASCENDING".equals(query.get("sortOrder")) ? order : order.reversed())
                .toList();
    }
}
