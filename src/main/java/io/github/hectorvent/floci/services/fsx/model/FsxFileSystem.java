package io.github.hectorvent.floci.services.fsx.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * An FSx file system. The type-specific configuration is kept in its response shape, built from
 * the request's configuration plus the members AWS fills in.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class FsxFileSystem {

    private String fileSystemId;
    private String ownerId;
    private long creationTime;
    private String fileSystemType;
    private String lifecycle = "AVAILABLE";
    private Integer storageCapacity;
    private String storageType;
    private String vpcId;
    private List<String> subnetIds = new ArrayList<>();
    private List<String> securityGroupIds = new ArrayList<>();
    private String dnsName;
    private String kmsKeyId;
    private String resourceArn;
    private String fileSystemTypeVersion;
    private String networkType;
    private String region;
    private ObjectNode configuration;
    private Map<String, String> tags = new LinkedHashMap<>();

    public FsxFileSystem() {}

    public String getFileSystemId() { return fileSystemId; }
    public void setFileSystemId(String v) { this.fileSystemId = v; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String v) { this.ownerId = v; }
    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long v) { this.creationTime = v; }
    public String getFileSystemType() { return fileSystemType; }
    public void setFileSystemType(String v) { this.fileSystemType = v; }
    public String getLifecycle() { return lifecycle; }
    public void setLifecycle(String v) { this.lifecycle = v; }
    public Integer getStorageCapacity() { return storageCapacity; }
    public void setStorageCapacity(Integer v) { this.storageCapacity = v; }
    public String getStorageType() { return storageType; }
    public void setStorageType(String v) { this.storageType = v; }
    public String getVpcId() { return vpcId; }
    public void setVpcId(String v) { this.vpcId = v; }
    public List<String> getSubnetIds() { return subnetIds; }
    public void setSubnetIds(List<String> v) { this.subnetIds = v != null ? new ArrayList<>(v) : new ArrayList<>(); }
    public List<String> getSecurityGroupIds() { return securityGroupIds; }
    public void setSecurityGroupIds(List<String> v) {
        this.securityGroupIds = v != null ? new ArrayList<>(v) : new ArrayList<>();
    }
    public String getDnsName() { return dnsName; }
    public void setDnsName(String v) { this.dnsName = v; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String v) { this.kmsKeyId = v; }
    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String v) { this.resourceArn = v; }
    public String getFileSystemTypeVersion() { return fileSystemTypeVersion; }
    public void setFileSystemTypeVersion(String v) { this.fileSystemTypeVersion = v; }
    public String getNetworkType() { return networkType; }
    public void setNetworkType(String v) { this.networkType = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    /** The LustreConfiguration, WindowsConfiguration, OntapConfiguration or OpenZFSConfiguration. */
    public ObjectNode getConfiguration() { return configuration; }
    public void setConfiguration(ObjectNode v) { this.configuration = v; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> v) { this.tags = v != null ? new LinkedHashMap<>(v) : new LinkedHashMap<>(); }
}
