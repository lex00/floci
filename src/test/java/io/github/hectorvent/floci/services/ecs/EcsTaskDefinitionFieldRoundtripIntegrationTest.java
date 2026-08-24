package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * Regression coverage for lex00/floci#129: RegisterTaskDefinition/DescribeTaskDefinition dropped
 * most of the documented ContainerDefinition schema (dependsOn, linuxParameters, restartPolicy,
 * versionConsistency, memoryReservation, privileged, pseudoTerminal, readonlyRootFilesystem,
 * interactive, startTimeout, stopTimeout, user, firelensConfiguration, volumesFrom,
 * portMappings[].name) and TaskDefinition had no runtimePlatform field at all - confirmed
 * against terraform-aws-modules/terraform-aws-ecs's flagship fargate example, where
 * hashicorp/aws's own JSON diff on container_definitions (and runtime_platform's ForceNew
 * schema attribute) forced a replacement on every plan after the first apply, under stock
 * terraform too, before any choudoufu marker ever touched the resource.
 */
@QuarkusTest
class EcsTaskDefinitionFieldRoundtripIntegrationTest {

    private static final String TARGET = "AmazonEC2ContainerServiceV20141113.";
    private static final String CT = "application/x-amz-json-1.1";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then().statusCode(200)
                .extract().response();
    }

    @Test
    void registerTaskDefinitionRoundTripsRuntimePlatformAndContainerFieldsOnRegisterAndDescribe() {
        String body = "{"
                + "\"family\":\"ci-fidelity-td\","
                + "\"requiresCompatibilities\":[\"FARGATE\"],"
                + "\"networkMode\":\"awsvpc\","
                + "\"cpu\":\"512\",\"memory\":\"1024\","
                + "\"runtimePlatform\":{\"cpuArchitecture\":\"ARM64\",\"operatingSystemFamily\":\"LINUX\"},"
                + "\"containerDefinitions\":[{"
                + "  \"name\":\"app\",\"image\":\"nginx:latest\",\"essential\":true,"
                + "  \"memoryReservation\":100,"
                + "  \"portMappings\":[{\"name\":\"app-http\",\"containerPort\":80,\"hostPort\":80,\"protocol\":\"tcp\"}],"
                + "  \"dependsOn\":[{\"containerName\":\"sidecar\",\"condition\":\"START\"}],"
                + "  \"linuxParameters\":{\"initProcessEnabled\":true,\"capabilities\":{\"add\":[],\"drop\":[\"NET_RAW\"]}},"
                + "  \"restartPolicy\":{\"enabled\":true,\"ignoredExitCodes\":[1],\"restartAttemptPeriod\":60},"
                + "  \"versionConsistency\":\"disabled\","
                + "  \"interactive\":false,\"pseudoTerminal\":false,"
                + "  \"privileged\":false,\"readonlyRootFilesystem\":false,"
                + "  \"startTimeout\":30,\"stopTimeout\":120,"
                + "  \"user\":\"0\","
                + "  \"volumesFrom\":[{\"sourceContainer\":\"sidecar\",\"readOnly\":false}]"
                + "},{"
                + "  \"name\":\"sidecar\",\"image\":\"aws-for-fluent-bit:stable\",\"essential\":true,"
                + "  \"firelensConfiguration\":{\"type\":\"fluentbit\"}"
                + "}]"
                + "}";

        Response registered = call("RegisterTaskDefinition", body);
        registered.then()
                .body("taskDefinition.runtimePlatform.cpuArchitecture", equalTo("ARM64"))
                .body("taskDefinition.runtimePlatform.operatingSystemFamily", equalTo("LINUX"))
                .body("taskDefinition.containerDefinitions", hasSize(2))
                .body("taskDefinition.containerDefinitions[0].memoryReservation", equalTo(100))
                .body("taskDefinition.containerDefinitions[0].portMappings[0].name", equalTo("app-http"))
                .body("taskDefinition.containerDefinitions[0].dependsOn[0].containerName", equalTo("sidecar"))
                .body("taskDefinition.containerDefinitions[0].dependsOn[0].condition", equalTo("START"))
                .body("taskDefinition.containerDefinitions[0].linuxParameters.initProcessEnabled", equalTo(true))
                .body("taskDefinition.containerDefinitions[0].linuxParameters.capabilities.drop[0]", equalTo("NET_RAW"))
                .body("taskDefinition.containerDefinitions[0].restartPolicy.enabled", equalTo(true))
                .body("taskDefinition.containerDefinitions[0].restartPolicy.restartAttemptPeriod", equalTo(60))
                .body("taskDefinition.containerDefinitions[0].versionConsistency", equalTo("disabled"))
                .body("taskDefinition.containerDefinitions[0].readonlyRootFilesystem", equalTo(false))
                .body("taskDefinition.containerDefinitions[0].startTimeout", equalTo(30))
                .body("taskDefinition.containerDefinitions[0].stopTimeout", equalTo(120))
                .body("taskDefinition.containerDefinitions[0].user", equalTo("0"))
                .body("taskDefinition.containerDefinitions[0].volumesFrom[0].sourceContainer", equalTo("sidecar"))
                .body("taskDefinition.containerDefinitions[1].firelensConfiguration.type", equalTo("fluentbit"));

        // The wall this estate actually hit: a SECOND read (DescribeTaskDefinition), against
        // storage rather than the register response, has to carry the same fields - a stored
        // object that only looks right in the response that created it is exactly the read-path
        // half of this gap that a register-only assertion would miss.
        call("DescribeTaskDefinition", "{\"taskDefinition\":\"ci-fidelity-td\"}").then()
                .body("taskDefinition.runtimePlatform.cpuArchitecture", equalTo("ARM64"))
                .body("taskDefinition.runtimePlatform.operatingSystemFamily", equalTo("LINUX"))
                .body("taskDefinition.containerDefinitions[0].memoryReservation", equalTo(100))
                .body("taskDefinition.containerDefinitions[0].portMappings[0].name", equalTo("app-http"))
                .body("taskDefinition.containerDefinitions[0].dependsOn[0].containerName", equalTo("sidecar"))
                .body("taskDefinition.containerDefinitions[0].linuxParameters.initProcessEnabled", equalTo(true))
                .body("taskDefinition.containerDefinitions[0].restartPolicy.restartAttemptPeriod", equalTo(60))
                .body("taskDefinition.containerDefinitions[0].versionConsistency", equalTo("disabled"))
                .body("taskDefinition.containerDefinitions[0].startTimeout", equalTo(30))
                .body("taskDefinition.containerDefinitions[0].stopTimeout", equalTo(120))
                .body("taskDefinition.containerDefinitions[0].user", equalTo("0"))
                .body("taskDefinition.containerDefinitions[0].volumesFrom[0].sourceContainer", equalTo("sidecar"))
                .body("taskDefinition.containerDefinitions[1].firelensConfiguration.type", equalTo("fluentbit"));
    }

    @Test
    void registerTaskDefinitionWithNoOptionalFieldsStillOmitsThemRatherThanLeakingRawNulls() {
        // Guards the merge-order fix itself: a container definition that never mentions
        // restartPolicy/dependsOn/etc. must not grow those keys just because the response is
        // now built by merging the raw request first. floci's own computed fields (name, image,
        // essential, absence of the rest) still have to be the final word.
        String body = "{\"family\":\"ci-fidelity-td-bare\","
                + "\"containerDefinitions\":[{\"name\":\"web\",\"image\":\"nginx\",\"memory\":128}]}";

        call("RegisterTaskDefinition", body).then()
                .body("taskDefinition.containerDefinitions[0].name", equalTo("web"))
                .body("taskDefinition.containerDefinitions[0].essential", equalTo(true))
                .body("taskDefinition.containerDefinitions", hasSize(1))
                .body("taskDefinition.containsKey('runtimePlatform')", equalTo(false))
                .body("taskDefinition.containerDefinitions[0].containsKey('restartPolicy')", equalTo(false))
                .body("taskDefinition.containerDefinitions[0].containsKey('dependsOn')", equalTo(false))
                .body("taskDefinition.containerDefinitions[0].containsKey('linuxParameters')", equalTo(false));
    }
}
