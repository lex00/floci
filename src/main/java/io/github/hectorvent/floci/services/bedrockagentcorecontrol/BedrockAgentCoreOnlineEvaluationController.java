package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.OnlineEvaluationConfig;
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

/**
 * AgentCore Evaluations online evaluation configuration endpoints
 * ({@code /online-evaluation-configs}). Timestamps are epoch seconds.
 */
@Path("/online-evaluation-configs")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockAgentCoreOnlineEvaluationController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreOnlineEvaluationController.class);

    private final BedrockAgentCoreEvaluationService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreOnlineEvaluationController(BedrockAgentCoreEvaluationService service,
                                                      RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/create")
    public Response createOnlineEvaluationConfig(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            OnlineEvaluationConfig config = service.createOnlineConfig(
                    BedrockAgentCoreEvaluationValidation.parseObject(objectMapper, body), region);
            ObjectNode out = identity(config, region);
            out.put("createdAt", config.getCreatedAt().getEpochSecond());
            if (config.getOutputConfig() != null) {
                out.set("outputConfig", config.getOutputConfig());
            }
            out.put("status", config.getStatus());
            out.put("executionStatus", config.getExecutionStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "creating online evaluation config");
        }
    }

    @GET
    @Path("/{onlineEvaluationConfigId}")
    public Response getOnlineEvaluationConfig(@Context HttpHeaders headers,
                                              @PathParam("onlineEvaluationConfigId") String id) {
        String region = regionResolver.resolveRegion(headers);
        try {
            OnlineEvaluationConfig config = service.getOnlineConfig(id, region);
            ObjectNode out = identity(config, region);
            out.put("onlineEvaluationConfigName", config.getOnlineEvaluationConfigName());
            if (config.getDescription() != null) {
                out.put("description", config.getDescription());
            }
            out.set("rule", config.getRule());
            out.set("dataSourceConfig", config.getDataSourceConfig());
            setIfPresent(out, "evaluators", config.getEvaluators());
            setIfPresent(out, "insights", config.getInsights());
            setIfPresent(out, "clusteringConfig", config.getClusteringConfig());
            setIfPresent(out, "outputConfig", config.getOutputConfig());
            out.put("evaluationExecutionRoleArn", config.getEvaluationExecutionRoleArn());
            out.put("status", config.getStatus());
            out.put("executionStatus", config.getExecutionStatus());
            out.put("createdAt", config.getCreatedAt().getEpochSecond());
            out.put("updatedAt", config.getUpdatedAt().getEpochSecond());
            return Response.ok(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "getting online evaluation config");
        }
    }

    @PUT
    @Path("/{onlineEvaluationConfigId}")
    public Response updateOnlineEvaluationConfig(@Context HttpHeaders headers,
                                                 @PathParam("onlineEvaluationConfigId") String id, String body) {
        String region = regionResolver.resolveRegion(headers);
        try {
            OnlineEvaluationConfig config = service.updateOnlineConfig(id,
                    BedrockAgentCoreEvaluationValidation.parseObject(objectMapper, body), region);
            ObjectNode out = identity(config, region);
            out.put("updatedAt", config.getUpdatedAt().getEpochSecond());
            out.put("status", config.getStatus());
            out.put("executionStatus", config.getExecutionStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "updating online evaluation config");
        }
    }

    @DELETE
    @Path("/{onlineEvaluationConfigId}")
    public Response deleteOnlineEvaluationConfig(@Context HttpHeaders headers,
                                                 @PathParam("onlineEvaluationConfigId") String id) {
        String region = regionResolver.resolveRegion(headers);
        try {
            OnlineEvaluationConfig config = service.deleteOnlineConfig(id, region);
            ObjectNode out = identity(config, region);
            out.put("status", config.getStatus());
            return Response.status(202).entity(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "deleting online evaluation config");
        }
    }

    @POST
    @Path("/")
    public Response listOnlineEvaluationConfigs(@Context HttpHeaders headers,
                                                @QueryParam("maxResults") String maxResultsParam,
                                                @QueryParam("nextToken") String nextToken) {
        String region = regionResolver.resolveRegion(headers);
        try {
            Integer maxResults = Pagination.parseMaxResults(maxResultsParam, "ValidationException");
            PaginatedResult<OnlineEvaluationConfig> page = service.listOnlineConfigs(maxResults, nextToken, region);
            ObjectNode out = objectMapper.createObjectNode();
            ArrayNode items = out.putArray("onlineEvaluationConfigs");
            for (OnlineEvaluationConfig config : page.items()) {
                ObjectNode node = identity(config, region);
                node.put("onlineEvaluationConfigName", config.getOnlineEvaluationConfigName());
                if (config.getDescription() != null) {
                    node.put("description", config.getDescription());
                }
                node.put("status", config.getStatus());
                node.put("executionStatus", config.getExecutionStatus());
                node.put("createdAt", config.getCreatedAt().getEpochSecond());
                node.put("updatedAt", config.getUpdatedAt().getEpochSecond());
                setIfPresent(node, "insights", config.getInsights());
                setIfPresent(node, "clusteringConfig", config.getClusteringConfig());
                items.add(node);
            }
            if (page.nextToken() != null) {
                out.put("nextToken", page.nextToken());
            }
            return Response.ok(out).build();
        } catch (Exception e) {
            return BedrockAgentCoreControllerSupport.error(LOG, e, "listing online evaluation configs");
        }
    }

    private ObjectNode identity(OnlineEvaluationConfig config, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        out.put("onlineEvaluationConfigArn", service.onlineConfigArn(config, region));
        out.put("onlineEvaluationConfigId", config.getOnlineEvaluationConfigId());
        return out;
    }

    private static void setIfPresent(ObjectNode out, String field, JsonNode value) {
        if (value != null) {
            out.set(field, value);
        }
    }
}
