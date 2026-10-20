package io.github.hectorvent.floci.services.globalaccelerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Objects;

/** One resource of a cross-account attachment: an endpoint id or a BYOIP CIDR. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttachmentResource {

    @JsonProperty("EndpointId")
    private String endpointId;

    @JsonProperty("Cidr")
    private String cidr;

    @JsonProperty("Region")
    private String region;

    public AttachmentResource() {}

    public String getEndpointId() { return endpointId; }
    public void setEndpointId(String endpointId) { this.endpointId = endpointId; }

    public String getCidr() { return cidr; }
    public void setCidr(String cidr) { this.cidr = cidr; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    /** The endpoint id or CIDR that identifies the resource within an attachment. */
    public String identity() {
        return endpointId != null ? endpointId : cidr;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AttachmentResource that && Objects.equals(identity(), that.identity());
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(identity());
    }
}
