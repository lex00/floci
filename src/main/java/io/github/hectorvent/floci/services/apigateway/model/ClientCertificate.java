package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

/** A client certificate API Gateway presents to an HTTP integration backend. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClientCertificate {
    private String clientCertificateId;
    private String description;
    private String pemEncodedCertificate;
    private long createdDate;
    private long expirationDate;
    private Map<String, String> tags = new HashMap<>();

    public String getClientCertificateId() { return clientCertificateId; }
    public void setClientCertificateId(String clientCertificateId) { this.clientCertificateId = clientCertificateId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPemEncodedCertificate() { return pemEncodedCertificate; }
    public void setPemEncodedCertificate(String pemEncodedCertificate) { this.pemEncodedCertificate = pemEncodedCertificate; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public long getExpirationDate() { return expirationDate; }
    public void setExpirationDate(long expirationDate) { this.expirationDate = expirationDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
