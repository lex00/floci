package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudwatch.logs.CloudWatchLogsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes the server span of each {@code InvokeAgentRuntime} call to the {@code aws/spans} log group,
 * as AgentCore observability does once CloudWatch Transaction Search is on. A span is one
 * OpenTelemetry span record in JSON, keyed to the call's session by its {@code session.id}
 * attribute, so a Logs Insights query over {@code aws/spans} finds the sessions an evaluation reads.
 */
@ApplicationScoped
public class AgentRuntimeSpanWriter {

    static final String LOG_GROUP = "aws/spans";
    static final String LOG_STREAM = "default";

    private static final Logger LOG = Logger.getLogger(AgentRuntimeSpanWriter.class);
    private static final Pattern XRAY_ROOT = Pattern.compile("Root=1-([0-9a-fA-F]{8})-([0-9a-fA-F]{24})");
    private static final Pattern XRAY_PARENT = Pattern.compile("Parent=([0-9a-fA-F]{16})");
    private static final Pattern TRACEPARENT = Pattern.compile("^[0-9a-f]{2}-([0-9a-f]{32})-([0-9a-f]{16})-");

    private final boolean enabled;
    private final CloudWatchLogsService logsService;
    private final ObjectMapper objectMapper;

    @Inject
    public AgentRuntimeSpanWriter(EmulatorConfig config, CloudWatchLogsService logsService, ObjectMapper objectMapper) {
        this(config.services().bedrockAgentCore().writeTraceSpans(), logsService, objectMapper);
    }

    AgentRuntimeSpanWriter(boolean enabled, CloudWatchLogsService logsService, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.logsService = logsService;
        this.objectMapper = objectMapper;
    }

    /** One finished invocation: what the span names and how it ended. */
    public record Invocation(String accountId, String region, String runtimeArn, String runtimeName,
                             String endpointName, String sessionId, String xrayTraceHeader,
                             String traceparent, Instant start, Instant end, int status) {}

    public boolean enabled() {
        return enabled;
    }

    public void write(Invocation invocation) {
        if (!enabled) {
            return;
        }
        try {
            ensureLogStream(invocation.accountId(), invocation.region());
            logsService.putLogEventsForAccount(invocation.accountId(), LOG_GROUP, LOG_STREAM,
                    List.of(Map.of("timestamp", invocation.end().toEpochMilli(),
                            "message", objectMapper.writeValueAsString(span(invocation)))),
                    invocation.region());
        } catch (Exception e) {
            LOG.warnv("Could not write the AgentCore span for session {0} to {1}: {2}",
                    invocation.sessionId(), LOG_GROUP, e.getMessage());
        }
    }

    ObjectNode span(Invocation invocation) {
        String[] ids = traceIds(invocation.xrayTraceHeader(), invocation.traceparent());
        ObjectNode span = objectMapper.createObjectNode();
        ObjectNode resource = span.putObject("resource").putObject("attributes");
        resource.put("aws.service.type", "gen_ai_agent");
        resource.put("cloud.provider", "aws");
        resource.put("cloud.platform", "aws_bedrock_agentcore");
        resource.put("cloud.region", invocation.region());
        resource.put("cloud.resource_id", invocation.runtimeArn());
        resource.put("service.name", invocation.runtimeName() + "." + invocation.endpointName());
        span.putObject("scope").put("name", "bedrock-agentcore");
        span.put("traceId", ids[0]);
        span.put("spanId", randomHex(8));
        span.put("parentSpanId", ids[1]);
        span.put("flags", 256);
        span.put("name", "InvokeAgentRuntime");
        span.put("kind", "SERVER");
        long startNanos = nanos(invocation.start());
        long endNanos = nanos(invocation.end());
        span.put("startTimeUnixNano", startNanos);
        span.put("endTimeUnixNano", endNanos);
        span.put("durationNano", endNanos - startNanos);
        ObjectNode attributes = span.putObject("attributes");
        attributes.put("session.id", invocation.sessionId());
        attributes.put("aws.operation.name", "InvokeAgentRuntime");
        attributes.put("aws.agent.runtime.arn", invocation.runtimeArn());
        attributes.put("aws.agent.runtime.endpoint", invocation.endpointName());
        attributes.put("http.response.status_code", invocation.status());
        span.putObject("status").put("code", invocation.status() < 400 ? "OK" : "ERROR");
        return span;
    }

    private void ensureLogStream(String accountId, String region) {
        try {
            logsService.createLogGroupForAccount(accountId, LOG_GROUP, null, null, region);
        } catch (AwsException expected) {
            // ResourceAlreadyExistsException: the group outlives the first span.
            LOG.tracev("Log group {0} is in place: {1}", LOG_GROUP, expected.getMessage());
        }
        try {
            logsService.createLogStreamForAccount(accountId, LOG_GROUP, LOG_STREAM, region);
        } catch (AwsException expected) {
            LOG.tracev("Log stream {0}/{1} is in place: {2}", LOG_GROUP, LOG_STREAM, expected.getMessage());
        }
    }

    /** The trace and parent span the caller propagated, or a new trace with no parent. */
    static String[] traceIds(String xrayTraceHeader, String traceparent) {
        if (traceparent != null) {
            Matcher matcher = TRACEPARENT.matcher(traceparent.trim());
            if (matcher.find()) {
                return new String[] {matcher.group(1), matcher.group(2)};
            }
        }
        if (xrayTraceHeader != null) {
            Matcher matcher = XRAY_ROOT.matcher(xrayTraceHeader);
            if (matcher.find()) {
                Matcher parent = XRAY_PARENT.matcher(xrayTraceHeader);
                return new String[] {(matcher.group(1) + matcher.group(2)).toLowerCase(),
                        parent.find() ? parent.group(1).toLowerCase() : ""};
            }
        }
        return new String[] {Long.toHexString(Instant.now().getEpochSecond()) + randomHex(12), ""};
    }

    private static long nanos(Instant instant) {
        return Duration.between(Instant.EPOCH, instant).toNanos();
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(random);
        return HexFormat.of().formatHex(random);
    }
}
