package io.github.hectorvent.floci.services.ec2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * A security group rule is identified by (protocol, port range, one source), and its description is
 * not part of that identity.
 *
 * <p>Floci used to store whole permissions as handed in and match them on protocol and ports alone.
 * Two consequences, both reproduced here: the same tuple could be authorized twice as long as the
 * descriptions differed, where AWS answers {@code InvalidPermission.Duplicate}; and revoking one
 * source took every other source sharing the port range with it.
 */
@QuarkusTest
class Ec2SecurityGroupRuleIdentityIntegrationTest {

    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    @Test
    void duplicateEgressRuleIsRejectedEvenWhenOnlyTheDescriptionDiffers() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("egress-dup"), vpcId);

        authorizeEgressCidr(groupId, "443", "0.0.0.0/0", "first")
            .then().statusCode(200);

        authorizeEgressCidr(groupId, "443", "0.0.0.0/0", "second")
            .then()
            .statusCode(400)
            .body(containsString("InvalidPermission.Duplicate"))
            .body(containsString("peer: 0.0.0.0/0"));

        // Rejected in full: the second description must not have been stored either.
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<description>first</description>"))
            .body(not(containsString("<description>second</description>")));
    }

    @Test
    void duplicateIngressRuleIsRejectedToo() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("ingress-dup"), vpcId);

        authorizeIngressCidr(groupId, "22", "10.0.0.0/8", "ssh").then().statusCode(200);
        authorizeIngressCidr(groupId, "22", "10.0.0.0/8", "ssh-again")
            .then()
            .statusCode(400)
            .body(containsString("InvalidPermission.Duplicate"));
    }

    @Test
    void aRequestThatRepeatsItselfIsDuplicateAndStoresNothing() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("self-dup"), vpcId);

        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "8080")
            .formParam("IpPermissions.1.ToPort", "8080")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.9.0.0/16")
            .formParam("IpPermissions.2.IpProtocol", "tcp")
            .formParam("IpPermissions.2.FromPort", "8080")
            .formParam("IpPermissions.2.ToPort", "8080")
            .formParam("IpPermissions.2.IpRanges.1.CidrIp", "10.9.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidPermission.Duplicate"));

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("10.9.0.0/16")));
    }

    @Test
    void aDifferentSourceOnTheSamePortIsNotADuplicate() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("same-port"), vpcId);

        authorizeEgressCidr(groupId, "80", "10.1.0.0/16", null).then().statusCode(200);
        authorizeEgressCidr(groupId, "80", "10.2.0.0/16", null).then().statusCode(200);

        // And DescribeSecurityGroups reports them the way AWS does: one permission per port range,
        // carrying every source authorized for it.
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            // [0] is the default allow-all egress the group is created with.
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissionsEgress.item.size()",
                    equalTo(2))
            .body("DescribeSecurityGroupsResponse.securityGroupInfo.item.ipPermissionsEgress.item[1]"
                    + ".ipRanges.item.cidrIp", hasItems("10.1.0.0/16", "10.2.0.0/16"));
    }

    @Test
    void revokingOneSourceLeavesTheOthersSharingItsPortRange() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("revoke-one"), vpcId);

        authorizeEgressCidr(groupId, "443", "10.1.0.0/16", "keep").then().statusCode(200);
        authorizeEgressCidr(groupId, "443", "10.2.0.0/16", "drop").then().statusCode(200);

        given()
            .formParam("Action", "RevokeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "443")
            .formParam("IpPermissions.1.ToPort", "443")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.2.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("10.1.0.0/16"))
            .body(not(containsString("10.2.0.0/16")));

        // The flattened rule goes with the permission it came from, or
        // DescribeSecurityGroupRules keeps serving a rule that no longer exists.
        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("Filter.1.Name", "group-id")
            .formParam("Filter.1.Value.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<cidrIpv4>10.1.0.0/16</cidrIpv4>"))
            .body(not(containsString("<cidrIpv4>10.2.0.0/16</cidrIpv4>")));
    }

    @Test
    void revokeIgnoresTheDescriptionAsAuthorizeDoes() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("revoke-desc"), vpcId);

        authorizeEgressCidr(groupId, "8443", "10.3.0.0/16", "stored-description").then().statusCode(200);

        // A caller revoking by tuple alone, with no description, still names the same rule.
        given()
            .formParam("Action", "RevokeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "8443")
            .formParam("IpPermissions.1.ToPort", "8443")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.3.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("10.3.0.0/16")));
    }

    @Test
    void revokingByGroupReferenceMatchesAResolvedReference() {
        String vpcId = createVpc();
        String sourceName = uniqueName("revoke-source");
        String sourceId = createSecurityGroup(sourceName, vpcId);
        String groupId = createSecurityGroup(uniqueName("revoke-target"), vpcId);

        given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "5432")
            .formParam("IpPermissions.1.ToPort", "5432")
            .formParam("IpPermissions.1.Groups.1.GroupId", sourceId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Revoked by name: the stored rule carries an id and an account, so the revoke has to be
        // resolved the same way the authorize was before the two can be compared.
        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "5432")
            .formParam("IpPermissions.1.ToPort", "5432")
            .formParam("IpPermissions.1.Groups.1.GroupName", sourceName)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<fromPort>5432</fromPort>")));
    }

    /**
     * The other way to name what to revoke, and the one Terraform's per-rule resources
     * (aws_vpc_security_group_ingress_rule / egress_rule) always use: not (protocol, ports,
     * source) but the rule's own id, sent as a top-level {@code SecurityGroupRuleId.N} parameter
     * rather than nested under {@code IpPermissions}.
     *
     * <p>Found running choudoufu's own destroy-order gauntlet unit against this image: the plan
     * proposed destroying a security group and its rules in the correct order (rules before the
     * group), the apply reported "Return: true" for every one of them, and the rule was still
     * live on the very next DescribeSecurityGroupRules call - reproduced here with no Terraform
     * or choudoufu in the loop at all, straight against the query API, per this project's own
     * "confirm against a service directly before calling anything upstream" discipline.
     */
    @Test
    void revokingBySecurityGroupRuleIdActuallyRemovesTheRule() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("revoke-by-id"), vpcId);

        String ruleId = given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "5432")
            .formParam("IpPermissions.1.ToPort", "5432")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.0.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.securityGroupRuleId");

        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("SecurityGroupRuleId.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("SecurityGroupRuleId.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(ruleId)));

        // The group's own summary list has to agree, the same way it does for the
        // IpPermissions-based revoke path (revokingByGroupReferenceMatchesAResolvedReference,
        // just above): a rule gone from DescribeSecurityGroupRules but still echoed in
        // DescribeSecurityGroups' own ipPermissions would be the two views disagreeing.
        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("<fromPort>5432</fromPort>")));
    }

    /** revokingBySecurityGroupRuleIdActuallyRemovesTheRule's egress sibling. */
    @Test
    void revokingBySecurityGroupRuleIdActuallyRemovesTheEgressRule() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("revoke-by-id-egress"), vpcId);

        String ruleId = authorizeEgressCidr(groupId, "8443", "10.5.0.0/16", "revoke-me")
            .then()
            .statusCode(200)
            .extract().path("AuthorizeSecurityGroupEgressResponse.securityGroupRuleSet.item.securityGroupRuleId");

        given()
            .formParam("Action", "RevokeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("SecurityGroupRuleId.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("SecurityGroupRuleId.1", ruleId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString(ruleId)));
    }

    /**
     * The mutation-check boundary: a rule id that belongs to a DIFFERENT security group must not
     * be removed by a revoke call naming the wrong group. AWS validates this; a fix that just
     * looked the rule up by id alone and deleted it, with no group or direction check, would pass
     * every test above and still be wrong here.
     */
    @Test
    void revokingBySecurityGroupRuleIdIgnoresARuleFromAnotherGroup() {
        String vpcId = createVpc();
        String targetGroupId = createSecurityGroup(uniqueName("revoke-by-id-wrong-group"), vpcId);
        String otherGroupId = createSecurityGroup(uniqueName("revoke-by-id-other-group"), vpcId);

        String otherRuleId = given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", otherGroupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "9999")
            .formParam("IpPermissions.1.ToPort", "9999")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.0.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("AuthorizeSecurityGroupIngressResponse.securityGroupRuleSet.item.securityGroupRuleId");

        // Named against targetGroupId, which never authorized this rule.
        given()
            .formParam("Action", "RevokeSecurityGroupIngress")
            .formParam("GroupId", targetGroupId)
            .formParam("SecurityGroupRuleId.1", otherRuleId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroupRules")
            .formParam("SecurityGroupRuleId.1", otherRuleId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString(otherRuleId));
    }

    @Test
    void revokingAPortRangeDoesNotTakeADifferentProtocolWithIt() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("revoke-proto"), vpcId);

        authorizeEgressCidr(groupId, "53", "10.4.0.0/16", "tcp-dns").then().statusCode(200);
        given()
            .formParam("Action", "AuthorizeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "udp")
            .formParam("IpPermissions.1.FromPort", "53")
            .formParam("IpPermissions.1.ToPort", "53")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.4.0.0/16")
            .formParam("IpPermissions.1.IpRanges.1.Description", "udp-dns")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "RevokeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", "53")
            .formParam("IpPermissions.1.ToPort", "53")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.4.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<description>udp-dns</description>"))
            .body(not(containsString("<description>tcp-dns</description>")));
    }

    @Test
    void theDefaultEgressRuleCanBeRevokedWithExplicitZeroPorts() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("default-egress"), vpcId);

        // AWS returns the all-protocols rule with no ports at all, but a client holding its own
        // normalized copy sends the revoke back as ports 0 to 0. Both name the same rule, and a
        // group whose default egress cannot be removed can never match a configuration that
        // declares its own.
        given()
            .formParam("Action", "RevokeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "-1")
            .formParam("IpPermissions.1.FromPort", "0")
            .formParam("IpPermissions.1.ToPort", "0")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "0.0.0.0/0")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .formParam("Action", "DescribeSecurityGroups")
            .formParam("GroupId.1", groupId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(not(containsString("0.0.0.0/0")));
    }

    @Test
    void aProtocolNumberNamesTheSameRuleAsItsName() {
        String vpcId = createVpc();
        String groupId = createSecurityGroup(uniqueName("protocol-number"), vpcId);

        authorizeEgressCidr(groupId, "9090", "10.5.0.0/16", "by-name").then().statusCode(200);

        // 6 is tcp. Authorizing it again under its number is the same rule.
        given()
            .formParam("Action", "AuthorizeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "6")
            .formParam("IpPermissions.1.FromPort", "9090")
            .formParam("IpPermissions.1.ToPort", "9090")
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", "10.5.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body(containsString("InvalidPermission.Duplicate"));
    }

    private io.restassured.response.Response authorizeEgressCidr(String groupId, String port,
                                                                 String cidr, String description) {
        var request = given()
            .formParam("Action", "AuthorizeSecurityGroupEgress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", port)
            .formParam("IpPermissions.1.ToPort", port)
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", cidr)
            .header("Authorization", AUTH_HEADER);
        if (description != null) {
            request = request.formParam("IpPermissions.1.IpRanges.1.Description", description);
        }
        return request.when().post("/");
    }

    private io.restassured.response.Response authorizeIngressCidr(String groupId, String port,
                                                                  String cidr, String description) {
        return given()
            .formParam("Action", "AuthorizeSecurityGroupIngress")
            .formParam("GroupId", groupId)
            .formParam("IpPermissions.1.IpProtocol", "tcp")
            .formParam("IpPermissions.1.FromPort", port)
            .formParam("IpPermissions.1.ToPort", port)
            .formParam("IpPermissions.1.IpRanges.1.CidrIp", cidr)
            .formParam("IpPermissions.1.IpRanges.1.Description", description)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/");
    }

    private String createVpc() {
        return given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", "10.0.0.0/16")
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    /** The suite shares one emulator and group names are unique per VPC, so never reuse one. */
    private String uniqueName(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private String createSecurityGroup(String groupName, String vpcId) {
        return given()
            .formParam("Action", "CreateSecurityGroup")
            .formParam("GroupName", groupName)
            .formParam("GroupDescription", groupName)
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH_HEADER)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().path("CreateSecurityGroupResponse.groupId");
    }
}
