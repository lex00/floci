package io.github.hectorvent.floci.services.ivs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IvsIntegrationTest {

    private static String channelArn;
    private static String keyPairArn;
    private static String recordingConfigurationArn;

    @Test
    @Order(1)
    void createChannel() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"name": "integration-channel", "tags": {"team": "video"}}
                """)
        .when()
            .post("/CreateChannel")
        .then()
            .statusCode(200)
            .body("channel.arn", containsString(":ivs:"))
            .body("channel.arn", containsString(":channel/"))
            .body("channel.name", equalTo("integration-channel"))
            .body("channel.latencyMode", equalTo("LOW"))
            .body("channel.type", equalTo("STANDARD"))
            .body("channel.preset", equalTo(""))
            .body("channel.ingestEndpoint", notNullValue())
            .body("channel.playbackUrl", notNullValue())
            .body("channel.tags.team", equalTo("video"))
            .body("streamKey.arn", containsString(":stream-key/"))
            .body("streamKey.value", notNullValue())
            .extract();
        channelArn = response.path("channel.arn");
    }

    @Test
    @Order(2)
    void advancedChannelDefaultsToHigherBandwidthPreset() {
        String arn = given()
            .contentType("application/json")
            .body("""
                {"name": "advanced-channel", "type": "ADVANCED_HD"}
                """)
        .when()
            .post("/CreateChannel")
        .then()
            .statusCode(200)
            .body("channel.type", equalTo("ADVANCED_HD"))
            .body("channel.preset", equalTo("HIGHER_BANDWIDTH_DELIVERY"))
            .extract().path("channel.arn");

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + arn + "\"}")
        .when()
            .post("/DeleteChannel")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(3)
    void presetOnStandardChannelIsRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "standard-preset", "type": "STANDARD", "preset": "HIGHER_BANDWIDTH_DELIVERY"}
                """)
        .when()
            .post("/CreateChannel")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(4)
    void unknownLatencyModeIsRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "bad-latency", "latencyMode": "ULTRA"}
                """)
        .when()
            .post("/CreateChannel")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(5)
    void getChannel() {
        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + channelArn + "\"}")
        .when()
            .post("/GetChannel")
        .then()
            .statusCode(200)
            .body("channel.arn", equalTo(channelArn))
            .body("channel.name", equalTo("integration-channel"));
    }

    @Test
    @Order(6)
    void getMissingChannelReturnsResourceNotFound() {
        given()
            .contentType("application/json")
            .body("{\"arn\": \"arn:aws:ivs:us-east-1:000000000000:channel/missing000000\"}")
        .when()
            .post("/GetChannel")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(7)
    void listTagsForChannel() {
        given()
        .when()
            .get("/tags/" + channelArn)
        .then()
            .statusCode(200)
            .body("tags.team", equalTo("video"));
    }

    @Test
    @Order(8)
    void importAndGetPlaybackKeyPair() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"publicKeyMaterial": "placeholder", "name": "integration-key"}
                """)
        .when()
            .post("/ImportPlaybackKeyPair")
        .then()
            .statusCode(200)
            .body("keyPair.arn", containsString(":playback-key/"))
            .body("keyPair.name", equalTo("integration-key"))
            .body("keyPair.fingerprint", containsString(":"))
            .extract();
        keyPairArn = response.path("keyPair.arn");

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + keyPairArn + "\"}")
        .when()
            .post("/GetPlaybackKeyPair")
        .then()
            .statusCode(200)
            .body("keyPair.arn", equalTo(keyPairArn));
    }

    @Test
    @Order(9)
    void createAndGetRecordingConfiguration() {
        var response = given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "placeholder"}},
                 "tags": {"team": "video"}}
                """)
        .when()
            .post("/CreateRecordingConfiguration")
        .then()
            .statusCode(200)
            .body("recordingConfiguration.arn", containsString(":recording-configuration/"))
            .body("recordingConfiguration.state", equalTo("ACTIVE"))
            .body("recordingConfiguration.recordingReconnectWindowSeconds", equalTo(0))
            .body("recordingConfiguration.destinationConfiguration.s3.bucketName", equalTo("placeholder"))
            .extract();
        recordingConfigurationArn = response.path("recordingConfiguration.arn");

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + recordingConfigurationArn + "\"}")
        .when()
            .post("/GetRecordingConfiguration")
        .then()
            .statusCode(200)
            .body("recordingConfiguration.arn", equalTo(recordingConfigurationArn))
            .body("recordingConfiguration.state", equalTo("ACTIVE"));
    }

    @Test
    @Order(10)
    void intervalThumbnailConfigurationGetsTheModelledDefaults() {
        String arn = given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "thumbnails"}},
                 "thumbnailConfiguration": {"recordingMode": "INTERVAL"}}
                """)
        .when()
            .post("/CreateRecordingConfiguration")
        .then()
            .statusCode(200)
            .body("recordingConfiguration.thumbnailConfiguration.targetIntervalSeconds", equalTo(60))
            .body("recordingConfiguration.thumbnailConfiguration.storage[0]", equalTo("SEQUENTIAL"))
            .extract().path("recordingConfiguration.arn");

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + arn + "\"}")
        .when()
            .post("/DeleteRecordingConfiguration")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(11)
    void disabledThumbnailModeRejectsTargetInterval() {
        given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "thumbnails"}},
                 "thumbnailConfiguration": {"recordingMode": "DISABLED", "targetIntervalSeconds": 30}}
                """)
        .when()
            .post("/CreateRecordingConfiguration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(12)
    void customRenditionSelectionRequiresRenditions() {
        given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "renditions"}},
                 "renditionConfiguration": {"renditionSelection": "CUSTOM"}}
                """)
        .when()
            .post("/CreateRecordingConfiguration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(13)
    void twoDestinationTypesAreRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "one"}, "firehose": {"deliveryStreamName": "two"}}}
                """)
        .when()
            .post("/CreateRecordingConfiguration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(14)
    void reconnectWindowOutsideTheModelledRangeIsRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"destinationConfiguration": {"s3": {"bucketName": "reconnect"}},
                 "recordingReconnectWindowSeconds": 301}
                """)
        .when()
            .post("/CreateRecordingConfiguration")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }

    @Test
    @Order(15)
    void channelRejectsAnUnknownRecordingConfiguration() {
        given()
            .contentType("application/json")
            .body("""
                {"name": "orphan-recording",
                 "recordingConfigurationArn":
                     "arn:aws:ivs:us-east-1:000000000000:recording-configuration/missing00000"}
                """)
        .when()
            .post("/CreateChannel")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(16)
    void deleteResources() {
        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + recordingConfigurationArn + "\"}")
        .when()
            .post("/DeleteRecordingConfiguration")
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + keyPairArn + "\"}")
        .when()
            .post("/DeletePlaybackKeyPair")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + channelArn + "\"}")
        .when()
            .post("/DeleteChannel")
        .then()
            .statusCode(204);

        given()
            .contentType("application/json")
            .body("{\"arn\": \"" + channelArn + "\"}")
        .when()
            .post("/GetChannel")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
