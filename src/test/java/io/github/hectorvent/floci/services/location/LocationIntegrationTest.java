package io.github.hectorvent.floci.services.location;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class LocationIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    /** The SDK prefixes each operation's host, so the requests carry that prefixed Host header. */
    private static RequestSpecification geo(String hostPrefix) {
        return given()
                .header("Host", hostPrefix + "localhost.floci.io")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/geo/aws4_request")
                .contentType("application/json");
    }

    @Test
    void geofenceCollectionLifecycleThroughThePrefixedHost() {
        String arn = geo("cp.geofencing.")
            .body("{\"CollectionName\": \"fences\", \"Description\": \"Depots\", \"Tags\": {\"team\": \"ops\"}}")
        .when()
            .post("/geofencing/v0/collections")
        .then()
            .statusCode(200)
            .body("CollectionArn", equalTo("arn:aws:geo:us-east-1:000000000000:geofence-collection/fences"))
            .body("CreateTime", notNullValue())
            .extract().path("CollectionArn");

        geo("cp.geofencing.")
            .body("{\"CollectionName\": \"fences\"}")
        .when()
            .post("/geofencing/v0/collections")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ConflictException"));

        geo("cp.geofencing.")
            .body("{\"Description\": \"Warehouses\"}")
        .when()
            .patch("/geofencing/v0/collections/fences")
        .then()
            .statusCode(200)
            .body("CollectionName", equalTo("fences"));

        geo("cp.geofencing.")
        .when()
            .get("/geofencing/v0/collections/fences")
        .then()
            .statusCode(200)
            .body("Description", equalTo("Warehouses"))
            .body("Tags.team", equalTo("ops"))
            .body("GeofenceCount", equalTo(0));

        geo("cp.geofencing.")
            .body("{}")
        .when()
            .post("/geofencing/v0/list-collections")
        .then()
            .statusCode(200)
            .body("Entries.CollectionName", hasItem("fences"));

        geo("cp.metadata.")
            .body("{\"Tags\": {\"env\": \"dev\"}}")
        .when()
            .post("/tags/" + arn)
        .then()
            .statusCode(200);

        geo("cp.metadata.")
        .when()
            .get("/tags/" + arn)
        .then()
            .statusCode(200)
            .body("Tags.env", equalTo("dev"))
            .body("Tags.team", equalTo("ops"));

        geo("cp.geofencing.")
        .when()
            .delete("/geofencing/v0/collections/fences")
        .then()
            .statusCode(200);

        geo("cp.geofencing.")
        .when()
            .get("/geofencing/v0/collections/fences")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void mapsPlaceIndexesRouteCalculatorsAndTrackers() {
        geo("cp.maps.")
            .body("{\"MapName\": \"city\", \"Configuration\": {\"Style\": \"VectorHereExplore\"}}")
        .when()
            .post("/maps/v0/maps")
        .then()
            .statusCode(200);
        geo("cp.maps.")
        .when()
            .get("/maps/v0/maps/city")
        .then()
            .statusCode(200)
            .body("DataSource", equalTo("Here"))
            .body("Configuration.Style", equalTo("VectorHereExplore"));

        geo("cp.places.")
            .body("{\"IndexName\": \"places\", \"DataSource\": \"Esri\"}")
        .when()
            .post("/places/v0/indexes")
        .then()
            .statusCode(200);
        geo("cp.places.")
        .when()
            .get("/places/v0/indexes/places")
        .then()
            .statusCode(200)
            .body("DataSourceConfiguration.IntendedUse", equalTo("SingleUse"));

        geo("cp.routes.")
            .body("{\"CalculatorName\": \"routes\"}")
        .when()
            .post("/routes/v0/calculators")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"));
        geo("cp.routes.")
            .body("{\"CalculatorName\": \"routes\", \"DataSource\": \"Esri\"}")
        .when()
            .post("/routes/v0/calculators")
        .then()
            .statusCode(200);

        geo("cp.tracking.")
            .body("{\"TrackerName\": \"fleet\", \"PositionFiltering\": \"TimeBased\"}")
        .when()
            .post("/tracking/v0/trackers")
        .then()
            .statusCode(200)
            .body("TrackerArn", equalTo("arn:aws:geo:us-east-1:000000000000:tracker/fleet"));
        geo("cp.tracking.")
            .body("{}")
        .when()
            .post("/tracking/v0/list-trackers")
        .then()
            .statusCode(200)
            .body("Entries.TrackerName", hasItem("fleet"));
        geo("cp.tracking.")
        .when()
            .delete("/tracking/v0/trackers/fleet")
        .then()
            .statusCode(200);
        geo("cp.tracking.")
            .body("{}")
        .when()
            .post("/tracking/v0/list-trackers")
        .then()
            .statusCode(200)
            .body("Entries.TrackerName", not(hasItem("fleet")));
    }
}
