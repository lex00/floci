package io.github.hectorvent.floci.services.ec2;

import java.util.List;
import java.util.Set;

/**
 * A service whose own data plane connects to protected endpoints from the Floci process, such as
 * a load balancer forwarding to its targets. Its peers stand in that data plane's security groups
 * when a target's ingress rules are compiled.
 */
public interface DataPlanePeerSource {

    List<DataPlanePeer> dataPlanePeers();

    /**
     * One data-plane resource. {@code logicalAddresses} are addresses inside its subnets, matched
     * against CIDR rules the way its nodes' private addresses are on AWS.
     */
    record DataPlanePeer(String accountId, String region, String vpcId, Set<String> groupIds,
                         List<String> logicalAddresses) {}
}
