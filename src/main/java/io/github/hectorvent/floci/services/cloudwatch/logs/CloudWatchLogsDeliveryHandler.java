package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.Delivery;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.DeliveryDestination;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.DeliverySource;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wire shapes of the vended log delivery operations, reached through
 * {@link CloudWatchLogsHandler}.
 */
class CloudWatchLogsDeliveryHandler {

    private final CloudWatchLogsDeliveryService deliveries;
    private final ObjectMapper objectMapper;

    CloudWatchLogsDeliveryHandler(CloudWatchLogsDeliveryService deliveries, ObjectMapper objectMapper) {
        this.deliveries = deliveries;
        this.objectMapper = objectMapper;
    }

    Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "PutDeliverySource" -> putDeliverySource(request, region);
            case "GetDeliverySource" -> ok("deliverySource",
                    sourceNode(deliveries.getDeliverySource(text(request, "name"), region)));
            case "DescribeDeliverySources" -> describeDeliverySources(request, region);
            case "DeleteDeliverySource" -> {
                deliveries.deleteDeliverySource(text(request, "name"), region);
                yield empty();
            }
            case "PutDeliveryDestination" -> putDeliveryDestination(request, region);
            case "GetDeliveryDestination" -> ok("deliveryDestination",
                    destinationNode(deliveries.getDeliveryDestination(text(request, "name"), region)));
            case "DescribeDeliveryDestinations" -> describeDeliveryDestinations(request, region);
            case "DeleteDeliveryDestination" -> {
                deliveries.deleteDeliveryDestination(text(request, "name"), region);
                yield empty();
            }
            case "PutDeliveryDestinationPolicy" -> policy(deliveries.putDeliveryDestinationPolicy(
                    text(request, "deliveryDestinationName"), text(request, "deliveryDestinationPolicy"), region));
            case "GetDeliveryDestinationPolicy" -> policy(deliveries.getDeliveryDestinationPolicy(
                    text(request, "deliveryDestinationName"), region));
            case "DeleteDeliveryDestinationPolicy" -> {
                deliveries.deleteDeliveryDestinationPolicy(text(request, "deliveryDestinationName"), region);
                yield empty();
            }
            case "CreateDelivery" -> createDelivery(request, region);
            case "GetDelivery" -> ok("delivery", deliveryNode(deliveries.getDelivery(text(request, "id"), region)));
            case "DescribeDeliveries" -> describeDeliveries(request, region);
            case "UpdateDeliveryConfiguration" -> updateDeliveryConfiguration(request, region);
            case "DeleteDelivery" -> {
                deliveries.deleteDelivery(text(request, "id"), region);
                yield empty();
            }
            default -> throw new IllegalArgumentException("Not a delivery action: " + action);
        };
    }

    private Response putDeliverySource(JsonNode request, String region) {
        DeliverySource source = new DeliverySource();
        source.setName(text(request, "name"));
        source.setLogType(text(request, "logType"));
        source.setDeliverySourceConfiguration(stringMap(request.path("deliverySourceConfiguration")));
        source.setTags(stringMap(request.path("tags")));
        return ok("deliverySource", sourceNode(deliveries.putDeliverySource(source, text(request, "resourceArn"), region)));
    }

    private Response describeDeliverySources(JsonNode request, String region) {
        PaginatedResult<DeliverySource> page = deliveries.describeDeliverySources(
                limit(request), text(request, "nextToken"), region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("deliverySources");
        page.items().forEach(s -> items.add(sourceNode(s)));
        return paged(response, page.nextToken());
    }

    private Response putDeliveryDestination(JsonNode request, String region) {
        DeliveryDestination destination = new DeliveryDestination();
        destination.setName(text(request, "name"));
        destination.setOutputFormat(text(request, "outputFormat"));
        destination.setDeliveryDestinationType(text(request, "deliveryDestinationType"));
        destination.setDestinationResourceArn(
                text(request.path("deliveryDestinationConfiguration"), "destinationResourceArn"));
        destination.setRoleArn(text(request, "roleArn"));
        destination.setTags(stringMap(request.path("tags")));
        return ok("deliveryDestination", destinationNode(deliveries.putDeliveryDestination(destination, region)));
    }

    private Response describeDeliveryDestinations(JsonNode request, String region) {
        PaginatedResult<DeliveryDestination> page = deliveries.describeDeliveryDestinations(
                limit(request), text(request, "nextToken"), region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("deliveryDestinations");
        page.items().forEach(d -> items.add(destinationNode(d)));
        return paged(response, page.nextToken());
    }

    private Response createDelivery(JsonNode request, String region) {
        Delivery delivery = new Delivery();
        delivery.setDeliverySourceName(text(request, "deliverySourceName"));
        delivery.setDeliveryDestinationArn(text(request, "deliveryDestinationArn"));
        delivery.setRecordFields(stringList(request.path("recordFields")));
        delivery.setFieldDelimiter(text(request, "fieldDelimiter"));
        JsonNode s3 = request.path("s3DeliveryConfiguration");
        delivery.setSuffixPath(text(s3, "suffixPath"));
        delivery.setEnableHiveCompatiblePath(s3.has("enableHiveCompatiblePath")
                ? s3.path("enableHiveCompatiblePath").asBoolean() : null);
        delivery.setTags(stringMap(request.path("tags")));
        return ok("delivery", deliveryNode(deliveries.createDelivery(delivery, region)));
    }

    private Response describeDeliveries(JsonNode request, String region) {
        PaginatedResult<Delivery> page = deliveries.describeDeliveries(
                limit(request), text(request, "nextToken"), region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode items = response.putArray("deliveries");
        page.items().forEach(d -> items.add(deliveryNode(d)));
        return paged(response, page.nextToken());
    }

    private Response updateDeliveryConfiguration(JsonNode request, String region) {
        JsonNode s3 = request.path("s3DeliveryConfiguration");
        deliveries.updateDeliveryConfiguration(text(request, "id"),
                request.has("recordFields") ? stringList(request.path("recordFields")) : null,
                text(request, "fieldDelimiter"), text(s3, "suffixPath"),
                s3.has("enableHiveCompatiblePath") ? s3.path("enableHiveCompatiblePath").asBoolean() : null,
                region);
        return empty();
    }

    private ObjectNode sourceNode(DeliverySource source) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", source.getName());
        node.put("arn", source.getArn());
        ArrayNode arns = node.putArray("resourceArns");
        source.getResourceArns().forEach(arns::add);
        node.put("service", source.getService());
        node.put("logType", source.getLogType());
        putMap(node, "tags", source.getTags());
        putMap(node, "deliverySourceConfiguration", source.getDeliverySourceConfiguration());
        node.put("status", "ACTIVE");
        return node;
    }

    private ObjectNode destinationNode(DeliveryDestination destination) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", destination.getName());
        node.put("arn", destination.getArn());
        node.put("deliveryDestinationType", destination.getDeliveryDestinationType());
        if (destination.getOutputFormat() != null) {
            node.put("outputFormat", destination.getOutputFormat());
        }
        if (destination.getDestinationResourceArn() != null) {
            node.putObject("deliveryDestinationConfiguration")
                    .put("destinationResourceArn", destination.getDestinationResourceArn());
        }
        if (destination.getRoleArn() != null) {
            node.put("roleArn", destination.getRoleArn());
        }
        putMap(node, "tags", destination.getTags());
        return node;
    }

    private ObjectNode deliveryNode(Delivery delivery) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", delivery.getId());
        node.put("arn", delivery.getArn());
        node.put("deliverySourceName", delivery.getDeliverySourceName());
        node.put("deliveryDestinationArn", delivery.getDeliveryDestinationArn());
        node.put("deliveryDestinationType", delivery.getDeliveryDestinationType());
        if (!delivery.getRecordFields().isEmpty()) {
            ArrayNode fields = node.putArray("recordFields");
            delivery.getRecordFields().forEach(fields::add);
        }
        if (delivery.getFieldDelimiter() != null) {
            node.put("fieldDelimiter", delivery.getFieldDelimiter());
        }
        if (delivery.getSuffixPath() != null || delivery.getEnableHiveCompatiblePath() != null) {
            ObjectNode s3 = node.putObject("s3DeliveryConfiguration");
            if (delivery.getSuffixPath() != null) {
                s3.put("suffixPath", delivery.getSuffixPath());
            }
            if (delivery.getEnableHiveCompatiblePath() != null) {
                s3.put("enableHiveCompatiblePath", delivery.getEnableHiveCompatiblePath());
            }
        }
        putMap(node, "tags", delivery.getTags());
        return node;
    }

    private Response policy(String policy) {
        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode node = response.putObject("policy");
        if (policy != null) {
            node.put("deliveryDestinationPolicy", policy);
        }
        return Response.ok(response).build();
    }

    private Response ok(String member, ObjectNode value) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set(member, value);
        return Response.ok(response).build();
    }

    private Response paged(ObjectNode response, String nextToken) {
        if (nextToken != null) {
            response.put("nextToken", nextToken);
        }
        return Response.ok(response).build();
    }

    private Response empty() {
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private static void putMap(ObjectNode node, String member, Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            ObjectNode map = node.putObject(member);
            values.forEach(map::put);
        }
    }

    private static Integer limit(JsonNode request) {
        return request.has("limit") ? request.path("limit").asInt() : null;
    }

    private static String text(JsonNode node, String member) {
        JsonNode value = node.path(member);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Map<String, String> stringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node.isObject()) {
            node.properties().forEach(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    private static List<String> stringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        node.forEach(v -> list.add(v.asText()));
        return list;
    }
}
