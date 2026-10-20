package io.github.hectorvent.floci.services.codecommit.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** A stored CodeCommit repository. The handler renders it as {@code RepositoryMetadata}. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Repository {

    private String accountId;
    private String repositoryId;
    private String repositoryName;
    private String repositoryDescription;
    private String defaultBranch;
    private Double lastModifiedDate;
    private Double creationDate;
    private String cloneUrlHttp;
    private String cloneUrlSsh;
    private String arn;
    private String kmsKeyId;
    private String region;
    private Map<String, String> tags = new LinkedHashMap<>();

    public Repository() {}

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRepositoryId() { return repositoryId; }
    public void setRepositoryId(String repositoryId) { this.repositoryId = repositoryId; }

    public String getRepositoryName() { return repositoryName; }
    public void setRepositoryName(String repositoryName) { this.repositoryName = repositoryName; }

    public String getRepositoryDescription() { return repositoryDescription; }
    public void setRepositoryDescription(String repositoryDescription) {
        this.repositoryDescription = repositoryDescription;
    }

    public String getDefaultBranch() { return defaultBranch; }
    public void setDefaultBranch(String defaultBranch) { this.defaultBranch = defaultBranch; }

    public Double getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(Double lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }

    public Double getCreationDate() { return creationDate; }
    public void setCreationDate(Double creationDate) { this.creationDate = creationDate; }

    public String getCloneUrlHttp() { return cloneUrlHttp; }
    public void setCloneUrlHttp(String cloneUrlHttp) { this.cloneUrlHttp = cloneUrlHttp; }

    public String getCloneUrlSsh() { return cloneUrlSsh; }
    public void setCloneUrlSsh(String cloneUrlSsh) { this.cloneUrlSsh = cloneUrlSsh; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
