package io.github.hectorvent.floci.services.appflow;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class AppFlowIntegrationTest {

    private static final String ACCOUNT_ID = "000000000000";

    @Test
    void connectorProfileAndFlowRoundTrip() {
        appflow()
                .body("""
                        {"connectorProfileName":"it-sales","connectorType":"Salesforce","connectionMode":"Public",
                         "connectorProfileConfig":{
                           "connectorProfileProperties":{"Salesforce":{"instanceUrl":"https://example.my.salesforce.com"}},
                           "connectorProfileCredentials":{"Salesforce":{"accessToken":"secret-token"}}}}""")
        .when()
                .post("/create-connector-profile")
        .then()
                .statusCode(200)
                .body("connectorProfileArn",
                        equalTo("arn:aws:appflow:us-east-1:" + ACCOUNT_ID + ":connectorprofile/it-sales"));

        appflow()
                .body("{\"connectorProfileNames\":[\"it-sales\"]}")
        .when()
                .post("/describe-connector-profiles")
        .then()
                .statusCode(200)
                .body("connectorProfileDetails", hasSize(1))
                .body("connectorProfileDetails[0].connectorType", equalTo("Salesforce"))
                .body("connectorProfileDetails[0].connectionMode", equalTo("Public"))
                .body("connectorProfileDetails[0].connectorProfileProperties.Salesforce.instanceUrl",
                        equalTo("https://example.my.salesforce.com"))
                .body("connectorProfileDetails[0].credentialsArn", startsWith("arn:aws:secretsmanager:"))
                .body("connectorProfileDetails[0].createdAt", notNullValue())
                .body("connectorProfileDetails[0].connectorProfileCredentials", nullValue())
                .body(not(containsString("secret-token")));

        appflow()
                .body("""
                        {"connectorProfileName":"it-sales","connectionMode":"Private",
                         "connectorProfileConfig":{"connectorProfileProperties":{"Salesforce":{
                           "instanceUrl":"https://other.my.salesforce.com"}}}}""")
        .when()
                .post("/update-connector-profile")
        .then()
                .statusCode(200);

        String flowArn = appflow()
                .body("""
                        {"flowName":"it-sync","triggerConfig":{"triggerType":"OnDemand"},
                         "sourceFlowConfig":{"connectorType":"Salesforce","connectorProfileName":"it-sales",
                           "sourceConnectorProperties":{"Salesforce":{"object":"Account"}}},
                         "destinationFlowConfigList":[{"connectorType":"S3",
                           "destinationConnectorProperties":{"S3":{"bucketName":"out"}}}],
                         "tasks":[{"taskType":"Map","sourceFields":["Id"],"destinationField":"Id"}],
                         "tags":{"env":"test"}}""")
        .when()
                .post("/create-flow")
        .then()
                .statusCode(200)
                .body("flowStatus", equalTo("Active"))
                .extract().path("flowArn");

        appflow()
                .body("{\"flowName\":\"it-sync\"}")
        .when()
                .post("/describe-flow")
        .then()
                .statusCode(200)
                .body("flowArn", equalTo(flowArn))
                .body("sourceFlowConfig.connectorProfileName", equalTo("it-sales"))
                .body("destinationFlowConfigList[0].connectorType", equalTo("S3"))
                .body("triggerConfig.triggerType", equalTo("OnDemand"))
                .body("tags.env", equalTo("test"))
                .body("schemaVersion", equalTo(1));

        appflow()
                .body("{\"tags\":{\"team\":\"data\"}}")
        .when()
                .post("/tags/" + flowArn)
        .then()
                .statusCode(200);
        appflow()
        .when()
                .get("/tags/" + flowArn)
        .then()
                .statusCode(200)
                .body("tags.env", equalTo("test"))
                .body("tags.team", equalTo("data"));

        appflow()
                .body("{}")
        .when()
                .post("/list-flows")
        .then()
                .statusCode(200)
                .body("flows.flowName", hasItem("it-sync"));

        appflow()
                .body("{\"connectorProfileName\":\"it-sales\"}")
        .when()
                .post("/delete-connector-profile")
        .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"));

        appflow()
                .body("{\"flowName\":\"it-sync\"}")
        .when()
                .post("/delete-flow")
        .then()
                .statusCode(200);
        appflow()
                .body("{\"flowName\":\"it-sync\"}")
        .when()
                .post("/describe-flow")
        .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        appflow()
                .body("{\"connectorProfileName\":\"it-sales\"}")
        .when()
                .post("/delete-connector-profile")
        .then()
                .statusCode(200);
        appflow()
                .body("{\"connectorProfileNames\":[\"it-sales\"]}")
        .when()
                .post("/describe-connector-profiles")
        .then()
                .statusCode(200)
                .body("connectorProfileDetails", hasSize(0));
    }

    @Test
    void describeConnectorProfilesPages() {
        for (String name : new String[] {"it-page-a", "it-page-b", "it-page-c"}) {
            appflow()
                    .body("""
                            {"connectorProfileName":"%s","connectorType":"Zendesk","connectionMode":"Public",
                             "connectorProfileConfig":{"connectorProfileProperties":{"Zendesk":{
                               "instanceUrl":"https://x.zendesk.com"}}}}""".formatted(name))
            .when()
                    .post("/create-connector-profile")
            .then()
                    .statusCode(200);
        }
        String token = appflow()
                .body("{\"connectorType\":\"Zendesk\",\"maxResults\":2}")
        .when()
                .post("/describe-connector-profiles")
        .then()
                .statusCode(200)
                .body("connectorProfileDetails", hasSize(2))
                .body("nextToken", notNullValue())
                .extract().path("nextToken");
        appflow()
                .body("{\"connectorType\":\"Zendesk\",\"maxResults\":2,\"nextToken\":\"" + token + "\"}")
        .when()
                .post("/describe-connector-profiles")
        .then()
                .statusCode(200)
                .body("connectorProfileDetails", hasSize(1))
                .body("nextToken", nullValue());
    }

    @Test
    void createConnectorProfileValidatesRequiredMembers() {
        appflow()
                .body("{\"connectorProfileName\":\"it-bad\",\"connectorType\":\"Salesforce\"}")
        .when()
                .post("/create-connector-profile")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    private static RequestSpecification appflow() {
        return given()
                .contentType("application/json")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20260101/us-east-1/appflow/aws4_request");
    }
}
