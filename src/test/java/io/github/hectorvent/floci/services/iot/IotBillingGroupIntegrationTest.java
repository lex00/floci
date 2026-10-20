package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class IotBillingGroupIntegrationTest {

    @Test
    void billingGroupLifecycle() {
        String arn = given().contentType("application/json")
                .body("{\"billingGroupProperties\":{\"billingGroupDescription\":\"fleet\"},"
                        + "\"tags\":[{\"Key\":\"env\",\"Value\":\"it\"}]}")
            .when().post("/billing-groups/it-billing")
            .then().statusCode(200)
                .body("billingGroupName", equalTo("it-billing"))
                .body("billingGroupId", notNullValue())
                .body("billingGroupArn", equalTo("arn:aws:iot:us-east-1:000000000000:billinggroup/it-billing"))
                .extract().path("billingGroupArn");

        given().when().get("/billing-groups/it-billing")
            .then().statusCode(200)
                .body("version", equalTo(1))
                .body("billingGroupProperties.billingGroupDescription", equalTo("fleet"))
                .body("billingGroupMetadata.creationDate", notNullValue());

        given().contentType("application/json")
                .body("{\"billingGroupProperties\":{\"billingGroupDescription\":\"changed\"},\"expectedVersion\":1}")
            .when().patch("/billing-groups/it-billing")
            .then().statusCode(200).body("version", equalTo(2));
        given().contentType("application/json")
                .body("{\"billingGroupProperties\":{},\"expectedVersion\":1}")
            .when().patch("/billing-groups/it-billing")
            .then().statusCode(409).body("__type", equalTo("VersionConflictException"));

        given().queryParam("resourceArn", arn).when().get("/tags")
            .then().statusCode(200).body("tags.Key", hasItem("env"));

        given().when().get("/billing-groups?namePrefixFilter=it-")
            .then().statusCode(200).body("billingGroups.groupName", hasItem("it-billing"));

        given().when().delete("/billing-groups/it-billing").then().statusCode(200);
        given().when().get("/billing-groups/it-billing")
            .then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void roleAliasLifecycle() {
        given().contentType("application/json")
                .body("{\"roleArn\":\"arn:aws:iam::000000000000:role/device\",\"credentialDurationSeconds\":1800}")
            .when().post("/role-aliases/it-alias")
            .then().statusCode(200)
                .body("roleAliasArn", equalTo("arn:aws:iot:us-east-1:000000000000:rolealias/it-alias"));

        given().contentType("application/json")
                .body("{\"roleArn\":\"arn:aws:iam::000000000000:role/device\"}")
            .when().post("/role-aliases/it-alias")
            .then().statusCode(409).body("__type", equalTo("ResourceAlreadyExistsException"));

        given().when().get("/role-aliases/it-alias")
            .then().statusCode(200)
                .body("roleAliasDescription.roleArn", equalTo("arn:aws:iam::000000000000:role/device"))
                .body("roleAliasDescription.credentialDurationSeconds", equalTo(1800))
                .body("roleAliasDescription.owner", equalTo("000000000000"));

        given().contentType("application/json").body("{\"credentialDurationSeconds\":7200}")
            .when().put("/role-aliases/it-alias")
            .then().statusCode(200);
        given().when().get("/role-aliases")
            .then().statusCode(200).body("roleAliases", hasItem("it-alias"));

        given().when().delete("/role-aliases/it-alias").then().statusCode(200);
        given().when().get("/role-aliases")
            .then().statusCode(200).body("roleAliases", not(hasItem("it-alias")));
    }

    @Test
    void topicRuleDestinationLifecycle() {
        String arn = given().contentType("application/json")
                .body("{\"destinationConfiguration\":{\"httpUrlConfiguration\":"
                        + "{\"confirmationUrl\":\"https://example.com/confirm\"}}}")
            .when().post("/destinations")
            .then().statusCode(200)
                .body("topicRuleDestination.status", equalTo("IN_PROGRESS"))
                .body("topicRuleDestination.httpUrlProperties.confirmationUrl", equalTo("https://example.com/confirm"))
                .body("topicRuleDestination.arn",
                        startsWith("arn:aws:iot:us-east-1:000000000000:ruledestination/http/"))
                .extract().path("topicRuleDestination.arn");

        given().contentType("application/json")
                .body("{\"arn\":\"" + arn + "\",\"status\":\"ENABLED\"}")
            .when().patch("/destinations")
            .then().statusCode(200);

        given().when().get("/destinations/" + arn)
            .then().statusCode(200).body("topicRuleDestination.status", equalTo("ENABLED"));
        given().when().get("/destinations")
            .then().statusCode(200).body("destinationSummaries.arn", hasItem(arn));

        given().when().delete("/destinations/" + arn).then().statusCode(200);
        given().when().get("/destinations")
            .then().statusCode(200).body("destinationSummaries.arn", not(hasItem(arn)));
    }

    @Test
    void httpDestinationConfirmsItselfAfterCreate() {
        String arn = given().contentType("application/json")
                .body("{\"destinationConfiguration\":{\"httpUrlConfiguration\":"
                        + "{\"confirmationUrl\":\"https://example.com/self-confirm\"}}}")
            .when().post("/destinations")
            .then().statusCode(200)
                .body("topicRuleDestination.status", equalTo("IN_PROGRESS"))
                .extract().path("topicRuleDestination.arn");

        given().when().get("/destinations/" + arn)
            .then().statusCode(200)
                .body("topicRuleDestination.status", equalTo("ENABLED"))
                .body("topicRuleDestination.statusReason", nullValue());

        given().when().get("/confirmdestination/not-a-token")
            .then().statusCode(400).body("__type", equalTo("InvalidRequestException"));
        given().when().delete("/destinations/" + arn).then().statusCode(200);
    }
}
