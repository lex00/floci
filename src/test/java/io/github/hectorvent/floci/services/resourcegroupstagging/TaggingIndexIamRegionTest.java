package io.github.hectorvent.floci.services.resourcegroupstagging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.hectorvent.floci.services.resourcegroupstagging.ResourceGroupsTaggingService.servedInRegion;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule that decides which ARNs the Resource Groups Tagging API's read side serves to a caller
 * in a given region, read directly rather than through the wire protocol.
 *
 * <p>{@link EstateTaggingIndexIntegrationTest} is the behavioural gate and goes through the real
 * JSON 1.1 endpoint; this class covers the shapes that are expensive to set up there — every
 * region rather than one other region, an IAM ARN carrying a path, and the malformed input the
 * rule has to leave alone.
 *
 * <p>The behaviour itself was measured against a live AWS account (INTENTIUS/choudoufu#1134,
 * filed here as #205): {@code iam:policy} and {@code iam:instance-profile} are in the index in
 * us-east-1 only, {@code iam:role} is in no region's.
 */
class TaggingIndexIamRegionTest {

    private static final String POLICY = "arn:aws:iam::000000000000:policy/ReadOnly";
    private static final String POLICY_WITH_PATH = "arn:aws:iam::000000000000:policy/team/a/ReadOnly";
    private static final String INSTANCE_PROFILE = "arn:aws:iam::000000000000:instance-profile/web";
    private static final String ROLE = "arn:aws:iam::000000000000:role/web";
    private static final String ROLE_WITH_PATH = "arn:aws:iam::000000000000:role/service-role/web";
    private static final String USER = "arn:aws:iam::000000000000:user/alice";

    @Test
    void theTwoIndexedIamTypesAreServedInUsEast1() {
        assertTrue(servedInRegion(POLICY, "us-east-1"));
        assertTrue(servedInRegion(INSTANCE_PROFILE, "us-east-1"));
    }

    /** A path lives inside the resource segment, so the type is only what precedes the first slash. */
    @Test
    void aPathDoesNotHideTheIamResourceType() {
        assertTrue(servedInRegion(POLICY_WITH_PATH, "us-east-1"));
        assertFalse(servedInRegion(ROLE_WITH_PATH, "us-east-1"));
    }

    /**
     * The asymmetry, across every region floci advertises rather than one sample: IAM ARNs carry
     * no region, so without this rule they would be served in all of them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"us-east-2", "us-west-1", "us-west-2", "eu-west-1", "eu-central-1",
            "ap-northeast-1", "ap-southeast-2", "sa-east-1", "ca-central-1"})
    void noIamTypeIsServedOutsideUsEast1(String region) {
        assertFalse(servedInRegion(POLICY, region));
        assertFalse(servedInRegion(INSTANCE_PROFILE, region));
        assertFalse(servedInRegion(ROLE, region));
    }

    /** #202 was right about roles, and this change does not touch that. */
    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-west-2", "eu-west-1"})
    void theRoleIsServedNowhere(String region) {
        assertFalse(servedInRegion(ROLE, region));
    }

    /**
     * An IAM type nobody has measured stays out, in us-east-1 too. The set is an allowlist of
     * measurements; guessing is what produced the defect this change fixes.
     */
    @Test
    void anUnmeasuredIamTypeStaysOut() {
        assertFalse(servedInRegion(USER, "us-east-1"));
    }

    /** Non-IAM ARNs keep the rule floci has always applied: the ARN's own region, or everywhere. */
    @Test
    void nonIamArnsAreUnaffected() {
        String volume = "arn:aws:ec2:us-west-2:000000000000:volume/vol-1";
        assertTrue(servedInRegion(volume, "us-west-2"));
        assertFalse(servedInRegion(volume, "us-east-1"));

        // An empty region segment on a non-IAM service is served everywhere, as before.
        String bucket = "arn:aws:s3:::my-bucket";
        assertTrue(servedInRegion(bucket, "us-east-1"));
        assertTrue(servedInRegion(bucket, "eu-west-1"));
    }

    /** A malformed ARN is a different problem; it is served as before rather than dropped here. */
    @Test
    void malformedArnsAreLeftAlone() {
        assertTrue(servedInRegion("not-an-arn", "us-east-1"));
        assertTrue(servedInRegion("arn:aws:iam", "us-east-1"));
    }
}
