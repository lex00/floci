package io.github.hectorvent.floci.services.workspacesweb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Amazon WorkSpaces Secure Browser control plane (signing name {@code workspaces-web}). A portal
 * reports {@code Active} once it has browser, network and user settings. No browser sessions run.
 */
@ApplicationScoped
public class WorkSpacesWebService implements TagHandler, Resettable {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final TypeReference<Map<String, ObjectNode>> NODES = new TypeReference<>() {};
    private static final Set<String> NEVER_STORED =
            Set.of("tags", "clientToken", "brandingConfigurationInput", "certificateList",
                    "certificatesToAdd", "certificatesToDelete");

    /**
     * Describes one settings resource type by its URL collection and response member names. The
     * last three components give the list summary members, the members a create requires, and
     * whether the type reports a creation date.
     */
    public record Kind(String collection, String member, String listMember, String noun, String listAction,
                       List<String> summary, List<String> required, boolean creationDate) {
        public String arnMember() {
            return member + "Arn";
        }
    }

    public static final Map<String, Kind> KINDS = kinds(
            new Kind("browserSettings", "browserSettings", "browserSettings", "BrowserSettings", "ListBrowserSettings",
                    List.of("browserSettingsArn"), List.of(), false),
            new Kind("dataProtectionSettings", "dataProtectionSettings", "dataProtectionSettings",
                    "DataProtectionSettings", "ListDataProtectionSettings",
                    List.of("dataProtectionSettingsArn", "displayName", "description", "creationDate"),
                    List.of(), true),
            new Kind("ipAccessSettings", "ipAccessSettings", "ipAccessSettings", "IpAccessSettings",
                    "ListIpAccessSettings",
                    List.of("ipAccessSettingsArn", "displayName", "description", "creationDate"),
                    List.of("ipRules"), true),
            new Kind("networkSettings", "networkSettings", "networkSettings", "NetworkSettings", "ListNetworkSettings",
                    List.of("networkSettingsArn", "vpcId"), List.of("vpcId", "subnetIds", "securityGroupIds"), false),
            new Kind("sessionLoggers", "sessionLogger", "sessionLoggers", "SessionLogger", "ListSessionLoggers",
                    List.of("sessionLoggerArn", "logConfiguration", "displayName", "creationDate"),
                    List.of("eventFilter", "logConfiguration"), true),
            new Kind("trustStores", "trustStore", "trustStores", "TrustStore", "ListTrustStores",
                    List.of("trustStoreArn"), List.of("certificateList"), false),
            new Kind("userAccessLoggingSettings", "userAccessLoggingSettings", "userAccessLoggingSettings",
                    "UserAccessLoggingSettings", "ListUserAccessLoggingSettings",
                    List.of("userAccessLoggingSettingsArn", "kinesisStreamArn"), List.of("kinesisStreamArn"), false),
            new Kind("userSettings", "userSettings", "userSettings", "UserSettings", "ListUserSettings",
                    List.of("userSettingsArn", "copyAllowed", "pasteAllowed", "downloadAllowed", "uploadAllowed",
                            "printAllowed", "disconnectTimeoutInMinutes", "idleDisconnectTimeoutInMinutes",
                            "cookieSynchronizationConfiguration", "deepLinkAllowed", "toolbarConfiguration",
                            "brandingConfiguration", "webAuthnAllowed"),
                    List.of("copyAllowed", "pasteAllowed", "downloadAllowed", "uploadAllowed", "printAllowed"),
                    false));

    /** The portal sub-path each Associate and Disassociate operation uses, mapped to its kind. */
    public static final Map<String, Kind> ASSOCIATION_PATHS = Map.of(
            "browserSettings", KINDS.get("browserSettings"),
            "dataProtectionSettings", KINDS.get("dataProtectionSettings"),
            "ipAccessSettings", KINDS.get("ipAccessSettings"),
            "networkSettings", KINDS.get("networkSettings"),
            "sessionLogger", KINDS.get("sessionLoggers"),
            "trustStores", KINDS.get("trustStores"),
            "userAccessLoggingSettings", KINDS.get("userAccessLoggingSettings"),
            "userSettings", KINDS.get("userSettings"));

