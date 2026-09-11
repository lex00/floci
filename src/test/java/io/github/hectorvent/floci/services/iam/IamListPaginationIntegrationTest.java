package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

/**
 * Real AWS IAM pages {@code ListRoles}, {@code ListPolicies}, {@code ListPolicyVersions} and
 * {@code ListAttachedRolePolicies} at {@code MaxItems} (default and max 100) per the IAM API
 * reference, handing back {@code IsTruncated=true} and a {@code Marker} when more remain, and
 * honouring that {@code Marker} on the next call. floci used to return every matching item in a
 * single page with {@code IsTruncated} hardcoded {@code false} — this is the regression gate for
 * that gap (lex00/floci's tagging-api-iam-and-pagination issue, INTENTIUS/choudoufu#1046).
 *
 * <p>Every resource here lives under a path unique to this test class ({@link #PATH_PREFIX}) and
 * every assertion filters on it, so this class is independent of how many other roles/policies
 * the rest of the suite has created in the one shared in-memory account this
 * {@code @QuarkusTest} instance reuses across test classes.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamListPaginationIntegrationTest {

    private static final String IAM_CREDENTIAL =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static final String PATH_PREFIX = "/tagging-iam-pagination-test/";

    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"lambda.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";

    private static String attachedPolicyArn;

    private static void createRole(String roleName) {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", roleName)
            .formParam("Path", PATH_PREFIX)
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    private static String createPolicy(String policyName) {
        return given()
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", policyName)
            .formParam("Path", PATH_PREFIX)
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract()
            .path("CreatePolicyResponse.CreatePolicyResult.Policy.Arn");
    }

    // =========================================================================
    // ListRoles
    // =========================================================================

    @Test
    @Order(1)
    void createThreeRolesForListRolesPagination() {
        createRole("pgn-role-1");
        createRole("pgn-role-2");
        createRole("pgn-role-3");
    }

    @Test
    @Order(2)
    void listRolesFirstPageIsTruncatedAtMaxItems() {
        given()
            .formParam("Action", "ListRoles")
            .formParam("PathPrefix", PATH_PREFIX)
            .formParam("MaxItems", "2")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListRolesResponse.ListRolesResult.Roles.member.size()", equalTo(2))
            .body("ListRolesResponse.ListRolesResult.Roles.member[0].RoleName", equalTo("pgn-role-1"))
            .body("ListRolesResponse.ListRolesResult.Roles.member[1].RoleName", equalTo("pgn-role-2"))
            .body("ListRolesResponse.ListRolesResult.IsTruncated", equalTo("true"))
            .body("ListRolesResponse.ListRolesResult.Marker", not(emptyOrNullString()));
    }

    @Test
    @Order(3)
    void listRolesSecondPageHonoursTheMarkerAndIsNotTruncated() {
        String marker = given()
            .formParam("Action", "ListRoles")
            .formParam("PathPrefix", PATH_PREFIX)
            .formParam("MaxItems", "2")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract()
            .path("ListRolesResponse.ListRolesResult.Marker");

        given()
            .formParam("Action", "ListRoles")
            .formParam("PathPrefix", PATH_PREFIX)
            .formParam("MaxItems", "2")
            .formParam("Marker", marker)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListRolesResponse.ListRolesResult.Roles.member.size()", equalTo(1))
            .body("ListRolesResponse.ListRolesResult.Roles.member[0].RoleName", equalTo("pgn-role-3"))
            .body("ListRolesResponse.ListRolesResult.IsTruncated", equalTo("false"));
    }

    // =========================================================================
    // ListPolicies
    // =========================================================================

    @Test
    @Order(4)
    void createThreePoliciesForListPoliciesPagination() {
        createPolicy("pgn-policy-1");
        createPolicy("pgn-policy-2");
        createPolicy("pgn-policy-3");
    }

    @Test
    @Order(5)
    void listPoliciesFirstPageIsTruncatedAtMaxItems() {
        given()
            .formParam("Action", "ListPolicies")
            .formParam("Scope", "Local")
            .formParam("PathPrefix", PATH_PREFIX)
            .formParam("MaxItems", "2")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPoliciesResponse.ListPoliciesResult.Policies.member.size()", equalTo(2))
            .body("ListPoliciesResponse.ListPoliciesResult.IsTruncated", equalTo("true"))
            .body("ListPoliciesResponse.ListPoliciesResult.Marker", not(emptyOrNullString()));
    }

    @Test
    @Order(6)
    void listPoliciesSecondPageHonoursTheMarkerAndIsNotTruncated() {
        String marker = given()
            .formParam("Action", "ListPolicies")
            .formParam("Scope", "Local")
            .formParam("PathPrefix", PATH_PREFIX)
            .formParam("MaxItems", "2")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract()
            .path("ListPoliciesResponse.ListPoliciesResult.Marker");

        given()
            .formParam("Action", "ListPolicies")
            .formParam("Scope", "Local")
            .formParam("PathPrefix", PATH_PREFIX)
            .formParam("MaxItems", "2")
            .formParam("Marker", marker)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPoliciesResponse.ListPoliciesResult.Policies.member.size()", equalTo(1))
            .body("ListPoliciesResponse.ListPoliciesResult.IsTruncated", equalTo("false"));
    }

    // =========================================================================
    // ListPolicyVersions
    // =========================================================================

    @Test
    @Order(7)
    void createFourVersionsOfOnePolicyForListPolicyVersionsPagination() {
        attachedPolicyArn = createPolicy("pgn-versions-policy");
        // CreatePolicy already made v1 (the default version); add three more.
        for (int i = 0; i < 3; i++) {
            given()
                .formParam("Action", "CreatePolicyVersion")
                .formParam("PolicyArn", attachedPolicyArn)
                .formParam("PolicyDocument", POLICY_DOCUMENT)
                .header("Authorization", IAM_CREDENTIAL)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(8)
    void listPolicyVersionsFirstPageIsTruncatedAtMaxItems() {
        given()
            .formParam("Action", "ListPolicyVersions")
            .formParam("PolicyArn", attachedPolicyArn)
            .formParam("MaxItems", "2")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.Versions.member.size()",
                    equalTo(2))
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.Versions.member[0].VersionId",
                    equalTo("v1"))
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.Versions.member[1].VersionId",
                    equalTo("v2"))
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.IsTruncated", equalTo("true"))
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.Marker", not(emptyOrNullString()));
    }

    @Test
    @Order(9)
    void listPolicyVersionsSecondPageHonoursTheMarkerAndIsNotTruncated() {
        String marker = given()
            .formParam("Action", "ListPolicyVersions")
            .formParam("PolicyArn", attachedPolicyArn)
            .formParam("MaxItems", "2")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract()
            .path("ListPolicyVersionsResponse.ListPolicyVersionsResult.Marker");

        given()
            .formParam("Action", "ListPolicyVersions")
            .formParam("PolicyArn", attachedPolicyArn)
            .formParam("MaxItems", "2")
            .formParam("Marker", marker)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.Versions.member.size()",
                    equalTo(2))
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.Versions.member[0].VersionId",
                    equalTo("v3"))
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.Versions.member[1].VersionId",
                    equalTo("v4"))
            .body("ListPolicyVersionsResponse.ListPolicyVersionsResult.IsTruncated", equalTo("false"));
    }

    // =========================================================================
    // ListAttachedRolePolicies
    // =========================================================================

    private static final String ATTACH_ROLE = "pgn-attach-role";

    @Test
    @Order(10)
    void attachThreePoliciesToOneRoleForListAttachedRolePoliciesPagination() {
        createRole(ATTACH_ROLE);
        for (String policyName : new String[] {"pgn-attach-1", "pgn-attach-2", "pgn-attach-3"}) {
            String policyArn = createPolicy(policyName);
            given()
                .formParam("Action", "AttachRolePolicy")
                .formParam("RoleName", ATTACH_ROLE)
                .formParam("PolicyArn", policyArn)
                .header("Authorization", IAM_CREDENTIAL)
            .when()
                .post("/")
            .then()
                .statusCode(200);
        }
    }

    @Test
    @Order(11)
    void listAttachedRolePoliciesFirstPageIsTruncatedAtMaxItems() {
        given()
            .formParam("Action", "ListAttachedRolePolicies")
            .formParam("RoleName", ATTACH_ROLE)
            .formParam("MaxItems", "2")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.AttachedPolicies.member.size()",
                    equalTo(2))
            .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.IsTruncated", equalTo("true"))
            .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.Marker",
                    not(emptyOrNullString()));
    }

    @Test
    @Order(12)
    void listAttachedRolePoliciesSecondPageHonoursTheMarkerAndIsNotTruncated() {
        String marker = given()
            .formParam("Action", "ListAttachedRolePolicies")
            .formParam("RoleName", ATTACH_ROLE)
            .formParam("MaxItems", "2")
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
        .extract()
            .path("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.Marker");

        given()
            .formParam("Action", "ListAttachedRolePolicies")
            .formParam("RoleName", ATTACH_ROLE)
            .formParam("MaxItems", "2")
            .formParam("Marker", marker)
            .header("Authorization", IAM_CREDENTIAL)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.AttachedPolicies.member.size()",
                    equalTo(1))
            .body("ListAttachedRolePoliciesResponse.ListAttachedRolePoliciesResult.IsTruncated", equalTo("false"));
    }
}
