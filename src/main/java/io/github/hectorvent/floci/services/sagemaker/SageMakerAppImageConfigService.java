package io.github.hectorvent.floci.services.sagemaker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.AppImageConfigResource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;

/**
 * Stores SageMaker app image configurations, the settings a custom Studio image runs with. No
 * Studio app runs in Floci to use them.
 */
@ApplicationScoped
public class SageMakerAppImageConfigService {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9](-*[a-zA-Z0-9]){0,62}");
    private static final List<String> CONFIG_MEMBERS =
            List.of("KernelGatewayImageConfig", "JupyterLabAppImageConfig", "CodeEditorAppImageConfig");

    private final StorageBackend<String, AppImageConfigResource> store;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Inject
    public SageMakerAppImageConfigService(StorageFactory storageFactory, RegionResolver regionResolver,
                                          ObjectMapper mapper) {
        this(storageFactory.create("sagemaker", "sagemaker-app-image-configs.json",
                new TypeReference<Map<String, AppImageConfigResource>>() {}), regionResolver, mapper,
                Clock.systemUTC());
    }

    SageMakerAppImageConfigService(StorageBackend<String, AppImageConfigResource> store,
                                   RegionResolver regionResolver, ObjectMapper mapper, Clock clock) {
        this.store = store;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
        this.clock = clock;
    }

    public static boolean owns(String arn) {
        return arn != null && arn.contains(":app-image-config/");
    }

    public synchronized ObjectNode create(JsonNode request, String region) {
        String name = SageMakerService.required(request, "AppImageConfigName");
        if (!NAME_PATTERN.matcher(name).matches()) {
            throw SageMakerService.validation("AppImageConfigName " + name + " is not valid.");
        }
        if (store.get(key(region, name)).isPresent()) {
            throw new AwsException("ResourceInUse", "AppImageConfig " + name + " already exists.", 400);
        }
        validateKernelSpecs(request);
        AppImageConfigResource config = new AppImageConfigResource();
        config.appImageConfigName = name;
        config.appImageConfigArn = AwsArnUtils.Arn.of("sagemaker", region, regionResolver.getAccountId(),
                "app-image-config/" + name).toString();
        config.creationTime = clock.millis();
        config.lastModifiedTime = config.creationTime;
        config.region = region;
        for (String member : CONFIG_MEMBERS) {
            if (request.path(member).isObject()) {
                config.configs.put(member, SageMakerService.map(request.path(member)));
            }
        }
        config.tags = SageMakerService.tagsFromList(request.path("Tags"));
        store.put(key(region, name), config);
        ObjectNode out = mapper.createObjectNode();
        out.put("AppImageConfigArn", config.appImageConfigArn);
        return out;
    }

    public ObjectNode describe(JsonNode request, String region) {
        AppImageConfigResource config = require(SageMakerService.required(request, "AppImageConfigName"), region);
        ObjectNode out = mapper.createObjectNode();
        render(config, out);
        return out;
    }

    /** Replaces the image configuration members; one the request leaves out is removed. */
    public synchronized ObjectNode update(JsonNode request, String region) {
        AppImageConfigResource config = require(SageMakerService.required(request, "AppImageConfigName"), region);
        validateKernelSpecs(request);
        config.configs.clear();
        for (String member : CONFIG_MEMBERS) {
            if (request.path(member).isObject()) {
                config.configs.put(member, SageMakerService.map(request.path(member)));
            }
        }
        config.lastModifiedTime = clock.millis();
        store.put(key(region, config.appImageConfigName), config);
        ObjectNode out = mapper.createObjectNode();
        out.put("AppImageConfigArn", config.appImageConfigArn);
        return out;
    }

    public synchronized ObjectNode delete(JsonNode request, String region) {
        AppImageConfigResource config = require(SageMakerService.required(request, "AppImageConfigName"), region);
        store.delete(key(region, config.appImageConfigName));
        return mapper.createObjectNode();
    }

    public ObjectNode list(JsonNode request, String region) {
        String nameContains = SageMakerService.text(request, "NameContains");
        Predicate<AppImageConfigResource> filter = c -> nameContains == null || c.appImageConfigName.contains(nameContains);
        filter = filter.and(time(request, "CreationTimeBefore", c -> c.creationTime, true))
                .and(time(request, "CreationTimeAfter", c -> c.creationTime, false))
                .and(time(request, "ModifiedTimeBefore", c -> c.lastModifiedTime, true))
                .and(time(request, "ModifiedTimeAfter", c -> c.lastModifiedTime, false));
        String sortBy = SageMakerService.text(request, "SortBy");
        Comparator<AppImageConfigResource> order = switch (sortBy == null ? "CreationTime" : sortBy) {
            case "Name" -> Comparator.comparing(c -> c.appImageConfigName);
            case "LastModifiedTime" -> Comparator.<AppImageConfigResource>comparingLong(c -> c.lastModifiedTime)
                    .thenComparing(c -> c.appImageConfigName);
            case "CreationTime" -> Comparator.<AppImageConfigResource>comparingLong(c -> c.creationTime)
                    .thenComparing(c -> c.appImageConfigName);
            default -> throw SageMakerService.validation("SortBy must be CreationTime, LastModifiedTime or Name.");
        };
        if ("Descending".equals(SageMakerService.text(request, "SortOrder"))) {
            order = order.reversed();
        }
        List<AppImageConfigResource> sorted = store.scan(k -> k.startsWith(region + "::")).stream()
                .filter(filter).sorted(order).toList();
        int max = request.hasNonNull("MaxResults") ? request.path("MaxResults").asInt() : SageMakerService.DEFAULT_LIMIT;
        if (max < 1 || max > SageMakerService.MAX_RESULTS_CEILING) {
            throw SageMakerService.validation("MaxResults must be between 1 and "
                    + SageMakerService.MAX_RESULTS_CEILING + ".");
        }
        int start = 0;
        String nextToken = SageMakerService.text(request, "NextToken");
        if (nextToken != null && !nextToken.isBlank()) {
            while (start < sorted.size() && !sorted.get(start).appImageConfigName.equals(nextToken)) {
                start++;
            }
        }
        int end = Math.min(sorted.size(), start + max);
        ObjectNode out = mapper.createObjectNode();
        ArrayNode items = out.putArray("AppImageConfigs");
        sorted.subList(start, end).forEach(c -> render(c, items.addObject()));
        if (end < sorted.size()) {
            out.put("NextToken", sorted.get(end).appImageConfigName);
        }
        return out;
    }

    public synchronized ObjectNode addTags(JsonNode request) {
        AppImageConfigResource config = byArn(SageMakerService.required(request, "ResourceArn"));
        config.tags.putAll(SageMakerService.tagsFromList(request.path("Tags")));
        store.put(key(config.region, config.appImageConfigName), config);
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagsArray(config.tags));
        return out;
    }

    public ObjectNode listTags(JsonNode request) {
        AppImageConfigResource config = byArn(SageMakerService.required(request, "ResourceArn"));
        ObjectNode out = mapper.createObjectNode();
        out.set("Tags", tagsArray(config.tags));
        return out;
    }

    public synchronized ObjectNode deleteTags(JsonNode request) {
        AppImageConfigResource config = byArn(SageMakerService.required(request, "ResourceArn"));
        request.path("TagKeys").forEach(k -> config.tags.remove(k.asText()));
        store.put(key(config.region, config.appImageConfigName), config);
        return mapper.createObjectNode();
    }

    private ArrayNode tagsArray(Map<String, String> tags) {
        ArrayNode array = mapper.createArrayNode();
        tags.forEach((k, v) -> array.addObject().put("Key", k).put("Value", v));
        return array;
    }

    private AppImageConfigResource byArn(String arn) {
        return store.scan(k -> true).stream().filter(c -> arn.equals(c.appImageConfigArn)).findFirst()
                .orElseThrow(() -> SageMakerService.validation("Could not find AppImageConfig for " + arn + "."));
    }

    private void render(AppImageConfigResource config, ObjectNode out) {
        out.put("AppImageConfigArn", config.appImageConfigArn);
        out.put("AppImageConfigName", config.appImageConfigName);
        out.put("CreationTime", SageMakerService.epoch(config.creationTime));
        out.put("LastModifiedTime", SageMakerService.epoch(config.lastModifiedTime));
        config.configs.forEach((member, value) -> out.set(member, mapper.valueToTree(value)));
    }

    private AppImageConfigResource require(String name, String region) {
        return store.get(key(region, name)).orElseThrow(() -> new AwsException("ResourceNotFound",
                "AppImageConfig " + name + " does not exist.", 400));
    }

    private static void validateKernelSpecs(JsonNode request) {
        JsonNode kernel = request.path("KernelGatewayImageConfig");
        if (kernel.isObject()) {
            JsonNode specs = kernel.path("KernelSpecs");
            if (!specs.isArray() || specs.isEmpty() || specs.size() > 5) {
                throw SageMakerService.validation("KernelGatewayImageConfig.KernelSpecs must hold 1 to 5 kernels.");
            }
            specs.forEach(spec -> SageMakerService.required(spec, "Name"));
        }
    }

    private static Predicate<AppImageConfigResource> time(JsonNode request, String member,
                                                          ToLongFunction<AppImageConfigResource> field,
                                                          boolean before) {
        JsonNode value = request.path(member);
        if (value.isMissingNode() || value.isNull()) {
            return c -> true;
        }
        long millis = value.isNumber() ? (long) (value.asDouble() * 1000) : Instant.parse(value.asText()).toEpochMilli();
        return before ? c -> field.applyAsLong(c) < millis : c -> field.applyAsLong(c) > millis;
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }
}
