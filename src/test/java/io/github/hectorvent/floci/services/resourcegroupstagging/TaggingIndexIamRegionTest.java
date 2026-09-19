package io.github.hectorvent.floci.services.resourcegroupstagging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads {@link ResourceGroupsTaggingService#servedInRegion} directly, for the arms that are
 * expensive to cover on the wire: many regions rather than one sample, IAM ARNs carrying a path,
 * an IAM type nobody has measured, and malformed input.
 */
class TaggingIndexIamRegionTest {

    private static final String POLICY = "arn:aws:iam::000000000000:policy/ReadOnly";
    private static final String INSTANCE_PROFILE = "arn:aws:iam::000000000000:instance-profile/web";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";

    @Test
    void theIndexServesAPolicyAndAnInstanceProfileInUsEast1() {
        assertTrue(ResourceGroupsTaggingService.servedInRegion(POLICY, "us-east-1"));
        assertTrue(ResourceGroupsTaggingService.servedInRegion(INSTANCE_PROFILE, "us-east-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-2", "us-west-1", "us-west-2", "eu-west-1", "eu-central-1",
            "ap-southeast-1", "ap-northeast-1", "sa-east-1"})
    void neitherServedTypeIsInTheIndexOutsideUsEast1(String region) {
        assertFalse(ResourceGroupsTaggingService.servedInRegion(POLICY, region));
        assertFalse(ResourceGroupsTaggingService.servedInRegion(INSTANCE_PROFILE, region));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-east-2", "us-west-2", "eu-west-1", "ap-southeast-1"})
    void theRoleIsInNoRegionsIndex(String region) {
        assertFalse(ResourceGroupsTaggingService.servedInRegion(ROLE, region));
    }

    @Test
    void anIamPathBelongsToTheResourceIdAndNotToTheType() {
        assertTrue(ResourceGroupsTaggingService.servedInRegion(
                "arn:aws:iam::000000000000:policy/team/a/ReadOnly", "us-east-1"));
        assertTrue(ResourceGroupsTaggingService.servedInRegion(
                "arn:aws:iam::000000000000:instance-profile/team/a/web", "us-east-1"));
        assertFalse(ResourceGroupsTaggingService.servedInRegion(
                "arn:aws:iam::000000000000:role/service-role/web", "us-east-1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"user/alice", "mfa/token", "oidc-provider/example.com",
            "saml-provider/adfs", "server-certificate/star-example"})
    void anIamTypeNobodyHasMeasuredStaysOut(String resource) {
        assertFalse(ResourceGroupsTaggingService.servedInRegion(
                "arn:aws:iam::000000000000:" + resource, "us-east-1"));
    }

    @Test
    void anAwsManagedPolicyArnFollowsTheSameRuleAsACustomerManagedOne() {
        assertTrue(ResourceGroupsTaggingService.servedInRegion(
                "arn:aws:iam::aws:policy/AdministratorAccess", "us-east-1"));
        assertFalse(ResourceGroupsTaggingService.servedInRegion(
                "arn:aws:iam::aws:policy/AdministratorAccess", "eu-west-1"));
    }

    @Test
    void aRegionalArnIsStillServedInItsOwnRegionOnly() {
        String volume = "arn:aws:ec2:us-west-2:000000000000:volume/vol-123";
        assertTrue(ResourceGroupsTaggingService.servedInRegion(volume, "us-west-2"));
        assertFalse(ResourceGroupsTaggingService.servedInRegion(volume, "us-east-1"));
    }

    @Test
    void anArnWithNoRegionSegmentIsStillServedEverywhereUnlessItIsIam() {
        String bucket = "arn:aws:s3:::my-bucket";
        assertTrue(ResourceGroupsTaggingService.servedInRegion(bucket, "us-east-1"));
        assertTrue(ResourceGroupsTaggingService.servedInRegion(bucket, "ap-south-1"));
    }

    @Test
    void aStringTooShortToCarryARegionIsLeftAlone() {
        assertTrue(ResourceGroupsTaggingService.servedInRegion("not-an-arn", "us-east-1"));
        assertTrue(ResourceGroupsTaggingService.servedInRegion("arn:aws:s3", "eu-west-1"));
    }

    @Test
    void aTruncatedIamArnCarriesNoTypeAndIsNotServed() {
        assertFalse(ResourceGroupsTaggingService.servedInRegion("arn:aws:iam::000000000000", "us-east-1"));
    }

    @Test
    void aNullArnIsNotServed() {
        assertFalse(ResourceGroupsTaggingService.servedInRegion(null, "us-east-1"));
    }
}
