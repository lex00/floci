package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class SageMakerJsonHandler {
    private final SageMakerService service;
    private final SageMakerAppImageConfigService appImageConfigs;

    @Inject
    public SageMakerJsonHandler(SageMakerService service, SageMakerAppImageConfigService appImageConfigs) {
        this.service = service;
        this.appImageConfigs = appImageConfigs;
    }

    public Response handle(String action, JsonNode request, String region) {
        Object entity = switch (action) {
            case "CreateModel" -> service.createModel(request, region);
            case "DescribeModel" -> service.describeModel(request, region);
            case "DeleteModel" -> service.deleteModel(request, region);
            case "ListModels" -> service.listModels(request, region);
            case "CreateEndpointConfig" -> service.createEndpointConfig(request, region);
            case "DescribeEndpointConfig" -> service.describeEndpointConfig(request, region);
            case "DeleteEndpointConfig" -> service.deleteEndpointConfig(request, region);
            case "ListEndpointConfigs" -> service.listEndpointConfigs(request, region);
            case "CreateEndpoint" -> service.createEndpoint(request, region);
            case "DescribeEndpoint" -> service.describeEndpoint(request, region);
            case "DeleteEndpoint" -> service.deleteEndpoint(request, region);
            case "ListEndpoints" -> service.listEndpoints(request, region);
            case "UpdateEndpoint" -> service.updateEndpoint(request, region);
            case "CreateTrainingJob" -> service.createTrainingJob(request, region);
            case "DescribeTrainingJob" -> service.describeTrainingJob(request, region);
            case "ListTrainingJobs" -> service.listTrainingJobs(request, region);
            case "StopTrainingJob" -> service.stopTrainingJob(request, region);
            case "CreateAppImageConfig" -> appImageConfigs.create(request, region);
            case "DescribeAppImageConfig" -> appImageConfigs.describe(request, region);
            case "UpdateAppImageConfig" -> appImageConfigs.update(request, region);
            case "DeleteAppImageConfig" -> appImageConfigs.delete(request, region);
            case "ListAppImageConfigs" -> appImageConfigs.list(request, region);
            case "AddTags" -> ownsArn(request) ? appImageConfigs.addTags(request) : service.addTags(request);
            case "ListTags" -> ownsArn(request) ? appImageConfigs.listTags(request) : service.listTags(request);
            case "DeleteTags" -> ownsArn(request) ? appImageConfigs.deleteTags(request) : service.deleteTags(request);
            default -> throw SageMakerService.validation("Action " + action + " is not supported");
        };
        return Response.ok(entity).build();
    }

    private static boolean ownsArn(JsonNode request) {
        return SageMakerAppImageConfigService.owns(SageMakerService.text(request, "ResourceArn"));
    }
}
