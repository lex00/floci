package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

/**
 * What the tag index does with IAM, end to end: IAM's Query protocol writes the tags, the JSON 1.1
 * tagging API reads them back.
 *
 * <p>Real AWS was measured against a live account on 2026-09-14. A tagged IAM policy and a tagged
 * IAM instance profile come back from {@code GetResources} in us-east-1, where global IAM indexes,
 * and nowhere else. A tagged IAM role comes back from no region at all, however the tag was
 * written.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IamTaggingIndexIntegrationTest {

    private static final String JSON_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "ResourceGroupsTaggingAPI_20170126.";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private static final String US_EAST_1 =
            "AWS4-HMAC-SHA256 Credential=test/20260914/us-east-1/tagging/aws4_request";
    private static final String US_WEST_2 =
            "AWS4-HMAC-SHA256 Credential=test/20260914/us-west-2/tagging/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260914/us-east-1/iam/aws4_request";

    private static final String ESTATE_KEY = "tagindex-probe-estate";
    private static final String ESTATE_VALUE = "probe";
    private static final String POLICY_ONLY_KEY = "tagindex-probe-policy-only";
    private static final String ROLE_ONLY_KEY = "tagindex-probe-role-only";

    private static final String POLICY_ARN = "arn:aws:iam::000000000000:policy/tagindex-probe-policy";
    private static final String PROFILE_ARN =
            "arn:aws:iam::000000000000:instance-profile/tagindex-probe-profile";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/tagindex-probe-role";
    private static final String VOLUME_ARN =
            "arn:aws:ec2:us-west-2:000000000000:volume/vol-tagindexprobe";

    private static final String POLICY_DOCUMENT =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}";
    private static final String TRUST_POLICY =
            "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
            + "\"Principal\":{\"Service\":\"ec2.amazonaws.com\"},\"Action\":\"sts:AssumeRole\"}]}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void iamWritesTheTagsThroughItsOwnApi() {
        given()
            .header("Authorization", IAM_AUTH)
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", "tagindex-probe-policy")
            .formParam("PolicyDocument", POLICY_DOCUMENT)
            .formParam("Tags.member.1.Key", ESTATE_KEY)
            .formParam("Tags.member.1.Value", ESTATE_VALUE)
            .formParam("Tags.member.2.Key", POLICY_ONLY_KEY)
            .formParam("Tags.member.2.Value", "policy")
        .when().post("/")
        .then().statusCode(200);

        given()
            .header("Authorization", IAM_AUTH)
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "CreateInstanceProfile")
            .formParam("InstanceProfileName", "tagindex-probe-profile")
            .formParam("Tags.member.1.Key", ESTATE_KEY)
            .formParam("Tags.member.1.Value", ESTATE_VALUE)
        .when().post("/")
        .then().statusCode(200);

        given()
            .header("Authorization", IAM_AUTH)
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "CreateRole")
            .formParam("RoleName", "tagindex-probe-role")
            .formParam("AssumeRolePolicyDocument", TRUST_POLICY)
            .formParam("Tags.member.1.Key", ESTATE_KEY)
            .formParam("Tags.member.1.Value", ESTATE_VALUE)
            .formParam("Tags.member.2.Key", ROLE_ONLY_KEY)
            .formParam("Tags.member.2.Value", "role")
        .when().post("/")
        .then().statusCode(200);
    }

    /**
     * CreateInstanceProfile takes a Tags list and the tags are readable straight back, which is
     * what the tag index then has to find. Before this was fixed the create silently dropped them.
     */
    @Test
    @Order(2)
    void createInstanceProfileKeepsTheTagsItWasGiven() {
        given()
            .header("Authorization", IAM_AUTH)
            .contentType(FORM_CONTENT_TYPE)
            .formParam("Action", "ListInstanceProfileTags")
            .formParam("InstanceProfileName", "tagindex-probe-profile")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ListInstanceProfileTagsResponse.ListInstanceProfileTagsResult.Tags.member.Key",
                    equalTo(ESTATE_KEY))
            .body("ListInstanceProfileTagsResponse.ListInstanceProfileTagsResult.Tags.member.Value",
                    equalTo(ESTATE_VALUE));
    }

    @Test
    @Order(3)
    void aTaggedEc2VolumeInUsWest2IsTheControlForTheAbsenceArms() {
        given()
            .header("Authorization", US_WEST_2)
            .header("X-Amz-Target", TARGET_PREFIX + "TagResources")
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["%s"], "Tags": {"%s": "%s"}}
                """.formatted(VOLUME_ARN, ESTATE_KEY, ESTATE_VALUE))
        .when().post("/")
        .then().statusCode(200);
    }

    @Test
    @Order(4)
    void getResourcesServesTheIamPolicyInUsEast1() {
        given()
            .header("Authorization", US_EAST_1)
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(JSON_CONTENT_TYPE)
            .body("{\"ResourceTypeFilters\": [\"iam:policy\"]}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItem(POLICY_ARN));
    }

    @Test
    @Order(5)
    void getResourcesServesTheIamInstanceProfileInUsEast1() {
        given()
            .header("Authorization", US_EAST_1)
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(JSON_CONTENT_TYPE)
            .body("{\"ResourceTypeFilters\": [\"iam:instance-profile\"]}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItem(PROFILE_ARN));
    }

    @Test
    @Order(6)
    void aTagFilterInUsEast1FindsBothServedTypesAndNotTheRole() {
        given()
            .header("Authorization", US_EAST_1)
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "%s", "Values": ["%s"]}]}
                """.formatted(ESTATE_KEY, ESTATE_VALUE))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItems(POLICY_ARN, PROFILE_ARN))
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(ROLE_ARN)));
    }

    @Test
    @Order(7)
    void getResourcesNeverServesTheIamRoleEvenThoughItCarriesTheSameTag() {
        given()
            .header("Authorization", US_EAST_1)
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(JSON_CONTENT_TYPE)
            .body("{\"ResourceTypeFilters\": [\"iam:role\"]}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(ROLE_ARN)));
    }

    /**
     * The us-west-2 volume is asserted present in the same response, so an index that answers
     * nothing at all cannot pass this arm by accident.
     */
    @Test
    @Order(8)
    void neitherServedTypeIsInTheIndexOutsideUsEast1() {
        given()
            .header("Authorization", US_WEST_2)
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "%s", "Values": ["%s"]}]}
                """.formatted(ESTATE_KEY, ESTATE_VALUE))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItem(VOLUME_ARN))
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(POLICY_ARN)))
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(PROFILE_ARN)));
    }

    /**
     * The exclusion is a property of the resource type, not of where the tag came from: a role
     * tagged through this service's own TagResources stays out of the index too.
     */
    @Test
    @Order(9)
    void theRoleStaysUnservedAfterTagResourcesHasWrittenIt() {
        given()
            .header("Authorization", US_EAST_1)
            .header("X-Amz-Target", TARGET_PREFIX + "TagResources")
            .contentType(JSON_CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["%s"], "Tags": {"tagindex-probe-via-tag-resources": "yes"}}
                """.formatted(ROLE_ARN))
        .when().post("/")
        .then().statusCode(200);

        given()
            .header("Authorization", US_EAST_1)
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(JSON_CONTENT_TYPE)
            .body("{}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(ROLE_ARN)));
    }

    @Test
    @Order(10)
    void getTagKeysFollowsTheSameRegionRule() {
        given()
            .header("Authorization", US_EAST_1)
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagKeys")
            .contentType(JSON_CONTENT_TYPE)
            .body("{}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("TagKeys", hasItem(POLICY_ONLY_KEY))
            .body("TagKeys", not(hasItem(ROLE_ONLY_KEY)))
            .body("TagKeys", not(hasItem("tagindex-probe-via-tag-resources")));

        given()
            .header("Authorization", US_WEST_2)
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagKeys")
            .contentType(JSON_CONTENT_TYPE)
            .body("{}")
        .when().post("/")
        .then()
            .statusCode(200)
            .body("TagKeys", hasItem(ESTATE_KEY))
            .body("TagKeys", not(hasItem(POLICY_ONLY_KEY)));
    }

    @Test
    @Order(11)
    void getTagValuesFollowsTheSameRegionRule() {
        given()
            .header("Authorization", US_EAST_1)
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagValues")
            .contentType(JSON_CONTENT_TYPE)
            .body("{\"Key\": \"%s\"}".formatted(POLICY_ONLY_KEY))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("TagValues", hasItem("policy"));

        given()
            .header("Authorization", US_WEST_2)
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagValues")
            .contentType(JSON_CONTENT_TYPE)
            .body("{\"Key\": \"%s\"}".formatted(POLICY_ONLY_KEY))
        .when().post("/")
        .then()
            .statusCode(200)
            .body("TagValues", not(hasItem("policy")));
    }
}
