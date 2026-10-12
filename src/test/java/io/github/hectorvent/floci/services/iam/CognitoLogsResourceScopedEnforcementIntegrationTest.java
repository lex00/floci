package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.EnforcementFixtures;
import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.github.hectorvent.floci.testing.PartitionCleanup;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.util.UUID;

import static io.github.hectorvent.floci.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.config;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With {@code iam.enforcement-enabled=true}, a Cognito or CloudWatch Logs action is evaluated
 * against the user pool or log group it names, so a grant scoped to that resource's ARN allows the
 * call and a grant on another pool or group does not.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class CognitoLogsResourceScopedEnforcementIntegrationTest {

    @TestHTTPResource("/")
    static URI baseUri;

    @RegisterExtension
    final PartitionCleanup cleanup = new PartitionCleanup();

    private static final String ACCOUNT = "111111111111";
    private static final String COGNITO_TARGET = "AWSCognitoIdentityProviderService.";
    private static final String LOGS_TARGET = "Logs_20140328.";

    @Test
    void aGrantOnOneUserPoolAllowsThatPoolOnly() {
        String granted = createUserPool();
        String other = createUserPool();
        String accessKeyId = userWithPolicy(allow("cognito-idp:DescribeUserPool",
                "arn:aws:cognito-idp:us-east-1:" + ACCOUNT + ":userpool/" + granted));

        assertEquals(granted, json(cognito(accessKeyId, "DescribeUserPool",
                "{\"UserPoolId\":\"" + granted + "\"}"), 200).getString("UserPool.Id"));
        assertAccessDenied(cognito(accessKeyId, "DescribeUserPool", "{\"UserPoolId\":\"" + other + "\"}"));
    }

    @Test
    void aUserPoolGrantCoversTheClientsInThatPool() {
        String granted = createUserPool();
        String other = createUserPool();
        String accessKeyId = userWithPolicy(allow("cognito-idp:ListUserPoolClients",
                "arn:aws:cognito-idp:us-east-1:" + ACCOUNT + ":userpool/" + granted));

        cognito(accessKeyId, "ListUserPoolClients", "{\"UserPoolId\":\"" + granted + "\",\"MaxResults\":10}")
                .then().statusCode(200);
        assertAccessDenied(cognito(accessKeyId, "ListUserPoolClients",
                "{\"UserPoolId\":\"" + other + "\",\"MaxResults\":10}"));
    }

    /**
     * The other group is granted by its ARN without the trailing {@code :*}. The CloudWatch Logs API
     * Reference has a policy use the {@code :*} form for every action but tagging, because the bare
     * group ARN does not match the resource AWS checks.
     */
    @Test
    void aGrantOnOneLogGroupAllowsReadingThatGroupOnly() {
        String granted = createLogGroup();
        String other = createLogGroup();
        String accessKeyId = userWithPolicy("{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Action\":\"logs:FilterLogEvents\",\"Resource\":["
                + "\"arn:aws:logs:us-east-1:" + ACCOUNT + ":log-group:" + granted + ":*\","
                + "\"arn:aws:logs:us-east-1:" + ACCOUNT + ":log-group:" + other + "\"]}]}");

        logs(accessKeyId, "FilterLogEvents", "{\"logGroupName\":\"" + granted + "\"}").then().statusCode(200);
        assertAccessDenied(logs(accessKeyId, "FilterLogEvents", "{\"logGroupName\":\"" + other + "\"}"));
    }

    @Test
    void aQueryIsAllowedOnlyWhenEveryGroupItNamesIs() {
        String granted = createLogGroup();
        String other = createLogGroup();
        String accessKeyId = userWithPolicy(allow("logs:StartQuery",
                "arn:aws:logs:us-east-1:" + ACCOUNT + ":log-group:" + granted + ":*"));

        logs(accessKeyId, "StartQuery", startQuery("\"" + granted + "\"")).then().statusCode(200);
        assertAccessDenied(logs(accessKeyId, "StartQuery", startQuery("\"" + granted + "\",\"" + other + "\"")));
    }

    @Test
    void aGrantOnALogGroupArnAllowsCreatingThatGroupOnly() {
        String granted = "/scoped/" + UUID.randomUUID().toString().substring(0, 8);
        String other = "/scoped/" + UUID.randomUUID().toString().substring(0, 8);
        registerLogGroupRemoval(granted);
        registerLogGroupRemoval(other);
        String accessKeyId = userWithPolicy(allow("logs:CreateLogGroup",
                "arn:aws:logs:us-east-1:" + ACCOUNT + ":log-group:" + granted + ":*"));

        logs(accessKeyId, "CreateLogGroup", "{\"logGroupName\":\"" + granted + "\"}").then().statusCode(200);
        assertAccessDenied(logs(accessKeyId, "CreateLogGroup", "{\"logGroupName\":\"" + other + "\"}"));
    }

    @Test
    void aGrantOnALogStreamArnAllowsWritingThatStreamOnly() {
        String group = createLogGroup();
        String accessKeyId = userWithPolicy(allow("logs:CreateLogStream",
                "arn:aws:logs:us-east-1:" + ACCOUNT + ":log-group:" + group + ":log-stream:web-*"));

        logs(accessKeyId, "CreateLogStream", "{\"logGroupName\":\"" + group + "\",\"logStreamName\":\"web-1\"}")
                .then().statusCode(200);
        assertAccessDenied(logs(accessKeyId, "CreateLogStream",
                "{\"logGroupName\":\"" + group + "\",\"logStreamName\":\"worker-1\"}"));
    }

    private static String allow(String action, String resource) {
        return "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"" + action
                + "\",\"Resource\":\"" + resource + "\"}]}";
    }

    private static String startQuery(String groups) {
        return "{\"logGroupNames\":[" + groups + "],\"queryString\":\"fields @message\","
                + "\"startTime\":0,\"endTime\":1}";
    }

    /** AccessDeniedException, which a JSON 1.1 service answers with HTTP 400. */
    private static void assertAccessDenied(Response response) {
        assertEquals("AccessDeniedException", json(response, 400).getString("__type"));
    }

    private static JsonPath json(Response response, int status) {
        response.then().statusCode(status);
        return JsonPath.from(response.asString());
    }

    private static Response cognito(String accessKeyId, String operation, String body) {
        return json11(accessKeyId, "cognito-idp", COGNITO_TARGET + operation, body);
    }

    private static Response logs(String accessKeyId, String operation, String body) {
        return json11(accessKeyId, "logs", LOGS_TARGET + operation, body);
    }

    private static Response json11(String accessKeyId, String service, String target, String body) {
        return given().port(baseUri.getPort())
            .config(config().encoderConfig(EncoderConfig.encoderConfig()
                    .encodeContentTypeAs("application/x-amz-json-1.1", ContentType.JSON)))
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", target)
            .header("Authorization", auth(accessKeyId, service))
            .body(body)
        .when().post("/");
    }

    private String createUserPool() {
        String id = json(cognito(ACCOUNT, "CreateUserPool",
                "{\"PoolName\":\"scoped-" + UUID.randomUUID().toString().substring(0, 8) + "\"}"), 200)
                .getString("UserPool.Id");
        cleanup.register(() -> cognito(ACCOUNT, "DeleteUserPool", "{\"UserPoolId\":\"" + id + "\"}"));
        return id;
    }

    private String createLogGroup() {
        String name = "/scoped/" + UUID.randomUUID().toString().substring(0, 8);
        registerLogGroupRemoval(name);
        logs(ACCOUNT, "CreateLogGroup", "{\"logGroupName\":\"" + name + "\"}").then().statusCode(200);
        return name;
    }

    private void registerLogGroupRemoval(String name) {
        cleanup.register(() -> logs(ACCOUNT, "DeleteLogGroup", "{\"logGroupName\":\"" + name + "\"}"));
    }

    /** An IAM user in {@link #ACCOUNT} holding {@code policy} inline. */
    private String userWithPolicy(String policy) {
        String user = "scoped-" + UUID.randomUUID().toString().substring(0, 8);
        String accessKeyId = EnforcementFixtures.createUser(cleanup, baseUri.getPort(),
                auth(ACCOUNT, "iam"), user).accessKeyId();
        given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                .formParam("PolicyDocument", policy)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        return accessKeyId;
    }
}
