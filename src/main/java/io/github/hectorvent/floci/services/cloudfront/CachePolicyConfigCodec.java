package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.core.common.XmlParser;
import io.github.hectorvent.floci.core.common.XmlParser.XmlElement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses and re-serializes the two policy configs whose members decide a CloudFront cache key.
 *
 * <p>{@code CachePolicyConfig} carries the three TTLs and a
 * {@code ParametersInCacheKeyAndForwardedToOrigin} block naming the headers, cookies and query
 * strings that form the key. {@code OriginRequestPolicyConfig} carries the same three
 * header/cookie/query-string blocks without the TTLs, deciding what CloudFront forwards to the
 * origin rather than what it keys on.
 *
 * <p>Both parse into the policy's generic {@code config} map, nested maps and lists mirroring the
 * wire, so the whole config round-trips through Get and List without a bespoke model. That is the
 * shape {@link ResponseHeadersPolicyConfigCodec} already uses for the third policy kind.
 */
public final class CachePolicyConfigCodec {

    /** CachePolicyConfig documents these two as the defaults for a config that omits them. */
    private static final long DEFAULT_TTL = 86400L;
    private static final long MAX_TTL = 31536000L;

    private static final Set<String> CACHE_HEADER_BEHAVIORS = Set.of("none", "whitelist");
    private static final Set<String> CACHE_COOKIE_BEHAVIORS =
            Set.of("none", "whitelist", "allExcept", "all");
    private static final Set<String> CACHE_QUERY_BEHAVIORS =
            Set.of("none", "whitelist", "allExcept", "all");
    private static final Set<String> ORIGIN_HEADER_BEHAVIORS = Set.of("none", "whitelist",
            "allViewer", "allViewerAndWhitelistCloudFront", "allExcept");
    private static final Set<String> ORIGIN_COOKIE_BEHAVIORS =
            Set.of("none", "whitelist", "all", "allExcept");
    private static final Set<String> ORIGIN_QUERY_BEHAVIORS =
            Set.of("none", "whitelist", "all", "allExcept");

    private CachePolicyConfigCodec() {
    }

    // ── cache policy ──────────────────────────────────────────────────────────

    public static Map<String, Object> parseCacheConfig(String body) {
        XmlElement root = XmlParser.extractElementTree(body, "CachePolicyConfig");
        Map<String, Object> config = new LinkedHashMap<>();
        long minTtl = longOf(root, "MinTTL", 0L);
        long defaultTtl = longOf(root, "DefaultTTL", Math.max(DEFAULT_TTL, minTtl));
        config.put("MinTTL", minTtl);
        config.put("DefaultTTL", defaultTtl);
        config.put("MaxTTL", longOf(root, "MaxTTL", Math.max(MAX_TTL, defaultTtl)));

        XmlElement params = root == null ? null : root.child("ParametersInCacheKeyAndForwardedToOrigin");
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("EnableAcceptEncodingGzip", booleanOf(params, "EnableAcceptEncodingGzip"));
        if (params != null && params.child("EnableAcceptEncodingBrotli") != null) {
            parameters.put("EnableAcceptEncodingBrotli",
                    booleanOf(params, "EnableAcceptEncodingBrotli"));
        }
        parameters.put("HeadersConfig", namedList(params, "HeadersConfig", "HeaderBehavior",
                "Headers", CACHE_HEADER_BEHAVIORS));
        parameters.put("CookiesConfig", namedList(params, "CookiesConfig", "CookieBehavior",
                "Cookies", CACHE_COOKIE_BEHAVIORS));
        parameters.put("QueryStringsConfig", namedList(params, "QueryStringsConfig",
                "QueryStringBehavior", "QueryStrings", CACHE_QUERY_BEHAVIORS));
        config.put("ParametersInCacheKeyAndForwardedToOrigin", parameters);
        return config;
    }

    public static String serializeCacheConfig(Map<String, Object> config) {
        // A policy persisted before the config was parsed, or one a provisioner built directly,
        // carries no config; report the documented defaults rather than failing the read.
        if (config == null) {
            config = Map.of();
        }
        XmlBuilder xml = new XmlBuilder()
                .elem("DefaultTTL", String.valueOf(config.getOrDefault("DefaultTTL", DEFAULT_TTL)))
                .elem("MaxTTL", String.valueOf(config.getOrDefault("MaxTTL", MAX_TTL)))
                .elem("MinTTL", String.valueOf(config.getOrDefault("MinTTL", 0L)))
                .start("ParametersInCacheKeyAndForwardedToOrigin");
        Map<String, Object> parameters = subMap(config, "ParametersInCacheKeyAndForwardedToOrigin");
        xml.elem("EnableAcceptEncodingGzip",
                String.valueOf(parameters.getOrDefault("EnableAcceptEncodingGzip", false)));
        if (parameters.containsKey("EnableAcceptEncodingBrotli")) {
            xml.elem("EnableAcceptEncodingBrotli",
                    String.valueOf(parameters.get("EnableAcceptEncodingBrotli")));
        }
        xml.raw(namedListXml(parameters, "HeadersConfig", "HeaderBehavior", "Headers"));
        xml.raw(namedListXml(parameters, "CookiesConfig", "CookieBehavior", "Cookies"));
        xml.raw(namedListXml(parameters, "QueryStringsConfig", "QueryStringBehavior", "QueryStrings"));
        return xml.end("ParametersInCacheKeyAndForwardedToOrigin").build();
    }

