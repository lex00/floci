package io.github.hectorvent.floci.services.comprehend;

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
class ComprehendModelIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/comprehend-access";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static RequestSpecification call(String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "Comprehend_20171127." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/comprehend/aws4_request")
                .body(body);
    }

    @Test
    void documentClassifierLifecycle() {
        String arn = call("CreateDocumentClassifier", """
                {"DocumentClassifierName": "tickets", "VersionName": "v1", "LanguageCode": "en",
                 "DataAccessRoleArn": "%s",
                 "InputDataConfig": {"S3Uri": "s3://training/tickets.csv"},
                 "Tags": [{"Key": "team", "Value": "support"}]}
                """.formatted(ROLE))
            .when().post("/")
            .then().statusCode(200)
            .body("DocumentClassifierArn",
                    equalTo("arn:aws:comprehend:us-east-1:000000000000:document-classifier/tickets/version/v1"))
            .extract().path("DocumentClassifierArn");

        call("DescribeDocumentClassifier", "{\"DocumentClassifierArn\": \"" + arn + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .body("DocumentClassifierProperties.Status", equalTo("TRAINED"))
            .body("DocumentClassifierProperties.Mode", equalTo("MULTI_CLASS"))
            .body("DocumentClassifierProperties.InputDataConfig.S3Uri", equalTo("s3://training/tickets.csv"))
            .body("DocumentClassifierProperties.DataAccessRoleArn", equalTo(ROLE));

        call("ListDocumentClassifiers", "{\"Filter\": {\"DocumentClassifierName\": \"tickets\"}}")
            .when().post("/")
            .then().statusCode(200)
            .body("DocumentClassifierPropertiesList.DocumentClassifierArn", hasItem(arn));

        call("ListTagsForResource", "{\"ResourceArn\": \"" + arn + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .body("Tags[0].Key", equalTo("team"));

        call("CreateDocumentClassifier", """
                {"DocumentClassifierName": "tickets", "VersionName": "v1", "LanguageCode": "en",
                 "DataAccessRoleArn": "%s", "InputDataConfig": {"S3Uri": "s3://training/tickets.csv"}}
                """.formatted(ROLE))
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("ResourceInUseException"));

        call("DeleteDocumentClassifier", "{\"DocumentClassifierArn\": \"" + arn + "\"}")
            .when().post("/")
            .then().statusCode(200);

        call("DescribeDocumentClassifier", "{\"DocumentClassifierArn\": \"" + arn + "\"}")
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void entityRecognizerLifecycleAndValidation() {
        String arn = call("CreateEntityRecognizer", """
                {"RecognizerName": "products", "LanguageCode": "en", "DataAccessRoleArn": "%s",
                 "InputDataConfig": {"EntityTypes": [{"Type": "PRODUCT"}],
                                     "Documents": {"S3Uri": "s3://training/docs"},
                                     "EntityList": {"S3Uri": "s3://training/entities.csv"}}}
                """.formatted(ROLE))
            .when().post("/")
            .then().statusCode(200)
            .extract().path("EntityRecognizerArn");

        call("DescribeEntityRecognizer", "{\"EntityRecognizerArn\": \"" + arn + "\"}")
            .when().post("/")
            .then().statusCode(200)
            .body("EntityRecognizerProperties.Status", equalTo("TRAINED"))
            .body("EntityRecognizerProperties.RecognizerMetadata.EntityTypes[0].Type", equalTo("PRODUCT"));

        call("ListDocumentClassifiers", "{}")
            .when().post("/")
            .then().statusCode(200)
            .body("DocumentClassifierPropertiesList.DocumentClassifierArn", not(hasItem(arn)));

        call("CreateEntityRecognizer", """
                {"RecognizerName": "bad", "LanguageCode": "xx", "DataAccessRoleArn": "%s",
                 "InputDataConfig": {"EntityTypes": [{"Type": "PRODUCT"}]}}
                """.formatted(ROLE))
            .when().post("/")
            .then().statusCode(400)
            .body("__type", equalTo("UnsupportedLanguageException"));

        call("DeleteEntityRecognizer", "{\"EntityRecognizerArn\": \"" + arn + "\"}")
            .when().post("/")
            .then().statusCode(200);
    }
}
