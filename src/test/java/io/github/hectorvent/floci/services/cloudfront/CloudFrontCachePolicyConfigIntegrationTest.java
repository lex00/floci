package io.github.hectorvent.floci.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CreateCachePolicy and CreateOriginRequestPolicy kept only the name and the comment, so
 * GetCachePolicy, GetCachePolicyConfig and the list operations reported a policy with no TTLs and
 * no cache-key members. A caller reading its own policy back saw an empty config every time.
 */
@QuarkusTest
class CloudFrontCachePolicyConfigIntegrationTest {

    private static final String API = "/2020-05-31/";

    private static final String CACHE_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <CachePolicyConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
              <Name>round-trip-cache-policy</Name>
              <Comment>cache key on one header and one cookie</Comment>
              <DefaultTTL>3600</DefaultTTL>
              <MaxTTL>86400</MaxTTL>
              <MinTTL>60</MinTTL>
              <ParametersInCacheKeyAndForwardedToOrigin>
                <EnableAcceptEncodingGzip>true</EnableAcceptEncodingGzip>
                <EnableAcceptEncodingBrotli>false</EnableAcceptEncodingBrotli>
                <HeadersConfig>
                  <HeaderBehavior>whitelist</HeaderBehavior>
                  <Headers><Quantity>1</Quantity><Items><Name>Accept-Language</Name></Items></Headers>
                </HeadersConfig>
                <CookiesConfig>
                  <CookieBehavior>whitelist</CookieBehavior>
                  <Cookies><Quantity>1</Quantity><Items><Name>session</Name></Items></Cookies>
                </CookiesConfig>
                <QueryStringsConfig><QueryStringBehavior>all</QueryStringBehavior></QueryStringsConfig>
              </ParametersInCacheKeyAndForwardedToOrigin>
            </CachePolicyConfig>
            """;

    private static final String ORIGIN_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <OriginRequestPolicyConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
              <Name>round-trip-origin-request-policy</Name>
              <Comment>forward everything the viewer sent</Comment>
              <HeadersConfig><HeaderBehavior>allViewer</HeaderBehavior></HeadersConfig>
              <CookiesConfig><CookieBehavior>all</CookieBehavior></CookiesConfig>
              <QueryStringsConfig>
                <QueryStringBehavior>whitelist</QueryStringBehavior>
                <QueryStrings><Quantity>1</Quantity><Items><Name>lang</Name></Items></QueryStrings>
              </QueryStringsConfig>
            </OriginRequestPolicyConfig>
            """;

    private static XmlPath post(String path, String body) {
        return XmlPath.from(given().contentType("application/xml").body(body)
                .when().post(API + path)
                .then().statusCode(201)
                .extract().asString());
    }

    private static XmlPath get(String path) {
        return XmlPath.from(given().when().get(API + path)
                .then().statusCode(200).extract().asString());
    }

    @Test
    void cachePolicy_roundTripsItsWholeConfig() {
        String id = post("cache-policy", CACHE_BODY).getString("CachePolicy.Id");

        for (String read : new String[] {"CachePolicy.CachePolicyConfig.", "CachePolicyConfig."}) {
            XmlPath described = get(read.startsWith("CachePolicy.")
                    ? "cache-policy/" + id : "cache-policy/" + id + "/config");
            assertEquals("3600", described.getString(read + "DefaultTTL"));
            assertEquals("86400", described.getString(read + "MaxTTL"));
            assertEquals("60", described.getString(read + "MinTTL"));

            String params = read + "ParametersInCacheKeyAndForwardedToOrigin.";
            assertEquals("true", described.getString(params + "EnableAcceptEncodingGzip"));
            assertEquals("false", described.getString(params + "EnableAcceptEncodingBrotli"));
            assertEquals("whitelist", described.getString(params + "HeadersConfig.HeaderBehavior"));
            assertEquals("Accept-Language",
                    described.getString(params + "HeadersConfig.Headers.Items.Name"));
            assertEquals("1", described.getString(params + "HeadersConfig.Headers.Quantity"));
            assertEquals("whitelist", described.getString(params + "CookiesConfig.CookieBehavior"));
            assertEquals("session",
                    described.getString(params + "CookiesConfig.Cookies.Items.Name"));
            assertEquals("all",
                    described.getString(params + "QueryStringsConfig.QueryStringBehavior"));
        }
    }

    @Test
    void originRequestPolicy_roundTripsItsWholeConfig() {
        String id = post("origin-request-policy", ORIGIN_BODY)
                .getString("OriginRequestPolicy.Id");

        XmlPath described = get("origin-request-policy/" + id);
        String base = "OriginRequestPolicy.OriginRequestPolicyConfig.";
        assertEquals("allViewer", described.getString(base + "HeadersConfig.HeaderBehavior"));
        assertEquals("all", described.getString(base + "CookiesConfig.CookieBehavior"));
        assertEquals("whitelist",
                described.getString(base + "QueryStringsConfig.QueryStringBehavior"));
        assertEquals("lang",
                described.getString(base + "QueryStringsConfig.QueryStrings.Items.Name"));

        XmlPath config = get("origin-request-policy/" + id + "/config");
        assertEquals("allViewer",
                config.getString("OriginRequestPolicyConfig.HeadersConfig.HeaderBehavior"));
    }
}
