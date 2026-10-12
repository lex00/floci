package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Evaluator;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Set;

/**
 * AgentCore Evaluations evaluator endpoints ({@code /evaluators}). Timestamps are epoch seconds,
 * the REST JSON default, since the model sets no timestamp format on these members.
 */
@Path("/evaluators")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreEvaluatorController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreEvaluatorController.class);

    private final BedrockAgentCoreEvaluationService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreEvaluatorController(BedrockAgentCoreEvaluationService service,
                                               RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/create")
    public Response createEvaluator(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Evaluator evaluator = service.createEvaluator(parse(body), region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("evaluatorArn", service.evaluatorArn(evaluator, region));
            out.put("evaluatorId", evaluator.getEvaluatorId());
            out.put("createdAt", evaluator.getCreatedAt().getEpochSecond());
            out.put("status", evaluator.getStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "creating evaluator");
        }
    }

    @GET
    @Path("/{evaluatorId}")
    public Response getEvaluator(@Context HttpHeaders headers, @PathParam("evaluatorId") String id,
                                 @QueryParam("includedData") String includedData) {
        String region = regionResolver.resolveRegion(headers);
        try {
            if (includedData != null && !Set.of("ALL_DATA", "METADATA_ONLY").contains(includedData)) {
                throw BedrockAgentCoreEvaluationValidation.invalid(
                        "includedData must be one of [ALL_DATA, METADATA_ONLY].");
            }
            Evaluator evaluator = service.getEvaluator(id, region);
            ObjectNode out = service.summary(evaluator, region);
            JsonNode config = evaluator.getEvaluatorConfig().deepCopy();
            if ("METADATA_ONLY".equals(includedData) && config.path("llmAsAJudge").isObject()) {
                // Metadata and model configuration only: the instructions and rating scale are the
                // data a customer managed key protects.
                ObjectNode judge = (ObjectNode) config.get("llmAsAJudge");
                judge.remove("instructions");
                judge.remove("ratingScale");
            }
            out.set("evaluatorConfig", config);
            return Response.ok(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "getting evaluator");
        }
    }

    @PUT
    @Path("/{evaluatorId}")
    public Response updateEvaluator(@Context HttpHeaders headers, @PathParam("evaluatorId") String id,
                                    String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Evaluator evaluator = service.updateEvaluator(id, parse(body), region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("evaluatorArn", service.evaluatorArn(evaluator, region));
            out.put("evaluatorId", evaluator.getEvaluatorId());
            out.put("updatedAt", evaluator.getUpdatedAt().getEpochSecond());
            out.put("status", evaluator.getStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "updating evaluator");
        }
    }

    @DELETE
    @Path("/{evaluatorId}")
    public Response deleteEvaluator(@Context HttpHeaders headers, @PathParam("evaluatorId") String id) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Evaluator evaluator = service.deleteEvaluator(id, region);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("evaluatorArn", service.evaluatorArn(evaluator, region));
            out.put("evaluatorId", evaluator.getEvaluatorId());
            out.put("status", evaluator.getStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "deleting evaluator");
        }
    }

    @POST
    @Path("/")
    public Response listEvaluators(@Context HttpHeaders headers,
                                   @QueryParam("maxResults") String maxResultsParam,
                                   @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<Evaluator> page = service.listEvaluators(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode items = out.putArray("evaluators");
            for (Evaluator evaluator : page.items()) {
                items.add(service.summary(evaluator, region));
            }
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "listing evaluators");
        }
    }

    private JsonNode parse(String body) throws IOException {
        return BedrockAgentCoreEvaluationValidation.parseObject(objectMapper, body);
    }
}
