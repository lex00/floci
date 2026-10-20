package io.github.hectorvent.floci.services.appstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** AppStream 2.0 JSON 1.1 dispatcher (target prefix {@code PhotonAdminProxyService}). */
@ApplicationScoped
public class AppStreamJsonHandler {

    private final AppStreamService service;
    private final ObjectMapper objectMapper;

    @Inject
    public AppStreamJsonHandler(AppStreamService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateStack" -> wrap("Stack", service.createStack(request, region));
            case "DescribeStacks" -> list("Stacks", service.describeStacks(request.get("Names"), region));
            case "UpdateStack" -> wrap("Stack", service.updateStack(request, region));
            case "DeleteStack" -> {
                service.deleteStack(text(request, "Name"), region);
                yield empty();
            }
            case "CreateFleet" -> wrap("Fleet", service.createFleet(request, region));
            case "DescribeFleets" -> list("Fleets", service.describeFleets(request.get("Names"), region));
            case "UpdateFleet" -> wrap("Fleet", service.updateFleet(request, region));
            case "StartFleet" -> {
                service.startFleet(text(request, "Name"), region);
                yield empty();
            }
            case "StopFleet" -> {
                service.stopFleet(text(request, "Name"), region);
                yield empty();
            }
            case "DeleteFleet" -> {
                service.deleteFleet(text(request, "Name"), region);
                yield empty();
            }
            case "AssociateFleet" -> {
                service.associateFleet(text(request, "FleetName"), text(request, "StackName"), region);
                yield empty();
            }
            case "DisassociateFleet" -> {
                service.disassociateFleet(text(request, "FleetName"), text(request, "StackName"), region);
                yield empty();
            }
            case "ListAssociatedStacks" -> names(service.listAssociatedStacks(text(request, "FleetName"), region));
            case "ListAssociatedFleets" -> names(service.listAssociatedFleets(text(request, "StackName"), region));
            case "CreateUser" -> {
                service.createUser(request, region);
                yield empty();
            }
            case "DescribeUsers" -> list("Users", service.describeUsers(request, region));
            case "EnableUser" -> {
                service.setUserEnabled(request, region, true);
                yield empty();
            }
            case "DisableUser" -> {
                service.setUserEnabled(request, region, false);
                yield empty();
            }
            case "DeleteUser" -> {
                service.deleteUser(request, region);
                yield empty();
            }
            case "CreateImageBuilder" -> wrap("ImageBuilder", service.createImageBuilder(request, region));
            case "DescribeImageBuilders" ->
                    list("ImageBuilders", service.describeImageBuilders(request.get("Names"), region));
            case "StartImageBuilder" ->
                    wrap("ImageBuilder", service.setImageBuilderState(text(request, "Name"), region, true));
            case "StopImageBuilder" ->
                    wrap("ImageBuilder", service.setImageBuilderState(text(request, "Name"), region, false));
            case "DeleteImageBuilder" -> wrap("ImageBuilder", service.deleteImageBuilder(text(request, "Name"), region));
            case "CreateImageBuilderStreamingURL" ->
                    Response.ok(service.createImageBuilderStreamingUrl(request, region)).build();
            case "DescribeImages" -> list("Images", service.describeImages(request, region));
            case "CreateUpdatedImage" -> Response.ok(service.createUpdatedImage(request, region)).build();
            case "CopyImage" -> {
                ObjectNode response = objectMapper.createObjectNode();
                response.put("DestinationImageName", service.copyImage(request, region));
                yield Response.ok(response).build();
            }
            case "DeleteImage" -> wrap("Image", service.deleteImage(text(request, "Name"), region));
            case "DescribeImagePermissions" -> Response.ok(service.describeImagePermissions(request, region)).build();
            case "UpdateImagePermissions" -> {
                service.updateImagePermissions(request, region);
                yield empty();
            }
            case "DeleteImagePermissions" -> {
                service.deleteImagePermissions(request, region);
                yield empty();
            }
            case "CreateAppBlock" -> wrap("AppBlock", service.createAppBlock(request, region));
            case "DescribeAppBlocks" -> list("AppBlocks", service.describeAppBlocks(request.get("Arns"), region));
            case "DeleteAppBlock" -> {
                service.deleteAppBlock(text(request, "Name"), region);
                yield empty();
            }
            case "CreateApplication" -> wrap("Application", service.createApplication(request, region));
            case "DescribeApplications" ->
                    list("Applications", service.describeApplications(request.get("Arns"), region));
            case "UpdateApplication" -> wrap("Application", service.updateApplication(request, region));
            case "DeleteApplication" -> {
                service.deleteApplication(text(request, "Name"), region);
                yield empty();
            }
            case "AssociateApplicationFleet" -> wrap("ApplicationFleetAssociation", service.associateApplicationFleet(
                    text(request, "FleetName"), text(request, "ApplicationArn"), region));
            case "DisassociateApplicationFleet" -> {
                service.disassociateApplicationFleet(text(request, "FleetName"), text(request, "ApplicationArn"), region);
                yield empty();
            }
            case "DescribeApplicationFleetAssociations" -> list("ApplicationFleetAssociations",
                    service.describeApplicationFleetAssociations(request, region));
            case "CreateEntitlement" -> wrap("Entitlement", service.createEntitlement(request, region));
            case "DescribeEntitlements" -> list("Entitlements", service.describeEntitlements(request, region));
            case "UpdateEntitlement" -> wrap("Entitlement", service.updateEntitlement(request, region));
            case "DeleteEntitlement" -> {
                service.deleteEntitlement(text(request, "Name"), text(request, "StackName"), region);
                yield empty();
            }
            case "AssociateApplicationToEntitlement" -> {
                service.setEntitledApplication(request, region, true);
                yield empty();
            }
            case "DisassociateApplicationFromEntitlement" -> {
                service.setEntitledApplication(request, region, false);
                yield empty();
            }
            case "ListEntitledApplications" ->
                    list("EntitledApplications", service.listEntitledApplications(request, region));
            case "TagResource" -> {
                service.tagResource(text(request, "ResourceArn"), request.path("Tags"));
                yield empty();
            }
            case "UntagResource" -> {
                service.untagResource(text(request, "ResourceArn"), request.path("TagKeys"));
                yield empty();
            }
            case "ListTagsForResource" -> wrap("Tags", service.listTagsForResource(text(request, "ResourceArn")));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: PhotonAdminProxyService." + action))
                    .build();
        };
    }

    private Response wrap(String member, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(member, value);
        return Response.ok(response).build();
    }

    private Response list(String member, List<ObjectNode> values) {
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray(member).addAll(values);
        return Response.ok(response).build();
    }

    private Response names(List<String> names) {
        ObjectNode response = objectMapper.createObjectNode();
        names.forEach(response.putArray("Names")::add);
        return Response.ok(response).build();
    }

    private Response empty() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static String text(JsonNode request, String field) {
        JsonNode value = request.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
