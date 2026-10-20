package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class CloudWatchLogsDeliveryIntegrationTest {

    private static final String DISTRIBUTION_ARN = "arn:aws:cloudfront::000000000000:distribution/E1DELIVERY";
    private static final String LOG_GROUP_ARN = "arn:aws:logs:us-east-1:000000000000:log-group:/aws/cloudfront/delivery";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse logs(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Logs_20140328." + action)
                .body(body)
                .post("/")
                .then();
    }

    @Test
    void sourceDestinationAndDeliveryLifecycle() {
        logs("PutDeliverySource", """
                {"name":"cf-source","resourceArn":"%s","logType":"ACCESS_LOGS","tags":{"team":"edge"}}
                """.formatted(DISTRIBUTION_ARN))
                .statusCode(200)
                .body("deliverySource.arn", equalTo("arn:aws:logs:us-east-1:000000000000:delivery-source:cf-source"))
                .body("deliverySource.service", equalTo("cloudfront"))
                .body("deliverySource.resourceArns", hasItem(DISTRIBUTION_ARN));

        logs("GetDeliverySource", "{\"name\":\"cf-source\"}")
                .statusCode(200)
                .body("deliverySource.logType", equalTo("ACCESS_LOGS"))
                .body("deliverySource.tags.team", equalTo("edge"));

        String destinationArn = logs("PutDeliveryDestination", """
                {"name":"cf-destination","deliveryDestinationConfiguration":{"destinationResourceArn":"%s"}}
                """.formatted(LOG_GROUP_ARN))
                .statusCode(200)
                .body("deliveryDestination.deliveryDestinationType", equalTo("CWL"))
                .extract().path("deliveryDestination.arn");

        logs("GetDeliveryDestination", "{\"name\":\"cf-destination\"}")
                .statusCode(200)
                .body("deliveryDestination.deliveryDestinationConfiguration.destinationResourceArn",
                        equalTo(LOG_GROUP_ARN));

        String id = logs("CreateDelivery", """
                {"deliverySourceName":"cf-source","deliveryDestinationArn":"%s"}
                """.formatted(destinationArn))
                .statusCode(200)
                .body("delivery.id", notNullValue())
                .body("delivery.deliveryDestinationType", equalTo("CWL"))
                .extract().path("delivery.id");

        logs("CreateDelivery", """
                {"deliverySourceName":"cf-source","deliveryDestinationArn":"%s"}
                """.formatted(destinationArn))
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));
        logs("DeleteDeliverySource", "{\"name\":\"cf-source\"}")
                .statusCode(409);

        logs("DescribeDeliveries", "{}")
                .statusCode(200)
                .body("deliveries.id", hasItem(id));

        logs("DeleteDelivery", "{\"id\":\"" + id + "\"}").statusCode(200);
        logs("GetDelivery", "{\"id\":\"" + id + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        logs("DeleteDeliveryDestination", "{\"name\":\"cf-destination\"}").statusCode(200);
        logs("DeleteDeliverySource", "{\"name\":\"cf-source\"}").statusCode(200);
        logs("DescribeDeliverySources", "{}")
                .statusCode(200)
                .body("deliverySources.name", not(hasItem("cf-source")));
    }

    @Test
    void getMissingDeliveryResourcesReportsNotFound() {
        logs("GetDeliverySource", "{\"name\":\"never-created\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
        logs("GetDeliveryDestination", "{\"name\":\"never-created\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void destinationPolicyAndTagsRoundTrip() {
        String arn = logs("PutDeliveryDestination", """
                {"name":"policy-destination","deliveryDestinationConfiguration":
                 {"destinationResourceArn":"arn:aws:s3:::delivery-bucket"}}
                """)
                .statusCode(200)
                .body("deliveryDestination.deliveryDestinationType", equalTo("S3"))
                .extract().path("deliveryDestination.arn");

        logs("PutDeliveryDestinationPolicy",
                "{\"deliveryDestinationName\":\"policy-destination\",\"deliveryDestinationPolicy\":\"{}\"}")
                .statusCode(200)
                .body("policy.deliveryDestinationPolicy", equalTo("{}"));
        logs("GetDeliveryDestinationPolicy", "{\"deliveryDestinationName\":\"policy-destination\"}")
                .statusCode(200)
                .body("policy.deliveryDestinationPolicy", equalTo("{}"));

        logs("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":{\"env\":\"dev\"}}").statusCode(200);
        logs("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.env", equalTo("dev"));
        logs("UntagResource", "{\"resourceArn\":\"" + arn + "\",\"tagKeys\":[\"env\"]}").statusCode(200);
        logs("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.env", equalTo(null));

        logs("DeleteDeliveryDestinationPolicy", "{\"deliveryDestinationName\":\"policy-destination\"}")
                .statusCode(200);
        logs("DeleteDeliveryDestination", "{\"name\":\"policy-destination\"}").statusCode(200);
    }
}
