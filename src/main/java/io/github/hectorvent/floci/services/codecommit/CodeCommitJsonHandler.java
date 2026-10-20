package io.github.hectorvent.floci.services.codecommit;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codecommit.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 1.1 handler for {@code X-Amz-Target: CodeCommit_20150413.*}. */
@ApplicationScoped
public class CodeCommitJsonHandler {

    private final CodeCommitService service;

    @Inject
    public CodeCommitJsonHandler(CodeCommitService service) {
        this.service = service;
    }

    public Response handle(String action, JsonNode request, String region, String accountId) {
        return switch (action) {
            case "CreateRepository" -> ok(Map.of("repositoryMetadata", metadata(service.createRepository(region,
                    accountId, text(request, "repositoryName"), text(request, "repositoryDescription"),
                    text(request, "kmsKeyId"), tagsMap(request.get("tags"))))));
            case "GetRepository" -> ok(Map.of("repositoryMetadata",
                    metadata(service.getRepository(region, text(request, "repositoryName")))));
            case "BatchGetRepositories" -> batchGetRepositories(request, region);
            case "UpdateRepositoryDescription" -> {
                service.updateRepositoryDescription(region, text(request, "repositoryName"),
                        text(request, "repositoryDescription"));
                yield ok(Map.of());
            }
            case "UpdateRepositoryName" -> {
                service.updateRepositoryName(region, accountId, text(request, "oldName"), text(request, "newName"));
                yield ok(Map.of());
            }
            case "DeleteRepository" -> {
                String id = service.deleteRepository(region, text(request, "repositoryName"));
                Map<String, Object> body = new LinkedHashMap<>();
                if (id != null) {
                    body.put("repositoryId", id);
                }
                yield ok(body);
            }
            case "ListRepositories" -> listRepositories(request, region);
            case "TagResource" -> {
                service.tagResource(region, text(request, "resourceArn"), tagsMap(request.get("tags")));
                yield ok(Map.of());
            }
            case "UntagResource" -> {
                service.untagResource(region, text(request, "resourceArn"), stringList(request.get("tagKeys")));
                yield ok(Map.of());
            }
            case "ListTagsForResource" -> ok(Map.of("tags", service.listTags(region, text(request, "resourceArn"))));
            default -> throw new AwsException("UnknownOperationException",
                    "Unknown operation: CodeCommit_20150413." + action, 400);
        };
    }

    private Response batchGetRepositories(JsonNode request, String region) {
        List<String> names = stringList(request.get("repositoryNames"));
        if (names == null || names.isEmpty()) {
            throw new AwsException("RepositoryNamesRequiredException", "At least one repository name is required.", 400);
        }
        List<Map<String, Object>> found = new ArrayList<>();
        List<String> notFound = new ArrayList<>();
        for (String name : names) {
            try {
                found.add(metadata(service.getRepository(region, name)));
            } catch (AwsException e) {
                if (!"RepositoryDoesNotExistException".equals(e.getErrorCode())) {
                    throw e;
                }
                notFound.add(name);
            }
        }
        return ok(Map.of("repositories", found, "repositoriesNotFound", notFound, "errors", List.of()));
    }

    private Response listRepositories(JsonNode request, String region) {
        List<Map<String, Object>> pairs = new ArrayList<>();
        for (Repository repository : service.listRepositories(region,
                text(request, "sortBy"), text(request, "order"))) {
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("repositoryName", repository.getRepositoryName());
            pair.put("repositoryId", repository.getRepositoryId());
            pairs.add(pair);
        }
        return ok(Map.of("repositories", pairs));
    }

    private static Map<String, Object> metadata(Repository repository) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", repository.getAccountId());
        body.put("repositoryId", repository.getRepositoryId());
        body.put("repositoryName", repository.getRepositoryName());
        putIfPresent(body, "repositoryDescription", repository.getRepositoryDescription());
        putIfPresent(body, "defaultBranch", repository.getDefaultBranch());
        body.put("lastModifiedDate", repository.getLastModifiedDate());
        body.put("creationDate", repository.getCreationDate());
        body.put("cloneUrlHttp", repository.getCloneUrlHttp());
        body.put("cloneUrlSsh", repository.getCloneUrlSsh());
        body.put("Arn", repository.getArn());
        putIfPresent(body, "kmsKeyId", repository.getKmsKeyId());
        return body;
    }

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static Map<String, String> tagsMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        return tags;
    }

    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asText()));
        return values;
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
