package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.storage.PersistentStorage;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.KeyPair;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupRule;
import io.github.hectorvent.floci.services.ec2.model.Snapshot;
import io.github.hectorvent.floci.services.ec2.model.SpotInstanceRequest;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.ec2.model.Volume;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcEndpoint;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression test for issue #1667: DescribeSubnets ignored the cidr-block filter
 * (it fell to the default:true branch), so a cidr-scoped lookup returned every
 * subnet in the VPC — breaking subnet idempotency for EC2/ALB/NAT workflows.
 */
class Ec2SubnetCidrFilterTest {

    private static final String REGION = "us-east-1";

    @Test
    void describeSubnetsHonorsCidrBlockFilter(@TempDir Path dir) {
        Ec2Service ec2 = newService(dir);
        Vpc vpc = ec2.createVpc(REGION, "10.0.0.0/16", false);
        Subnet pub = ec2.createSubnet(REGION, vpc.getVpcId(), "10.0.0.0/24", REGION + "a");
        ec2.createSubnet(REGION, vpc.getVpcId(), "10.0.1.0/24", REGION + "a");

        List<Subnet> hit = ec2.describeSubnets(REGION, List.of(), Map.of("cidr-block", List.of("10.0.0.0/24")));
        assertEquals(1, hit.size(), "cidr-block filter must return exactly the matching subnet");
        assertEquals(pub.getSubnetId(), hit.get(0).getSubnetId());
        assertEquals("10.0.0.0/24", hit.get(0).getCidrBlock());

        List<Subnet> none = ec2.describeSubnets(REGION, List.of(), Map.of("cidr-block", List.of("10.9.9.0/24")));
        assertEquals(0, none.size(), "a non-matching cidr-block must return no subnets, not all of them");
    }

    private Ec2Service newService(Path dir) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn("000000000000");
        Ec2ImageCatalog imageCatalog = new Ec2ImageCatalog();
        return new Ec2Service(config, mock(Ec2ContainerManager.class), mock(Ec2PortForwardManager.class),
                new AmiImageResolver(imageCatalog), imageCatalog,
                new Ec2InstanceTypeCatalog(),
                load(dir, "ec2-vpcs.json", new TypeReference<Map<String, Vpc>>() {}),
                load(dir, "ec2-subnets.json", new TypeReference<Map<String, Subnet>>() {}),
                load(dir, "ec2-security-groups.json", new TypeReference<Map<String, SecurityGroup>>() {}),
                load(dir, "ec2-security-group-rules.json", new TypeReference<Map<String, SecurityGroupRule>>() {}),
                load(dir, "ec2-internet-gateways.json", new TypeReference<Map<String, InternetGateway>>() {}),
                load(dir, "ec2-route-tables.json", new TypeReference<Map<String, RouteTable>>() {}),
                load(dir, "ec2-key-pairs.json", new TypeReference<Map<String, KeyPair>>() {}),
                load(dir, "ec2-addresses.json", new TypeReference<Map<String, Address>>() {}),
                load(dir, "ec2-instances.json", new TypeReference<Map<String, Instance>>() {}),
                load(dir, "ec2-volumes.json", new TypeReference<Map<String, Volume>>() {}),
                load(dir, "ec2-registered-images.json", new TypeReference<Map<String, Image>>() {}),
                load(dir, "ec2-snapshots.json", new TypeReference<Map<String, Snapshot>>() {}),
                load(dir, "ec2-launch-templates.json", new TypeReference<Map<String, LaunchTemplate>>() {}),
                load(dir, "ec2-vpc-endpoints.json", new TypeReference<Map<String, VpcEndpoint>>() {}),
                load(dir, "ec2-nat-gateways.json", new TypeReference<Map<String, NatGateway>>() {}),
                load(dir, "ec2-spot-instance-requests.json", new TypeReference<Map<String, SpotInstanceRequest>>() {}),
                load(dir, "ec2-network-acls.json", new TypeReference<Map<String, NetworkAcl>>() {}),
                load(dir, "ec2-tags.json", new TypeReference<Map<String, List<Tag>>>() {}));
    }

    private <V> StorageBackend<String, V> load(Path dir, String file, TypeReference<Map<String, V>> type) {
        PersistentStorage<String, V> backend = new PersistentStorage<>(dir.resolve(file), type);
        backend.load();
        return backend;
    }
}
