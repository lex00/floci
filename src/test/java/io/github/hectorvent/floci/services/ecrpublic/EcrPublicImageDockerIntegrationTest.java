package io.github.hectorvent.floci.services.ecrpublic;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** Pushes an image into an ECR Public repository through the layer and image operations, then pulls it. */
@QuarkusTest
class EcrPublicImageDockerIntegrationTest {

    private static final String ACCOUNT = "000000000000";

    @BeforeAll
    static void requireDocker() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for ECR Public images");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void pushedImageIsDescribedPulledAndDeleted() throws Exception {
        String unique = UUID.randomUUID().toString();
        String repository = "images-it-" + unique.substring(0, 8);
        call("CreateRepository", "{\"repositoryName\":\"" + repository + "\"}").when().post("/").then().statusCode(200);

        byte[] config = ("{\"architecture\":\"amd64\",\"os\":\"linux\",\"rootfs\":{\"type\":\"layers\",\"diff_ids\":[]},"
                + "\"comment\":\"" + unique + "\"}").getBytes(StandardCharsets.UTF_8);
        byte[] layer = ("layer-bytes-" + unique).getBytes(StandardCharsets.UTF_8);
        String configDigest = upload(repository, config);
        String layerDigest = upload(repository, layer);

        call("BatchCheckLayerAvailability", """
                {"repositoryName":"%s","layerDigests":["%s","sha256:%s"]}"""
                .formatted(repository, layerDigest, "0".repeat(64)))
        .when().post("/").then().statusCode(200)
            .body("layers[0].layerAvailability", equalTo("AVAILABLE"))
            .body("layers[0].layerSize", equalTo(layer.length))
            .body("layers[1].layerAvailability", equalTo("UNAVAILABLE"));

        String manifest = """
                {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json",
                 "config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"%s","size":%d},
                 "layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"%s","size":%d}]}"""
                .formatted(configDigest, config.length, layerDigest, layer.length).replace("\n", "");
        String digest = call("PutImage", new ObjectMapper().writeValueAsString(
                Map.of("repositoryName", repository, "imageManifest", manifest, "imageTag", "v1",
                        "imageManifestMediaType", "application/vnd.oci.image.manifest.v1+json")))
        .when().post("/").then().statusCode(200)
            .body("image.imageId.imageTag", equalTo("v1"))
            .extract().path("image.imageId.imageDigest");

        call("DescribeImages", "{\"repositoryName\":\"" + repository + "\"}")
        .when().post("/").then().statusCode(200)
            .body("imageDetails", hasSize(1))
            .body("imageDetails[0].imageDigest", equalTo(digest))
            .body("imageDetails[0].imageTags", contains("v1"));
        call("DescribeImageTags", "{\"repositoryName\":\"" + repository + "\"}")
        .when().post("/").then().statusCode(200)
            .body("imageTagDetails[0].imageTag", equalTo("v1"))
            .body("imageTagDetails[0].imageDetail.imageDigest", equalTo(digest));

        given().header("Host", "public.ecr.localhost:4566")
                .header("Accept", "application/vnd.oci.image.manifest.v1+json")
        .when().get("/v2/" + ACCOUNT + "/" + repository + "/manifests/v1")
        .then().statusCode(200).header("Docker-Content-Digest", equalTo(digest));

        call("DeleteRepository", "{\"repositoryName\":\"" + repository + "\"}")
        .when().post("/").then().statusCode(400).body("__type", equalTo("RepositoryNotEmptyException"));
        call("BatchDeleteImage", "{\"repositoryName\":\"" + repository + "\",\"imageIds\":[{\"imageTag\":\"v1\"},"
                + "{\"imageTag\":\"missing\"}]}")
        .when().post("/").then().statusCode(200)
            .body("imageIds[0].imageDigest", equalTo(digest))
            .body("failures[0].failureCode", equalTo("ImageNotFound"));
        call("DeleteRepository", "{\"repositoryName\":\"" + repository + "\",\"force\":true}")
        .when().post("/").then().statusCode(200);
    }

    private String upload(String repository, byte[] blob) throws Exception {
        String uploadId = call("InitiateLayerUpload", "{\"repositoryName\":\"" + repository + "\"}")
        .when().post("/").then().statusCode(200).extract().path("uploadId");
        call("UploadLayerPart", """
                {"repositoryName":"%s","uploadId":"%s","partFirstByte":0,"partLastByte":%d,"layerPartBlob":"%s"}"""
                .formatted(repository, uploadId, blob.length - 1, Base64.getEncoder().encodeToString(blob)))
        .when().post("/").then().statusCode(200).body("lastByteReceived", equalTo(blob.length - 1));
        String digest = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(blob));
        call("CompleteLayerUpload", "{\"repositoryName\":\"" + repository + "\",\"uploadId\":\"" + uploadId
                + "\",\"layerDigests\":[\"" + digest + "\"]}")
        .when().post("/").then().statusCode(200).body("layerDigest", equalTo(digest));
        return digest;
    }

    private static RequestSpecification call(String action, String body) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/ecr-public/aws4_request")
                .header("X-Amz-Target", "SpencerFrontendService." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body);
    }
}
