package io.github.hectorvent.floci.services.ecrpublic.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A stored ECR Public repository with its catalog data, policy and tags. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PublicRepository {

    private String repositoryArn;
    private String registryId;
    private String repositoryName;
    private String repositoryUri;
    private Double createdAt;
    private Map<String, Object> catalogData;
    private String policyText;
    private List<Map<String, String>> tags = new ArrayList<>();

    public PublicRepository() {}

    public String getRepositoryArn() { return repositoryArn; }
    public void setRepositoryArn(String repositoryArn) { this.repositoryArn = repositoryArn; }

    public String getRegistryId() { return registryId; }
    public void setRegistryId(String registryId) { this.registryId = registryId; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getRepositoryUri() { return repositoryUri; }
    public void setRepositoryUri(String repositoryUri) { this.repositoryUri = repositoryUri; }

    public Double getCreatedAt() { return createdAt; }
    public void setCreatedAt(Double createdAt) { this.createdAt = createdAt; }

    public Map<String, Object> getCatalogData() { return catalogData; }
    public void setCatalogData(Map<String, Object> catalogData) { this.catalogData = catalogData; }

    public String getPolicyText() { return policyText; }
    public void setPolicyText(String policyText) { this.policyText = policyText; }

    public List<Map<String, String>> getTags() { return tags; }
    public void setTags(List<Map<String, String>> tags) { this.tags = tags; }
}
