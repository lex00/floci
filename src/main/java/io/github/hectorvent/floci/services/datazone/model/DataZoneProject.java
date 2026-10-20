package io.github.hectorvent.floci.services.datazone.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** A DataZone project, the owner of glossaries. */
@RegisterForReflection
public class DataZoneProject {

    private String id;
    private String domainId;
    private String name;
    private String description;
    private String domainUnitId;
    private List<String> glossaryTerms;
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

    public String getDomainId() {
        return domainId;
    }

    public void setDomainId(String domainId) {
        this.domainId = domainId;
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

    public String getDomainUnitId() {
        return domainUnitId;
    }

    public void setDomainUnitId(String domainUnitId) {
        this.domainUnitId = domainUnitId;
    }

    public List<String> getGlossaryTerms() {
        return glossaryTerms;
    }

    public void setGlossaryTerms(List<String> glossaryTerms) {
        this.glossaryTerms = glossaryTerms;
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
