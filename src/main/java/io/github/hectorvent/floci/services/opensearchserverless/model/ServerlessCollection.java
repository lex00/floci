package io.github.hectorvent.floci.services.opensearchserverless.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/** An OpenSearch Serverless collection. */
@RegisterForReflection
public class ServerlessCollection {

    private String id;
    private String name;
    private String region;
    private String arn;
    private String type;
    private String description;
    private String status;
    private String kmsKeyArn;
    private String standbyReplicas;
    private JsonNode vectorOptions;
    private String collectionGroupName;
    private long createdDate;
    private long lastModifiedDate;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getKmsKeyArn() { return kmsKeyArn; }
    public void setKmsKeyArn(String kmsKeyArn) { this.kmsKeyArn = kmsKeyArn; }

    public String getStandbyReplicas() { return standbyReplicas; }
    public void setStandbyReplicas(String standbyReplicas) { this.standbyReplicas = standbyReplicas; }

    public JsonNode getVectorOptions() { return vectorOptions; }
    public void setVectorOptions(JsonNode vectorOptions) { this.vectorOptions = vectorOptions; }

    public String getCollectionGroupName() { return collectionGroupName; }
    public void setCollectionGroupName(String collectionGroupName) { this.collectionGroupName = collectionGroupName; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
