package io.github.hectorvent.floci.services.kendra;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class KendraIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/kendra-index";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification call(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSKendraFrontendService." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/kendra/aws4_request")
                .body(body);
    }

    @Test
    void indexLifecycleWithTags() {
        String id = call("CreateIndex", """
                {"Name": "docs", "RoleArn": "%s", "Edition": "DEVELOPER_EDITION",
                 "Description": "Product docs", "Tags": [{"Key": "team", "Value": "search"}]}
                """.formatted(ROLE))
            .when().post("/")
            .then().statusCode(200)
            .extract().path("Id");

        call("DescribeIndex", "{\"Id\": \"" + id + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .body("Name", equalTo("docs"))
            .body("Status", equalTo("ACTIVE"))
            .body("Edition", equalTo("DEVELOPER_EDITION"))
            .body("RoleArn", equalTo(ROLE))
            .body("IndexStatistics.TextDocumentStatistics.IndexedTextDocumentsCount", equalTo(0));

        call("UpdateIndex", "{\"Id\": \"" + id + "\", \"Description\": \"Updated\"}")
            .when().post("/")
            .then().statusCode(200);

        call("ListIndices", "{}")
            .when().post("/")
            .then().statusCode(200)
            .body("IndexConfigurationSummaryItems.Id", hasItem(id));

        String arn = "arn:aws:kendra:us-east-1:000000000000:index/" + id;
        call("ListTagsForResource", "{\"ResourceARN\": \"" + arn + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .body("Tags[0].Key", equalTo("team"));

        call("DeleteIndex", "{\"Id\": \"" + id + "\"}")
            .when().post("/")
            .then().statusCode(200);

        call("ListIndices", "{}")
            .when().post("/")
            .then().statusCode(200)
            .body("IndexConfigurationSummaryItems.Id", not(hasItem(id)));

        call("DescribeIndex", "{\"Id\": \"" + id + "\"}")
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createIndexRequiresARoleArn() {
        call("CreateIndex", "{\"Name\": \"norole\"}")
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("ValidationException"));
    }
}
