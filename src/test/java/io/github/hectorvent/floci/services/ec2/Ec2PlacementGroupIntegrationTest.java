package io.github.hectorvent.floci.services.ec2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class Ec2PlacementGroupIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void createDescribeAndDeleteAPartitionGroup() {
        given()
            .formParam("Action", "CreatePlacementGroup")
            .formParam("GroupName", "pg-partition")
            .formParam("Strategy", "partition")
            .formParam("PartitionCount", "3")
            .formParam("TagSpecification.1.ResourceType", "placement-group")
            .formParam("TagSpecification.1.Tag.1.Key", "env")
            .formParam("TagSpecification.1.Tag.1.Value", "test")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreatePlacementGroupResponse.placementGroup.groupName", equalTo("pg-partition"))
            .body("CreatePlacementGroupResponse.placementGroup.groupId", startsWith("pg-"))
            .body("CreatePlacementGroupResponse.placementGroup.state", equalTo("available"))
            .body("CreatePlacementGroupResponse.placementGroup.partitionCount", equalTo("3"));

        given()
            .formParam("Action", "DescribePlacementGroups")
            .formParam("Filter.1.Name", "tag:env")
            .formParam("Filter.1.Value.1", "test")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribePlacementGroupsResponse.placementGroupSet.item.groupName", equalTo("pg-partition"))
            .body("DescribePlacementGroupsResponse.placementGroupSet.item.strategy", equalTo("partition"))
            .body("DescribePlacementGroupsResponse.placementGroupSet.item.tagSet.item.value", equalTo("test"))
            .body("DescribePlacementGroupsResponse.placementGroupSet.item.groupArn",
                    containsString(":placement-group/pg-partition"));

        given()
            .formParam("Action", "DeletePlacementGroup")
            .formParam("GroupName", "pg-partition")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DeletePlacementGroupResponse.return", equalTo("true"));

        given()
            .formParam("Action", "DescribePlacementGroups")
            .formParam("GroupName.1", "pg-partition")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidPlacementGroup.Unknown</Code>"));
    }

    @Test
    void duplicateNameAndBadStrategyAreRejected() {
        given()
            .formParam("Action", "CreatePlacementGroup")
            .formParam("GroupName", "pg-spread")
            .formParam("Strategy", "spread")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreatePlacementGroupResponse.placementGroup.spreadLevel", equalTo("rack"));

        given()
            .formParam("Action", "CreatePlacementGroup")
            .formParam("GroupName", "pg-spread")
            .formParam("Strategy", "spread")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidPlacementGroup.Duplicate</Code>"));

        given()
            .formParam("Action", "CreatePlacementGroup")
            .formParam("GroupName", "pg-bad")
            .formParam("Strategy", "nearby")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("<Code>InvalidParameterValue</Code>"));
    }
}
