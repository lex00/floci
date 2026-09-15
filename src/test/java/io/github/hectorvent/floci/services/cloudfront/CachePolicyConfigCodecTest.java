package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A full cache policy config and a full origin request policy config parse into the generic config
 * map and survive a serialize round-trip, and the members each shape marks required are enforced.
 */
class CachePolicyConfigCodecTest {

    private static final String CACHE_XML = """
            <CachePolicyConfig>
              <Name>full</Name>
              <Comment>everything set</Comment>
              <DefaultTTL>3600</DefaultTTL>
              <MaxTTL>86400</MaxTTL>
              <MinTTL>60</MinTTL>
              <ParametersInCacheKeyAndForwardedToOrigin>
                <EnableAcceptEncodingGzip>true</EnableAcceptEncodingGzip>
                <EnableAcceptEncodingBrotli>true</EnableAcceptEncodingBrotli>
                <HeadersConfig>
                  <HeaderBehavior>whitelist</HeaderBehavior>
                  <Headers><Quantity>2</Quantity><Items><Name>Accept</Name><Name>Origin</Name></Items></Headers>
                </HeadersConfig>
                <CookiesConfig>
                  <CookieBehavior>allExcept</CookieBehavior>
                  <Cookies><Quantity>1</Quantity><Items><Name>session</Name></Items></Cookies>
                </CookiesConfig>
                <QueryStringsConfig>
                  <QueryStringBehavior>all</QueryStringBehavior>
                </QueryStringsConfig>
              </ParametersInCacheKeyAndForwardedToOrigin>
            </CachePolicyConfig>
            """;

    private static final String MINIMAL_CACHE_XML = """
            <CachePolicyConfig>
              <Name>minimal</Name>
              <MinTTL>1</MinTTL>
              <ParametersInCacheKeyAndForwardedToOrigin>
                <EnableAcceptEncodingGzip>false</EnableAcceptEncodingGzip>
                <HeadersConfig><HeaderBehavior>none</HeaderBehavior></HeadersConfig>
                <CookiesConfig><CookieBehavior>none</CookieBehavior></CookiesConfig>
                <QueryStringsConfig><QueryStringBehavior>none</QueryStringBehavior></QueryStringsConfig>
              </ParametersInCacheKeyAndForwardedToOrigin>
            </CachePolicyConfig>
            """;

    private static final String ORIGIN_XML = """
            <OriginRequestPolicyConfig>
              <Name>orp</Name>
              <HeadersConfig>
                <HeaderBehavior>allViewerAndWhitelistCloudFront</HeaderBehavior>
                <Headers><Quantity>1</Quantity><Items><Name>CloudFront-Viewer-Country</Name></Items></Headers>
              </HeadersConfig>
              <CookiesConfig><CookieBehavior>all</CookieBehavior></CookiesConfig>
              <QueryStringsConfig>
                <QueryStringBehavior>whitelist</QueryStringBehavior>
                <QueryStrings><Quantity>1</Quantity><Items><Name>lang</Name></Items></QueryStrings>
              </QueryStringsConfig>
            </OriginRequestPolicyConfig>
            """;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    @Test
    void cacheConfig_parsesEveryMemberOfTheCacheKey() {
        Map<String, Object> config = CachePolicyConfigCodec.parseCacheConfig(CACHE_XML);

        assertEquals(60L, config.get("MinTTL"));
        assertEquals(3600L, config.get("DefaultTTL"));
        assertEquals(86400L, config.get("MaxTTL"));

        Map<String, Object> params = sub(config, "ParametersInCacheKeyAndForwardedToOrigin");
        assertEquals(true, params.get("EnableAcceptEncodingGzip"));
        assertEquals(true, params.get("EnableAcceptEncodingBrotli"));

        Map<String, Object> headers = sub(params, "HeadersConfig");
        assertEquals("whitelist", headers.get("HeaderBehavior"));
        assertEquals(List.of("Accept", "Origin"), headers.get("Headers"));

        Map<String, Object> cookies = sub(params, "CookiesConfig");
        assertEquals("allExcept", cookies.get("CookieBehavior"));
        assertEquals(List.of("session"), cookies.get("Cookies"));

        Map<String, Object> queryStrings = sub(params, "QueryStringsConfig");
        assertEquals("all", queryStrings.get("QueryStringBehavior"));
        assertFalse(queryStrings.containsKey("QueryStrings"));
    }

    @Test
    void cacheConfig_survivesASerializeRoundTrip() {
        Map<String, Object> first = CachePolicyConfigCodec.parseCacheConfig(CACHE_XML);
        String serialized = "<CachePolicyConfig>"
                + CachePolicyConfigCodec.serializeCacheConfig(first) + "</CachePolicyConfig>";

        assertEquals(first, CachePolicyConfigCodec.parseCacheConfig(serialized));
    }

