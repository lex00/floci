package io.github.hectorvent.floci.services.resourcegroupstagging;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

/**
 * {@code GetResources} must see resources tagged through the owning service's own API, not only
 * the ones tagged through {@code TagResources}.
 *
 * <p>This is the regression gate for the defect where an estate full of tagged resources came
 * back as {@code "ResourceTagMappingList": []}: floci keeps a resource's tags on that resource's
 * own model, and the tagging service read a store nothing but EventBridge and Glue ever wrote to.
 * Every assertion here goes through the public wire protocols — EC2 Query for the volume, IAM
 * Query for the role, JSON 1.1 for the tagging API — so it fails if the seam between them breaks
 * for any reason, not just the one this test was written for.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EstateTaggingIndexIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "ResourceGroupsTaggingAPI_20170126.";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";
    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/iam/aws4_request";

    /**
     * SigV4 credential scope for a caller in a region that is not us-east-1. The tagging
     * controller resolves the request region from this header alone
     * ({@code AwsJson11Controller} -> {@code RegionResolver.resolveRegion}), so it is the whole
     * mechanism for asking the index a question "from" another region. Every tagging call in
     * this class that omits it is answered as us-east-1, the configured default region.
     */
    private static final String OTHER_REGION_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-west-2/tagging/aws4_request";

    private static final String ESTATE_TAG = "estate-index-probe";
    private static final String ROLE_NAME = "estate-index-probe-role";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/" + ROLE_NAME;
    private static final String POLICY_NAME = "estate-index-probe-policy";
    private static final String POLICY_ARN = "arn:aws:iam::000000000000:policy/" + POLICY_NAME;
    private static final String PROFILE_NAME = "estate-index-probe-profile";
    private static final String PROFILE_ARN =
            "arn:aws:iam::000000000000:instance-profile/" + PROFILE_NAME;

    /** A regional ARN tagged from us-west-2, so the "nothing IAM outside us-east-1" arms can tell
     * an empty answer caused by the IAM rule from an empty answer caused by anything else. */
    private static final String US_WEST_2_VOLUME_ARN =
            "arn:aws:ec2:us-west-2:000000000000:volume/vol-estateindexprobe";

    /** Carried only by the IAM role, tagged natively through CreateRole — never through
     * TagResources — so GetTagKeys/GetTagValues excluding it proves the exclusion covers the
     * live-scan source, not just the service's own explicit-tag store. */
    private static final String IAM_ONLY_TAG_KEY = "estate-index-probe-iam-only";

    /** Carried only by the IAM policy, and written through {@code TagResources} in us-east-1, so
     * GetTagKeys seeing it there and not in us-west-2 is about the region rule and not about
     * which store the tag came from. */
    private static final String POLICY_ONLY_TAG_KEY = "estate-index-probe-policy-only";

    private static String volumeId;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createVolumeWithInlineTags() {
        volumeId = given()
            .formParam("Action", "CreateVolume")
            .formParam("AvailabilityZone", "us-east-1a")
            .formParam("Size", "8")
            .formParam("TagSpecification.1.ResourceType", "volume")
            .formParam("TagSpecification.1.Tag.1.Key", "tofu-estate")
            .formParam("TagSpecification.1.Tag.1.Value", ESTATE_TAG)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVolumeResponse.volumeId");
    }

    @Test
    @Order(2)
    void createRoleWithTags() {
        given()
            .formParam("Action", "CreateRole")
            .formParam("RoleName", ROLE_NAME)
            .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[]}")
            .formParam("Tags.member.1.Key", "tofu-estate")
            .formParam("Tags.member.1.Value", ESTATE_TAG)
            .formParam("Tags.member.2.Key", IAM_ONLY_TAG_KEY)
            .formParam("Tags.member.2.Value", "probe")
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    /**
     * An EC2 model carries an id, not an ARN; the ARN has to be assembled and be the real one.
     *
     * <p>The IAM role is tagged with the same estate tag and would match this filter too, but
     * real AWS's Resource Groups Tagging API never returns IAM resources from GetResources - see
     * {@link #getResourcesNeverServesTheIamRoleEvenThoughItCarriesTheEstateTag}.
     */
    @Test
    @Order(3)
    void getResourcesFindsTheVolumeWithoutAnyTagResourcesCall() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN",
                    hasItem("arn:aws:ec2:us-east-1:000000000000:volume/" + volumeId));
    }

    /**
     * Regression gate for floci returning IAM resources from GetResources: real AWS never does,
     * for any IAM resource type, no matter how it was tagged (natively here, through CreateRole's
     * own Tags parameter). See lex00/floci's tagging-api-iam-and-pagination issue
     * (INTENTIUS/choudoufu#1045, INTENTIUS/choudoufu#1046) for the choudoufu-side evidence: two
     * of its tests hardcode this exact fact about real AWS and failed against a floci image that
     * served IAM here.
     */
    @Test
    @Order(11)
    void getResourcesNeverServesTheIamRoleEvenThoughItCarriesTheEstateTag() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(ROLE_ARN)));
    }

    /** An explicit {@code iam:role} type filter must come back empty, not just miss the estate tag. */
    @Test
    @Order(12)
    void getResourcesResourceTypeFilterForIamRoleFindsNothing() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceTypeFilters": ["iam:role"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList", empty());
    }

    /**
     * {@code GetTagKeys} must not surface a key that only an IAM resource carries - proving the
     * exclusion covers the live estate-wide scan ({@link io.github.hectorvent.floci.core.common.TaggedResourceScanner}),
     * not only resources tagged through this API's own {@code TagResources}.
     */
    @Test
    @Order(13)
    void getTagKeysNeverIncludesAKeyOnlyTheIamRoleCarries() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagKeys")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagKeys", not(hasItem(IAM_ONLY_TAG_KEY)));
    }

    /**
     * {@code TagResources} itself must keep accepting an IAM ARN: real AWS's docs list role among
     * the eight IAM resource types {@code TagResources}/{@code UntagResources} support, even
     * though the read side never serves IAM. This is the other half of the fix - only the
     * read-side methods exclude IAM, not the write side.
     */
    @Test
    @Order(14)
    void tagResourcesStillAcceptsAnIamRoleArn() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "TagResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["%s"], "Tags": {"via-tag-resources": "yes"}}
                """.formatted(ROLE_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("FailedResourcesMap.size()", equalTo(0));
    }

    @Test
    @Order(4)
    void resourceTypeFilterSelectsTheSynthesizedEc2Type() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}],
                 "ResourceTypeFilters": ["ec2:volume"]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.size()", equalTo(1))
            .body("ResourceTagMappingList[0].ResourceARN",
                    equalTo("arn:aws:ec2:us-east-1:000000000000:volume/" + volumeId));
    }

    @Test
    @Order(5)
    void tagKeysAndValuesSeeTheSameEstate() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagValues")
            .contentType(CONTENT_TYPE)
            .body("{\"Key\": \"tofu-estate\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagValues", hasItem(ESTATE_TAG));
    }

    /**
     * {@code CreateTags} merges with what the resource already has. It used to merge only with
     * the {@code ec2-tags.json} side-store, which a volume created with {@code TagSpecification}
     * never appears in — so adding one tag silently dropped the tags it was created with, both
     * from {@code DescribeVolumes} and from the tagging index.
     */
    @Test
    @Order(6)
    void createTagsKeepsTheTagsTheResourceWasCreatedWith() {
        given()
            .formParam("Action", "CreateTags")
            .formParam("ResourceId.1", volumeId)
            .formParam("Tag.1.Key", "added-later")
            .formParam("Tag.1.Value", "yes")
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeVolumes")
            .formParam("VolumeId.1", volumeId)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeVolumesResponse.volumeSet.item.tagSet.item.key",
                    hasItems("tofu-estate", "added-later"));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["arn:aws:ec2:us-east-1:000000000000:volume/%s"]}
                """.formatted(volumeId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList[0].Tags.Key", hasItems("tofu-estate", "added-later"));
    }

    /** DescribeTags used to report {@code unknown} for every id outside a 12-entry prefix list. */
    @Test
    @Order(7)
    void describeTagsNamesTheResourceType() {
        given()
            .formParam("Action", "DescribeTags")
            .formParam("Filter.1.Name", "resource-id")
            .formParam("Filter.1.Value.1", volumeId)
            .header("Authorization", EC2_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("DescribeTagsResponse.tagSet.item.resourceType", hasItem("volume"));
    }

    /**
     * Route53 keeps a hosted zone's tags in a side store ({@code route53-tags.json}, keyed
     * {@code hostedzone/<id>}) with nothing on the model itself — no {@code tags} field, no
     * {@code arn} field. {@code list-tags-for-resource} always saw them; {@code get-resources}
     * did not, because the estate-wide scan only read tags off a resource's own model. This is
     * the regression gate for that gap.
     */
    private static String hostedZoneId;

    @Test
    @Order(8)
    void createHostedZoneAndTagItThroughRoute53() {
        String createBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <Name>estate-index-probe.example.com</Name>
                  <CallerReference>estate-index-probe</CallerReference>
                </CreateHostedZoneRequest>
                """;
        String location = given()
                .contentType("application/xml")
                .body(createBody)
            .when()
                .post("/2013-04-01/hostedzone")
            .then()
                .statusCode(201)
                .extract().header("Location");
        hostedZoneId = location.substring(location.lastIndexOf('/') + 1);

        String addTagBody = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ChangeTagsForResourceRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                  <AddTags>
                    <Tag><Key>tofu-estate</Key><Value>%s</Value></Tag>
                  </AddTags>
                </ChangeTagsForResourceRequest>
                """.formatted(ESTATE_TAG);
        given()
                .contentType("application/xml")
                .body(addTagBody)
            .when()
                .post("/2013-04-01/tags/hostedzone/" + hostedZoneId)
            .then()
                .statusCode(200);
    }

    @Test
    @Order(9)
    void getResourcesFindsTheHostedZoneWithNoTagResourcesCall() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["arn:aws:route53:::hostedzone/%s"]}
                """.formatted(hostedZoneId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList[0].ResourceARN", equalTo("arn:aws:route53:::hostedzone/" + hostedZoneId))
            .body("ResourceTagMappingList[0].Tags.Key", hasItem("tofu-estate"))
            .body("ResourceTagMappingList[0].Tags.Value", hasItem(ESTATE_TAG));
    }

    @Test
    @Order(10)
    void resourceTypeFilterSelectsTheSynthesizedRoute53Type() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}],
                 "ResourceTypeFilters": ["route53:hostedzone"]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItem("arn:aws:route53:::hostedzone/" + hostedZoneId));
    }

    // =========================================================================
    // The IAM asymmetry: iam:policy and iam:instance-profile ARE in real AWS's tag index,
    // in us-east-1 only; iam:role is in no region's. Measured against a live AWS account
    // at INTENTIUS/choudoufu#1134 and filed as lex00/floci#205.
    // =========================================================================

    @Test
    @Order(15)
    void createPolicyAndInstanceProfileWithTags() {
        given()
            .formParam("Action", "CreatePolicy")
            .formParam("PolicyName", POLICY_NAME)
            .formParam("PolicyDocument",
                    "{\"Version\":\"2012-10-17\",\"Statement\":"
                    + "[{\"Effect\":\"Allow\",\"Action\":\"s3:GetObject\",\"Resource\":\"*\"}]}")
            .formParam("Tags.member.1.Key", "tofu-estate")
            .formParam("Tags.member.1.Value", ESTATE_TAG)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreatePolicyResponse.CreatePolicyResult.Policy.Arn", equalTo(POLICY_ARN));

        given()
            .formParam("Action", "CreateInstanceProfile")
            .formParam("InstanceProfileName", PROFILE_NAME)
            .formParam("Tags.member.1.Key", "tofu-estate")
            .formParam("Tags.member.1.Value", ESTATE_TAG)
            .header("Authorization", IAM_AUTH)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CreateInstanceProfileResponse.CreateInstanceProfileResult.InstanceProfile.Arn",
                    equalTo(PROFILE_ARN));
    }

    /**
     * Real AWS indexes {@code iam:policy} in the Resource Groups Tagging API - in us-east-1, IAM
     * being global and indexing there. floci served nothing for it, which is the half of
     * lex00/floci#202 that over-corrected: it generalised a probe of {@code iam:role} to the
     * whole service.
     */
    @Test
    @Order(16)
    void getResourcesServesTheIamPolicyInUsEast1() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceTypeFilters": ["iam:policy"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItem(POLICY_ARN));
    }

    /** The type {@code day2_remove} needs (INTENTIUS/choudoufu#881): same rule as the policy. */
    @Test
    @Order(17)
    void getResourcesServesTheIamInstanceProfileInUsEast1() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceTypeFilters": ["iam:instance-profile"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItem(PROFILE_ARN));
    }

    /** Both served types answer a tag filter too, not only an explicit type filter. */
    @Test
    @Order(18)
    void aTagFilterInUsEast1FindsBothServedIamTypesAndStillNotTheRole() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItems(POLICY_ARN, PROFILE_ARN))
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(ROLE_ARN)));
    }

    /**
     * The positive control for the us-west-2 arms below. Without it an empty
     * {@code ResourceTagMappingList} from us-west-2 would prove nothing: a broken region
     * parameter, a rejected credential scope or an empty index all look the same.
     */
    @Test
    @Order(19)
    void aUsWest2CallerSeesAUsWest2Resource() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "TagResources")
            .header("Authorization", OTHER_REGION_AUTH)
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["%s"], "Tags": {"tofu-estate": "%s"}}
                """.formatted(US_WEST_2_VOLUME_ARN, ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .header("Authorization", OTHER_REGION_AUTH)
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList.ResourceARN", hasItem(US_WEST_2_VOLUME_ARN));
    }

    /**
     * The arm that pins the asymmetry. IAM ARNs carry no region, so the ARN-region filter lets
     * them through in every region by default; real AWS indexes them in us-east-1 alone. Serving
     * them everywhere would be a smaller lie than serving them nowhere, and still a lie - a
     * consumer that reads the index from eu-west-1 would pass here and find nothing on AWS.
     */
    @Test
    @Order(20)
    void neitherServedIamTypeIsInTheIndexOutsideUsEast1() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .header("Authorization", OTHER_REGION_AUTH)
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceTypeFilters": ["iam:policy", "iam:instance-profile", "iam:role"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList", empty());

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .header("Authorization", OTHER_REGION_AUTH)
            .contentType(CONTENT_TYPE)
            .body("""
                {"TagFilters": [{"Key": "tofu-estate", "Values": ["%s"]}]}
                """.formatted(ESTATE_TAG))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // Three separate not(hasItem(...)) rather than one not(hasItems(...)): the latter is
            // allOf(...) negated, which passes when any ONE of the three is missing.
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(POLICY_ARN)))
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(PROFILE_ARN)))
            .body("ResourceTagMappingList.ResourceARN", not(hasItem(ROLE_ARN)))
            // ... and the same call must still be answering with the regional control, so an
            // empty answer cannot pass this arm by accident.
            .body("ResourceTagMappingList.ResourceARN", hasItem(US_WEST_2_VOLUME_ARN));
    }

    /**
     * {@code iam:role} stays out of us-east-1's index even after {@code TagResources} has written
     * it into this service's own store (order 14 did exactly that). The exclusion is a property
     * of the type, not of which source the tags came from.
     */
    @Test
    @Order(21)
    void theRoleIsStillUnservedInUsEast1AfterTagResourcesWroteIt() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceTypeFilters": ["iam:role"]}
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList", empty());

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["%s"]}
                """.formatted(ROLE_ARN))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("ResourceTagMappingList", empty());
    }

    /**
     * {@code GetTagKeys} follows {@code GetResources}: a key only the served IAM policy carries is
     * visible in us-east-1 and not in us-west-2. Not independently measured against real AWS -
     * it follows by construction from the same index, and the measured fact is the GetResources
     * one. The role's own key stays invisible in both (order 13 covers us-east-1).
     */
    @Test
    @Order(22)
    void getTagKeysFollowsTheSameRegionRule() {
        given()
            .header("X-Amz-Target", TARGET_PREFIX + "TagResources")
            .contentType(CONTENT_TYPE)
            .body("""
                {"ResourceARNList": ["%s"], "Tags": {"%s": "yes"}}
                """.formatted(POLICY_ARN, POLICY_ONLY_TAG_KEY))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagKeys")
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagKeys", hasItem(POLICY_ONLY_TAG_KEY));

        given()
            .header("X-Amz-Target", TARGET_PREFIX + "GetTagKeys")
            .header("Authorization", OTHER_REGION_AUTH)
            .contentType(CONTENT_TYPE)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("TagKeys", not(hasItem(POLICY_ONLY_TAG_KEY)));
    }
}
