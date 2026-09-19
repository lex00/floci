package io.github.hectorvent.floci.services.ivschat;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasLength;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IvschatIntegrationTest {

    private static String roomArn;
    private static String roomId;
    private static String loggingConfigurationArn;

    @Test
    @Order(1)
    void createRoom() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"name": "integration-room", "tags": {"team": "chat"}}
                """)
        .when()
            .post("/CreateRoom")
        .then()
            .statusCode(200)
            .body("arn", containsString(":ivschat:"))
            .body("arn", containsString(":room/"))
            .body("id", notNullValue())
            .body("id", hasLength(12))
            .body("name", equalTo("integration-room"))
            .body("createTime", containsString("T"))
            .body("maximumMessageRatePerSecond", equalTo(10))
            .body("maximumMessageLength", equalTo(500))
            .body("tags.team", equalTo("chat"))
            .extract();
        roomArn = response.path("arn");
        roomId = response.path("id");
    }

    @Test
    @Order(2)
    void messageReviewHandlerGetsTheDefaultFallbackResult() {
        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "reviewed-room",
                 "messageReviewHandler":
                     {"uri": "arn:aws:lambda:us-east-1:000000000000:function:review"}}
                """)
        .when()
            .post("/CreateRoom")
        .then()
            .statusCode(200)
            .body("messageReviewHandler.fallbackResult", equalTo("ALLOW"))
            .extract().path("arn");

        given()
            .contentType("application/json")
            .body("{\"identifier\": \"" + arn + "\"}")
        .when()
            .post("/DeleteRoom")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(3)
    void messageRateOutsideTheModelledRangeIsRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "too-fast", "maximumMessageRatePerSecond": 101}
                """)
        .when()
            .post("/CreateRoom")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(4)
    void moreThanThreeLoggingConfigurationIdentifiersAreRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "over-logged",
                 "loggingConfigurationIdentifiers": ["one", "two", "three", "four"]}
                """)
        .when()
            .post("/CreateRoom")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(5)
    void getRoomByArnAndById() {
        given()
            .contentType("application/json")
            .body("{\"identifier\": \"" + roomArn + "\"}")
        .when()
            .post("/GetRoom")
        .then()
            .statusCode(200)
            .body("arn", equalTo(roomArn));

        given()
            .contentType("application/json")
            .body("{\"identifier\": \"" + roomId + "\"}")
        .when()
            .post("/GetRoom")
        .then()
            .statusCode(200)
            .body("id", equalTo(roomId));
    }

    @Test
    @Order(6)
    void listTagsForRoom() {
        given()
        .when()
            .get("/tags/" + roomArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("chat"));
    }

    @Test
    @Order(7)
    void loggingConfigurationRoundTrip() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "placeholder"}}}
                """)
        .when()
            .post("/CreateLoggingConfiguration")
        .then()
            .statusCode(200)
            .body("arn", containsString(":logging-configuration/"))
            .body("id", hasLength(12))
            .body("state", equalTo("ACTIVE"))
            .body("destinationConfiguration.s3.bucketName", equalTo("placeholder"))
            .extract();
        loggingConfigurationArn = response.path("arn");

        given()
            .contentType("application/json")
            .body("{\"identifier\": \"" + loggingConfigurationArn + "\"}")
        .when()
            .post("/GetLoggingConfiguration")
        .then()
            .statusCode(200)
            .body("arn", equalTo(loggingConfigurationArn))
            .body("state", equalTo("ACTIVE"));
    }

    @Test
    @Order(8)
    void twoLogDestinationsAreRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "one"},
                                              "cloudWatchLogs": {"logGroupName": "two"}}}
                """)
        .when()
            .post("/CreateLoggingConfiguration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(9)
    void logDestinationWithoutItsRequiredFieldIsRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"firehose": {}}}
                """)
        .when()
            .post("/CreateLoggingConfiguration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(10)
    void deleteResources() {
        given()
            .contentType("application/json")
            .body("{\"identifier\": \"" + loggingConfigurationArn + "\"}")
        .when()
            .post("/DeleteLoggingConfiguration")
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
            .body("{\"identifier\": \"" + roomArn + "\"}")
        .when()
            .post("/DeleteRoom")
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
            .body("{\"identifier\": \"" + roomArn + "\"}")
        .when()
            .post("/GetRoom")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
