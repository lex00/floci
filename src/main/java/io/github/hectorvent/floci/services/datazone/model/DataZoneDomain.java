package io.github.hectorvent.floci.services.datazone.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** A DataZone domain. */
@RegisterForReflection
public class DataZoneDomain {

    private String id;
    private String arn;
    private String name;
    private String description;
    private String domainExecutionRole;
    private String serviceRole;
    private String kmsKeyIdentifier;
    private String domainVersion;
    private JsonNode singleSignOn;
    private String status;
    private String portalUrl;
    private String rootDomainUnitId;
    private Map<String, String> tags;
    private String region;
    private long createdAt;
    private long updatedAt;
    private String createdBy;
    private String updatedBy;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDomainExecutionRole() {
        return domainExecutionRole;
    }

    public void setDomainExecutionRole(String domainExecutionRole) {
        this.domainExecutionRole = domainExecutionRole;
    }

    public String getServiceRole() {
        return serviceRole;
    }

    public void setServiceRole(String serviceRole) {
        this.serviceRole = serviceRole;
    }

    public String getKmsKeyIdentifier() {
        return kmsKeyIdentifier;
    }

    public void setKmsKeyIdentifier(String kmsKeyIdentifier) {
        this.kmsKeyIdentifier = kmsKeyIdentifier;
    }

    public String getDomainVersion() {
        return domainVersion;
    }

    public void setDomainVersion(String domainVersion) {
        this.domainVersion = domainVersion;
    }

    public JsonNode getSingleSignOn() {
        return singleSignOn;
    }

    public void setSingleSignOn(JsonNode singleSignOn) {
        this.singleSignOn = singleSignOn;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPortalUrl() {
        return portalUrl;
    }

    public void setPortalUrl(String portalUrl) {
        this.portalUrl = portalUrl;
    }

    public String getRootDomainUnitId() {
        return rootDomainUnitId;
    }

    public void setRootDomainUnitId(String rootDomainUnitId) {
        this.rootDomainUnitId = rootDomainUnitId;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
