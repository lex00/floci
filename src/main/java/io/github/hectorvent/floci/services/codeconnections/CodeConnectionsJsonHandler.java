package io.github.hectorvent.floci.services.codeconnections;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.codeconnections.model.Connection;
import io.github.hectorvent.floci.services.codeconnections.model.Host;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 1.0 handler for {@code X-Amz-Target: com.amazonaws.codeconnections.CodeConnections_20231201.*}.
 */
@ApplicationScoped
public class CodeConnectionsJsonHandler {

    private final CodeConnectionsService service;
    private final ObjectMapper mapper;

    @Inject
    public CodeConnectionsJsonHandler(CodeConnectionsService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @SuppressWarnings("unchecked")
    public Response handle(String action, JsonNode request, String region, String accountId) throws Exception {
        return switch (action) {
            case "CreateConnection" -> {
                Connection connection = service.createConnection(region, accountId,
                        text(request, "ConnectionName"), text(request, "ProviderType"),
                        text(request, "HostArn"), tags(request.get("Tags")));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("ConnectionArn", connection.getConnectionArn());
                if (!connection.getTags().isEmpty()) {
                    body.put("Tags", connection.getTags());
                }
                yield ok(body);
            }
            case "GetConnection" -> ok(Map.of("Connection",
                    connection(service.getConnection(text(request, "ConnectionArn")))));
            case "ListConnections" -> {
                List<Map<String, Object>> items = new ArrayList<>();
                for (Connection c : service.listConnections(region, text(request, "ProviderTypeFilter"),
                        text(request, "HostArnFilter"))) {
                    items.add(connection(c));
                }
                yield ok(Map.of("Connections", items));
            }
            case "DeleteConnection" -> {
                service.deleteConnection(text(request, "ConnectionArn"));
                yield ok(Map.of());
            }
            case "CreateHost" -> {
                Map<String, Object> vpc = request.hasNonNull("VpcConfiguration")
                        ? mapper.treeToValue(request.get("VpcConfiguration"), Map.class) : null;
                Host host = service.createHost(region, accountId, text(request, "Name"),
                        text(request, "ProviderType"), text(request, "ProviderEndpoint"), vpc,
                        tags(request.get("Tags")));
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("HostArn", host.getHostArn());
                if (!host.getTags().isEmpty()) {
                    body.put("Tags", host.getTags());
                }
                yield ok(body);
            }
            case "GetHost" -> {
                Host host = service.getHost(text(request, "HostArn"));
                Map<String, Object> body = host(host);
                body.remove("HostArn");
                body.remove("StatusMessage");
                yield ok(body);
            }
            case "ListHosts" -> {
                List<Map<String, Object>> items = new ArrayList<>();
                for (Host h : service.listHosts(region)) {
                    items.add(host(h));
                }
                yield ok(Map.of("Hosts", items));
            }
            case "DeleteHost" -> {
                service.deleteHost(text(request, "HostArn"));
                yield ok(Map.of());
            }
            case "TagResource" -> {
                service.tagResource(text(request, "ResourceArn"), tags(request.get("Tags")));
                yield ok(Map.of());
            }
            case "UntagResource" -> {
                List<String> keys = new ArrayList<>();
                request.path("TagKeys").forEach(k -> keys.add(k.asText()));
                service.untagResource(text(request, "ResourceArn"), keys);
                yield ok(Map.of());
            }
            case "ListTagsForResource" -> ok(Map.of("Tags", service.listTags(text(request, "ResourceArn"))));
            default -> throw new AwsException("UnknownOperationException",
                    "Unknown operation: com.amazonaws.codeconnections.CodeConnections_20231201." + action, 400);
        };
    }

    private static Map<String, Object> connection(Connection connection) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ConnectionName", connection.getConnectionName());
        body.put("ConnectionArn", connection.getConnectionArn());
        body.put("ProviderType", connection.getProviderType());
        body.put("OwnerAccountId", connection.getOwnerAccountId());
        body.put("ConnectionStatus", connection.getConnectionStatus());
        if (connection.getHostArn() != null) {
            body.put("HostArn", connection.getHostArn());
        }
        return body;
    }

    private static Map<String, Object> host(Host host) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Name", host.getName());
        body.put("HostArn", host.getHostArn());
        body.put("ProviderType", host.getProviderType());
        body.put("ProviderEndpoint", host.getProviderEndpoint());
        if (host.getVpcConfiguration() != null) {
            body.put("VpcConfiguration", host.getVpcConfiguration());
        }
        body.put("Status", host.getStatus());
        return body;
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
