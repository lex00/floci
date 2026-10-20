package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class SageMakerAppImageConfigIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/sagemaker/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void appImageConfigLifecycleAndTags() {
        String arn = post("CreateAppImageConfig", """
                {"AppImageConfigName":"it-aic",
                 "KernelGatewayImageConfig":{"KernelSpecs":[{"Name":"python3","DisplayName":"Python 3"}],
                   "FileSystemConfig":{"MountPath":"/home/sagemaker-user","DefaultUid":1000,"DefaultGid":100}},
                 "Tags":[{"Key":"env","Value":"it"}]}
                """)
                .then().statusCode(200)
                .body("AppImageConfigArn", equalTo("arn:aws:sagemaker:us-east-1:000000000000:app-image-config/it-aic"))
                .extract().path("AppImageConfigArn");

        post("CreateAppImageConfig", "{\"AppImageConfigName\":\"it-aic\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceInUse"));

        post("DescribeAppImageConfig", "{\"AppImageConfigName\":\"it-aic\"}")
                .then().statusCode(200)
                .body("KernelGatewayImageConfig.KernelSpecs[0].Name", equalTo("python3"))
                .body("KernelGatewayImageConfig.FileSystemConfig.DefaultUid", equalTo(1000))
                .body("CreationTime", notNullValue());

        post("UpdateAppImageConfig", """
                {"AppImageConfigName":"it-aic","JupyterLabAppImageConfig":{"ContainerConfig":{"ContainerArguments":["--x"]}}}
                """)
                .then().statusCode(200).body("AppImageConfigArn", equalTo(arn));
        post("DescribeAppImageConfig", "{\"AppImageConfigName\":\"it-aic\"}")
                .then().statusCode(200)
                .body("JupyterLabAppImageConfig.ContainerConfig.ContainerArguments[0]", equalTo("--x"))
                .body("KernelGatewayImageConfig", equalTo(null));

        post("ListAppImageConfigs", "{\"NameContains\":\"it-\"}")
                .then().statusCode(200).body("AppImageConfigs.AppImageConfigName", hasItem("it-aic"));

        post("ListTags", "{\"ResourceArn\":\"" + arn + "\"}")
                .then().statusCode(200).body("Tags[0].Key", equalTo("env"));

        post("DeleteAppImageConfig", "{\"AppImageConfigName\":\"it-aic\"}").then().statusCode(200);
        post("DescribeAppImageConfig", "{\"AppImageConfigName\":\"it-aic\"}")
                .then().statusCode(400).body("__type", equalTo("ResourceNotFound"));
        post("ListAppImageConfigs", "{}")
                .then().statusCode(200).body("AppImageConfigs.AppImageConfigName", not(hasItem("it-aic")));
    }

    private Response post(String action, String body) {
        return given().header("Authorization", AUTH)
                .header("X-Amz-Target", "SageMaker." + action)
                .contentType("application/x-amz-json-1.1")
                .body(body)
                .when().post("/");
    }
}
