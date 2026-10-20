package io.github.hectorvent.floci.services.ecrpublic;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class EcrPublicIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void repositoryLifecycle() {
        String arn = ecrPublic("CreateRepository")
                .body("{\"repositoryName\":\"it-public\",\"catalogData\":{\"description\":\"demo\"},"
                        + "\"tags\":[{\"Key\":\"team\",\"Value\":\"a\"}]}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repository.repositoryArn", equalTo("arn:aws:ecr-public::000000000000:repository/it-public"))
                .body("repository.repositoryUri", matchesPattern("public\\.ecr\\.localhost:[0-9]+/000000000000/it-public"))
                .body("repository.createdAt", notNullValue())
                .body("catalogData.description", equalTo("demo"))
                .extract().path("repository.repositoryArn");

        ecrPublic("DescribeRepositories")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repositories.repositoryName", hasItem("it-public"));

        ecrPublic("PutRepositoryCatalogData")
                .body("{\"repositoryName\":\"it-public\",\"catalogData\":{\"aboutText\":\"about\"}}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("catalogData.aboutText", equalTo("about"));

        ecrPublic("SetRepositoryPolicy")
                .body("{\"repositoryName\":\"it-public\",\"policyText\":\"{}\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("policyText", equalTo("{}"));

        ecrPublic("GetRepositoryPolicy")
                .body("{\"repositoryName\":\"it-public\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("registryId", equalTo("000000000000"));

        ecrPublic("ListTagsForResource")
                .body("{\"resourceArn\":\"" + arn + "\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("tags[0].Key", equalTo("team"));

        ecrPublic("DeleteRepository")
                .body("{\"repositoryName\":\"it-public\"}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repository.repositoryName", equalTo("it-public"));

        ecrPublic("DescribeRepositories")
                .body("{\"repositoryNames\":[\"it-public\"]}")
        .when()
                .post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("RepositoryNotFoundException"));

        ecrPublic("DescribeRepositories")
                .body("{}")
        .when()
                .post("/")
        .then()
                .statusCode(200)
                .body("repositories.repositoryName", not(hasItem("it-public")));
    }

    private static RequestSpecification ecrPublic(String action) {
        return given()
                .header("X-Amz-Target", "SpencerFrontendService." + action)
                .contentType("application/x-amz-json-1.1");
    }
}
