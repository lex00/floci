package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

/**
 * The Resource Groups Tagging API writing to a Route 53 hosted zone and health check, and Route 53
 * reading it back, end to end.
 *
 * <p>On AWS, {@code TagResources} on a hosted zone's ARN changes the zone's own tags: Route 53's
 * {@code ListTagsForResource} returns them, and a zone tagged through Route 53's own
 * {@code ChangeTagsForResource} is in the tag index. Floci used to record a tagging-API write in
 * the tagging service's own store, where {@code GetResources} found it and Route 53 never did, so a
 * client that marks a zone after creating it - the only way to tag a hosted zone at all, since
 * {@code CreateHostedZone} takes no tags - read back a zone with no tags.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Route53TaggingWriteThroughIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "ResourceGroupsTaggingAPI_20170126.";
    private static final String XML = "application/xml";
    private static final String US_EAST_1 =
            "AWS4-HMAC-SHA256 Credential=test/20260922/us-east-1/tagging/aws4_request";

    private static String zoneId;
    private static String healthCheckId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String zoneArn() {
        return "arn:aws:route53:::hostedzone/" + zoneId;
    }

    private static String healthCheckArn() {
        return "arn:aws:route53:::healthcheck/" + healthCheckId;
    }

    @Test
    @Order(1)
    void createTheZoneAndTheHealthCheckWithNoTags() {
        String zone = given()
                .contentType(XML)
                .body("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                          <Name>writethrough.example</Name>
                          <CallerReference>writethrough-zone</CallerReference>
                        </CreateHostedZoneRequest>
                        """)
                .when().post("/2013-04-01/hostedzone")
                .then().statusCode(201)
                .extract().header("Location");
        zoneId = zone.substring(zone.lastIndexOf('/') + 1);

        String check = given()
                .contentType(XML)
                .body("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <CreateHealthCheckRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                          <CallerReference>writethrough-hc</CallerReference>
                          <HealthCheckConfig>
                            <Type>HTTPS</Type>
                            <FullyQualifiedDomainName>writethrough.example</FullyQualifiedDomainName>
                            <Port>443</Port>
                          </HealthCheckConfig>
                        </CreateHealthCheckRequest>
                        """)
                .when().post("/2013-04-01/healthcheck")
                .then().statusCode(201)
                .extract().header("Location");
        healthCheckId = check.substring(check.lastIndexOf('/') + 1);
    }

    @Test
    @Order(2)
    void tagResourcesLandsOnTheZoneAndTheHealthCheck() {
        given()
                .header("Authorization", US_EAST_1)
                .header("X-Amz-Target", TARGET_PREFIX + "TagResources")
                .contentType(JSON_CONTENT_TYPE)
                .body("{\"ResourceARNList\": [\"" + zoneArn() + "\", \"" + healthCheckArn() + "\"],"
                        + " \"Tags\": {\"owner\": \"estate-a\", \"address\": \"aws_route53_zone.main\"}}")
                .when().post("/")
                .then().statusCode(200);

        // The assertion that was false: Route 53 itself reads the tags back.
        given()
                .when().get("/2013-04-01/tags/hostedzone/" + zoneId)
                .then().statusCode(200)
                .body("ListTagsForResourceResponse.ResourceTagSet.Tags.Tag.Key",
                        hasItems("owner", "address"));
        given()
                .when().get("/2013-04-01/tags/healthcheck/" + healthCheckId)
                .then().statusCode(200)
                .body("ListTagsForResourceResponse.ResourceTagSet.Tags.Tag.Key", hasItem("owner"));
    }

    @Test
    @Order(3)
    void theIndexStillServesThem() {
        given()
                .header("Authorization", US_EAST_1)
                .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
                .contentType(JSON_CONTENT_TYPE)
                .body("{\"TagFilters\": [{\"Key\": \"owner\", \"Values\": [\"estate-a\"]}]}")
                .when().post("/")
                .then().statusCode(200)
                .body("ResourceTagMappingList.ResourceARN", hasItems(zoneArn(), healthCheckArn()));
    }

    @Test
    @Order(4)
    void aTagWrittenThroughRoute53ItselfIsInTheIndex() {
        given()
                .contentType(XML)
                .body("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <ChangeTagsForResourceRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                          <AddTags><Tag><Key>native</Key><Value>route53</Value></Tag></AddTags>
                        </ChangeTagsForResourceRequest>
                        """)
                .when().post("/2013-04-01/tags/hostedzone/" + zoneId)
                .then().statusCode(200);

        given()
                .header("Authorization", US_EAST_1)
                .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
                .contentType(JSON_CONTENT_TYPE)
                .body("{\"TagFilters\": [{\"Key\": \"native\", \"Values\": [\"route53\"]}]}")
                .when().post("/")
                .then().statusCode(200)
                .body("ResourceTagMappingList.ResourceARN", hasItem(zoneArn()));
    }

    @Test
    @Order(5)
    void untagResourcesRemovesFromTheZone() {
        given()
                .header("Authorization", US_EAST_1)
                .header("X-Amz-Target", TARGET_PREFIX + "UntagResources")
                .contentType(JSON_CONTENT_TYPE)
                .body("{\"ResourceARNList\": [\"" + zoneArn() + "\"], \"TagKeys\": [\"owner\"]}")
                .when().post("/")
                .then().statusCode(200);

        given()
                .when().get("/2013-04-01/tags/hostedzone/" + zoneId)
                .then().statusCode(200)
                .body("ListTagsForResourceResponse.ResourceTagSet.Tags.Tag.Key", not(hasItem("owner")))
                .body("ListTagsForResourceResponse.ResourceTagSet.Tags.Tag.Key", hasItem("address"));

        given()
                .header("Authorization", US_EAST_1)
                .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
                .contentType(JSON_CONTENT_TYPE)
                .body("{\"TagFilters\": [{\"Key\": \"owner\", \"Values\": [\"estate-a\"]}]}")
                .when().post("/")
                .then().statusCode(200)
                .body("ResourceTagMappingList.ResourceARN", not(hasItem(zoneArn())));
    }
}
