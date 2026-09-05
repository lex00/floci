package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * A permissions boundary was storable through PutRolePermissionsBoundary but was never
 * serialized back into any response, so the Terraform provider read {@code null} for it on
 * every refresh and planned the same attach forever. CreateRole's own
 * {@code PermissionsBoundary} parameter was ignored outright.
 *
 * <p>The inclusion is asymmetric the same way tags are: ListRoles and ListUsers document
 * PermissionsBoundary among the attributes their subset deliberately omits, and the negative
 * cases below pin that half.
 */
@QuarkusTest
class IamPermissionsBoundaryInResponseTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260905/us-east-1/iam/aws4_request";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ecs-tasks.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static final String BOUNDARY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":[\"s3:*\",\"logs:*\"],\"Resource\":\"*\"}]}";

    private static io.restassured.specification.RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String createBoundaryPolicy(String policyName) {
        return iam("CreatePolicy")
            .formParam("PolicyName", policyName)
            .formParam("PolicyDocument", BOUNDARY_DOCUMENT)
        .when().post("/").then()
            .statusCode(200)
            .extract().path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    // ── Detail operations must return the boundary ────────────────────────────

    @Test
    void createRoleWithABoundaryEchoesItAndGetRoleReadsItBack() {
        String boundaryArn = createBoundaryPolicy("BoundaryEchoPolicy");
        String role = "BoundaryEchoRole";

        iam("CreateRole")
            .formParam("RoleName", role)
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("PermissionsBoundary", boundaryArn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<PermissionsBoundary>"))
            .body(containsString("<PermissionsBoundaryType>PermissionsBoundaryPolicy</PermissionsBoundaryType>"))
            .body(containsString("<PermissionsBoundaryArn>" + boundaryArn + "</PermissionsBoundaryArn>"));

        iam("GetRole")
            .formParam("RoleName", role)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<PermissionsBoundaryType>PermissionsBoundaryPolicy</PermissionsBoundaryType>"))
            .body(containsString("<PermissionsBoundaryArn>" + boundaryArn + "</PermissionsBoundaryArn>"));
    }

    @Test
    void boundaryAttachedAfterCreationAppearsOnGetRole() {
        String boundaryArn = createBoundaryPolicy("LaterBoundaryPolicy");
        String role = "LaterBoundaryRole";

        iam("CreateRole")
            .formParam("RoleName", role)
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString("<PermissionsBoundary>")));

        iam("PutRolePermissionsBoundary")
            .formParam("RoleName", role)
            .formParam("PermissionsBoundary", boundaryArn)
        .when().post("/").then().statusCode(200);

        iam("GetRole")
            .formParam("RoleName", role)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<PermissionsBoundaryArn>" + boundaryArn + "</PermissionsBoundaryArn>"));
    }

    @Test
    void getRoleOmitsTheElementEntirelyWhenNoBoundaryIsSet() {
        String role = "UnboundedRole";

        iam("CreateRole")
            .formParam("RoleName", role)
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
        .when().post("/").then().statusCode(200);

        // An empty <PermissionsBoundary/> would read back as a boundary the caller never set.
        iam("GetRole")
            .formParam("RoleName", role)
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString("<PermissionsBoundary>")));
    }

    @Test
    void deletingTheBoundaryRemovesItFromGetRole() {
        String boundaryArn = createBoundaryPolicy("DetachedBoundaryPolicy");
        String role = "DetachedBoundaryRole";

        iam("CreateRole")
            .formParam("RoleName", role)
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("PermissionsBoundary", boundaryArn)
        .when().post("/").then().statusCode(200);

        iam("DeleteRolePermissionsBoundary")
            .formParam("RoleName", role)
        .when().post("/").then().statusCode(200);

        iam("GetRole")
            .formParam("RoleName", role)
        .when().post("/").then()
            .statusCode(200)
            .body(not(containsString("<PermissionsBoundary>")));
    }

    @Test
    void createUserWithABoundaryEchoesItAndGetUserReadsItBack() {
        String boundaryArn = createBoundaryPolicy("UserBoundaryEchoPolicy");
        String user = "BoundaryEchoUser";

        iam("CreateUser")
            .formParam("UserName", user)
            .formParam("PermissionsBoundary", boundaryArn)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<PermissionsBoundaryType>PermissionsBoundaryPolicy</PermissionsBoundaryType>"))
            .body(containsString("<PermissionsBoundaryArn>" + boundaryArn + "</PermissionsBoundaryArn>"));

        iam("GetUser")
            .formParam("UserName", user)
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<PermissionsBoundaryArn>" + boundaryArn + "</PermissionsBoundaryArn>"));
    }

    // ── Listing operations must keep omitting it ──────────────────────────────

    @Test
    void listRolesOmitsTheBoundaryEvenForABoundedRole() {
        String boundaryArn = createBoundaryPolicy("ListSubsetBoundaryPolicy");

        iam("CreateRole")
            .formParam("RoleName", "ListSubsetBoundaryRole")
            .formParam("Path", "/")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("PermissionsBoundary", boundaryArn)
        .when().post("/").then().statusCode(200);

        // "This operation does not return the following attributes, even though they are an
        // attribute of the returned object: PermissionsBoundary, RoleLastUsed, Tags."
        iam("ListRoles")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<RoleName>ListSubsetBoundaryRole</RoleName>"))
            .body(not(containsString("<PermissionsBoundary>")));
    }

    @Test
    void listUsersOmitsTheBoundaryEvenForABoundedUser() {
        String boundaryArn = createBoundaryPolicy("ListSubsetUserBoundaryPolicy");

        iam("CreateUser")
            .formParam("UserName", "ListSubsetBoundaryUser")
            .formParam("PermissionsBoundary", boundaryArn)
        .when().post("/").then().statusCode(200);

        iam("ListUsers")
        .when().post("/").then()
            .statusCode(200)
            .body(containsString("<UserName>ListSubsetBoundaryUser</UserName>"))
            .body(not(containsString("<PermissionsBoundary>")));
    }
}
