package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.IotBillingGroup;
import io.github.hectorvent.floci.services.iot.model.IotRoleAlias;
import io.github.hectorvent.floci.services.iot.model.IotTopicRuleDestination;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
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

import java.time.Instant;
import java.util.List;

/**
 * REST-JSON routes for AWS IoT billing groups, role aliases and topic rule destinations, on the
 * paths and shapes the AWS SDKs use.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.WILDCARD)
public class IotBillingGroupController {

    private final IotBillingGroupService service;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotBillingGroupController(IotBillingGroupService service, RegionResolver regionResolver,
                                     ObjectMapper objectMapper) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    // ── Billing groups ────────────────────────────────────────────────────────

    @POST
    @Path("/billing-groups/{billingGroupName}")
    public Response createBillingGroup(@Context HttpHeaders headers,
                                       @PathParam("billingGroupName") String name, String body) {
        IotBillingGroup group = service.createBillingGroup(name, IotRequestBody.read(objectMapper, body),
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("billingGroupName", group.getBillingGroupName());
        response.put("billingGroupArn", group.getBillingGroupArn());
        response.put("billingGroupId", group.getBillingGroupId());
        return Response.ok(response).build();
    }

    @GET
    @Path("/billing-groups/{billingGroupName}")
    public Response describeBillingGroup(@Context HttpHeaders headers,
                                         @PathParam("billingGroupName") String name) {
        IotBillingGroup group = service.describeBillingGroup(name, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("billingGroupName", group.getBillingGroupName());
        response.put("billingGroupId", group.getBillingGroupId());
        response.put("billingGroupArn", group.getBillingGroupArn());
        response.put("version", group.getVersion());
        ObjectNode properties = response.putObject("billingGroupProperties");
        if (group.getDescription() != null) {
            properties.put("billingGroupDescription", group.getDescription());
        }
        response.putObject("billingGroupMetadata").put("creationDate", seconds(group.getCreationDate()));
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/billing-groups/{billingGroupName}")
    public Response updateBillingGroup(@Context HttpHeaders headers,
                                       @PathParam("billingGroupName") String name, String body) {
        long version = service.updateBillingGroup(name, IotRequestBody.read(objectMapper, body),
                regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode().put("version", version)).build();
    }

    @DELETE
    @Path("/billing-groups/{billingGroupName}")
    public Response deleteBillingGroup(@Context HttpHeaders headers, @PathParam("billingGroupName") String name,
                                       @QueryParam("expectedVersion") Long expectedVersion) {
        service.deleteBillingGroup(name, expectedVersion, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/billing-groups")
    public Response listBillingGroups(@Context HttpHeaders headers, @QueryParam("nextToken") String nextToken,
                                      @QueryParam("maxResults") Integer maxResults,
                                      @QueryParam("namePrefixFilter") String namePrefix) {
        IotService.Page<IotBillingGroup> page = service.listBillingGroups(namePrefix, nextToken, maxResults,
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode groups = response.putArray("billingGroups");
        for (IotBillingGroup group : page.items()) {
            groups.addObject().put("groupName", group.getBillingGroupName())
                    .put("groupArn", group.getBillingGroupArn());
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    // ── Role aliases ──────────────────────────────────────────────────────────

    @POST
    @Path("/role-aliases/{roleAlias}")
    public Response createRoleAlias(@Context HttpHeaders headers, @PathParam("roleAlias") String alias,
                                    String body) {
        IotRoleAlias roleAlias = service.createRoleAlias(alias, IotRequestBody.read(objectMapper, body),
                regionResolver.resolveRegion(headers));
        return Response.ok(aliasAndArn(roleAlias)).build();
    }

    @GET
    @Path("/role-aliases/{roleAlias}")
    public Response describeRoleAlias(@Context HttpHeaders headers, @PathParam("roleAlias") String alias) {
        IotRoleAlias roleAlias = service.describeRoleAlias(alias, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode description = response.putObject("roleAliasDescription");
        description.put("roleAlias", roleAlias.getRoleAlias());
        description.put("roleAliasArn", roleAlias.getRoleAliasArn());
        description.put("roleArn", roleAlias.getRoleArn());
        description.put("owner", roleAlias.getOwner());
        description.put("credentialDurationSeconds", roleAlias.getCredentialDurationSeconds());
        description.put("creationDate", seconds(roleAlias.getCreationDate()));
        description.put("lastModifiedDate", seconds(roleAlias.getLastModifiedDate()));
        return Response.ok(response).build();
    }

    @PUT
    @Path("/role-aliases/{roleAlias}")
    public Response updateRoleAlias(@Context HttpHeaders headers, @PathParam("roleAlias") String alias,
                                    String body) {
        IotRoleAlias roleAlias = service.updateRoleAlias(alias, IotRequestBody.read(objectMapper, body),
                regionResolver.resolveRegion(headers));
        return Response.ok(aliasAndArn(roleAlias)).build();
    }

    @DELETE
    @Path("/role-aliases/{roleAlias}")
    public Response deleteRoleAlias(@Context HttpHeaders headers, @PathParam("roleAlias") String alias) {
        service.deleteRoleAlias(alias, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/role-aliases")
    public Response listRoleAliases(@Context HttpHeaders headers, @QueryParam("pageSize") Integer pageSize,
                                    @QueryParam("marker") String marker,
                                    @QueryParam("isAscendingOrder") Boolean ascending) {
        IotService.Page<IotRoleAlias> page = service.listRoleAliases(marker, pageSize,
                ascending == null || ascending, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode aliases = response.putArray("roleAliases");
        page.items().forEach(a -> aliases.add(a.getRoleAlias()));
        if (page.nextToken() != null) {
            response.put("nextMarker", page.nextToken());
        }
        return Response.ok(response).build();
    }

    // ── Topic rule destinations ───────────────────────────────────────────────

    @POST
    @Path("/destinations")
    public Response createTopicRuleDestination(@Context HttpHeaders headers, String body) {
        IotTopicRuleDestination destination = service.createTopicRuleDestination(
                IotRequestBody.read(objectMapper, body), regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("topicRuleDestination", destinationNode(destination));
        return Response.ok(response).build();
    }

    @GET
    @Path("/destinations/{arn: .+}")
    public Response getTopicRuleDestination(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        IotTopicRuleDestination destination = service.getTopicRuleDestination(arn,
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.set("topicRuleDestination", destinationNode(destination));
        return Response.ok(response).build();
    }

    @PATCH
    @Path("/destinations")
    public Response updateTopicRuleDestination(@Context HttpHeaders headers, String body) {
        JsonNode request = IotRequestBody.read(objectMapper, body);
        service.updateTopicRuleDestination(request.path("arn").asText(null), request.path("status").asText(null),
                regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @DELETE
    @Path("/destinations/{arn: .+}")
    public Response deleteTopicRuleDestination(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        service.deleteTopicRuleDestination(arn, regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/destinations")
    public Response listTopicRuleDestinations(@Context HttpHeaders headers,
                                              @QueryParam("maxResults") Integer maxResults,
                                              @QueryParam("nextToken") String nextToken) {
        IotService.Page<IotTopicRuleDestination> page = service.listTopicRuleDestinations(nextToken, maxResults,
                regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode summaries = response.putArray("destinationSummaries");
        for (IotTopicRuleDestination destination : page.items()) {
            ObjectNode summary = summaries.addObject();
            summary.put("arn", destination.getArn());
            summary.put("status", destination.getStatus());
            summary.put("createdAt", seconds(destination.getCreatedAt()));
            summary.put("lastUpdatedAt", seconds(destination.getLastUpdatedAt()));
            if (destination.getStatusReason() != null) {
                summary.put("statusReason", destination.getStatusReason());
            }
            putProperties(destination, summary, "httpUrlSummary", "vpcDestinationSummary", "influxDBSummary");
        }
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        return Response.ok(response).build();
    }

    private ObjectNode aliasAndArn(IotRoleAlias roleAlias) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("roleAlias", roleAlias.getRoleAlias());
        response.put("roleAliasArn", roleAlias.getRoleAliasArn());
        return response;
    }

    private ObjectNode destinationNode(IotTopicRuleDestination destination) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("arn", destination.getArn());
        node.put("status", destination.getStatus());
        node.put("createdAt", seconds(destination.getCreatedAt()));
        node.put("lastUpdatedAt", seconds(destination.getLastUpdatedAt()));
        if (destination.getStatusReason() != null) {
            node.put("statusReason", destination.getStatusReason());
        }
        putProperties(destination, node, "httpUrlProperties", "vpcProperties", "influxDBProperties");
        return node;
    }

    private static void putProperties(IotTopicRuleDestination destination, ObjectNode node,
                                      String httpMember, String vpcMember, String influxMember) {
        if (destination.getConfirmationUrl() != null) {
            node.putObject(httpMember).put("confirmationUrl", destination.getConfirmationUrl());
        } else if (destination.getVpcId() != null) {
            ObjectNode vpc = node.putObject(vpcMember);
            putList(vpc.putArray("subnetIds"), destination.getSubnetIds());
            putList(vpc.putArray("securityGroups"), destination.getSecurityGroups());
            vpc.put("vpcId", destination.getVpcId());
            vpc.put("roleArn", destination.getRoleArn());
        } else if (destination.getInfluxDb() != null) {
            ObjectNode influx = node.putObject(influxMember);
            destination.getInfluxDb().forEach(influx::put);
        }
    }

    private static void putList(ArrayNode array, List<String> values) {
        values.forEach(array::add);
    }

    private static double seconds(Instant instant) {
        return instant == null ? 0 : instant.toEpochMilli() / 1000.0;
    }
}
