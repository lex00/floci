package io.github.hectorvent.floci.services.rekognition;

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
class RekognitionCollectionIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification call(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "RekognitionService." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/rekognition/aws4_request")
                .body(body);
    }

    @Test
    void collectionLifecycleWithTags() {
        String arn = call("CreateCollection", "{\"CollectionId\": \"faces-1\", \"Tags\": {\"team\": \"vision\"}}")
            .when().post("/")
            .then().statusCode(200)
            .body("StatusCode", equalTo(200))
            .body("CollectionArn", equalTo("arn:aws:rekognition:us-east-1:000000000000:collection/faces-1"))
            .extract().path("CollectionArn");

        call("CreateCollection", "{\"CollectionId\": \"faces-1\"}")
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("ResourceAlreadyExistsException"));

        call("DescribeCollection", "{\"CollectionId\": \"faces-1\"}")
            .when().post("/")
            .then().statusCode(200)
            .body("CollectionARN", equalTo(arn))
            .body("FaceCount", equalTo(0));

        call("ListCollections", "{}")
            .when().post("/")
            .then().statusCode(200)
            .body("CollectionIds", hasItem("faces-1"));

        call("TagResource", "{\"ResourceArn\": \"" + arn + "\", \"Tags\": {\"env\": \"dev\"}}")
            .when().post("/").then().statusCode(200);
        call("UntagResource", "{\"ResourceArn\": \"" + arn + "\", \"TagKeys\": [\"team\"]}")
            .when().post("/").then().statusCode(200);
        call("ListTagsForResource", "{\"ResourceArn\": \"" + arn + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .body("Tags.env", equalTo("dev"))
            .body("Tags.size()", equalTo(1));

        call("DeleteCollection", "{\"CollectionId\": \"faces-1\"}")
            .when().post("/")
            .then().statusCode(200)
            .body("StatusCode", equalTo(200));

        call("ListCollections", "{}")
            .when().post("/")
            .then().statusCode(200)
            .body("CollectionIds", not(hasItem("faces-1")));

        call("DescribeCollection", "{\"CollectionId\": \"faces-1\"}")
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void createCollectionRejectsAnInvalidId() {
        call("CreateCollection", "{\"CollectionId\": \"bad id\"}")
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("InvalidParameterException"));
    }
}
