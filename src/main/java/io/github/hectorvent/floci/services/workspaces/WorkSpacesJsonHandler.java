package io.github.hectorvent.floci.services.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** Amazon WorkSpaces JSON 1.1 dispatcher (target prefix {@code WorkspacesService}). */
@ApplicationScoped
public class WorkSpacesJsonHandler {

    private final WorkSpacesService service;
    private final ObjectMapper objectMapper;

    @Inject
    public WorkSpacesJsonHandler(WorkSpacesService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateIpGroup" -> ok(objectMapper.createObjectNode()
                    .put("GroupId", service.createIpGroup(request, region)));
            case "DescribeIpGroups" -> list("Result", service.describeIpGroups(request.get("GroupIds"), region));
            case "DeleteIpGroup" -> {
                service.deleteIpGroup(text(request, "GroupId"), region);
                yield empty();
            }
            case "AuthorizeIpRules" -> {
                service.authorizeIpRules(text(request, "GroupId"), request.path("UserRules"), region);
                yield empty();
            }
            case "RevokeIpRules" -> {
                service.revokeIpRules(text(request, "GroupId"), request.path("UserRules"), region);
                yield empty();
            }
            case "UpdateRulesOfIpGroup" -> {
                service.updateRulesOfIpGroup(text(request, "GroupId"), request.path("UserRules"), region);
                yield empty();
            }
            case "CreateConnectionAlias" -> ok(objectMapper.createObjectNode()
                    .put("AliasId", service.createConnectionAlias(request, region)));
            case "DescribeConnectionAliases" -> list("ConnectionAliases",
                    service.describeConnectionAliases(request.get("AliasIds"), region));
            case "DeleteConnectionAlias" -> {
                service.deleteConnectionAlias(text(request, "AliasId"), region);
                yield empty();
            }
            case "CreateWorkspaces" -> {
                List<List<ObjectNode>> outcome = service.createWorkspaces(request.get("Workspaces"), region);
                ObjectNode response = objectMapper.createObjectNode();
                response.putArray("FailedRequests").addAll(outcome.get(1));
                response.putArray("PendingRequests").addAll(outcome.get(0));
                yield ok(response);
            }
            case "DescribeWorkspaces" -> list("Workspaces", service.describeWorkspaces(request, region));
            case "ModifyWorkspaceProperties" -> {
                service.modifyWorkspaceProperties(text(request, "WorkspaceId"),
                        request.get("WorkspaceProperties"), region);
                yield empty();
            }
            case "TerminateWorkspaces" -> list("FailedRequests",
                    service.terminateWorkspaces(request.path("TerminateWorkspaceRequests"), region));
            case "CreateWorkspacesPool" -> wrap("WorkspacesPool", service.createWorkspacesPool(request, region));
            case "DescribeWorkspacesPools" -> list("WorkspacesPools",
                    service.describeWorkspacesPools(request, region));
            case "UpdateWorkspacesPool" -> wrap("WorkspacesPool", service.updateWorkspacesPool(request, region));
            case "StartWorkspacesPool" -> {
                service.setPoolRunning(text(request, "PoolId"), region, true);
                yield empty();
            }
            case "StopWorkspacesPool" -> {
                service.setPoolRunning(text(request, "PoolId"), region, false);
                yield empty();
            }
            case "TerminateWorkspacesPool" -> {
                service.terminateWorkspacesPool(text(request, "PoolId"), region);
                yield empty();
            }
            case "CreateTags" -> {
                service.createTags(text(request, "ResourceId"), request.path("Tags"), region);
                yield empty();
            }
            case "DeleteTags" -> {
                service.deleteTags(text(request, "ResourceId"), request.path("TagKeys"), region);
                yield empty();
            }
            case "DescribeTags" -> list("TagList", service.describeTags(text(request, "ResourceId"), region));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: WorkspacesService." + action))
                    .build();
        };
    }

    private Response ok(ObjectNode response) {
        return Response.ok(response).build();
    }

    private Response wrap(String member, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(member, value);
        return ok(response);
    }

    private Response list(String member, List<ObjectNode> values) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(member).addAll(values);
        return ok(response);
    }

    private Response empty() {
        return ok(objectMapper.createObjectNode());
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
