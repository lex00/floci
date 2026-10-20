package io.github.hectorvent.floci.services.ec2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.model.ClientVpnEndpoint;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * AWS Client VPN endpoints with their target networks and authorization rules. Associations and
 * rules take effect at once: an association reads {@code associated} and a rule {@code active},
 * and an endpoint becomes {@code available} with its first association.
 */
@ApplicationScoped
public class Ec2ClientVpnService {

    private static final DateTimeFormatter CREATION_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // region|endpointId -> endpoint
    private final StorageBackend<String, ClientVpnEndpoint> endpoints;
    private final Ec2Service ec2Service;

    @Inject
    public Ec2ClientVpnService(StorageFactory storageFactory, Ec2Service ec2Service) {
        this(storageFactory.create("ec2", "ec2-client-vpn-endpoints.json",
                new TypeReference<Map<String, ClientVpnEndpoint>>() {}), ec2Service);
    }

    Ec2ClientVpnService(StorageBackend<String, ClientVpnEndpoint> endpoints, Ec2Service ec2Service) {
        this.endpoints = endpoints;
        this.ec2Service = ec2Service;
    }

    public synchronized ClientVpnEndpoint create(String region, ClientVpnEndpoint request) {
        requireParameter(request.getClientCidrBlock(), "ClientCidrBlock");
        requireParameter(request.getServerCertificateArn(), "ServerCertificateArn");
        if (request.getAuthenticationOptions().isEmpty()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter AuthenticationOptions", 400);
        }
        if (request.getConnectionLogEnabled() == null) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter ConnectionLogOptions", 400);
        }
        String[] cidr = request.getClientCidrBlock().split("/");
        int prefix = cidr.length == 2 ? parsePrefix(cidr[1]) : -1;
        if (prefix < 12 || prefix > 22) {
            throw new AwsException("InvalidParameterValue",
                    "The client CIDR block must have a netmask between /12 and /22.", 400);
        }
        if (request.getVpcId() != null) {
            requireSecurityGroups(region, request.getVpcId(), request.getSecurityGroupIds());
        }
        String id = "cvpn-endpoint-" + randomHex(17);
        request.setClientVpnEndpointId(id);
        request.setRegion(region);
        request.setCreationTime(ZonedDateTime.now(ZoneOffset.UTC).format(CREATION_TIME));
        request.setDnsName("*." + id + ".prod.clientvpn." + region + "." + AwsRegions.dnsSuffixFor(region));
        endpoints.put(key(region, id), request);
        return request;
    }

    public List<ClientVpnEndpoint> describe(String region, List<String> ids, Map<String, List<String>> filters) {
        for (String id : ids) {
            require(region, id);
        }
        return endpoints.scan(k -> k.startsWith(region + "|")).stream()
                .filter(e -> ids.isEmpty() || ids.contains(e.getClientVpnEndpointId()))
                .filter(e -> matches(filters, "endpoint-id", e.getClientVpnEndpointId()))
                .filter(e -> matches(filters, "transport-protocol", e.getTransportProtocol()))
                .sorted(Comparator.comparing(ClientVpnEndpoint::getClientVpnEndpointId))
                .toList();
    }

    public synchronized void modify(String region, String id, Consumer<ClientVpnEndpoint> change) {
        ClientVpnEndpoint endpoint = require(region, id);
        change.accept(endpoint);
        if (endpoint.getVpcId() != null) {
            requireSecurityGroups(region, endpoint.getVpcId(), endpoint.getSecurityGroupIds());
            for (ClientVpnEndpoint.TargetNetwork network : endpoint.getTargetNetworks()) {
                network.setSecurityGroups(endpoint.getSecurityGroupIds());
            }
        }
        endpoints.put(key(region, id), endpoint);
    }

    public synchronized ClientVpnEndpoint delete(String region, String id) {
        ClientVpnEndpoint endpoint = require(region, id);
        if (!endpoint.getTargetNetworks().isEmpty()) {
            throw new AwsException("OperationNotPermitted",
                    "The Client VPN endpoint " + id + " has associated target networks. "
                            + "Disassociate them before deleting the endpoint.", 400);
        }
        endpoints.delete(key(region, id));
        return endpoint;
    }

    public synchronized ClientVpnEndpoint.TargetNetwork associateTargetNetwork(String region, String id,
                                                                              String subnetId) {
        ClientVpnEndpoint endpoint = require(region, id);
        requireParameter(subnetId, "SubnetId");
        Subnet subnet = ec2Service.describeSubnets(region, List.of(subnetId), Map.of()).getFirst();
        if (endpoint.getVpcId() != null && !endpoint.getVpcId().equals(subnet.getVpcId())) {
            throw new AwsException("InvalidParameterValue",
                    "The subnet " + subnetId + " is not in the Client VPN endpoint's VPC " + endpoint.getVpcId() + ".",
                    400);
        }
        for (ClientVpnEndpoint.TargetNetwork existing : endpoint.getTargetNetworks()) {
            if (existing.getSubnetId().equals(subnetId)) {
                throw new AwsException("InvalidClientVpnDuplicateAssociationException",
                        "The subnet " + subnetId + " is already associated with " + id + ".", 400);
            }
        }
        if (endpoint.getVpcId() == null) {
            endpoint.setVpcId(subnet.getVpcId());
            if (endpoint.getSecurityGroupIds().isEmpty()) {
                endpoint.setSecurityGroupIds(defaultSecurityGroup(region, subnet.getVpcId()));
            }
        }
        ClientVpnEndpoint.TargetNetwork network = new ClientVpnEndpoint.TargetNetwork();
        network.setAssociationId("cvpn-assoc-" + randomHex(17));
        network.setSubnetId(subnetId);
        network.setVpcId(subnet.getVpcId());
        network.setSecurityGroups(endpoint.getSecurityGroupIds());
        endpoint.getTargetNetworks().add(network);
        endpoints.put(key(region, id), endpoint);
        return network;
    }

    public List<ClientVpnEndpoint.TargetNetwork> describeTargetNetworks(String region, String id,
                                                                        List<String> associationIds,
                                                                        Map<String, List<String>> filters) {
        ClientVpnEndpoint endpoint = require(region, id);
        return endpoint.getTargetNetworks().stream()
                .filter(n -> associationIds.isEmpty() || associationIds.contains(n.getAssociationId()))
                .filter(n -> matches(filters, "association-id", n.getAssociationId()))
                .filter(n -> matches(filters, "target-network-id", n.getSubnetId()))
                .filter(n -> matches(filters, "vpc-id", n.getVpcId()))
                .toList();
    }

    public synchronized ClientVpnEndpoint.TargetNetwork disassociateTargetNetwork(String region, String id,
                                                                                 String associationId) {
        ClientVpnEndpoint endpoint = require(region, id);
        ClientVpnEndpoint.TargetNetwork network = endpoint.getTargetNetworks().stream()
                .filter(n -> n.getAssociationId().equals(associationId))
                .findFirst()
                .orElseThrow(() -> new AwsException("InvalidClientVpnAssociationId.NotFound",
                        "The association ID '" + associationId + "' does not exist.", 400));
        endpoint.getTargetNetworks().remove(network);
        endpoints.put(key(region, id), endpoint);
        return network;
    }

    public synchronized void authorizeIngress(String region, String id, String cidr, String groupId,
                                              boolean allGroups, String description) {
        ClientVpnEndpoint endpoint = require(region, id);
        requireParameter(cidr, "TargetNetworkCidr");
        if (allGroups == (groupId != null)) {
            throw new AwsException("InvalidParameterCombination",
                    "Specify either AccessGroupId or AuthorizeAllGroups.", 400);
        }
        boolean duplicate = endpoint.getAuthorizationRules().stream().anyMatch(rule ->
                rule.getDestinationCidr().equals(cidr) && Objects.equals(rule.getGroupId(), groupId));
        if (duplicate) {
            throw new AwsException("InvalidClientVpnDuplicateAuthorizationRule",
                    "An authorization rule for " + cidr + " already exists.", 400);
        }
        ClientVpnEndpoint.AuthorizationRule rule = new ClientVpnEndpoint.AuthorizationRule();
        rule.setDestinationCidr(cidr);
        rule.setGroupId(groupId);
        rule.setAccessAll(allGroups);
        rule.setDescription(description);
        endpoint.getAuthorizationRules().add(rule);
        endpoints.put(key(region, id), endpoint);
    }

    public List<ClientVpnEndpoint.AuthorizationRule> describeAuthorizationRules(String region, String id,
                                                                                Map<String, List<String>> filters) {
        return require(region, id).getAuthorizationRules().stream()
                .filter(rule -> matches(filters, "destination-cidr", rule.getDestinationCidr()))
                .filter(rule -> matches(filters, "group-id", rule.getGroupId()))
                .filter(rule -> matches(filters, "description", rule.getDescription()))
                .toList();
    }

    public synchronized void revokeIngress(String region, String id, String cidr, String groupId,
                                           boolean allGroups) {
        ClientVpnEndpoint endpoint = require(region, id);
        requireParameter(cidr, "TargetNetworkCidr");
        boolean removed = endpoint.getAuthorizationRules().removeIf(rule ->
                rule.getDestinationCidr().equals(cidr)
                        && (allGroups ? rule.isAccessAll() : Objects.equals(rule.getGroupId(), groupId)));
        if (!removed) {
            throw new AwsException("InvalidClientVpnEndpointAuthorizationRuleNotFound",
                    "Cannot find an authorization rule for " + cidr + ".", 400);
        }
        endpoints.put(key(region, id), endpoint);
    }

    private ClientVpnEndpoint require(String region, String id) {
        requireParameter(id, "ClientVpnEndpointId");
        return endpoints.get(key(region, id)).orElseThrow(() -> new AwsException(
                "InvalidClientVpnEndpointId.NotFound",
                "The Client VPN endpoint ID '" + id + "' does not exist.", 400));
    }

    private void requireSecurityGroups(String region, String vpcId, List<String> groupIds) {
        if (groupIds.isEmpty()) {
            return;
        }
        List<SecurityGroup> groups = ec2Service.describeSecurityGroups(region, groupIds, List.of(), Map.of());
        for (String groupId : groupIds) {
            SecurityGroup group = groups.stream().filter(g -> g.getGroupId().equals(groupId)).findFirst()
                    .orElseThrow(() -> new AwsException("InvalidGroup.NotFound",
                            "The security group '" + groupId + "' does not exist", 400));
            if (!vpcId.equals(group.getVpcId())) {
                throw new AwsException("InvalidParameterValue",
                        "The security group " + groupId + " does not belong to VPC " + vpcId + ".", 400);
            }
        }
    }

    private List<String> defaultSecurityGroup(String region, String vpcId) {
        return ec2Service.describeSecurityGroups(region, List.of(), List.of(), Map.of()).stream()
                .filter(g -> vpcId.equals(g.getVpcId()) && "default".equals(g.getGroupName()))
                .map(SecurityGroup::getGroupId)
                .limit(1)
                .toList();
    }

    private static boolean matches(Map<String, List<String>> filters, String name, String value) {
        List<String> values = filters.get(name);
        return values == null || values.contains(value);
    }

    private static void requireParameter(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new AwsException("MissingParameter", "The request must contain the parameter " + name, 400);
        }
    }

    private static int parsePrefix(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String key(String region, String id) {
        return region + "|" + id;
    }

    private static String randomHex(int len) {
        StringBuilder sb = new StringBuilder(len);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < len; i++) {
            sb.append(Character.forDigit(random.nextInt(16), 16));
        }
        return sb.toString();
    }
}
