package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class ApiGatewayClientCertificateIntegrationTest {

    @Test
    void generateGetUpdateTagAndDelete() {
        String id = given()
                .contentType(ContentType.JSON)
                .body("{\"description\":\"backend mTLS\",\"tags\":{\"team\":\"edge\"}}")
                .when().post("/clientcertificates")
                .then()
                .statusCode(201)
                .body("description", equalTo("backend mTLS"))
                .body("pemEncodedCertificate", startsWith("-----BEGIN CERTIFICATE-----"))
                .body("expirationDate", greaterThan(0))
                .body("tags.team", equalTo("edge"))
                .extract().path("clientCertificateId");

        given().when().get("/clientcertificates/" + id)
                .then().statusCode(200)
                .body("clientCertificateId", equalTo(id));

        given().when().get("/clientcertificates")
                .then().statusCode(200)
                .body("item.clientCertificateId", hasItem(id));

        given().contentType(ContentType.JSON)
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/description\",\"value\":\"rotated\"}]}")
                .when().patch("/clientcertificates/" + id)
                .then().statusCode(200)
                .body("description", equalTo("rotated"));

        String arn = "arn:aws:apigateway:us-east-1::/clientcertificates/" + id;
        given().contentType(ContentType.JSON)
                .body("{\"tags\":{\"env\":\"dev\"}}")
                .pathParam("arn", arn).when().put("/tags/{arn}")
                .then().statusCode(204);
        given().pathParam("arn", arn).when().get("/tags/{arn}")
                .then().statusCode(200)
                .body("tags.env", equalTo("dev"));

        given().when().delete("/clientcertificates/" + id).then().statusCode(202);
        given().when().get("/clientcertificates/" + id)
                .then().statusCode(404);
        given().when().get("/clientcertificates")
                .then().statusCode(200)
                .body("item.clientCertificateId", not(hasItem(id)));
    }

    @Test
    void generateAcceptsAnEmptyBody() {
        given().when().post("/clientcertificates")
                .then().statusCode(201)
                .body("pemEncodedCertificate", startsWith("-----BEGIN CERTIFICATE-----"));
    }
}
