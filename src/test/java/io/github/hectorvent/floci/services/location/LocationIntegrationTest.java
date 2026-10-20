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

    @Test
    void geofencesDevicePositionsAndRoutes() {
        geo("cp.geofencing.").body("{\"CollectionName\": \"zones\"}")
            .when().post("/geofencing/v0/collections").then().statusCode(200);
        geo("geofencing.").body("""
                {"Geometry": {"Polygon": [[[-123.2, 49.2], [-123.0, 49.2], [-123.0, 49.3], [-123.2, 49.3], [-123.2, 49.2]]]},
                 "GeofenceProperties": {"kind": "depot"}}""")
            .when().put("/geofencing/v0/collections/zones/geofences/depot")
            .then().statusCode(200)
            .body("GeofenceId", equalTo("depot"))
            .body("CreateTime", notNullValue());
        geo("geofencing.").body("""
                {"Entries": [{"GeofenceId": "ring", "Geometry": {"Circle": {"Center": [-123.1, 49.25], "Radius": 500}}},
                             {"GeofenceId": "bad", "Geometry": {}}]}""")
            .when().post("/geofencing/v0/collections/zones/put-geofences")
            .then().statusCode(200)
            .body("Successes.GeofenceId", hasItem("ring"))
            .body("Errors[0].GeofenceId", equalTo("bad"))
            .body("Errors[0].Error.Code", equalTo("ValidationError"));
        geo("geofencing.").when().get("/geofencing/v0/collections/zones/geofences/depot")
            .then().statusCode(200)
            .body("Status", equalTo("ACTIVE"))
            .body("GeofenceProperties.kind", equalTo("depot"));
        geo("cp.geofencing.").when().get("/geofencing/v0/collections/zones")
            .then().statusCode(200).body("GeofenceCount", equalTo(2));

        geo("cp.tracking.").body("{\"TrackerName\": \"fleet\"}")
            .when().post("/tracking/v0/trackers").then().statusCode(200);
        geo("tracking.").body("""
                {"Updates": [{"DeviceId": "truck-1", "SampleTime": "2026-01-01T10:00:00Z", "Position": [-123.1, 49.25]},
                             {"DeviceId": "truck-1", "SampleTime": "2026-01-01T10:05:00Z", "Position": [-123.05, 49.26]}]}""")
            .when().post("/tracking/v0/trackers/fleet/positions")
            .then().statusCode(200).body("Errors.size()", equalTo(0));
        geo("tracking.").when().get("/tracking/v0/trackers/fleet/devices/truck-1/positions/latest")
            .then().statusCode(200)
            .body("SampleTime", equalTo("2026-01-01T10:05:00Z"))
            .body("Position[0]", equalTo(-123.05f));
        geo("tracking.").body("{}")
            .when().post("/tracking/v0/trackers/fleet/devices/truck-1/list-positions")
            .then().statusCode(200).body("DevicePositions.size()", equalTo(2));
        geo("tracking.").body("{}")
            .when().post("/tracking/v0/trackers/fleet/list-positions")
            .then().statusCode(200).body("Entries[0].DeviceId", equalTo("truck-1"));

        geo("cp.routes.").body("{\"CalculatorName\": \"router\", \"DataSource\": \"Esri\"}")
            .when().post("/routes/v0/calculators").then().statusCode(200);
        geo("routes.").body("""
                {"DeparturePosition": [-123.1, 49.25], "DestinationPosition": [-122.9, 49.2], "IncludeLegGeometry": true}""")
            .when().post("/routes/v0/calculators/router/calculate/route")
            .then().statusCode(200)
            .body("Legs.size()", equalTo(1))
            .body("Legs[0].Geometry.LineString.size()", equalTo(2))
            .body("Summary.DataSource", equalTo("Esri"))
            .body("Summary.DistanceUnit", equalTo("Kilometers"));
    }
}
