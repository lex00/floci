package io.github.hectorvent.floci.services.opensearchserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenSearchServerlessPolicyServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final OpenSearchServerlessService collections = new OpenSearchServerlessService(new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver, mapper);
    private final OpenSearchServerlessPolicyService service = new OpenSearchServerlessPolicyService(
            new InMemoryStorage<>(), collections, regionResolver, mapper);

    @Test
    void accessPolicyUpdateNeedsTheCurrentVersion() throws Exception {
        JsonNode created = service.createPolicy("access", mapper.readTree("""
                {"type": "data", "name": "readers", "policy": "[{\\"Rules\\": [{\\"ResourceType\\": \\"collection\\", \
                 \\"Resource\\": [\\"collection/logs*\\"], \\"Permission\\": [\\"aoss:*\\"]}], \
                 \\"Principal\\": [\\"arn:aws:iam::000000000000:role/r\\"]}]"}"""), REGION);

        AwsException stale = assertThrows(AwsException.class, () -> service.updatePolicy("access", mapper.readTree(
                "{\"type\": \"data\", \"name\": \"readers\", \"policyVersion\": \"old\"}"), REGION));
        assertEquals("ConflictException", stale.getErrorCode());
        service.updatePolicy("access", mapper.readTree("{\"type\": \"data\", \"name\": \"readers\", "
                + "\"description\": \"d\", \"policyVersion\": \"" + created.path("policyVersion").asText() + "\"}"),
                REGION);
        assertEquals(1, service.listPolicies("access", mapper.readTree(
                "{\"type\": \"data\", \"resource\": [\"collection/logs-app\"]}"), REGION).size());
        assertEquals(0, service.listPolicies("access", mapper.readTree(
                "{\"type\": \"data\", \"resource\": [\"collection/metrics\"]}"), REGION).size());
    }

    @Test
    void effectiveLifecyclePolicyMatchesTheIndex() throws Exception {
        service.createPolicy("lifecycle", mapper.readTree("""
                {"type": "retention", "name": "keep", "policy": "{\\"Rules\\": [{\\"ResourceType\\": \\"index\\", \
                 \\"Resource\\": [\\"index/logs/*\\"], \\"MinIndexRetention\\": \\"30d\\"}]}"}"""), REGION);
        JsonNode effective = service.batchGetEffectiveLifecyclePolicy(mapper.readTree(
                "[{\"type\": \"retention\", \"resource\": \"index/logs/app\"},"
                        + "{\"type\": \"retention\", \"resource\": \"index/other/app\"}]"), REGION);

        assertEquals("30d", effective.path("effectiveLifecyclePolicyDetails").get(0).path("retentionPeriod").asText());
        assertEquals(1, effective.path("effectiveLifecyclePolicyErrorDetails").size());
    }

    @Test
    void vpcEndpointSubnetsMerge() throws Exception {
        String id = service.createVpcEndpoint(mapper.readTree(
                "{\"name\": \"vpce-one\", \"vpcId\": \"vpc-1\", \"subnetIds\": [\"subnet-a\"]}"), REGION)
                .path("id").asText();
        JsonNode updated = service.updateVpcEndpoint(mapper.readTree("{\"id\": \"" + id + "\", "
                + "\"addSubnetIds\": [\"subnet-b\"], \"removeSubnetIds\": [\"subnet-a\"]}"), REGION);

        assertEquals("subnet-b", updated.path("subnetIds").get(0).asText());
        assertEquals(1, updated.path("subnetIds").size());
        assertEquals("DELETING", service.deleteVpcEndpoint(id, REGION).path("status").asText());
    }
}
