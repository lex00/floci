package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * An AWS Client VPN endpoint with its target network associations and authorization rules.
 * Tags live in the EC2 tag store.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientVpnEndpoint {

    private String clientVpnEndpointId;
    private String region;
    private String description;
    private String creationTime;
    private String dnsName;
    private String clientCidrBlock;
    private List<String> dnsServers = new ArrayList<>();
    private boolean splitTunnel;
    private String transportProtocol = "udp";
    private int vpnPort = 443;
    private String serverCertificateArn;
    private List<Authentication> authenticationOptions = new ArrayList<>();
    private Boolean connectionLogEnabled = false;
    private String connectionLogGroup;
    private String connectionLogStream;
    private List<String> securityGroupIds = new ArrayList<>();
    private String vpcId;
    private String selfServicePortal = "disabled";
    private Boolean clientConnectEnabled = false;
    private String clientConnectLambdaFunctionArn;
    private int sessionTimeoutHours = 24;
    private Boolean clientLoginBannerEnabled = false;
    private String clientLoginBannerText;
    private Boolean clientRouteEnforced = false;
    private boolean disconnectOnSessionTimeout = true;
    private String endpointIpAddressType = "ipv4";
    private String trafficIpAddressType = "ipv4";
    private List<TargetNetwork> targetNetworks = new ArrayList<>();
    private List<AuthorizationRule> authorizationRules = new ArrayList<>();

    public ClientVpnEndpoint() {}

    public String getClientVpnEndpointId() { return clientVpnEndpointId; }
    public void setClientVpnEndpointId(String v) { this.clientVpnEndpointId = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public String getCreationTime() { return creationTime; }
    public void setCreationTime(String v) { this.creationTime = v; }
    public String getDnsName() { return dnsName; }
    public void setDnsName(String v) { this.dnsName = v; }
    public String getClientCidrBlock() { return clientCidrBlock; }
    public void setClientCidrBlock(String v) { this.clientCidrBlock = v; }
    public List<String> getDnsServers() { return dnsServers; }
    public void setDnsServers(List<String> v) { this.dnsServers = v != null ? new ArrayList<>(v) : new ArrayList<>(); }
    public boolean isSplitTunnel() { return splitTunnel; }
    public void setSplitTunnel(boolean v) { this.splitTunnel = v; }
    public String getTransportProtocol() { return transportProtocol; }
    public void setTransportProtocol(String v) { this.transportProtocol = v; }
    public int getVpnPort() { return vpnPort; }
    public void setVpnPort(int v) { this.vpnPort = v; }
    public String getServerCertificateArn() { return serverCertificateArn; }
    public void setServerCertificateArn(String v) { this.serverCertificateArn = v; }
    public List<Authentication> getAuthenticationOptions() { return authenticationOptions; }
    public void setAuthenticationOptions(List<Authentication> v) {
        this.authenticationOptions = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }
    public Boolean getConnectionLogEnabled() { return connectionLogEnabled; }
    public void setConnectionLogEnabled(Boolean v) { this.connectionLogEnabled = v; }
    public String getConnectionLogGroup() { return connectionLogGroup; }
    public void setConnectionLogGroup(String v) { this.connectionLogGroup = v; }
    public String getConnectionLogStream() { return connectionLogStream; }
    public void setConnectionLogStream(String v) { this.connectionLogStream = v; }
    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> v) {
        this.securityGroupIds = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }
    public String getVpcId() { return vpcId; }
    public void setVpcId(String v) { this.vpcId = v; }
    public String getSelfServicePortal() { return selfServicePortal; }
    public void setSelfServicePortal(String v) { this.selfServicePortal = v; }
    public Boolean getClientConnectEnabled() { return clientConnectEnabled; }
    public void setClientConnectEnabled(Boolean v) { this.clientConnectEnabled = v; }
    public String getClientConnectLambdaFunctionArn() { return clientConnectLambdaFunctionArn; }
    public void setClientConnectLambdaFunctionArn(String v) { this.clientConnectLambdaFunctionArn = v; }
    public int getSessionTimeoutHours() { return sessionTimeoutHours; }
    public void setSessionTimeoutHours(int v) { this.sessionTimeoutHours = v; }
    public Boolean getClientLoginBannerEnabled() { return clientLoginBannerEnabled; }
    public void setClientLoginBannerEnabled(Boolean v) { this.clientLoginBannerEnabled = v; }
    public String getClientLoginBannerText() { return clientLoginBannerText; }
    public void setClientLoginBannerText(String v) { this.clientLoginBannerText = v; }
    public Boolean getClientRouteEnforced() { return clientRouteEnforced; }
    public void setClientRouteEnforced(Boolean v) { this.clientRouteEnforced = v; }
    public boolean isDisconnectOnSessionTimeout() { return disconnectOnSessionTimeout; }
    public void setDisconnectOnSessionTimeout(boolean v) { this.disconnectOnSessionTimeout = v; }
    public String getEndpointIpAddressType() { return endpointIpAddressType; }
    public void setEndpointIpAddressType(String v) { this.endpointIpAddressType = v; }
    public String getTrafficIpAddressType() { return trafficIpAddressType; }
    public void setTrafficIpAddressType(String v) { this.trafficIpAddressType = v; }
    public List<TargetNetwork> getTargetNetworks() { return targetNetworks; }
    public void setTargetNetworks(List<TargetNetwork> v) {
        this.targetNetworks = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }
    public List<AuthorizationRule> getAuthorizationRules() { return authorizationRules; }
    public void setAuthorizationRules(List<AuthorizationRule> v) {
        this.authorizationRules = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }

    /** {@code available} once a target network is associated, {@code pending-associate} before. */
    public String statusCode() {
        return targetNetworks.isEmpty() ? "pending-associate" : "available";
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Authentication {
        private String type;
        private String directoryId;
        private String clientRootCertificateChainArn;
        private String samlProviderArn;
        private String selfServiceSamlProviderArn;

        public Authentication() {}

        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
        public String getDirectoryId() { return directoryId; }
        public void setDirectoryId(String v) { this.directoryId = v; }
        public String getClientRootCertificateChainArn() { return clientRootCertificateChainArn; }
        public void setClientRootCertificateChainArn(String v) { this.clientRootCertificateChainArn = v; }
        public String getSamlProviderArn() { return samlProviderArn; }
        public void setSamlProviderArn(String v) { this.samlProviderArn = v; }
        public String getSelfServiceSamlProviderArn() { return selfServiceSamlProviderArn; }
        public void setSelfServiceSamlProviderArn(String v) { this.selfServiceSamlProviderArn = v; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetNetwork {
        private String associationId;
        private String subnetId;
        private String vpcId;
        private List<String> securityGroups = new ArrayList<>();

        public TargetNetwork() {}

        public String getAssociationId() { return associationId; }
        public void setAssociationId(String v) { this.associationId = v; }
        public String getSubnetId() { return subnetId; }
        public void setSubnetId(String v) { this.subnetId = v; }
        public String getVpcId() { return vpcId; }
        public void setVpcId(String v) { this.vpcId = v; }
        public List<String> getSecurityGroups() { return securityGroups; }
        public void setSecurityGroups(List<String> v) {
            this.securityGroups = v != null ? new ArrayList<>(v) : new ArrayList<>();
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthorizationRule {
        private String destinationCidr;
        private String groupId;
        private boolean accessAll;
        private String description;

        public AuthorizationRule() {}

        public String getDestinationCidr() { return destinationCidr; }
        public void setDestinationCidr(String v) { this.destinationCidr = v; }
        public String getGroupId() { return groupId; }
        public void setGroupId(String v) { this.groupId = v; }
        public boolean isAccessAll() { return accessAll; }
        public void setAccessAll(boolean v) { this.accessAll = v; }
        public String getDescription() { return description; }
        public void setDescription(String v) { this.description = v; }
    }
}
