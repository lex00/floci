package io.github.hectorvent.floci.services.ecrpublic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecrpublic.model.PublicRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 1.1 handler for ECR Public, {@code X-Amz-Target: SpencerFrontendService.*}. */
@ApplicationScoped
public class EcrPublicJsonHandler {

    private final EcrPublicService service;
    private final ObjectMapper mapper;

    @Inject
    public EcrPublicJsonHandler(EcrPublicService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region, String accountId) throws Exception {
        String registryId = text(request, "registryId");
        String name = text(request, "repositoryName");
        return switch (action) {
            case "CreateRepository" -> {
                PublicRepository repository = service.createRepository(region, accountId, name,
                        map(request.get("catalogData")), tags(request.get("tags")));
                yield ok(Map.of("repository", repository(repository), "catalogData", repository.getCatalogData()));
            }
            case "DescribeRepositories" -> {
                List<String> names = null;
                if (request.hasNonNull("repositoryNames")) {
                    names = new ArrayList<>();
                    for (JsonNode n : request.get("repositoryNames")) {
                        names.add(n.asText());
                    }
                }
                List<Map<String, Object>> items = new ArrayList<>();
                for (PublicRepository r : service.describeRepositories(accountId, registryId, names)) {
                    items.add(repository(r));
                }
                yield ok(Map.of("repositories", items));
            }
            case "DeleteRepository" -> ok(Map.of("repository",
                    repository(service.deleteRepository(accountId, registryId, name))));
            case "PutRepositoryCatalogData" -> ok(Map.of("catalogData",
                    service.putCatalogData(accountId, registryId, name, map(request.get("catalogData")))));
            case "GetRepositoryCatalogData" -> ok(Map.of("catalogData",
                    service.getRepository(accountId, registryId, name).getCatalogData()));
            case "SetRepositoryPolicy" -> {
                PublicRepository repository = service.setPolicy(accountId, registryId, name,
                        text(request, "policyText"));
                yield ok(policy(repository.getRegistryId(), name, repository.getPolicyText()));
            }
            case "GetRepositoryPolicy" -> ok(policy(accountId, name,
                    service.getPolicy(accountId, registryId, name)));
            case "DeleteRepositoryPolicy" -> ok(policy(accountId, name,
                    service.deletePolicy(accountId, registryId, name)));
            case "TagResource" -> {
                service.tagResource(accountId, text(request, "resourceArn"), tags(request.get("tags")));
                yield ok(Map.of());
            }
            case "UntagResource" -> {
                List<String> keys = new ArrayList<>();
                request.path("tagKeys").forEach(k -> keys.add(k.asText()));
                service.untagResource(accountId, text(request, "resourceArn"), keys);
                yield ok(Map.of());
            }
            case "ListTagsForResource" -> ok(Map.of("tags",
                    service.listTags(accountId, text(request, "resourceArn"))));
            default -> throw new AwsException("UnsupportedCommandException",
                    "The action " + action + " is not supported by this release.", 400);
        };
    }

    private static Map<String, Object> repository(PublicRepository repository) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repositoryArn", repository.getRepositoryArn());
        body.put("registryId", repository.getRegistryId());
        body.put("repositoryName", repository.getRepositoryName());
        body.put("repositoryUri", repository.getRepositoryUri());
        body.put("createdAt", repository.getCreatedAt());
        return body;
    }

    private static Map<String, Object> policy(String registryId, String name, String policyText) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registryId", registryId);
        body.put("repositoryName", name);
        body.put("policyText", policyText);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(JsonNode node) throws Exception {
        return node == null || node.isNull() ? null : mapper.treeToValue(node, Map.class);
    }

    private static List<Map<String, String>> tags(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<Map<String, String>> tags = new ArrayList<>();
        for (JsonNode tag : node) {
            Map<String, String> t = new LinkedHashMap<>();
            t.put("Key", tag.path("Key").asText());
            t.put("Value", tag.path("Value").asText());
            tags.add(t);
        }
        return tags;
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
