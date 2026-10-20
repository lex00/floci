package io.github.hectorvent.floci.services.workspacesweb;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class WorkSpacesWebIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/workspaces-web/aws4_request";
    private static final String CERTIFICATE = "MIIDETCCAfmgAwIBAgIUKGVViE3sU8BQASbPBR7WZ/6dL34wDQYJKoZIhvcNAQELBQAwGDEWMBQGA1UEAwwNZmxvY2ktdGVz"
            + "dC1jYTAeFw0yNjEwMjAxNzUwMTVaFw0zNjEwMTcxNzUwMTVaMBgxFjAUBgNVBAMMDWZsb2NpLXRlc3QtY2EwggEiMA0GCSqG"
            + "SIb3DQEBAQUAA4IBDwAwggEKAoIBAQDBrmT3oK4nd21X/IY1Y7+G6Q+hl4zg/KTttCjRlQoKXBtiUhd14c0m98Kq2N5ndmMg"
            + "yOLQqHn+ZKbHuoLbhTLqkBCVUkWBx0pfDsy3Bj/POW/VlpCrVgwg7cvGU/RYxQbOw/daS907F5isBNzcVtaF6S2YrnaXJcMM"
            + "g5PfgO/YKptjLybqJUD5QqbySiGhkGNcjGbob8j/Jr03kEVEoIrVyBf+KPIv5DKy37IKjl4aAZqtF7Arprua9nDWN83mvV0T"
            + "vyp/1Wkv00N7NUJs7mruD6WQeHSI3+gAoBbElFt2SFRac0B9q2dTelFlFYxAmTf4eouGQyeUq8t/uxJgCmqhAgMBAAGjUzBR"
            + "MB0GA1UdDgQWBBSgH2Sv9OB3rl5fJBj2TiXKqb24izAfBgNVHSMEGDAWgBSgH2Sv9OB3rl5fJBj2TiXKqb24izAPBgNVHRMB"
            + "Af8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQAx5cPYkUrS9U1Z27/mme+vn8oDt6Ki9seYbpymnLYxlrFz27qukcHOKbXD"
            + "iGq2LJI1Ro7NSeemcGEtxnWkt1wOd/T7iD9U8zS1V+rYWDy+rENVbG5cpNLJsynzvSoTbqq7NMATWzWfbX1h7CxqUtfObSWX"
            + "WTSczjpvLlAfpNokmKAXJxoUF7HqvISpAjajdr3oGigF/WiW18LUlGVUtXS7uP1FGEqwpNxhRa2zcw6T6/8n8q3/RJMhucj1"
            + "uGPucFZsyQbGgG2eCrXLsNKp+gsTzBtxty8c7d/7Ud0A30c6WeDCTzYEJ3bxjoCNwCzSXgjypQSbGeZ/Nd7W9YakpkIx";

    private static RequestSpecification request() {
        return given().contentType("application/json").header("Authorization", AUTH_HEADER);
    }

    @Test
    void portalAssociationsAndTags() {
        String portalArn = request()
                .body("{\"displayName\":\"it-portal\",\"tags\":[{\"Key\":\"team\",\"Value\":\"euc\"}]}")
            .when().post("/portals")
            .then().statusCode(200)
                .body("portalArn", startsWith("arn:aws:workspaces-web:us-east-1:000000000000:portal/"))
                .body("portalEndpoint", notNullValue())
                .extract().path("portalArn");
        String browserArn = request().body("{\"browserPolicy\":\"{\\\"chromePolicies\\\":{}}\"}")
            .when().post("/browserSettings")
            .then().statusCode(200).extract().path("browserSettingsArn");

        request().queryParam("browserSettingsArn", browserArn)
            .when().put("/portals/" + portalArn + "/browserSettings")
            .then().statusCode(200)
                .body("portalArn", equalTo(portalArn))
                .body("browserSettingsArn", equalTo(browserArn));
        request().when().get("/portals/" + portalArn)
            .then().statusCode(200)
                .body("portal.browserSettingsArn", equalTo(browserArn))
                .body("portal.portalStatus", equalTo("Incomplete"));
        request().when().get("/browserSettings/" + browserArn)
            .then().statusCode(200)
                .body("browserSettings.associatedPortalArns", hasItem(portalArn));
        request().when().get("/tags/" + portalArn)
            .then().statusCode(200)
                .body("tags[0].Key", equalTo("team"));

        request().when().delete("/browserSettings/" + browserArn)
            .then().statusCode(409);
        request().when().delete("/portals/" + portalArn + "/browserSettings")
            .then().statusCode(200);
        request().when().delete("/browserSettings/" + browserArn)
            .then().statusCode(200);
        request().body("{\"displayName\":\"renamed\"}")
            .when().put("/portals/" + portalArn)
            .then().statusCode(200)
                .body("portal.displayName", equalTo("renamed"));
        request().when().delete("/portals/" + portalArn)
            .then().statusCode(200);
        request().when().get("/portals/" + portalArn)
            .then().statusCode(404);
    }

    @Test
    void settingsResourcesCreateUpdateAndList() {
        String ipArn = request()
                .body("{\"displayName\":\"office\",\"ipRules\":[{\"ipRange\":\"10.0.0.0/16\"}]}")
            .when().post("/ipAccessSettings")
            .then().statusCode(200).extract().path("ipAccessSettingsArn");
        request().body("{\"description\":\"updated\"}")
            .when().patch("/ipAccessSettings/" + ipArn)
            .then().statusCode(200)
                .body("ipAccessSettings.description", equalTo("updated"))
                .body("ipAccessSettings.creationDate", notNullValue());
        request().when().get("/ipAccessSettings")
            .then().statusCode(200)
                .body("ipAccessSettings.ipAccessSettingsArn", hasItem(ipArn));

        String loggerArn = request()
                .body("{\"eventFilter\":{\"all\":{}},\"logConfiguration\":{\"s3\":{\"bucket\":\"b\","
                        + "\"folderStructure\":\"Flat\",\"logFileFormat\":\"Json\"}}}")
            .when().post("/sessionLoggers")
            .then().statusCode(200).extract().path("sessionLoggerArn");
        request().body("{\"displayName\":\"logger\"}")
            .when().post("/sessionLoggers/" + loggerArn)
            .then().statusCode(200)
                .body("sessionLogger.displayName", equalTo("logger"));

        request().body("{\"vpcId\":\"vpc-1\"}")
            .when().post("/networkSettings")
            .then().statusCode(400);
    }

    @Test
    void trustStoreCertificates() {
        String trustStoreArn = request().body("{\"certificateList\":[\"" + CERTIFICATE + "\"]}")
            .when().post("/trustStores")
            .then().statusCode(200).extract().path("trustStoreArn");
        String thumbprint = request().when().get("/trustStores/" + trustStoreArn + "/certificates")
            .then().statusCode(200)
                .body("certificateList", hasSize(1))
                .body("certificateList[0].subject", equalTo("CN=floci-test-ca"))
                .extract().path("certificateList[0].thumbprint");
        request().queryParam("thumbprint", thumbprint)
            .when().get("/trustStores/" + trustStoreArn + "/certificate")
            .then().statusCode(200)
                .body("certificate.body", equalTo(CERTIFICATE));
        request().when().delete("/trustStores/" + trustStoreArn)
            .then().statusCode(200);
    }
}