    // DefaultTTL documents 86400 and MaxTTL 31536000, each raised to the larger neighbour when
    // that neighbour exceeds it.
    @Test
    void cacheConfig_withoutTheOptionalTtls_takesTheDocumentedDefaults() {
        Map<String, Object> config = CachePolicyConfigCodec.parseCacheConfig(MINIMAL_CACHE_XML);

        assertEquals(1L, config.get("MinTTL"));
        assertEquals(86400L, config.get("DefaultTTL"));
        assertEquals(31536000L, config.get("MaxTTL"));
        assertFalse(sub(config, "ParametersInCacheKeyAndForwardedToOrigin")
                .containsKey("EnableAcceptEncodingBrotli"));
    }

    @Test
    void cacheConfig_withAMinTtlAboveTheDefaults_raisesThemToIt() {
        Map<String, Object> config = CachePolicyConfigCodec.parseCacheConfig("""
                <CachePolicyConfig>
                  <Name>long</Name>
                  <MinTTL>99999999</MinTTL>
                  <ParametersInCacheKeyAndForwardedToOrigin>
                    <EnableAcceptEncodingGzip>false</EnableAcceptEncodingGzip>
                    <HeadersConfig><HeaderBehavior>none</HeaderBehavior></HeadersConfig>
                    <CookiesConfig><CookieBehavior>none</CookieBehavior></CookiesConfig>
                    <QueryStringsConfig><QueryStringBehavior>none</QueryStringBehavior></QueryStringsConfig>
                  </ParametersInCacheKeyAndForwardedToOrigin>
                </CachePolicyConfig>
                """);

        assertEquals(99999999L, config.get("DefaultTTL"));
        assertEquals(99999999L, config.get("MaxTTL"));
    }

    @Test
    void originRequestConfig_parsesAndRoundTrips() {
        Map<String, Object> config = CachePolicyConfigCodec.parseOriginRequestConfig(ORIGIN_XML);

        assertEquals("allViewerAndWhitelistCloudFront",
                sub(config, "HeadersConfig").get("HeaderBehavior"));
        assertEquals(List.of("CloudFront-Viewer-Country"), sub(config, "HeadersConfig").get("Headers"));
        assertEquals("all", sub(config, "CookiesConfig").get("CookieBehavior"));
        assertEquals(List.of("lang"), sub(config, "QueryStringsConfig").get("QueryStrings"));

        String serialized = "<OriginRequestPolicyConfig>"
                + CachePolicyConfigCodec.serializeOriginRequestConfig(config)
                + "</OriginRequestPolicyConfig>";
        assertEquals(config, CachePolicyConfigCodec.parseOriginRequestConfig(serialized));
    }

    @Test
    void config_missingARequiredBlock_isRejected() {
        AwsException e = assertThrows(AwsException.class, () ->
                CachePolicyConfigCodec.parseCacheConfig("""
                        <CachePolicyConfig>
                          <Name>no-cookies</Name>
                          <MinTTL>0</MinTTL>
                          <ParametersInCacheKeyAndForwardedToOrigin>
                            <EnableAcceptEncodingGzip>false</EnableAcceptEncodingGzip>
                            <HeadersConfig><HeaderBehavior>none</HeaderBehavior></HeadersConfig>
                            <QueryStringsConfig><QueryStringBehavior>none</QueryStringBehavior></QueryStringsConfig>
                          </ParametersInCacheKeyAndForwardedToOrigin>
                        </CachePolicyConfig>
                        """));
        assertTrue(e.getMessage().contains("CookiesConfig"));
    }

    // allViewer is an origin request policy behavior; a cache policy header behavior is only
    // none or whitelist.
    @Test
    void cacheConfig_withAnOriginRequestHeaderBehavior_isRejected() {
        AwsException e = assertThrows(AwsException.class, () ->
                CachePolicyConfigCodec.parseCacheConfig("""
                        <CachePolicyConfig>
                          <Name>wrong-behavior</Name>
                          <MinTTL>0</MinTTL>
                          <ParametersInCacheKeyAndForwardedToOrigin>
                            <EnableAcceptEncodingGzip>false</EnableAcceptEncodingGzip>
                            <HeadersConfig><HeaderBehavior>allViewer</HeaderBehavior></HeadersConfig>
                            <CookiesConfig><CookieBehavior>none</CookieBehavior></CookiesConfig>
                            <QueryStringsConfig><QueryStringBehavior>none</QueryStringBehavior></QueryStringsConfig>
                          </ParametersInCacheKeyAndForwardedToOrigin>
                        </CachePolicyConfig>
                        """));
        assertTrue(e.getMessage().contains("allViewer"));
    }
}
