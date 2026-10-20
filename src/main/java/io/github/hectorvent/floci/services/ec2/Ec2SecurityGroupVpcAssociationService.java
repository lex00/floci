package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroupVpcAssociation;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Security groups for multiple VPCs: AssociateSecurityGroupVpc, DisassociateSecurityGroupVpc and
 * DescribeSecurityGroupVpcAssociations. An association is {@code associated} once created and
 * removed on disassociation.
 */
@ApplicationScoped
public class Ec2SecurityGroupVpcAssociationService {

    private static final Set<String> FILTERS =
            Set.of("group-id", "group-owner-id", "vpc-id", "vpc-owner-id", "state");

    // region|groupId|vpcId -> association
    private final StorageBackend<String, SecurityGroupVpcAssociation> associations;
    private final Ec2Service ec2Service;

    @Inject
    public Ec2SecurityGroupVpcAssociationService(StorageFactory storageFactory, Ec2Service ec2Service) {
        this(storageFactory.create("ec2", "ec2-security-group-vpc-associations.json",
                new TypeReference<Map<String, SecurityGroupVpcAssociation>>() {}), ec2Service);
    }

    Ec2SecurityGroupVpcAssociationService(StorageBackend<String, SecurityGroupVpcAssociation> associations,
                                          Ec2Service ec2Service) {
        this.associations = associations;
        this.ec2Service = ec2Service;
    }

    public synchronized SecurityGroupVpcAssociation associate(String region, String groupId, String vpcId) {
        SecurityGroup group = requireGroup(region, groupId);
        Vpc vpc = requireVpc(region, vpcId);
        if (vpcId.equals(group.getVpcId())) {
            throw new AwsException("InvalidParameterValue",
                    "The security group " + groupId + " already belongs to VPC " + vpcId + ".", 400);
        }
        String key = key(region, groupId, vpcId);
        if (associations.get(key).isPresent()) {
            throw new AwsException("InvalidParameterValue",
                    "The security group " + groupId + " is already associated with VPC " + vpcId + ".", 400);
        }
        SecurityGroupVpcAssociation association = new SecurityGroupVpcAssociation();
        association.setGroupId(groupId);
        association.setGroupOwnerId(group.getOwnerId());
        association.setVpcId(vpcId);
        association.setVpcOwnerId(vpc.getOwnerId());
        association.setRegion(region);
        associations.put(key, association);
        return association;
    }

    public synchronized SecurityGroupVpcAssociation disassociate(String region, String groupId, String vpcId) {
        requireGroup(region, groupId);
        requireVpc(region, vpcId);
        String key = key(region, groupId, vpcId);
        SecurityGroupVpcAssociation association = associations.get(key).orElseThrow(() ->
                new AwsException("InvalidParameterValue",
                        "The security group " + groupId + " is not associated with VPC " + vpcId + ".", 400));
        associations.delete(key);
        association.setState("disassociating");
        return association;
    }

    public List<SecurityGroupVpcAssociation> describe(String region, Map<String, List<String>> filters) {
        for (String name : filters.keySet()) {
            if (!FILTERS.contains(name)) {
                throw new AwsException("InvalidParameterValue", "The filter '" + name + "' is invalid", 400);
            }
        }
        return associations.scan(k -> k.startsWith(region + "|")).stream()
                .filter(a -> matches(filters, "group-id", a.getGroupId()))
                .filter(a -> matches(filters, "group-owner-id", a.getGroupOwnerId()))
                .filter(a -> matches(filters, "vpc-id", a.getVpcId()))
                .filter(a -> matches(filters, "vpc-owner-id", a.getVpcOwnerId()))
                .filter(a -> matches(filters, "state", a.getState()))
                .sorted(Comparator.comparing(SecurityGroupVpcAssociation::getGroupId)
                        .thenComparing(SecurityGroupVpcAssociation::getVpcId))
                .toList();
    }

    private static boolean matches(Map<String, List<String>> filters, String name, String value) {
        List<String> values = filters.get(name);
        return values == null || values.contains(value);
    }

    private SecurityGroup requireGroup(String region, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter GroupId", 400);
        }
        return ec2Service.describeSecurityGroups(region, List.of(groupId), List.of(), Map.of()).stream()
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidGroup.NotFound",
                        "The security group '" + groupId + "' does not exist", 400));
    }

    private Vpc requireVpc(String region, String vpcId) {
        if (vpcId == null || vpcId.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter VpcId", 400);
        }
        return ec2Service.describeVpcs(region, List.of(vpcId), Map.of()).stream()
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidVpcID.NotFound",
                        "The vpc ID '" + vpcId + "' does not exist", 400));
    }

    private static String key(String region, String groupId, String vpcId) {
        return region + "|" + groupId + "|" + vpcId;
    }
}
