package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreControlService;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeVersion;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Amazon Bedrock AgentCore data plane ({@code InvokeAgentRuntime}).
 *
 * <p>Real endpoint: {@code POST /runtimes/{agentRuntimeArn}/invocations}. The ARN is
 * URL-encoded in the path, so the template regex captures the remaining path segments.
 * In the default {@code stub} runtime mode the payload is never parsed: the call returns a
 * fixed canned body and echoes the runtime session id. In {@code docker} mode the call goes to
 * a container running the runtime's artifact, and its answer streams back.
 *
 * <p>By default any ARN is accepted (permissive). When
 * {@code floci.services.bedrock-agent-core.validate-runtime-exists=true}, an unknown
 * runtime ARN returns {@code ResourceNotFoundException} (404).
 */
@Path("/")
public class BedrockAgentCoreController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreController.class);
    private static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";

    private final BedrockAgentCoreService service;
    private final BedrockAgentCoreHarnessService harnessService;
    private final BedrockAgentCoreControlService controlService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final boolean validateRuntimeExists;
    private final boolean runInDocker;
    private final AgentRuntimeSessions sessions;
    private final AgentRuntimeSpanWriter spanWriter;

    @Inject
    public BedrockAgentCoreController(BedrockAgentCoreService service,
                                      BedrockAgentCoreHarnessService harnessService,
                                      BedrockAgentCoreControlService controlService,
                                      RegionResolver regionResolver,
                                      ObjectMapper objectMapper,
                                      EmulatorConfig config,
                                      AgentRuntimeSessions sessions,
                                      AgentRuntimeSpanWriter spanWriter) {
        this.service = service;
        this.harnessService = harnessService;
        this.controlService = controlService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.validateRuntimeExists = config.services().bedrockAgentCore().validateRuntimeExists();
        this.runInDocker = "docker".equalsIgnoreCase(config.services().bedrockAgentCore().runtimeMode());
        this.sessions = sessions;
        this.spanWriter = spanWriter;
    }

    @POST
    @Blocking
    @Path("/runtimes/{agentRuntimeArn:.+}/invocations")
    @Consumes(MediaType.WILDCARD)
    public Response invokeAgentRuntime(@Context HttpHeaders headers,
                                       @PathParam("agentRuntimeArn") String agentRuntimeArn,
                                       @QueryParam("qualifier") String qualifier,
                                       @QueryParam("accountId") String accountId,
                                       byte[] payload) {
        String region = regionResolver.resolveRegion(headers);
        if (runInDocker) {
            return invokeInContainer(headers, region, agentRuntimeArn, qualifier, payload);
        }
        if (validateRuntimeExists && !controlService.runtimeArnExists(region, agentRuntimeArn)) {
            return Response.status(404)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", "ResourceNotFoundException")
                    .entity(new AwsErrorResponse("ResourceNotFoundException",
                            "AgentCore runtime not found: " + agentRuntimeArn))
                    .build();
        }

        Instant start = Instant.now();
        String sessionId = headers.getHeaderString(SESSION_HEADER);
        LOG.debugv("InvokeAgentRuntime: arn={0}, qualifier={1}, bytes={2}",
                agentRuntimeArn, qualifier, payload == null ? 0 : payload.length);

        Response.ResponseBuilder builder = Response.ok(service.invoke(), MediaType.APPLICATION_JSON);
        if (spanWriter.enabled()) {
            Optional<AgentRuntime> runtime = controlService.findRuntimeByInvokeArn(region, agentRuntimeArn);
            spanWriter.write(new AgentRuntimeSpanWriter.Invocation(regionResolver.getAccountId(), region,
                    agentRuntimeArn,
                    runtime.map(AgentRuntime::getAgentRuntimeName).orElse(runtimeIdOf(agentRuntimeArn)),
                    qualifier == null || qualifier.isBlank() ? "DEFAULT" : qualifier,
                    sessionId != null ? sessionId : UUID.randomUUID().toString(),
                    headers.getHeaderString("X-Amzn-Trace-Id"), headers.getHeaderString("traceparent"),
                    start, Instant.now(), 200));
        }
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        return builder.build();
    }

    private Response invokeInContainer(HttpHeaders headers, String region, String agentRuntimeArn,
                                       String qualifier, byte[] payload) {
        AgentRuntime runtime = controlService.findRuntimeByInvokeArn(region, agentRuntimeArn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "AgentCore runtime not found: " + agentRuntimeArn, 404));
        String endpointName = "DEFAULT";
        String version = null;
        if (qualifier != null && !qualifier.isBlank() && !"DEFAULT".equals(qualifier)) {
            if (qualifier.chars().allMatch(Character::isDigit)) {
                version = qualifier;
            } else {
                AgentRuntimeEndpoint endpoint = controlService.getEndpoint(
                        runtime.getAgentRuntimeId(), qualifier, region);
                endpointName = endpoint.getName();
                version = endpoint.getLiveVersion();
            }
        }
        AgentRuntimeVersion resolved = controlService.resolveVersion(runtime, version);
        String sessionId = headers.getHeaderString(SESSION_HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = UUID.randomUUID().toString();
        }
        Map<String, String> forwarded = new HashMap<>();
        for (String name : AgentRuntimeSessions.FORWARDED_REQUEST_HEADERS) {
            String value = headers.getHeaderString(name);
            if (value != null) {
                forwarded.put(name, value);
            }
        }
        Instant start = Instant.now();
        AgentRuntimeSessions.Invocation invocation = sessions.invoke(
                new AgentRuntimeSessions.Target(runtime.getAccountId(), region, runtime, resolved, endpointName),
                sessionId, forwarded, payload);
        LOG.debugv("InvokeAgentRuntime: runtime={0}, version={1}, session={2}, status={3}",
                runtime.getAgentRuntimeId(), resolved.getVersion(), sessionId, invocation.status());
        AgentRuntimeSpanWriter.Invocation span = new AgentRuntimeSpanWriter.Invocation(runtime.getAccountId(),
                region, agentRuntimeArn, runtime.getAgentRuntimeName(), endpointName, sessionId,
                headers.getHeaderString("X-Amzn-Trace-Id"), headers.getHeaderString("traceparent"),
                start, start, invocation.status());
        Response.ResponseBuilder builder = Response.status(invocation.status())
                .entity(streaming(out -> {
                    relay(invocation.body(), out);
                    spanWriter.write(new AgentRuntimeSpanWriter.Invocation(span.accountId(), span.region(),
                            span.runtimeArn(), span.runtimeName(), span.endpointName(), span.sessionId(),
                            span.xrayTraceHeader(), span.traceparent(), span.start(), Instant.now(),
                            span.status()));
                }))
                .header(SESSION_HEADER, sessionId);
        invocation.headers().forEach(builder::header);
        if (!invocation.headers().containsKey("Content-Type")) {
            builder.header("Content-Type", MediaType.APPLICATION_OCTET_STREAM);
        }
        return builder.build();
    }

    /** The runtime id an ARN's {@code runtime/<id>} or {@code agent/<id>} resource names. */
    private static String runtimeIdOf(String agentRuntimeArn) {
        int slash = agentRuntimeArn == null ? -1 : agentRuntimeArn.lastIndexOf('/');
        return slash < 0 ? String.valueOf(agentRuntimeArn) : agentRuntimeArn.substring(slash + 1);
    }

    /** Copies the runtime's answer as it arrives, so event streams reach the caller event by event. */
    private static void relay(InputStream body, OutputStream out) {
        byte[] buffer = new byte[8192];
        try (InputStream in = body) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException e) {
            LOG.debugv("The InvokeAgentRuntime stream ended early: {0}", e.getMessage());
        }
    }

    /**
     * {@code InvokeHarness}. Answers with an {@code application/vnd.amazon.eventstream} response,
     * the same framing ConverseStream uses, so an SDK client's stream iterator works unchanged.
     *
     * <p>{@code harnessArn} arrives as a query parameter and {@code runtimeSessionId} as a header,
     * which is how the SDK binds them; only {@code messages}, {@code model} and {@code tools} are in
     * the body.
     *
     * <p>Request validation happens before the first frame: once the stream is committed the status
     * is already 200 and a problem could only be reported as an in-stream exception frame.
     */
    @POST
    @Blocking
    @Path("/harnesses/invoke")
    @Consumes(MediaType.WILDCARD)
    public Response invokeHarness(@Context HttpHeaders headers,
                                 @QueryParam("harnessArn") String harnessArn,
                                 @QueryParam("qualifier") String harnessQualifier,
                                 String body) {
        ObjectNode request;
        try {
            JsonNode parsed = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            request = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();
        } catch (IOException e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON", 400);
        }

        // runtimeSessionId and runtimeUserId are headers on the wire, not body fields. Only the
        // session id is read: runtimeUserId reaches the harness on AWS but changes nothing here.
        String runtimeSessionId = headers.getHeaderString(SESSION_HEADER);
        Consumer<OutputStream> stream = harnessService.invokeHarness(harnessArn, runtimeSessionId, request);
        LOG.debugv("InvokeHarness: arn={0}, qualifier={1}, session={2}",
                harnessArn, harnessQualifier, runtimeSessionId);

        Response.ResponseBuilder builder = Response.ok(streaming(stream))
                .header("Content-Type", "application/vnd.amazon.eventstream");
        if (runtimeSessionId != null) {
            builder.header(SESSION_HEADER, runtimeSessionId);
        }
        return builder.build();
    }

    /**
     * The explicit return type is what lets {@code GenericEntity} keep {@code StreamingOutput} as
     * the entity type; inlining the constructor erases it to {@code Object}. Same helper as the
     * bedrock-runtime controller.
     */
    private static GenericEntity<StreamingOutput> streaming(Consumer<OutputStream> stream) {
        return new GenericEntity<>(stream::accept, StreamingOutput.class);
    }
}
