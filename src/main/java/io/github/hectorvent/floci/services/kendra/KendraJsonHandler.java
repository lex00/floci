package io.github.hectorvent.floci.services.kendra;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

/** JSON 1.1 handler for Amazon Kendra ({@code X-Amz-Target: AWSKendraFrontendService.*}). */
@ApplicationScoped
public class KendraJsonHandler {

    private final KendraService service;

    @Inject
    public KendraJsonHandler(KendraService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateIndex" -> ok(service.createIndex(request, region));
            case "DescribeIndex" -> ok(service.describeIndex(request, region));
            case "UpdateIndex" -> ok(service.updateIndex(request, region));
            case "DeleteIndex" -> ok(service.deleteIndex(request, region));
            case "ListIndices" -> ok(service.listIndices(request, region));
            case "TagResource" -> ok(service.tagResource(request, region));
            case "UntagResource" -> ok(service.untagResource(request, region));
            case "ListTagsForResource" -> ok(service.listTags(request, region));
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: AWSKendraFrontendService." + action))
                    .build();
        };
    }

    private static Response ok(JsonNode entity) {
        return Response.ok(entity).build();
    }
}
