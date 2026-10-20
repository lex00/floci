package io.github.hectorvent.floci.services.codecommit;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class CodeCommitIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void repositoryLifecycle() {
        String arn = codecommit("CreateRepository")
                .body("{\"repositoryName\":\"it-repo\",\"repositoryDescription\":\"first\",\"tags\":{\"team\":\"a\"}}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repositoryMetadata.repositoryName", equalTo("it-repo"))
                .body("repositoryMetadata.accountId", equalTo("000000000000"))
                .body("repositoryMetadata.cloneUrlHttp", startsWith("https://git-codecommit.us-east-1."))
                .body("repositoryMetadata.creationDate", notNullValue())
                .extract().path("repositoryMetadata.Arn");

        codecommit("CreateRepository")
                .body("{\"repositoryName\":\"it-repo\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("RepositoryNameExistsException"));

        codecommit("UpdateRepositoryDescription")
                .body("{\"repositoryName\":\"it-repo\",\"repositoryDescription\":\"second\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200);

        codecommit("GetRepository")
                .body("{\"repositoryName\":\"it-repo\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repositoryMetadata.repositoryDescription", equalTo("second"))
                .body("repositoryMetadata.Arn", equalTo(arn));

        codecommit("ListTagsForResource")
                .body("{\"resourceArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("tags.team", equalTo("a"));

        codecommit("ListRepositories")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repositories.repositoryName", hasItem("it-repo"));

        codecommit("DeleteRepository")
                .body("{\"repositoryName\":\"it-repo\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repositoryId", notNullValue());

        codecommit("GetRepository")
                .body("{\"repositoryName\":\"it-repo\"}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("RepositoryDoesNotExistException"));

        codecommit("ListRepositories")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repositories.repositoryName", not(hasItem("it-repo")));
    }

    private static RequestSpecification codecommit(String action) {
        return given()
                .header("X-Amz-Target", "CodeCommit_20150413." + action)
                .contentType("application/x-amz-json-1.1");
    }
}
