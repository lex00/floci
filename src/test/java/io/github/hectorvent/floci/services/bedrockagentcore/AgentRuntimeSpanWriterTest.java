package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimeSpanWriterTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String RUNTIME_ARN = "arn:aws:bedrock-agentcore:us-east-1:000000000000:runtime/agent-abc";

    private final ObjectMapper mapper = new ObjectMapper();
    private final CloudWatchLogsService logs = logsService();

    private static CloudWatchLogsService logsService() {
        StorageFactory storage = mock(StorageFactory.class);
        when(storage.create(any(), any(), any())).thenAnswer(i -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        when(storage.create(any(), any(), any(), any())).thenAnswer(i -> AccountAwareStorageBackend.inMemory(ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudwatchlogs().maxStoredEvents()).thenReturn(Integer.MAX_VALUE);
        when(config.services().cloudwatchlogs().maxEventsPerQuery()).thenReturn(10000);
        return new CloudWatchLogsService(storage, config, new RegionResolver(REGION, ACCOUNT), null, null);
    }

    private AgentRuntimeSpanWriter.Invocation invocation(String sessionId, String xray, String traceparent) {
        Instant start = Instant.parse("2026-10-20T10:00:00Z");
        return new AgentRuntimeSpanWriter.Invocation(ACCOUNT, REGION, RUNTIME_ARN, "agent", "DEFAULT",
                sessionId, xray, traceparent, start, start.plusMillis(250), 200);
    }

    @Test
    void spanQueryBySessionIdFindsTheInvocation() {
        AgentRuntimeSpanWriter writer = new AgentRuntimeSpanWriter(true, logs, mapper);
        writer.write(invocation("session-a", null, null));
        writer.write(invocation("session-b", null, null));

        long from = Instant.parse("2026-10-20T09:00:00Z").getEpochSecond();
        String queryId = logs.startQuery(List.of(AgentRuntimeSpanWriter.LOG_GROUP), from, from + 7200,
                "fields traceId, attributes.session.id | filter attributes.session.id = 'session-a'", null, REGION);
        List<LinkedHashMap<String, String>> rows = logs.getQueryResults(queryId).rows();

        assertEquals(1, rows.size());
        assertEquals("session-a", rows.get(0).get("attributes.session.id"));
        assertEquals(32, rows.get(0).get("traceId").length());
    }

    @Test
    void spanCarriesTheRuntimeSessionAndTiming() {
        JsonNode span = new AgentRuntimeSpanWriter(true, logs, mapper).span(invocation("session-a", null, null));

        assertEquals("InvokeAgentRuntime", span.path("name").asText());
        assertEquals("SERVER", span.path("kind").asText());
        assertEquals(RUNTIME_ARN, span.path("resource").path("attributes").path("cloud.resource_id").asText());
        assertEquals("session-a", span.path("attributes").path("session.id").asText());
        assertEquals(250_000_000L, span.path("durationNano").asLong());
        assertEquals("OK", span.path("status").path("code").asText());
    }

    @Test
    void propagatedTraceContextBecomesTheSpanTrace() {
        assertArrayEquals(new String[] {"5759e988bd862e3fe1be46a994272793", "53995c3f42cd8ad8"},
                AgentRuntimeSpanWriter.traceIds(
                        "Root=1-5759e988-bd862e3fe1be46a994272793;Parent=53995c3f42cd8ad8;Sampled=1", null));
        assertArrayEquals(new String[] {"4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7"},
                AgentRuntimeSpanWriter.traceIds(null, "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"));
        assertEquals("", AgentRuntimeSpanWriter.traceIds(null, null)[1]);
    }

    @Test
    void disabledWriterLeavesTheLogGroupAlone() {
        new AgentRuntimeSpanWriter(false, logs, mapper).write(invocation("session-a", null, null));

        assertTrue(logs.describeLogGroups(AgentRuntimeSpanWriter.LOG_GROUP, REGION).isEmpty());
    }
}
