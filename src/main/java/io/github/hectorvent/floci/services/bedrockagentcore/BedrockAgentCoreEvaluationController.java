package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcore.model.BatchEvaluation;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreControllerSupport;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreEvaluationValidation;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * AgentCore Evaluations data-plane endpoints under {@code /evaluations}: {@code Evaluate} and the
 * batch evaluation operations. Batch evaluation timestamps are ISO-8601 strings, which the model
 * sets explicitly on {@code SyntheticTimestamp_date_time}.
 */
@Path("/evaluations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class BedrockAgentCoreEvaluationController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreEvaluationController.class);

    private final BedrockAgentCoreEvaluateService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreEvaluationController(BedrockAgentCoreEvaluateService service,
                                                RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/evaluate/{evaluatorId}")
    public Response evaluate(@Context HttpHeaders headers, @PathParam("evaluatorId") String evaluatorId,
                             String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            return Response.ok(service.evaluate(evaluatorId,
                    BedrockAgentCoreEvaluationValidation.parseObject(objectMapper, body), region)).build();
        } catch (Exception e) {
            return error(e, "evaluating");
        }
    }

    @POST
    @Path("/batch-evaluate")
    public Response startBatchEvaluation(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            BatchEvaluation batch = service.startBatch(
                    BedrockAgentCoreEvaluationValidation.parseObject(objectMapper, body), region);
            ObjectNode out = identity(batch, region);
            out.put("batchEvaluationName", batch.getBatchEvaluationName());
            setIfPresent(out, "evaluators", batch.getEvaluators());
            setIfPresent(out, "insights", batch.getInsights());
            out.put("status", batch.getStatus());
            out.put("createdAt", iso(batch.getCreatedAt()));
            setIfPresent(out, "outputConfig", batch.getOutputConfig());
            if (!batch.getTags().isEmpty()) {
                out.set("tags", objectMapper.valueToTree(batch.getTags()));
            }
            putIfPresent(out, "kmsKeyArn", batch.getKmsKeyArn());
            putIfPresent(out, "description", batch.getDescription());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "starting batch evaluation");
        }
    }

    @GET
    @Path("/batch-evaluate/{batchEvaluationId}")
    public Response getBatchEvaluation(@Context HttpHeaders headers, @PathParam("batchEvaluationId") String id) {
        String region = regionResolver.resolveRegion(headers);
        try {
            BatchEvaluation batch = service.getBatch(id, region);
            ObjectNode out = summary(batch, region);
            out.set("dataSourceConfig", batch.getDataSourceConfig());
            setIfPresent(out, "outputConfig", batch.getOutputConfig());
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "getting batch evaluation");
        }
    }

    @GET
    @Path("/batch-evaluate")
    public Response listBatchEvaluations(@Context HttpHeaders headers,
                                         @QueryParam("maxResults") String maxResultsParam,
                                         @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<BatchEvaluation> page = service.listBatches(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode items = out.putArray("batchEvaluations");
            for (BatchEvaluation batch : page.items()) {
                items.add(summary(batch, region));
            }
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return error(e, "listing batch evaluations");
        }
    }

    @POST
    @Path("/batch-evaluate/{batchEvaluationId}/stop")
    public Response stopBatchEvaluation(@Context HttpHeaders headers, @PathParam("batchEvaluationId") String id) {
        String region = regionResolver.resolveRegion(headers);
        try {
            BatchEvaluation batch = service.stopBatch(id, region);
            ObjectNode out = identity(batch, region);
            out.put("status", batch.getStatus());
            putIfPresent(out, "description", batch.getDescription());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "stopping batch evaluation");
        }
    }

    @DELETE
    @Path("/batch-evaluate/{batchEvaluationId}")
    public Response deleteBatchEvaluation(@Context HttpHeaders headers, @PathParam("batchEvaluationId") String id) {
        String region = regionResolver.resolveRegion(headers);
        try {
            BatchEvaluation batch = service.deleteBatch(id, region);
            ObjectNode out = identity(batch, region);
            out.put("status", batch.getStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return error(e, "deleting batch evaluation");
        }
    }

    /** Members {@code GetBatchEvaluation} and {@code BatchEvaluationSummary} share. */
    private ObjectNode summary(BatchEvaluation batch, String region) {
        ObjectNode out = identity(batch, region);
        out.put("batchEvaluationName", batch.getBatchEvaluationName());
        out.put("status", batch.getStatus());
        out.put("createdAt", iso(batch.getCreatedAt()));
        putIfPresent(out, "description", batch.getDescription());
        setIfPresent(out, "evaluators", batch.getEvaluators());
        setIfPresent(out, "insights", batch.getInsights());
        if (batch.getEvaluationResults() != null && batch.getEvaluationResults().has("numberOfSessionsCompleted")) {
            out.set("evaluationResults", batch.getEvaluationResults());
        }
        putIfPresent(out, "kmsKeyArn", batch.getKmsKeyArn());
        out.put("updatedAt", iso(batch.getUpdatedAt()));
        return out;
    }

    private ObjectNode identity(BatchEvaluation batch, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("batchEvaluationId", batch.getBatchEvaluationId());
        out.put("batchEvaluationArn", service.batchArn(batch, region));
        return out;
    }

    private static String iso(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MILLIS).toString();
    }

    private static void setIfPresent(ObjectNode out, String field, JsonNode value) {
        if (value != null) {
            out.set(field, value);
        }
    }

    private static void putIfPresent(ObjectNode out, String field, String value) {
        if (value != null) {
            out.put(field, value);
        }
    }

    private static Response error(Exception e, String action) {
        return BedrockAgentCoreControllerSupport.error(LOG, e, action);
    }
}