    private static final String PORTAL_ARN = "portalArn";
    private static final Set<String> IDENTITY_PROVIDER_TYPES =
            Set.of("SAML", "Facebook", "Google", "LoginWithAmazon", "SignInWithApple", "OIDC");

    private static final List<String> PORTAL_SUMMARY_EXCLUDED =
            List.of("statusReason", "customerManagedKey", "additionalEncryptionContext");

    private final Map<String, StorageBackend<String, ObjectNode>> stores = new LinkedHashMap<>();
    private final StorageBackend<String, ObjectNode> portals;
    private final StorageBackend<String, ObjectNode> certificates;
    private final StorageBackend<String, ObjectNode> identityProviders;
    private final StorageBackend<String, ObjectNode> tags;
    private final RegionResolver regionResolver;

    @Inject
    public WorkSpacesWebService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(name -> storageFactory.create("workspacesweb", "workspaces-web-" + name + ".json", NODES),
                regionResolver);
    }

    WorkSpacesWebService(Function<String, StorageBackend<String, ObjectNode>> storeFor,
                         RegionResolver regionResolver) {
        this.regionResolver = regionResolver;
        for (Kind kind : KINDS.values()) {
            stores.put(kind.collection(), storeFor.apply(kind.collection()));
        }
        this.portals = storeFor.apply("portals");
        this.certificates = storeFor.apply("certificates");
        this.identityProviders = storeFor.apply("identity-providers");
        this.tags = storeFor.apply("tags");
    }

    @Override
    public void clear() {
        stores.values().forEach(StorageBackend::clear);
        portals.clear();
        certificates.clear();
        identityProviders.clear();
        tags.clear();
    }

    // ──────────────────────────── Settings resources ────────────────────────────

    public synchronized String create(Kind kind, JsonNode request, String region) {
        for (String member : kind.required()) {
            requireMember(request, member);
        }
        String arn = regionResolver.buildArn("workspaces-web", region, kind.member() + "/" + UUID.randomUUID());
        ObjectNode resource = JSON.objectNode();
        resource.put(kind.arnMember(), arn);
        resource.putArray("associatedPortalArns");
        if (kind.creationDate()) {
            resource.put("creationDate", now());
        }
        copyMembers(request, resource);
        if (request.get("brandingConfigurationInput") instanceof ObjectNode branding) {
            resource.set("brandingConfiguration", brandingConfiguration(branding, null));
        }
        if ("trustStores".equals(kind.collection())) {
            ObjectNode stored = JSON.objectNode();
            addCertificates(stored, request.path("certificateList"));
            certificates.put(arn, stored);
        }
        stores.get(kind.collection()).put(arn, resource);
        writeTags(arn, request.path("tags"));
        return arn;
    }

    public ObjectNode get(Kind kind, String arn) {
        return require(kind, arn).deepCopy();
    }

    public List<ObjectNode> list(Kind kind, String region) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode resource : inRegion(stores.get(kind.collection()), kind.arnMember(), region)) {
            ObjectNode summary = JSON.objectNode();
            for (String member : kind.summary()) {
                if (resource.has(member)) {
                    summary.set(member, resource.get(member).deepCopy());
                }
            }
            result.add(summary);
        }
        return result;
    }

    public synchronized ObjectNode update(Kind kind, String arn, JsonNode request) {
        ObjectNode resource = require(kind, arn);
        ObjectNode changes = request.deepCopy();
        changes.remove(kind.arnMember());
        copyMembers(changes, resource);
        if (request.get("brandingConfigurationInput") instanceof ObjectNode branding) {
            resource.set("brandingConfiguration",
                    brandingConfiguration(branding, resource.get("brandingConfiguration")));
        }
        if ("trustStores".equals(kind.collection())) {
            ObjectNode stored = certificates.get(arn).orElse(JSON.objectNode());
            addCertificates(stored, request.path("certificatesToAdd"));
            request.path("certificatesToDelete").forEach(thumbprint -> stored.remove(thumbprint.asText()));
            certificates.put(arn, stored);
        }
        stores.get(kind.collection()).put(arn, resource);
        return resource.deepCopy();
    }

    public synchronized void delete(Kind kind, String arn) {
        ObjectNode resource = require(kind, arn);
        if (!resource.path("associatedPortalArns").isEmpty()) {
            throw new AwsException("ConflictException",
                    "The " + kind.member() + " " + arn + " is associated with a portal.", 409);
        }
        stores.get(kind.collection()).delete(arn);
        certificates.delete(arn);
        tags.delete(arn);
    }

    private ObjectNode require(Kind kind, String arn) {
        return stores.get(kind.collection()).get(arn == null ? "" : arn)
                .orElseThrow(() -> notFound(arn));
    }

    // ──────────────────────────── Trust store certificates ────────────────────────────

    public List<ObjectNode> listTrustStoreCertificates(String arn) {
        require(KINDS.get("trustStores"), arn);
        List<ObjectNode> result = new ArrayList<>();
        certificates.get(arn).orElse(JSON.objectNode()).fields().forEachRemaining(e -> {
            ObjectNode summary = ((ObjectNode) e.getValue()).deepCopy();
            summary.remove("body");
            result.add(summary);
        });
        return result;
    }

    public ObjectNode getTrustStoreCertificate(String arn, String thumbprint) {
        require(KINDS.get("trustStores"), arn);
        JsonNode certificate = certificates.get(arn).orElse(JSON.objectNode()).get(thumbprint == null ? "" : thumbprint);
        if (certificate == null) {
            throw notFound(thumbprint);
        }
        return certificate.deepCopy();
    }

    private static void addCertificates(ObjectNode stored, JsonNode bodies) {
        for (JsonNode body : bodies) {
            ObjectNode certificate = parseCertificate(body.asText());
            stored.set(certificate.path("thumbprint").asText(), certificate);
        }
    }

    private static ObjectNode parseCertificate(String base64) {
        try {
            byte[] bytes = Base64.getDecoder().decode(base64.trim());
            X509Certificate x509 = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(bytes));
            ObjectNode certificate = JSON.objectNode();
            certificate.put("thumbprint", HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(x509.getEncoded())));
            certificate.put("subject", x509.getSubjectX500Principal().getName());
            certificate.put("issuer", x509.getIssuerX500Principal().getName());
            certificate.put("notValidBefore", x509.getNotBefore().toInstant().getEpochSecond());
            certificate.put("notValidAfter", x509.getNotAfter().toInstant().getEpochSecond());
            certificate.put("body", base64.trim());
            return certificate;
        } catch (IllegalArgumentException | CertificateException | NoSuchAlgorithmException e) {
            throw new AwsException("ValidationException", "The certificate could not be parsed: " + e.getMessage(),
                    400);
        }
    }

    // ──────────────────────────── Portals ────────────────────────────

    public synchronized ObjectNode createPortal(JsonNode request, String region) {
        String id = UUID.randomUUID().toString();
        ObjectNode portal = JSON.objectNode();
        portal.put("portalArn", regionResolver.buildArn("workspaces-web", region, "portal/" + id));
        portal.put("rendererType", "AppStream");
        portal.put("browserType", "Chrome");
        portal.put("portalEndpoint", id + ".workspaces-web.com");
        portal.put("creationDate", now());
        portal.put("authenticationType", "Standard");
        portal.put("instanceType", "standard.regular");
        copyMembers(request, portal);
        refreshPortalStatus(portal);
        portals.put(portal.path("portalArn").asText(), portal);
        writeTags(portal.path("portalArn").asText(), request.path("tags"));
        return portal.deepCopy();
    }

    public ObjectNode getPortal(String arn) {
        return requirePortal(arn).deepCopy();
    }

    public List<ObjectNode> listPortals(String region) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode portal : inRegion(portals, "portalArn", region)) {
            ObjectNode summary = portal.deepCopy();
            PORTAL_SUMMARY_EXCLUDED.forEach(summary::remove);
            result.add(summary);
        }
        return result;
    }

    public synchronized ObjectNode updatePortal(String arn, JsonNode request) {
        ObjectNode portal = requirePortal(arn);
        for (String member : List.of("displayName", "authenticationType", "instanceType",
                "maxConcurrentSessions", "portalCustomDomain")) {
            if (request.hasNonNull(member)) {
                portal.set(member, request.get(member).deepCopy());
            }
        }
        portals.put(arn, portal);
        return portal.deepCopy();
    }

    public synchronized void deletePortal(String arn) {
        ObjectNode portal = requirePortal(arn);
        for (Kind kind : KINDS.values()) {
            String associated = portal.path(kind.arnMember()).asText(null);
            if (associated != null) {
                unlink(kind, associated, arn);
            }
        }
        for (ObjectNode provider : identityProviders.scan(k -> true)) {
            if (arn.equals(provider.path(PORTAL_ARN).asText())) {
                String providerArn = provider.path("identityProviderArn").asText();
                identityProviders.delete(providerArn);
                tags.delete(providerArn);
            }
        }
        portals.delete(arn);
        tags.delete(arn);
    }

    public ObjectNode getPortalServiceProviderMetadata(String arn) {
        ObjectNode portal = requirePortal(arn);
        String endpoint = "https://" + portal.path("portalEndpoint").asText();
        String metadata = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" entityID=\""
                + endpoint + "/saml\"><md:SPSSODescriptor AuthnRequestsSigned=\"false\" "
                + "protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\">"
                + "<md:AssertionConsumerService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST\" "
                + "Location=\"" + endpoint + "/saml\" index=\"1\"/></md:SPSSODescriptor></md:EntityDescriptor>";
        return JSON.objectNode().put("portalArn", arn).put("serviceProviderSamlMetadata", metadata);
    }

    // ──────────────────────────── Identity providers ────────────────────────────

    public synchronized String createIdentityProvider(JsonNode request) {
        requireMember(request, PORTAL_ARN);
        requireMember(request, "identityProviderName");
        requireMember(request, "identityProviderType");
        requireMember(request, "identityProviderDetails");
        String portalArn = request.path(PORTAL_ARN).asText();
        requirePortal(portalArn);
        String type = request.path("identityProviderType").asText();
        if (!IDENTITY_PROVIDER_TYPES.contains(type)) {
            throw new AwsException("ValidationException", "Invalid identityProviderType " + type, 400);
        }
        String portalId = portalArn.substring(portalArn.lastIndexOf('/') + 1);
        String region = regionOf(portalArn);
        String arn = regionResolver.buildArn("workspaces-web", region,
                "identityProvider/" + portalId + "/" + UUID.randomUUID());
        ObjectNode provider = JSON.objectNode();
        provider.put("identityProviderArn", arn);
        provider.put("identityProviderName", request.path("identityProviderName").asText());
        provider.put("identityProviderType", type);
        provider.set("identityProviderDetails", request.path("identityProviderDetails").deepCopy());
        provider.put(PORTAL_ARN, portalArn);
        identityProviders.put(arn, provider);
        writeTags(arn, request.path("tags"));
        return arn;
    }

    public ObjectNode getIdentityProvider(String arn) {
        ObjectNode provider = requireIdentityProvider(arn).deepCopy();
        provider.remove(PORTAL_ARN);
        return provider;
    }

    public synchronized ObjectNode updateIdentityProvider(String arn, JsonNode request) {
        ObjectNode provider = requireIdentityProvider(arn);
        if (request.hasNonNull("identityProviderType")) {
            String type = request.path("identityProviderType").asText();
            if (!IDENTITY_PROVIDER_TYPES.contains(type)) {
                throw new AwsException("ValidationException", "Invalid identityProviderType " + type, 400);
            }
            provider.put("identityProviderType", type);
        }
        if (request.hasNonNull("identityProviderName")) {
            provider.put("identityProviderName", request.path("identityProviderName").asText());
        }
        if (request.get("identityProviderDetails") instanceof ObjectNode details) {
            provider.set("identityProviderDetails", details.deepCopy());
        }
        identityProviders.put(arn, provider);
        return getIdentityProvider(arn);
    }

    public synchronized void deleteIdentityProvider(String arn) {
        requireIdentityProvider(arn);
        identityProviders.delete(arn);
        tags.delete(arn);
    }

    public List<ObjectNode> listIdentityProviders(String portalArn) {
        requirePortal(portalArn);
        return identityProviders.scan(k -> true).stream()
                .filter(p -> portalArn.equals(p.path(PORTAL_ARN).asText()))
                .sorted(Comparator.comparing((ObjectNode n) -> n.path("identityProviderArn").asText()))
                .map(p -> JSON.objectNode()
                        .put("identityProviderArn", p.path("identityProviderArn").asText())
                        .put("identityProviderName", p.path("identityProviderName").asText())
                        .put("identityProviderType", p.path("identityProviderType").asText()))
                .toList();
    }

    private ObjectNode requireIdentityProvider(String arn) {
        return identityProviders.get(arn == null ? "" : arn).orElseThrow(() -> notFound(arn));
    }

    // ──────────────────────────── Sessions ────────────────────────────

    /** No browser sessions run, so a portal never has sessions to list. */
    public List<ObjectNode> listSessions(String portalId, String region) {
        requirePortalById(portalId, region);
        return List.of();
    }

    public ObjectNode getSession(String portalId, String sessionId, String region) {
        requirePortalById(portalId, region);
        throw notFound(sessionId);
    }

    private ObjectNode requirePortalById(String portalId, String region) {
        return requirePortal(regionResolver.buildArn("workspaces-web", region, "portal/" + portalId));
    }

    public synchronized void associate(String portalArn, Kind kind, String resourceArn) {
        ObjectNode portal = requirePortal(portalArn);
        ObjectNode resource = require(kind, resourceArn);
        String previous = portal.path(kind.arnMember()).asText(null);
        if (previous != null && !previous.equals(resourceArn)) {
            unlink(kind, previous, portalArn);
        }
        ArrayNode associated = resource.get("associatedPortalArns") instanceof ArrayNode existing
                ? existing : resource.putArray("associatedPortalArns");
        boolean present = false;
        for (JsonNode existing : associated) {
            present |= portalArn.equals(existing.asText());
        }
        if (!present) {
            associated.add(portalArn);
        }
        stores.get(kind.collection()).put(resourceArn, resource);
        portal.put(kind.arnMember(), resourceArn);
        refreshPortalStatus(portal);
        portals.put(portalArn, portal);
    }

    public synchronized void disassociate(String portalArn, Kind kind) {
        ObjectNode portal = requirePortal(portalArn);
        String associated = portal.path(kind.arnMember()).asText(null);
        if (associated == null) {
            throw notFound(kind.member() + " for " + portalArn);
        }
        unlink(kind, associated, portalArn);
        portal.remove(kind.arnMember());
        refreshPortalStatus(portal);
        portals.put(portalArn, portal);
    }

    private void unlink(Kind kind, String resourceArn, String portalArn) {
        stores.get(kind.collection()).get(resourceArn).ifPresent(resource -> {
            ArrayNode kept = JSON.arrayNode();
            resource.path("associatedPortalArns").forEach(p -> {
                if (!portalArn.equals(p.asText())) {
                    kept.add(p);
                }
            });
            resource.set("associatedPortalArns", kept);
            stores.get(kind.collection()).put(resourceArn, resource);
        });
    }

    private static void refreshPortalStatus(ObjectNode portal) {
        boolean complete = portal.has("browserSettingsArn") && portal.has("networkSettingsArn")
                && portal.has("userSettingsArn");
        portal.put("portalStatus", complete ? "Active" : "Incomplete");
    }

    private ObjectNode requirePortal(String arn) {
        return portals.get(arn == null ? "" : arn).orElseThrow(() -> notFound(arn));
    }

    // ──────────────────────────── Tags ────────────────────────────

    @Override
    public String serviceKey() {
        return "workspaces-web";
    }

    @Override
    public boolean tagsBodyIsList() {
        return true;
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
        requireTaggable(arn);
        Map<String, String> result = new LinkedHashMap<>();
        tags.get(arn).orElse(JSON.objectNode()).fields()
                .forEachRemaining(e -> result.put(e.getKey(), e.getValue().asText()));
        return result;
    }

    @Override
    public synchronized void tagResource(String region, String arn, Map<String, String> newTags) {
        requireTaggable(arn);
        ObjectNode current = tags.get(arn).orElse(JSON.objectNode());
        newTags.forEach(current::put);
        tags.put(arn, current);
    }

    @Override
    public synchronized void untagResource(String region, String arn, List<String> tagKeys) {
        requireTaggable(arn);
        ObjectNode current = tags.get(arn).orElse(JSON.objectNode());
        current.remove(tagKeys);
        tags.put(arn, current);
    }

    private void writeTags(String arn, JsonNode newTags) {
        ObjectNode current = JSON.objectNode();
        for (JsonNode tag : newTags) {
            current.put(tag.path("Key").asText(), tag.path("Value").asText(""));
        }
        tags.put(arn, current);
    }

    private void requireTaggable(String arn) {
        boolean exists = arn != null && (portals.get(arn).isPresent() || identityProviders.get(arn).isPresent()
                || stores.values().stream().anyMatch(store -> store.get(arn).isPresent()));
        if (!exists) {
            throw notFound(arn);
        }
    }

    // ──────────────────────────── Helpers ────────────────────────────

    private static ObjectNode brandingConfiguration(ObjectNode input, JsonNode previous) {
        ObjectNode result = previous instanceof ObjectNode existing ? existing.deepCopy() : JSON.objectNode();
        input.fields().forEachRemaining(e -> {
            String name = e.getKey();
            if (List.of("logo", "wallpaper", "favicon").contains(name)) {
                ObjectNode metadata = JSON.objectNode();
                String s3Uri = e.getValue().path("s3Uri").asText("");
                String extension = s3Uri.contains(".") ? s3Uri.substring(s3Uri.lastIndexOf('.') + 1) : "png";
                metadata.put("mimeType", "jpg".equals(extension) || "jpeg".equals(extension)
                        ? "image/jpeg" : "image/" + extension);
                metadata.put("fileExtension", extension);
                metadata.put("lastUploadTimestamp", now());
                result.set(name, metadata);
            } else {
                result.set(name, e.getValue().deepCopy());
            }
        });
        return result;
    }

    private static List<ObjectNode> inRegion(StorageBackend<String, ObjectNode> store, String arnMember,
                                             String region) {
        return store.scan(k -> region.equals(regionOf(k))).stream()
                .sorted(Comparator.comparing((ObjectNode n) -> n.path(arnMember).asText()))
                .toList();
    }

    private static String regionOf(String arn) {
        String[] parts = arn.split(":", 6);
        return parts.length > 3 ? parts[3] : "";
    }

    private static void copyMembers(JsonNode request, ObjectNode target) {
        request.fields().forEachRemaining(e -> {
            if (!NEVER_STORED.contains(e.getKey()) && !e.getValue().isNull()) {
                target.set(e.getKey(), e.getValue().deepCopy());
            }
        });
    }

    private static void requireMember(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || value.isNull() || (value.isTextual() && value.asText().isBlank())) {
            throw new AwsException("ValidationException", member + " is required.", 400);
        }
    }

    private static AwsException notFound(String identifier) {
        return new AwsException("ResourceNotFoundException", "Resource " + identifier + " not found.", 404);
    }

    private static double now() {
        return Instant.now().toEpochMilli() / 1000.0;
    }

    private static Map<String, Kind> kinds(Kind... kinds) {
        Map<String, Kind> result = new LinkedHashMap<>();
        for (Kind kind : kinds) {
            result.put(kind.collection(), kind);
        }
        return Map.copyOf(result);
    }
}
