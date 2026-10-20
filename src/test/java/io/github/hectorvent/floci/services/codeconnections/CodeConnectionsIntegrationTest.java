package io.github.hectorvent.floci.services.codeconnections;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CodeConnectionsIntegrationTest {

    private static final String TARGET = "com.amazonaws.codeconnections.CodeConnections_20231201.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void connectionLifecycle() {
        String arn = codeconnections("CreateConnection")
                .body("{\"ConnectionName\":\"it-conn\",\"ProviderType\":\"GitHub\","
                        + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"a\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("ConnectionArn", startsWith("arn:aws:codeconnections:us-east-1:"))
                .body("Tags[0].Key", equalTo("team"))
                .extract().path("ConnectionArn");

        codeconnections("GetConnection")
                .body("{\"ConnectionArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Connection.ConnectionName", equalTo("it-conn"))
                .body("Connection.ConnectionStatus", equalTo("PENDING"))
                .body("Connection.ProviderType", equalTo("GitHub"));

        codeconnections("ListConnections")
                .body("{\"ProviderTypeFilter\":\"GitHub\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Connections.ConnectionArn", hasItem(arn));

        codeconnections("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Tags[0].Value", equalTo("a"));

        codeconnections("DeleteConnection")
                .body("{\"ConnectionArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        codeconnections("GetConnection")
                .body("{\"ConnectionArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        codeconnections("ListConnections")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Connections.ConnectionArn", not(hasItem(arn)));
    }

    @Test
    void hostLifecycle() {
        String hostArn = codeconnections("CreateHost")
                .body("{\"Name\":\"it-host\",\"ProviderType\":\"GitHubEnterpriseServer\","
                        + "\"ProviderEndpoint\":\"https://ghes.example.com\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .extract().path("HostArn");

        codeconnections("GetHost")
                .body("{\"HostArn\":\"" + hostArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("Name", equalTo("it-host"))
                .body("Status", equalTo("PENDING"));

        codeconnections("DeleteHost")
                .body("{\"HostArn\":\"" + hostArn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);
    }

    private static RequestSpecification codeconnections(String action) {
        return given()
                .header("X-Amz-Target", TARGET + action)
                .contentType("application/x-amz-json-1.0");
    }
}
