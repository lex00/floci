package io.github.hectorvent.floci.services.connect;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class ConnectDirectoryIntegrationTest {

    private static String instanceId;
    private static String instanceArn;

    @BeforeEach
    void createInstance() {
        if (instanceId != null) {
            return;
        }
        instanceId = given()
            .contentType("application/json")
            .body("{\"IdentityManagementType\":\"CONNECT_MANAGED\",\"InstanceAlias\":\"floci-directory\","
                    + "\"InboundCallsEnabled\":true,\"OutboundCallsEnabled\":true}")
        .when()
            .put("/instance")
        .then()
            .statusCode(200)
            .extract().path("Id");
        instanceArn = given().when().get("/instance/" + instanceId).then().statusCode(200)
            .extract().path("Instance.Arn");
    }

    @Test
    void searchClaimDescribeAndReleasePhoneNumber() {
        String number = given()
            .contentType("application/json")
            .body("{\"TargetArn\":\"" + instanceArn + "\",\"PhoneNumberCountryCode\":\"US\","
                    + "\"PhoneNumberType\":\"TOLL_FREE\",\"MaxResults\":3}")
        .when()
            .post("/phone-number/search-available")
        .then()
            .statusCode(200)
            .body("AvailableNumbersList", hasSize(3))
            .body("AvailableNumbersList.PhoneNumber", everyItem(startsWith("+1800")))
            .body("AvailableNumbersList.PhoneNumberType", everyItem(equalTo("TOLL_FREE")))
            .extract().path("AvailableNumbersList[0].PhoneNumber");

        String phoneNumberId = given()
            .contentType("application/json")
            .body("{\"InstanceId\":\"" + instanceId + "\",\"PhoneNumber\":\"" + number + "\","
                    + "\"Tags\":{\"team\":\"cx\"}}")
        .when()
            .post("/phone-number/claim")
        .then()
            .statusCode(200)
            .body("PhoneNumberArn", startsWith("arn:aws:connect:us-east-1:"))
            .extract().path("PhoneNumberId");

        given()
        .when()
            .get("/phone-number/" + phoneNumberId)
        .then()
            .statusCode(200)
            .body("ClaimedPhoneNumberSummary.PhoneNumber", equalTo(number))
            .body("ClaimedPhoneNumberSummary.TargetArn", equalTo(instanceArn))
            .body("ClaimedPhoneNumberSummary.PhoneNumberStatus.Status", equalTo("CLAIMED"))
            .body("ClaimedPhoneNumberSummary.Tags.team", equalTo("cx"));

        given()
            .contentType("application/json")
            .body("{\"TargetArn\":\"" + instanceArn + "\",\"PhoneNumberCountryCode\":\"US\","
                    + "\"PhoneNumberType\":\"TOLL_FREE\"}")
        .when()
            .post("/phone-number/search-available")
        .then()
            .statusCode(200)
            .body("AvailableNumbersList.PhoneNumber", not(hasItem(number)));

        given().when().delete("/phone-number/" + phoneNumberId).then().statusCode(200);
        given().when().get("/phone-number/" + phoneNumberId).then().statusCode(404);
    }

    @Test
    void hierarchyStructureUpdatesAndClears() {
        given()
        .when()
            .get("/user-hierarchy-structure/" + instanceId)
        .then()
            .statusCode(200)
            .body("HierarchyStructure", anEmptyMap());

        given()
            .contentType("application/json")
            .body("{\"HierarchyStructure\":{\"LevelOne\":{\"Name\":\"Region\"},\"LevelTwo\":{\"Name\":\"Team\"}}}")
        .when()
            .post("/user-hierarchy-structure/" + instanceId)
        .then()
            .statusCode(200);

        String levelOneId = given()
        .when()
            .get("/user-hierarchy-structure/" + instanceId)
        .then()
            .statusCode(200)
            .body("HierarchyStructure.LevelOne.Name", equalTo("Region"))
            .body("HierarchyStructure.LevelTwo.Name", equalTo("Team"))
            .body("HierarchyStructure.LevelOne.Arn", startsWith(instanceArn + "/agent-group-level/"))
            .body("HierarchyStructure.LevelOne.LastModifiedTime", notNullValue())
            .extract().path("HierarchyStructure.LevelOne.Id");

        given()
            .contentType("application/json")
            .body("{\"HierarchyStructure\":{\"LevelOne\":{\"Name\":\"Region\"}}}")
        .when()
            .post("/user-hierarchy-structure/" + instanceId)
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/user-hierarchy-structure/" + instanceId)
        .then()
            .statusCode(200)
            .body("HierarchyStructure.LevelOne.Id", equalTo(levelOneId))
            .body("HierarchyStructure.LevelTwo", nullValue());

        given()
            .contentType("application/json")
            .body("{\"HierarchyStructure\":{\"LevelTwo\":{\"Name\":\"Team\"}}}")
        .when()
            .post("/user-hierarchy-structure/" + instanceId)
        .then()
            .statusCode(400);
    }
}
