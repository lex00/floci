package io.github.hectorvent.floci.services.cloudwatch.logs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.Delivery;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.DeliveryDestination;
import io.github.hectorvent.floci.services.cloudwatch.logs.model.DeliverySource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * CloudWatch Logs vended log deliveries: delivery sources, delivery destinations and the
 * deliveries that pair them. Floci stores the configuration and sends no log records.
 */
@ApplicationScoped
public class CloudWatchLogsDeliveryService {

    private static final int MAX_PAGE = 50;
    private static final Set<String> OUTPUT_FORMATS = Set.of("json", "plain", "w3c", "raw", "parquet");

    private final StorageBackend<String, DeliverySource> sourceStore;
    private final StorageBackend<String, DeliveryDestination> destinationStore;
    private final StorageBackend<String, Delivery> deliveryStore;
    private final RegionResolver regionResolver;

    @Inject
    public CloudWatchLogsDeliveryService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("cloudwatchlogs", "cwlogs-delivery-sources.json",
                        new TypeReference<Map<String, DeliverySource>>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-delivery-destinations.json",
                        new TypeReference<Map<String, DeliveryDestination>>() {}),
                storageFactory.create("cloudwatchlogs", "cwlogs-deliveries.json",
                        new TypeReference<Map<String, Delivery>>() {}),
                regionResolver);
    }

    public CloudWatchLogsDeliveryService(StorageBackend<String, DeliverySource> sourceStore,
                                         StorageBackend<String, DeliveryDestination> destinationStore,
                                         StorageBackend<String, Delivery> deliveryStore,
                                         RegionResolver regionResolver) {
        this.sourceStore = sourceStore;
        this.destinationStore = destinationStore;
        this.deliveryStore = deliveryStore;
        this.regionResolver = regionResolver;
    }

    // ── Delivery sources ──────────────────────────────────────────────────────

    /**
     * Creates the source, or updates it when the name is taken. An update must keep the source's
     * resource and log type, and keeps the tags it already has.
     */
    public DeliverySource putDeliverySource(DeliverySource source, String resourceArn, String region) {
        requireParameter(source.getName(), "name");
        requireParameter(resourceArn, "resourceArn");
        requireParameter(source.getLogType(), "logType");
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(resourceArn);
        } catch (IllegalArgumentException e) {
            throw validation("resourceArn " + resourceArn + " is not a valid ARN.");
        }
        Optional<DeliverySource> existing = sourceStore.get(key(region, source.getName()));
        if (existing.isPresent()) {
            DeliverySource previous = existing.get();
            if (!previous.getResourceArns().contains(resourceArn) || !previous.getLogType().equals(source.getLogType())) {
                throw new AwsException("ConflictException",
                        "Delivery source " + source.getName() + " already exists for another resource or log type.",
                        409);
            }
            source.setTags(previous.getTags());
        }
        source.setArn(regionResolver.buildArn("logs", region, "delivery-source:" + source.getName()));
        source.setResourceArns(List.of(resourceArn));
        source.setService(parsed.service());
        sourceStore.put(key(region, source.getName()), source);
        return source;
    }

    public DeliverySource getDeliverySource(String name, String region) {
        requireParameter(name, "name");
        return sourceStore.get(key(region, name)).orElseThrow(() -> notFound("delivery source", name));
    }

    public PaginatedResult<DeliverySource> describeDeliverySources(Integer limit, String nextToken, String region) {
        return page(sourceStore.scan(k -> k.startsWith(region + "::")), DeliverySource::getName,
                limit, nextToken);
    }

    public void deleteDeliverySource(String name, String region) {
        getDeliverySource(name, region);
        boolean inUse = deliveries(region).stream().anyMatch(d -> name.equals(d.getDeliverySourceName()));
        if (inUse) {
            throw new AwsException("ConflictException",
                    "Delivery source " + name + " is used by a delivery. Delete the delivery first.", 409);
        }
        sourceStore.delete(key(region, name));
    }

    // ── Delivery destinations ─────────────────────────────────────────────────

    /** Creates the destination, or updates it when the name is taken, keeping its tags and policy. */
    public DeliveryDestination putDeliveryDestination(DeliveryDestination destination, String region) {
        requireParameter(destination.getName(), "name");
        if (destination.getOutputFormat() != null && !OUTPUT_FORMATS.contains(destination.getOutputFormat())) {
            throw validation("outputFormat must be one of json, plain, w3c, raw or parquet.");
        }
        String type = destinationType(destination);
        destination.setDeliveryDestinationType(type);
        if (!"XRAY".equals(type) && destination.getDestinationResourceArn() == null) {
            throw validation("deliveryDestinationConfiguration.destinationResourceArn is required.");
        }
        Optional<DeliveryDestination> existing = destinationStore.get(key(region, destination.getName()));
        existing.ifPresent(previous -> {
            destination.setTags(previous.getTags());
            destination.setPolicy(previous.getPolicy());
        });
        destination.setArn(regionResolver.buildArn("logs", region, "delivery-destination:" + destination.getName()));
        destinationStore.put(key(region, destination.getName()), destination);
        return destination;
    }

    public DeliveryDestination getDeliveryDestination(String name, String region) {
        requireParameter(name, "name");
        return destinationStore.get(key(region, name)).orElseThrow(() -> notFound("delivery destination", name));
    }

    public PaginatedResult<DeliveryDestination> describeDeliveryDestinations(Integer limit, String nextToken,
                                                                             String region) {
        return page(destinationStore.scan(k -> k.startsWith(region + "::")), DeliveryDestination::getName,
                limit, nextToken);
    }

    public void deleteDeliveryDestination(String name, String region) {
        DeliveryDestination destination = getDeliveryDestination(name, region);
        boolean inUse = deliveries(region).stream()
                .anyMatch(d -> destination.getArn().equals(d.getDeliveryDestinationArn()));
        if (inUse) {
            throw new AwsException("ConflictException",
                    "Delivery destination " + name + " is used by a delivery. Delete the delivery first.", 409);
        }
        destinationStore.delete(key(region, name));
    }

    public String putDeliveryDestinationPolicy(String name, String policy, String region) {
        requireParameter(policy, "deliveryDestinationPolicy");
        DeliveryDestination destination = getDeliveryDestination(name, region);
        destination.setPolicy(policy);
        destinationStore.put(key(region, name), destination);
        return policy;
    }

    public String getDeliveryDestinationPolicy(String name, String region) {
        return getDeliveryDestination(name, region).getPolicy();
    }

    public void deleteDeliveryDestinationPolicy(String name, String region) {
        DeliveryDestination destination = getDeliveryDestination(name, region);
        if (destination.getPolicy() == null) {
            throw notFound("delivery destination policy", name);
        }
        destination.setPolicy(null);
        destinationStore.put(key(region, name), destination);
    }

    // ── Deliveries ────────────────────────────────────────────────────────────

    /** Pairs an existing source with an existing destination; each pair is delivered at most once. */
    public Delivery createDelivery(Delivery delivery, String region) {
        requireParameter(delivery.getDeliverySourceName(), "deliverySourceName");
        requireParameter(delivery.getDeliveryDestinationArn(), "deliveryDestinationArn");
        getDeliverySource(delivery.getDeliverySourceName(), region);
        DeliveryDestination destination = destinationStore.scan(k -> k.startsWith(region + "::")).stream()
                .filter(d -> delivery.getDeliveryDestinationArn().equals(d.getArn()))
                .findFirst()
                .orElseThrow(() -> notFound("delivery destination", delivery.getDeliveryDestinationArn()));
        boolean duplicate = deliveries(region).stream()
                .anyMatch(d -> d.getDeliverySourceName().equals(delivery.getDeliverySourceName())
                        && d.getDeliveryDestinationArn().equals(delivery.getDeliveryDestinationArn()));
        if (duplicate) {
            throw new AwsException("ConflictException",
                    "A delivery from " + delivery.getDeliverySourceName() + " to "
                            + delivery.getDeliveryDestinationArn() + " already exists.", 409);
        }
        String id = newId();
        delivery.setId(id);
        delivery.setArn(regionResolver.buildArn("logs", region, "delivery:" + id));
        delivery.setDeliveryDestinationType(destination.getDeliveryDestinationType());
        deliveryStore.put(key(region, id), delivery);
        return delivery;
    }

    public Delivery getDelivery(String id, String region) {
        requireParameter(id, "id");
        return deliveryStore.get(key(region, id)).orElseThrow(() -> notFound("delivery", id));
    }

    public PaginatedResult<Delivery> describeDeliveries(Integer limit, String nextToken, String region) {
        return page(deliveries(region), Delivery::getId, limit, nextToken);
    }

    public void updateDeliveryConfiguration(String id, List<String> recordFields, String fieldDelimiter,
                                            String suffixPath, Boolean enableHiveCompatiblePath, String region) {
        Delivery delivery = getDelivery(id, region);
        if (recordFields != null) {
            delivery.setRecordFields(recordFields);
        }
        if (fieldDelimiter != null) {
            delivery.setFieldDelimiter(fieldDelimiter);
        }
        if (suffixPath != null) {
            delivery.setSuffixPath(suffixPath);
        }
        if (enableHiveCompatiblePath != null) {
            delivery.setEnableHiveCompatiblePath(enableHiveCompatiblePath);
        }
        deliveryStore.put(key(region, id), delivery);
    }

    public void deleteDelivery(String id, String region) {
        getDelivery(id, region);
        deliveryStore.delete(key(region, id));
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    /** Whether an ARN names a delivery source, destination or delivery. */
    public static boolean isDeliveryArn(String resourceArn) {
        return resourceArn != null && (resourceArn.contains(":delivery-source:")
                || resourceArn.contains(":delivery-destination:") || resourceArn.contains(":delivery:"));
    }

    public Map<String, String> listTags(String resourceArn, String region) {
        return switch (resolve(resourceArn, region)) {
            case DeliverySource source -> source.getTags();
            case DeliveryDestination destination -> destination.getTags();
            case Delivery delivery -> delivery.getTags();
            default -> throw notFound("resource", resourceArn);
        };
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        updateTags(resourceArn, region, current -> current.putAll(tags));
    }

    public void untagResource(String resourceArn, List<String> tagKeys, String region) {
        updateTags(resourceArn, region, current -> tagKeys.forEach(current::remove));
    }

    private void updateTags(String resourceArn, String region, Consumer<Map<String, String>> change) {
        switch (resolve(resourceArn, region)) {
            case DeliverySource source -> {
                change.accept(source.getTags());
                sourceStore.put(key(region, source.getName()), source);
            }
            case DeliveryDestination destination -> {
                change.accept(destination.getTags());
                destinationStore.put(key(region, destination.getName()), destination);
            }
            case Delivery delivery -> {
                change.accept(delivery.getTags());
                deliveryStore.put(key(region, delivery.getId()), delivery);
            }
            default -> throw notFound("resource", resourceArn);
        }
    }

    private Object resolve(String resourceArn, String region) {
        String prefix = region + "::";
        if (resourceArn.contains(":delivery-source:")) {
            return sourceStore.scan(k -> k.startsWith(prefix)).stream()
                    .filter(s -> resourceArn.equals(s.getArn())).findFirst()
                    .orElseThrow(() -> notFound("delivery source", resourceArn));
        }
        if (resourceArn.contains(":delivery-destination:")) {
            return destinationStore.scan(k -> k.startsWith(prefix)).stream()
                    .filter(d -> resourceArn.equals(d.getArn())).findFirst()
                    .orElseThrow(() -> notFound("delivery destination", resourceArn));
        }
        return deliveryStore.scan(k -> k.startsWith(prefix)).stream()
                .filter(d -> resourceArn.equals(d.getArn())).findFirst()
                .orElseThrow(() -> notFound("delivery", resourceArn));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Delivery> deliveries(String region) {
        return deliveryStore.scan(k -> k.startsWith(region + "::"));
    }

    /** The destination type follows the service of the destination resource; X-Ray has no resource. */
    private static String destinationType(DeliveryDestination destination) {
        String arn = destination.getDestinationResourceArn();
        String derived = null;
        if (arn != null) {
            try {
                derived = switch (AwsArnUtils.parse(arn).service()) {
                    case "logs" -> "CWL";
                    case "s3" -> "S3";
                    case "firehose" -> "FH";
                    default -> null;
                };
            } catch (IllegalArgumentException e) {
                throw validation("destinationResourceArn " + arn + " is not a valid ARN.");
            }
            if (derived == null) {
                throw validation("destinationResourceArn must name a log group, an S3 bucket or a Firehose stream.");
            }
        }
        String requested = destination.getDeliveryDestinationType();
        if (requested != null && derived != null && !requested.equals(derived)) {
            throw validation("deliveryDestinationType " + requested + " does not match destinationResourceArn.");
        }
        if (requested == null && derived == null) {
            throw validation("deliveryDestinationConfiguration is required.");
        }
        return requested != null ? requested : derived;
    }

    private static <T> PaginatedResult<T> page(List<T> items, Function<T, String> cursor,
                                               Integer limit, String nextToken) {
        List<T> sorted = items.stream().sorted(Comparator.comparing(cursor)).toList();
        return Pagination.paginate(sorted, cursor, limit, nextToken, MAX_PAGE, "ValidationException");
    }

    private static String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static void requireParameter(String value, String name) {
        if (value == null || value.isBlank()) {
            throw validation("1 validation error detected: Value null at '" + name
                    + "' failed to satisfy constraint: Member must not be null");
        }
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String kind, String name) {
        return new AwsException("ResourceNotFoundException", "The " + kind + " " + name + " does not exist.", 400);
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }
}
