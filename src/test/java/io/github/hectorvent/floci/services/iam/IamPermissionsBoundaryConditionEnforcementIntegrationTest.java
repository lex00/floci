package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The delegation guardrail: an admin may create roles, but only roles that carry a specific
 * permissions boundary. AWS expresses that as a {@code StringEquals} on
 * {@code iam:PermissionsBoundary}, and populates that key from the {@code PermissionsBoundary}
 * parameter of the request.
 *
 * <p>The condition context resolver had no IAM cases at all, so the key was never present and
 * the condition never matched — the guardrail denied every CreateRole, boundary or not, which
 * looks identical to a policy that simply does not work.
 */
@QuarkusTest
@TestProfile(IamPermissionsBoundaryConditionEnforcementIntegrationTest.IamEnforcementProfile.class)
class IamPermissionsBoundaryConditionEnforcementIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String ROOT_AKID = "test";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static String boundaryArn;
    private static String delegatedAccessKeyId;

    // RestAssured's port is only wired up by the Quarkus extension's beforeEach callback, so
    // this provisioning cannot live in @BeforeAll; the guard keeps it to one delegated admin
    // shared by every test in the class.
    @BeforeEach
    void provisionDelegatedAdmin() {
        if (delegatedAccessKeyId != null) {
            return;
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        boundaryArn = root("CreatePolicy")
                .formParam("PolicyName", "wp-permission-boundary-" + suffix)
                .formParam("PolicyDocument", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {"Effect": "Allow", "Action": ["s3:*", "logs:*"], "Resource": "*"}
                      ]
                    }
                    """)
        .when().post("/").then()
                .statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        String userName = "delegated-admin-" + suffix;
        root("CreateUser").formParam("UserName", userName)
        .when().post("/").then().statusCode(200);

        root("PutUserPolicy")
                .formParam("UserName", userName)
                .formParam("PolicyName", "CreateRolesInsideBoundary")
                .formParam("PolicyDocument", """
                    {
                      "Version": "2012-10-17",
                      "Statement": [
                        {
                          "Effect": "Allow",
                          "Action": "iam:CreateRole",
                          "Resource": "*",
                          "Condition": {
                            "StringEquals": {"iam:PermissionsBoundary": "%s"}
                          }
                        },
                        {"Effect": "Allow", "Action": "iam:GetRole", "Resource": "*"}
                      ]
                    }
                    """.formatted(boundaryArn))
        .when().post("/").then().statusCode(200);

        delegatedAccessKeyId = root("CreateAccessKey").formParam("UserName", userName)
        .when().post("/").then()
                .statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    @Test
    void createRoleWithoutTheBoundaryIsDenied() {
        // The key is absent from the request context, so the StringEquals cannot match and the
        // only Allow for iam:CreateRole does not apply.
        delegated("CreateRole")
                .formParam("RoleName", "unbounded-role-" + UUID.randomUUID().toString().substring(0, 8))
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"))
                .body(containsString("iam:CreateRole"));
    }

    @Test
    void createRoleWithTheRequiredBoundaryIsAllowed() {
        String roleName = "bounded-role-" + UUID.randomUUID().toString().substring(0, 8);

        delegated("CreateRole")
                .formParam("RoleName", roleName)
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .formParam("PermissionsBoundary", boundaryArn)
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<PermissionsBoundaryArn>" + boundaryArn + "</PermissionsBoundaryArn>"));

        delegated("GetRole").formParam("RoleName", roleName)
        .when().post("/").then()
                .statusCode(200)
                .body(containsString("<PermissionsBoundaryArn>" + boundaryArn + "</PermissionsBoundaryArn>"));
    }

    @Test
    void createRoleWithADifferentBoundaryIsDenied() {
        String otherArn = root("CreatePolicy")
                .formParam("PolicyName", "other-boundary-" + UUID.randomUUID().toString().substring(0, 8))
                .formParam("PolicyDocument",
                        "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                        + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}")
        .when().post("/").then()
                .statusCode(200)
                .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");

        delegated("CreateRole")
                .formParam("RoleName", "wrong-boundary-role-" + UUID.randomUUID().toString().substring(0, 8))
                .formParam("Path", "/")
                .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
                .formParam("PermissionsBoundary", otherArn)
        .when().post("/").then()
                .statusCode(403)
                .body(containsString("<Code>AccessDenied</Code>"));
    }

    private static io.restassured.specification.RequestSpecification root(String action) {
        return given().header("Authorization", auth(ROOT_AKID)).formParam("Action", action);
    }

    private static io.restassured.specification.RequestSpecification delegated(String action) {
        return given().header("Authorization", auth(delegatedAccessKeyId)).formParam("Action", action);
    }

    private static String auth(String accessKeyId) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260905/" + REGION
                + "/iam/aws4_request, SignedHeaders=host, Signature=abc";
    }

    public static final class IamEnforcementProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.iam.enforcement-enabled", "true");
        }
    }
}
