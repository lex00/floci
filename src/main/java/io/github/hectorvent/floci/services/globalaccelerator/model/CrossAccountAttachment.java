package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A cross-account attachment: the principals allowed to use the resources it lists. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CrossAccountAttachment {

    @JsonProperty("AttachmentArn")
    private String attachmentArn;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("Principals")
    private List<String> principals = new ArrayList<>();

    @JsonProperty("Resources")
    private List<AttachmentResource> resources = new ArrayList<>();

    @JsonProperty("LastModifiedTime")
    private Long lastModifiedTime;

    @JsonProperty("CreatedTime")
    private Long createdTime;

    public CrossAccountAttachment() {}

    public String getAttachmentArn() { return attachmentArn; }
    public void setAttachmentArn(String attachmentArn) { this.attachmentArn = attachmentArn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public List<String> getPrincipals() { return principals; }
    public void setPrincipals(List<String> principals) {
        this.principals = principals != null ? new ArrayList<>(principals) : new ArrayList<>();
    }

    public List<AttachmentResource> getResources() { return resources; }
    public void setResources(List<AttachmentResource> resources) {
        this.resources = resources != null ? new ArrayList<>(resources) : new ArrayList<>();
    }

    public Long getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Long lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }

    public Long getCreatedTime() { return createdTime; }
    public void setCreatedTime(Long createdTime) { this.createdTime = createdTime; }
}
