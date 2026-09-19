package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.Collections.frequency;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class S3ConditionalWriteIntegrationTest {
    private static final String PRECONDITION_FAILED_MESSAGE =
            "At least one of the pre-conditions you specified did not hold";

    @Test
    void putObject_ifNoneMatchStar_succeedsWhenKeyMissing() {
        String bucket = createBucket("put-if-none-missing");

        given()
            .header("If-None-Match", "*")
            .body("first")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200)
            .header("ETag", notNullValue());

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void putObject_ifNoneMatchStar_412WhenKeyExistsAndDoesNotOverwrite() {
        String bucket = createBucket("put-if-none-existing");
        putObject(bucket, "object.txt", "first");

        ValidatableResponse response = given()
            .header("If-None-Match", "*")
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-None-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void concurrentPutObject_ifNoneMatchStar_allowsOnlyOneWriter()
            throws Exception {
        String bucket = createBucket("put-if-none-concurrent");
        String key = "object.txt";
        int writers = 16;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            List<Future<Integer>> futures = java.util.stream.IntStream.range(0, writers)
                    .mapToObj(writer -> executor.submit(() -> {
                        barrier.await();
                        return given()
                            .header("If-None-Match", "*")
                            .body("writer-" + writer)
                        .when()
                            .put("/" + bucket + "/" + key)
                        .then()
                            .extract()
                            .statusCode();
                    }))
                    .collect(toList());

            List<Integer> statusCodes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statusCodes.add(future.get(30, SECONDS));
            }

            assertEquals(1, frequency(statusCodes, 200));
            assertEquals(writers - 1, frequency(statusCodes, 412));
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void putObject_ifNoneMatchEtag_succeedsWhenEtagDiffers() {
        String bucket = createBucket("put-if-none-different");
        putObject(bucket, "object.txt", "first");

        given()
            .header("If-None-Match", "\"not-the-current-etag\"")
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        assertObjectBody(bucket, "object.txt", "second");
    }

    @Test
    void putObject_ifNoneMatchEtag_412WhenEtagMatches() {
        String bucket = createBucket("put-if-none-match");
        String eTag = putObject(bucket, "object.txt", "first");

        ValidatableResponse response = given()
            .header("If-None-Match", eTag)
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-None-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void putObject_ifMatch_succeedsOnMatch() {
        String bucket = createBucket("put-if-match");
        String eTag = putObject(bucket, "object.txt", "first");

        given()
            .header("If-Match", eTag)
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        assertObjectBody(bucket, "object.txt", "second");
    }

    @Test
    void putObject_ifMatch_412OnMismatch() {
        String bucket = createBucket("put-if-match-wrong");
        putObject(bucket, "object.txt", "first");

        ValidatableResponse response = given()
            .header("If-Match", "\"not-the-current-etag\"")
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void putObject_headerValueWithAndWithoutQuotes_bothHonoured() {
        String bucket = createBucket("put-quotes");
        String eTag = putObject(bucket, "object.txt", "first");

        given()
            .header("If-Match", stripQuotes(eTag))
            .body("second")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(200);

        String currentETag = given()
            .when()
                .head("/" + bucket + "/object.txt")
            .then()
                .statusCode(200)
                .extract().header("ETag");

        ValidatableResponse response = given()
            .header("If-None-Match", stripQuotes(currentETag))
            .body("third")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-None-Match");

        response = given()
            .header("If-None-Match", "\"*\"")
            .body("third")
        .when()
            .put("/" + bucket + "/object.txt")
        .then();

        assertPreconditionFailed(response, "If-None-Match");

        assertObjectBody(bucket, "object.txt", "second");
    }

    /**
     * A conditional write checks an existing object, so with no object to check the answer is
     * 404 NoSuchKey rather than 412. The S3 User Guide is explicit under "Conditional write
     * behavior": with an If-Match header, "if there's no current object version with the same
     * name, or if the current object version is a delete marker, the operation fails with a
     * 404 Not Found error". A caller that mistakes this for 412 keeps retrying a conditional
     * overwrite instead of falling back to an unconditional create, which is the trap
     * lex00/floci#213 reported after hitting it in a hand-written test double.
     */
    @Test
    void putObject_ifMatch_404WhenKeyIsMissing() {
        String bucket = createBucket("put-if-match-missing");

        given()
            .header("If-Match", "\"6805f2cfc46c0f04559748bb039d69ae\"")
            .body("should not be stored")
        .when()
            .put("/" + bucket + "/absent.txt")
        .then()
            .statusCode(404)
            .body("Error.Code", equalTo("NoSuchKey"));

        given()
        .when()
            .head("/" + bucket + "/absent.txt")
        .then()
            .statusCode(404);
    }

    @Test
    void putObject_ifMatch_404WhenTheKeyWasDeleted() {
        String bucket = createBucket("put-if-match-deleted");
        String eTag = putObject(bucket, "object.txt", "first");

        given()
        .when()
            .delete("/" + bucket + "/object.txt")
        .then()
            .statusCode(204);

        given()
            .header("If-Match", eTag)
            .body("should not be stored")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(404)
            .body("Error.Code", equalTo("NoSuchKey"));
    }

    @Test
    void putObject_ifMatch_404WhenTheCurrentVersionIsADeleteMarker() {
        String bucket = createBucket("put-if-match-delete-marker");
        given()
            .contentType("application/xml")
            .body("<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>")
        .when()
            .put("/" + bucket + "?versioning")
        .then()
            .statusCode(200);

        String eTag = putObject(bucket, "object.txt", "first");

        given()
        .when()
            .delete("/" + bucket + "/object.txt")
        .then()
            .statusCode(204);

        given()
            .header("If-Match", eTag)
            .body("should not be stored")
        .when()
            .put("/" + bucket + "/object.txt")
        .then()
            .statusCode(404)
            .body("Error.Code", equalTo("NoSuchKey"));
    }

    @Test
    void completeMultipartUpload_ifMatch_404WhenKeyIsMissing() {
        String bucket = createBucket("mpu-if-match-missing");
        String uploadId = initiateMultipartUpload(bucket, "object.txt");
        String partETag = uploadPart(bucket, "object.txt", uploadId, 1, "first");

        given()
            .contentType("application/xml")
            .header("If-Match", "\"6805f2cfc46c0f04559748bb039d69ae\"")
            .body(completeMultipartXml(1, partETag))
        .when()
            .post("/" + bucket + "/object.txt?uploadId=" + uploadId)
        .then()
            .statusCode(404)
            .body("Error.Code", equalTo("NoSuchKey"));
    }

    @Test
    void completeMultipartUpload_ifNoneMatchStar_412WhenKeyExists() {
        String bucket = createBucket("mpu-if-none-existing");
        putObject(bucket, "object.txt", "first");
        String uploadId = initiateMultipartUpload(bucket, "object.txt");
        String partETag = uploadPart(bucket, "object.txt", uploadId, 1, "second");

        ValidatableResponse response = given()
            .contentType("application/xml")
            .header("If-None-Match", "*")
            .body(completeMultipartXml(1, partETag))
        .when()
            .post("/" + bucket + "/object.txt?uploadId=" + uploadId)
        .then();

        assertPreconditionFailed(response, "If-None-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    @Test
    void completeMultipartUpload_ifMatch_succeedsOnMatch() {
        String bucket = createBucket("mpu-if-match");
        String eTag = putObject(bucket, "object.txt", "first");
        String uploadId = initiateMultipartUpload(bucket, "object.txt");
        String partETag = uploadPart(bucket, "object.txt", uploadId, 1, "second");

        given()
            .contentType("application/xml")
            .header("If-Match", eTag)
            .body(completeMultipartXml(1, partETag))
        .when()
            .post("/" + bucket + "/object.txt?uploadId=" + uploadId)
        .then()
            .statusCode(200)
            .body(containsString("<CompleteMultipartUploadResult"));

        assertObjectBody(bucket, "object.txt", "second");
    }

    @Test
    void completeMultipartUpload_ifMatch_412OnMismatchAndDoesNotOverwrite() {
        String bucket = createBucket("mpu-if-match-wrong");
        putObject(bucket, "object.txt", "first");
        String uploadId = initiateMultipartUpload(bucket, "object.txt");
        uploadPart(bucket, "object.txt", uploadId, 1, "second");

        ValidatableResponse response = given()
            .contentType("application/xml")
            .header("If-Match", "\"not-the-current-etag\"")
            .body(completeMultipartXml(1))
        .when()
            .post("/" + bucket + "/object.txt?uploadId=" + uploadId)
        .then();

        assertPreconditionFailed(response, "If-Match");

        assertObjectBody(bucket, "object.txt", "first");
    }

    private static String createBucket(String label) {
        String bucket = "cond-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
        given()
        .when()
            .put("/" + bucket)
        .then()
            .statusCode(200);
        return bucket;
    }

    private static String putObject(String bucket, String key, String body) {
        return given()
            .body(body)
        .when()
            .put("/" + bucket + "/" + key)
        .then()
            .statusCode(200)
            .extract().header("ETag");
    }

    private static void assertObjectBody(String bucket, String key, String body) {
        given()
        .when()
            .get("/" + bucket + "/" + key)
        .then()
            .statusCode(200)
            .body(equalTo(body));
    }

    private static void assertPreconditionFailed(ValidatableResponse response, String condition) {
        response.statusCode(412)
                .body("Error.Code", equalTo("PreconditionFailed"))
                .body("Error.Message", equalTo(PRECONDITION_FAILED_MESSAGE))
                .body("Error.Condition", equalTo(condition));
    }

    private static String initiateMultipartUpload(String bucket, String key) {
        return given()
            .contentType("application/octet-stream")
        .when()
            .post("/" + bucket + "/" + key + "?uploads")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("InitiateMultipartUploadResult.UploadId");
    }

    private static String uploadPart(String bucket, String key, String uploadId, int partNumber, String body) {
        return given()
            .body(body)
        .when()
            .put("/" + bucket + "/" + key + "?uploadId=" + uploadId + "&partNumber=" + partNumber)
            .then()
            .statusCode(200)
            .header("ETag", notNullValue())
            .extract().header("ETag");
    }

    private static String completeMultipartXml(int partNumber) {
        return completeMultipartXml(partNumber, "etag");
    }

    private static String completeMultipartXml(int partNumber, String eTag) {
        return """
                <CompleteMultipartUpload>
                    <Part><PartNumber>%d</PartNumber><ETag>%s</ETag></Part>
                </CompleteMultipartUpload>""".formatted(partNumber, eTag);
    }

    private static String stripQuotes(String eTag) {
        return eTag.replace("\"", "");
    }
}
