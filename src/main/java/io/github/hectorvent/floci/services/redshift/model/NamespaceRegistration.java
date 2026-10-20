package io.github.hectorvent.floci.services.redshift.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A provisioned or serverless namespace registered with RegisterNamespace, and the consumers it
 * is registered for. It is visible as the namespace's {@code ds_internal_namespace} data share.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NamespaceRegistration {

    private String namespaceId;
    private String namespaceArn;
    private String dataShareArn;
    private String region;
    private Map<String, Instant> consumers = new LinkedHashMap<>();

    public NamespaceRegistration() {}

    public String getNamespaceId() { return namespaceId; }
    public void setNamespaceId(String namespaceId) { this.namespaceId = namespaceId; }
    public String getNamespaceArn() { return namespaceArn; }
    public void setNamespaceArn(String namespaceArn) { this.namespaceArn = namespaceArn; }
    public String getDataShareArn() { return dataShareArn; }
    public void setDataShareArn(String dataShareArn) { this.dataShareArn = dataShareArn; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    /** Consumer identifier to the time it was registered. */
    public Map<String, Instant> getConsumers() { return consumers; }
    public void setConsumers(Map<String, Instant> consumers) {
        this.consumers = consumers != null ? new LinkedHashMap<>(consumers) : new LinkedHashMap<>();
    }
}
