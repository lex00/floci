package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupNftCompilerTest {

    @Test
    void managedPeersAreCheckedBeforeDockerAddressCidrs() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("10.0.0.0/16");
        permission.getIpRanges().add(cidr);
        IpRange broadTransport = new IpRange();
        broadTransport.setCidrIp("172.17.0.0/16");
        permission.getIpRanges().add(broadTransport);
        group.getIpPermissions().add(permission);

        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint allowed = endpoint("eni-b", "10.0.0.3", "172.17.0.3", group);
        SecurityGroupNftCompiler.Endpoint denied = endpoint("eni-c", "10.1.0.3", "172.17.0.4", group);
        String nft = SecurityGroupNftCompiler.compile(target, List.of(allowed, denied), Map.of());

        assertTrue(nft.contains("ip saddr 172.17.0.3 tcp dport 443 accept"));
        assertTrue(nft.indexOf("ip saddr 172.17.0.4 drop")
                < nft.indexOf("ip saddr 172.17.0.0/16 tcp dport 443 accept"));
    }

    @Test
    void icmpTypeAndCodeRepeatTheProtocolKeyword() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        // AWS maps IpPermission.FromPort to the ICMP type and ToPort to the ICMP code.
        IpPermission echo = new IpPermission();
        echo.setIpProtocol("icmp");
        echo.setFromPort(8);
        echo.setToPort(0);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("10.0.0.0/16");
        echo.getIpRanges().add(cidr);
        group.getIpPermissions().add(echo);
        IpPermission anyIcmpv6 = new IpPermission();
        anyIcmpv6.setIpProtocol("icmpv6");
        anyIcmpv6.setFromPort(-1);
        anyIcmpv6.setToPort(-1);
        IpRange allV4 = new IpRange();
        allV4.setCidrIp("10.1.0.0/16");
        anyIcmpv6.getIpRanges().add(allV4);
        group.getIpPermissions().add(anyIcmpv6);

        String nft = SecurityGroupNftCompiler.compile(
                endpoint("eni-a", "10.0.0.2", "172.17.0.2", group), List.of(), Map.of());

        assertTrue(nft.contains("ip saddr 10.0.0.0/16 icmp type 8 icmp code 0 accept"));
        assertFalse(nft.contains("icmp type 8 code 0"));
        assertTrue(nft.contains("ip saddr 10.1.0.0/16 meta l4proto icmpv6 accept"));
    }

    @Test
    void vpcResolversStayReachableWhenTheGroupsAllowNoEgress() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");

        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of("172.18.0.2", "fd00::53"));

        assertTrue(nft.contains("egress ip daddr 172.18.0.2 udp dport 53 accept"));
        assertTrue(nft.contains("egress ip daddr 172.18.0.2 tcp dport 53 accept"));
        assertTrue(nft.contains("egress ip6 daddr fd00::53 udp dport 53 accept"));
        assertFalse(nft.contains("daddr 172.18.0.2 accept"), "only DNS is allowed to the resolver");
    }

    @Test
    void ecsCredentialsEndpointStaysReachableOnItsOneListenerOnly() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");

        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of());

        assertTrue(nft.contains("add rule inet floci_sg egress ip daddr 169.254.170.2 tcp dport 80 accept\n"),
                nft);
        assertFalse(nft.contains("daddr 169.254.170.2 accept"), "only the credentials listener is allowed");
        assertFalse(nft.contains("saddr 169.254.170.2"), "replies ride the established rule, nothing opens ingress");
    }

    @Test
    void loadBalancerGroupReferenceAdmitsTheDataPlaneSource() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission fromAlb = new IpPermission();
        fromAlb.setIpProtocol("tcp");
        fromAlb.setFromPort(80);
        fromAlb.setToPort(80);
        UserIdGroupPair albGroup = new UserIdGroupPair();
        albGroup.setGroupId("sg-alb");
        fromAlb.getUserIdGroupPairs().add(albGroup);
        group.getIpPermissions().add(fromAlb);
        IpPermission fromOther = new IpPermission();
        fromOther.setIpProtocol("tcp");
        fromOther.setFromPort(9000);
        fromOther.setToPort(9000);
        UserIdGroupPair otherGroup = new UserIdGroupPair();
        otherGroup.setGroupId("sg-other");
        fromOther.getUserIdGroupPairs().add(otherGroup);
        group.getIpPermissions().add(fromOther);

        DataPlanePeerSource.DataPlanePeer alb = new DataPlanePeerSource.DataPlanePeer(
                "000000000000", "us-east-1", "vpc-1", Set.of("sg-alb"), List.of("10.0.0.0"));
        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of(), List.of(alb), "172.17.0.1");

        assertTrue(nft.contains("add rule inet floci_sg ingress ip saddr 172.17.0.1 tcp dport 80 accept"), nft);
        assertFalse(nft.contains("tcp dport 9000"), nft);
    }

    @Test
    void loadBalancerInAnotherVpcIsNotAdmitted() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission fromVpc = new IpPermission();
        fromVpc.setIpProtocol("tcp");
        fromVpc.setFromPort(80);
        fromVpc.setToPort(80);
        IpRange vpcCidr = new IpRange();
        vpcCidr.setCidrIp("10.0.0.0/16");
        fromVpc.getIpRanges().add(vpcCidr);
        group.getIpPermissions().add(fromVpc);

        DataPlanePeerSource.DataPlanePeer sameVpc = new DataPlanePeerSource.DataPlanePeer(
                "000000000000", "us-east-1", "vpc-1", Set.of("sg-alb"), List.of("10.0.1.0"));
        DataPlanePeerSource.DataPlanePeer otherVpc = new DataPlanePeerSource.DataPlanePeer(
                "000000000000", "us-east-1", "vpc-2", Set.of("sg-alb"), List.of("10.0.1.0"));

        assertTrue(SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of(), List.of(sameVpc), "172.17.0.1")
                .contains("ip saddr 172.17.0.1 tcp dport 80 accept"));
        assertFalse(SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of(), List.of(otherVpc), "172.17.0.1")
                .contains("ip saddr 172.17.0.1 "));
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
                                                               String transport, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, transport, Set.of("sg-1"), List.of(group));
    }
}
