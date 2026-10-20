package io.github.hectorvent.floci.services.opensearchserverless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.opensearchserverless.model.SecurityConfig;
import io.github.hectorvent.floci.services.opensearchserverless.model.SecurityPolicy;
import io.github.hectorvent.floci.services.opensearchserverless.model.ServerlessCollection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** JSON 1.0 handler for {@code X-Amz-Target: OpenSearchServerless.*}. */
@ApplicationScoped
public class OpenSearchServerlessJsonHandler {

    private final OpenSearchServerlessService service;
    private final ObjectMapper objectMapper;

    @Inject
    public OpenSearchServerlessJsonHandler(OpenSearchServerlessService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateSecurityConfig" -> createSecurityConfig(request, region);
            case "GetSecurityConfig" -> getSecurityConfig(request, region);
            case "UpdateSecurityConfig" -> updateSecurityConfig(request, region);
            case "DeleteSecurityConfig" -> deleteSecurityConfig(request, region);
            case "ListSecurityConfigs" -> listSecurityConfigs(request, region);
            case "CreateSecurityPolicy" -> createSecurityPolicy(request, region);
            case "GetSecurityPolicy" -> getSecurityPolicy(request, region);
            case "UpdateSecurityPolicy" -> updateSecurityPolicy(request, region);
            case "DeleteSecurityPolicy" -> deleteSecurityPolicy(request, region);
            case "ListSecurityPolicies" -> listSecurityPolicies(request, region);
            case "CreateCollection" -> createCollection(request, region);
            case "BatchGetCollection" -> batchGetCollection(request, region);
            case "ListCollections" -> listCollections(request, region);
            case "UpdateCollection" -> updateCollection(request, region);
            case "DeleteCollection" -> deleteCollection(request, region);
            case "TagResource" -> tagResource(request, region);
            case "UntagResource" -> untagResource(request, region);
            case "ListTagsForResource" -> listTagsForResource(request, region);
            default -> Response.status(400).entity(new AwsErrorResponse("UnknownOperationException",
                    "Unknown operation: OpenSearchServerless." + action)).build();
        };
    }

    // ── Security configurations ────────────────────────────────────────────────

    private Response createSecurityConfig(JsonNode request, String region) {
        SecurityConfig config = service.createSecurityConfig(region, text(request, "type"), text(request, "name"),
                text(request, "description"), object(request, "samlOptions"),
                object(request, "iamIdentityCenterOptions"), object(request, "iamFederationOptions"));
        return wrap("securityConfigDetail", configDetail(config));
    }

    private Response getSecurityConfig(JsonNode request, String region) {
        return wrap("securityConfigDetail", configDetail(service.getSecurityConfig(region, text(request, "id"))));
    }

    private Response updateSecurityConfig(JsonNode request, String region) {
        SecurityConfig config = service.updateSecurityConfig(region, text(request, "id"),
                text(request, "configVersion"), text(request, "description"), object(request, "samlOptions"),
                object(request, "iamIdentityCenterOptionsUpdates"), object(request, "iamFederationOptions"));
        return wrap("securityConfigDetail", configDetail(config));
    }

    private Response deleteSecurityConfig(JsonNode request, String region) {
        service.deleteSecurityConfig(region, text(request, "id"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listSecurityConfigs(JsonNode request, String region) {
        List<SecurityConfig> all = service.listSecurityConfigs(region, text(request, "type"));
        return page(request, all, "securityConfigSummaries", config -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", config.getId());
            node.put("type", config.getType());
            node.put("configVersion", config.getConfigVersion());
            putIfPresent(node, "description", config.getDescription());
            node.put("createdDate", config.getCreatedDate());
            node.put("lastModifiedDate", config.getLastModifiedDate());
            return node;
        });
    }

    private ObjectNode configDetail(SecurityConfig config) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", config.getId());
        node.put("type", config.getType());
        node.put("configVersion", config.getConfigVersion());
        putIfPresent(node, "description", config.getDescription());
        putIfPresent(node, "samlOptions", config.getSamlOptions());
        putIfPresent(node, "iamIdentityCenterOptions", config.getIamIdentityCenterOptions());
        putIfPresent(node, "iamFederationOptions", config.getIamFederationOptions());
        node.put("createdDate", config.getCreatedDate());
        node.put("lastModifiedDate", config.getLastModifiedDate());
        return node;
    }

    // ── Security policies ──────────────────────────────────────────────────────

    private Response createSecurityPolicy(JsonNode request, String region) {
        SecurityPolicy policy = service.createSecurityPolicy(region, text(request, "type"), text(request, "name"),
                text(request, "description"), text(request, "policy"));
        return wrap("securityPolicyDetail", policyDetail(policy));
    }

    private Response getSecurityPolicy(JsonNode request, String region) {
        return wrap("securityPolicyDetail",
                policyDetail(service.getSecurityPolicy(region, text(request, "type"), text(request, "name"))));
    }

    private Response updateSecurityPolicy(JsonNode request, String region) {
        SecurityPolicy policy = service.updateSecurityPolicy(region, text(request, "type"), text(request, "name"),
                text(request, "policyVersion"), text(request, "description"), text(request, "policy"));
        return wrap("securityPolicyDetail", policyDetail(policy));
    }

    private Response deleteSecurityPolicy(JsonNode request, String region) {
        service.deleteSecurityPolicy(region, text(request, "type"), text(request, "name"));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listSecurityPolicies(JsonNode request, String region) {
        List<SecurityPolicy> all = service.listSecurityPolicies(region, text(request, "type"),
                strings(request.path("resource")));
        return page(request, all, "securityPolicySummaries", policy -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("type", policy.getType());
            node.put("name", policy.getName());
            node.put("policyVersion", policy.getPolicyVersion());
            putIfPresent(node, "description", policy.getDescription());
            node.put("createdDate", policy.getCreatedDate());
            node.put("lastModifiedDate", policy.getLastModifiedDate());
            return node;
        });
    }

    private ObjectNode policyDetail(SecurityPolicy policy) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("type", policy.getType());
        node.put("name", policy.getName());
        node.put("policyVersion", policy.getPolicyVersion());
        putIfPresent(node, "description", policy.getDescription());
        try {
            node.set("policy", objectMapper.readTree(policy.getPolicy()));
        } catch (Exception e) {
            throw new AwsException("InternalServerException", "Stored policy document is not JSON", 500);
        }
        node.put("createdDate", policy.getCreatedDate());
        node.put("lastModifiedDate", policy.getLastModifiedDate());
        return node;
    }

    // ── Collections ────────────────────────────────────────────────────────────

    private Response createCollection(JsonNode request, String region) {
        ServerlessCollection collection = service.createCollection(region, text(request, "name"),
                text(request, "type"), text(request, "description"), tags(request.path("tags")),
                text(request, "standbyReplicas"), object(request, "vectorOptions"),
                text(request, "collectionGroupName"));
        ObjectNode detail = collectionBase(collection);
        detail.put("status", "CREATING");
        return wrap("createCollectionDetail", detail);
    }

    private Response batchGetCollection(JsonNode request, String region) {
        List<String> ids = strings(request.path("ids"));
        List<String> names = strings(request.path("names"));
        if (ids.isEmpty() == names.isEmpty()) {
            throw new AwsException("ValidationException",
                    "You must provide either ids or names, but not both", 400);
        }
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode details = out.putArray("collectionDetails");
        ArrayNode errors = out.putArray("collectionErrorDetails");
        boolean byId = !ids.isEmpty();
        for (String value : byId ? ids : names) {
            ServerlessCollection collection = byId
                    ? service.findCollectionById(region, value)
                    : service.findCollectionByName(region, value);
            if (collection == null) {
                ObjectNode error = errors.addObject();
                error.put(byId ? "id" : "name", value);
                error.put("errorCode", "NOT_FOUND");
                error.put("errorMessage", "The specified collection could not be found.");
                continue;
            }
            ObjectNode detail = collectionBase(collection);
            String endpoint = service.collectionEndpoint(collection);
            detail.put("collectionEndpoint", endpoint);
            detail.put("dashboardEndpoint", endpoint + "/_dashboards");
            details.add(detail);
        }
        return Response.ok(out).build();
    }

    private Response listCollections(JsonNode request, String region) {
        JsonNode filters = request.path("collectionFilters");
        String name = text(filters, "name");
        String status = text(filters, "status");
        String group = text(filters, "collectionGroupName");
        List<ServerlessCollection> all = service.listCollections(region).stream()
                .filter(c -> name == null || name.equals(c.getName()))
                .filter(c -> status == null || status.equals(c.getStatus()))
                .filter(c -> group == null || group.equals(c.getCollectionGroupName()))
                .toList();
        return page(request, all, "collectionSummaries", collection -> {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", collection.getId());
            node.put("name", collection.getName());
            node.put("status", collection.getStatus());
            node.put("arn", collection.getArn());
            putIfPresent(node, "kmsKeyArn", collection.getKmsKeyArn());
            putIfPresent(node, "collectionGroupName", collection.getCollectionGroupName());
            return node;
        });
    }

    private Response updateCollection(JsonNode request, String region) {
        ServerlessCollection collection = service.updateCollection(region, text(request, "id"),
                text(request, "description"));
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("id", collection.getId());
        detail.put("name", collection.getName());
        detail.put("status", collection.getStatus());
        detail.put("type", collection.getType());
        putIfPresent(detail, "description", collection.getDescription());
        detail.put("arn", collection.getArn());
        detail.put("createdDate", collection.getCreatedDate());
        detail.put("lastModifiedDate", collection.getLastModifiedDate());
        return wrap("updateCollectionDetail", detail);
    }

    private Response deleteCollection(JsonNode request, String region) {
        ServerlessCollection collection = service.deleteCollection(region, text(request, "id"));
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("id", collection.getId());
        detail.put("name", collection.getName());
        detail.put("status", "DELETING");
        return wrap("deleteCollectionDetail", detail);
    }

    private ObjectNode collectionBase(ServerlessCollection collection) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", collection.getId());
        node.put("name", collection.getName());
        node.put("status", collection.getStatus());
        node.put("type", collection.getType());
        putIfPresent(node, "description", collection.getDescription());
        node.put("arn", collection.getArn());
        putIfPresent(node, "kmsKeyArn", collection.getKmsKeyArn());
        putIfPresent(node, "standbyReplicas", collection.getStandbyReplicas());
        putIfPresent(node, "vectorOptions", collection.getVectorOptions());
        node.put("createdDate", collection.getCreatedDate());
        node.put("lastModifiedDate", collection.getLastModifiedDate());
        putIfPresent(node, "collectionGroupName", collection.getCollectionGroupName());
        return node;
    }

    // ── Tags ───────────────────────────────────────────────────────────────────

    private Response tagResource(JsonNode request, String region) {
        service.tagResource(region, text(request, "resourceArn"), tags(request.path("tags")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response untagResource(JsonNode request, String region) {
        service.untagResource(region, text(request, "resourceArn"), strings(request.path("tagKeys")));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response listTagsForResource(JsonNode request, String region) {
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode tags = out.putArray("tags");
        service.listTags(region, text(request, "resourceArn"))
                .forEach((key, value) -> tags.addObject().put("key", key).put("value", value));
        return Response.ok(out).build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private <T> Response page(JsonNode request, List<T> all, String field, Function<T, ObjectNode> render) {
        int maxResults = request.path("maxResults").asInt(20);
        if (maxResults < 1 || maxResults > 100) {
            throw new AwsException("ValidationException", "1 validation error detected: Value '" + maxResults
                    + "' at 'maxResults' failed to satisfy constraint: Member must have value between 1 and 100", 400);
        }
        int start = 0;
        String nextToken = text(request, "nextToken");
        if (nextToken != null) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationException", "Invalid nextToken", 400);
            }
        }
        ObjectNode out = objectMapper.createObjectNode();
        ArrayNode items = out.putArray(field);
        int end = Math.min(all.size(), start + maxResults);
        for (int i = Math.max(0, start); i < end; i++) {
            items.add(render.apply(all.get(i)));
        }
        if (end < all.size()) {
            out.put("nextToken", String.valueOf(end));
        }
        return Response.ok(out).build();
    }

    private Response wrap(String field, ObjectNode detail) {
        ObjectNode out = objectMapper.createObjectNode();
        out.set(field, detail);
        return Response.ok(out).build();
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static void putIfPresent(ObjectNode node, String field, JsonNode value) {
        if (value != null && !value.isNull() && !value.isMissingNode()) {
            node.set(field, value);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static JsonNode object(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isObject() ? value : null;
    }

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(item.asText()));
        }
        return out;
    }

    private static Map<String, String> tags(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null && node.isArray()) {
            node.forEach(tag -> out.put(tag.path("key").asText(), tag.path("value").asText()));
        }
        return out;
    }
}
