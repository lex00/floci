package io.github.hectorvent.floci.services.cloudfront.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A CloudFront Anycast static IP list. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnycastIpList {

    private String id;
    private String name;
    private String arn;
    private String status;
    private String ipAddressType;
    private int ipCount;
    private List<String> anycastIps = new ArrayList<>();
    private List<IpamCidrConfig> ipamCidrConfigs = new ArrayList<>();
    private Instant lastModifiedTime;
    private String etag;

    /** One IPAM CIDR the list draws its addresses from. */
    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class IpamCidrConfig {
        private String cidr;
        private String ipamPoolArn;
        private String anycastIp;
        private String status;

        public String getCidr() { return cidr; }
        public void setCidr(String cidr) { this.cidr = cidr; }

        public String getIpamPoolArn() { return ipamPoolArn; }
        public void setIpamPoolArn(String ipamPoolArn) { this.ipamPoolArn = ipamPoolArn; }

        public String getAnycastIp() { return anycastIp; }
        public void setAnycastIp(String anycastIp) { this.anycastIp = anycastIp; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getIpAddressType() { return ipAddressType; }
    public void setIpAddressType(String ipAddressType) { this.ipAddressType = ipAddressType; }

    public int getIpCount() { return ipCount; }
    public void setIpCount(int ipCount) { this.ipCount = ipCount; }

    public List<String> getAnycastIps() { return anycastIps; }
    public void setAnycastIps(List<String> anycastIps) { this.anycastIps = anycastIps; }

    public List<IpamCidrConfig> getIpamCidrConfigs() { return ipamCidrConfigs; }
    public void setIpamCidrConfigs(List<IpamCidrConfig> ipamCidrConfigs) { this.ipamCidrConfigs = ipamCidrConfigs; }

    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }
}
