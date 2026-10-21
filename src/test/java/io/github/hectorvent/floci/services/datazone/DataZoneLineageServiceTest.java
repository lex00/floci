package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.datazone.model.DataZoneDomain;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DataZoneLineageServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final DataZoneService domains = new DataZoneService(new InMemoryStorage<>(), new InMemoryStorage<>(),
            new InMemoryStorage<>(), new InMemoryStorage<>(), regionResolver);
    private final DataZoneLineageService service =
            new DataZoneLineageService(new InMemoryStorage<>(), domains, regionResolver);

    @Test
    void lateEventLandsInItsOwnPlaceInTheHistory() throws Exception {
        DataZoneDomain domain = domains.createDomain(REGION, "d", null, null, null, null, null, null, null);
        post(domain, "2026-01-03T00:00:00Z", "late");
        post(domain, "2026-01-01T00:00:00Z", "early");

        ObjectNode job = graphNode(domain, "etl/daily");
        List<ObjectNode> history = service.listLineageNodeHistory(REGION, domain.getId(), job.path("id").asText(),
                Map.of("sortOrder", "ASCENDING"));
        assertEquals(List.of("1767225600", "1767398400"),
                history.stream().map(n -> n.path("eventTimestamp").decimalValue().toBigInteger().toString()).toList());

        ObjectNode early = service.getLineageNode(REGION, domain.getId(), job.path("id").asText(),
                "2026-01-02T00:00:00Z");
        assertEquals(1, early.path("downstreamNodes").size());
        assertEquals(2, service.getLineageNode(REGION, domain.getId(), job.path("id").asText(), null)
                .path("downstreamNodes").size());
    }

    @Test
    void datasetEntriesNeedANamespaceAndName() throws Exception {
        DataZoneDomain domain = domains.createDomain(REGION, "d2", null, null, null, null, null, null, null);
        byte[] event = """
                {"eventTime": "2026-01-01T00:00:00Z", "run": {"runId": "r"}, "job": {"namespace": "etl", "name": "j"},
                 "inputs": [{"name": "orders"}]}""".getBytes(StandardCharsets.UTF_8);
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.postLineageEvent(REGION, domain.getId(), event, null)).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.queryGraph(REGION,
                domain.getId(), mapper.readTree("{\"match\": [{\"entityPattern\": {\"entityType\": \"ASSET\"}}]}")))
                .getErrorCode());
    }

    private void post(DataZoneDomain domain, String eventTime, String output) {
        String event = """
                {"eventType": "COMPLETE", "eventTime": "%s", "run": {"runId": "r"},
                 "job": {"namespace": "etl", "name": "daily"},
                 "outputs": [{"namespace": "s3://curated", "name": "%s"}]}""".formatted(eventTime, output);
        service.postLineageEvent(REGION, domain.getId(), event.getBytes(StandardCharsets.UTF_8), null);
    }

    private ObjectNode graphNode(DataZoneDomain domain, String sourceIdentifier) throws Exception {
        return (ObjectNode) service.queryGraph(REGION, domain.getId(), mapper.readTree(
                "{\"match\": [{\"entityPattern\": {\"entityType\": \"LINEAGE_NODE\", \"identifier\": \""
                        + sourceIdentifier + "\"}}]}")).getFirst().path("lineageNode");
    }
}
