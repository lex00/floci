package io.github.hectorvent.floci.services.codecommit;

import io.github.hectorvent.floci.testing.DockerAvailability;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pushes to and clones from a CodeCommit repository's clone URL with the git command line. */
@QuarkusTest
class CodeCommitGitDockerIntegrationTest {

    @TempDir
    Path work;

    @BeforeAll
    static void requireDockerAndGit() throws InterruptedException {
        Assumptions.assumeTrue(DockerAvailability.isAvailable(), "Docker daemon must be available for CodeCommit Git");
        Assumptions.assumeTrue(gitAvailable(), "The git command line must be installed for CodeCommit Git");
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void pushedCommitsCloneBack() throws Exception {
        given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/codecommit/aws4_request")
                .header("X-Amz-Target", "CodeCommit_20150413.CreateRepository")
                .contentType("application/x-amz-json-1.1")
                .body("{\"repositoryName\":\"git-it\"}")
        .when().post("/").then().statusCode(200)
                .body("repositoryMetadata.cloneUrlHttp", endsWith("/v1/repos/git-it"));

        String url = "http://localhost:" + RestAssured.port + "/v1/repos/git-it";
        Path source = Files.createDirectories(work.resolve("source"));
        git(source, "init", "--quiet", "--initial-branch=main");
        Files.writeString(source.resolve("README.md"), "hello from floci\n");
        git(source, "add", "README.md");
        git(source, "-c", "user.name=floci", "-c", "user.email=floci@example.com", "commit", "--quiet", "-m", "first");
        git(source, "push", "--quiet", url, "main");

        Path clone = work.resolve("clone");
        git(work, "clone", "--quiet", url, clone.toString());
        assertEquals("hello from floci\n", Files.readString(clone.resolve("README.md")));

        given().when().get("/v1/repos/missing-repo/info/refs?service=git-upload-pack").then().statusCode(404);
        given().when().get("/v1/repos/git-it/info/refs").then().statusCode(403);

        given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/codecommit/aws4_request")
                .header("X-Amz-Target", "CodeCommit_20150413.DeleteRepository")
                .contentType("application/x-amz-json-1.1")
                .body("{\"repositoryName\":\"git-it\"}")
        .when().post("/").then().statusCode(200);
    }

    private static void git(Path directory, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git", "-c", "credential.helper=", "-c", "protocol.version=2"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed: " + output);
        }
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