    // ── origin request policy ─────────────────────────────────────────────────

    public static Map<String, Object> parseOriginRequestConfig(String body) {
        XmlElement root = XmlParser.extractElementTree(body, "OriginRequestPolicyConfig");
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("HeadersConfig", namedList(root, "HeadersConfig", "HeaderBehavior",
                "Headers", ORIGIN_HEADER_BEHAVIORS));
        config.put("CookiesConfig", namedList(root, "CookiesConfig", "CookieBehavior",
                "Cookies", ORIGIN_COOKIE_BEHAVIORS));
        config.put("QueryStringsConfig", namedList(root, "QueryStringsConfig",
                "QueryStringBehavior", "QueryStrings", ORIGIN_QUERY_BEHAVIORS));
        return config;
    }

    public static String serializeOriginRequestConfig(Map<String, Object> config) {
        if (config == null) {
            return "";
        }
        return new XmlBuilder()
                .raw(namedListXml(config, "HeadersConfig", "HeaderBehavior", "Headers"))
                .raw(namedListXml(config, "CookiesConfig", "CookieBehavior", "Cookies"))
                .raw(namedListXml(config, "QueryStringsConfig", "QueryStringBehavior", "QueryStrings"))
                .build();
    }

    // ── the shared behavior-plus-names block ──────────────────────────────────

    /**
     * One {@code *Config} block: a behavior enum plus, when the behavior selects names, a
     * {@code Quantity}/{@code Items} list of them. The behavior is required, so a config that
     * omits it is rejected rather than stored as a silent "none".
     */
    private static Map<String, Object> namedList(XmlElement parent, String blockName,
                                                 String behaviorName, String namesName,
                                                 Set<String> validBehaviors) {
        XmlElement block = parent == null ? null : parent.child(blockName);
        if (block == null) {
            throw new AwsException("InvalidArgument", blockName + " is required.", 400);
        }
        XmlElement behaviorElement = block.child(behaviorName);
        String behavior = behaviorElement == null ? null : behaviorElement.text();
        if (behavior == null || behavior.isBlank()) {
            throw new AwsException("InvalidArgument", behaviorName + " is required.", 400);
        }
        if (!validBehaviors.contains(behavior)) {
            throw new AwsException("InvalidArgument", "Invalid " + behaviorName + ": " + behavior
                    + ". Valid values are " + String.join(", ", new java.util.TreeSet<>(validBehaviors))
                    + ".", 400);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(behaviorName, behavior);

        XmlElement names = block.child(namesName);
        List<String> items = new ArrayList<>();
        if (names != null) {
            XmlElement itemsElement = names.child("Items");
            if (itemsElement != null) {
                itemsElement.children().stream()
                        .filter(child -> "Name".equals(child.name()))
                        .forEach(child -> items.add(child.text()));
            }
        }
        if (!items.isEmpty()) {
            result.put(namesName, items);
        }
        return result;
    }

    private static String namedListXml(Map<String, Object> parent, String blockName,
                                       String behaviorName, String namesName) {
        Map<String, Object> block = subMap(parent, blockName);
        if (block.isEmpty()) {
            return "";
        }
        XmlBuilder xml = new XmlBuilder().start(blockName)
                .elem(behaviorName, String.valueOf(block.get(behaviorName)));
        List<?> items = block.get(namesName) instanceof List<?> list ? list : List.of();
        // Quantity is required whenever the names element is present, and AWS reports the element
        // with Quantity 0 rather than omitting it once a behavior that takes names is in use.
        if (!items.isEmpty()) {
            xml.start(namesName).elem("Quantity", String.valueOf(items.size())).start("Items");
            items.forEach(item -> xml.elem("Name", String.valueOf(item)));
            xml.end("Items").end(namesName);
        }
        return xml.end(blockName).build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> subMap(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static long longOf(XmlElement parent, String name, long fallback) {
        XmlElement child = parent == null ? null : parent.child(name);
        if (child == null || child.text() == null || child.text().isBlank()) {
            return fallback;
        }
        return Long.parseLong(child.text().trim());
    }

    private static boolean booleanOf(XmlElement parent, String name) {
        XmlElement child = parent == null ? null : parent.child(name);
        return child != null && Boolean.parseBoolean(child.text());
    }
}
