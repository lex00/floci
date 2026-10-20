package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.iot.model.IotBillingGroup;
import io.github.hectorvent.floci.services.iot.model.IotRoleAlias;
import io.github.hectorvent.floci.services.iot.model.IotTopicRuleDestination;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * AWS IoT billing groups, role aliases and topic rule destinations: registry records with no
 * behaviour behind them in the emulator. A role alias names a role the credentials provider would
 * assume, and a destination is ENABLED once created, except an HTTP one, which waits in
 * IN_PROGRESS for the confirmation AWS sends to its URL.
 */
@ApplicationScoped
public class IotBillingGroupService {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9:_-]{1,128}");
    private static final Pattern ROLE_ALIAS_PATTERN = Pattern.compile("[\\w=,@-]{1,128}");
    private static final int DEFAULT_CREDENTIAL_DURATION = 3600;
    private static final int MIN_CREDENTIAL_DURATION = 900;
    private static final int MAX_CREDENTIAL_DURATION = 43200;
    private static final List<String> DESTINATION_STATUSES = List.of("ENABLED", "DISABLED");

    private final StorageBackend<String, IotBillingGroup> billingGroups;
    private final StorageBackend<String, IotRoleAlias> roleAliases;
    private final StorageBackend<String, IotTopicRuleDestination> destinations;
    private final RegionResolver regionResolver;
    private final Object lock = new Object();

    @Inject
    public IotBillingGroupService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("iot", "iot-billing-groups.json",
                        new TypeReference<Map<String, IotBillingGroup>>() {}),
                storageFactory.create("iot", "iot-role-aliases.json",
                        new TypeReference<Map<String, IotRoleAlias>>() {}),
                storageFactory.create("iot", "iot-topic-rule-destinations.json",
                        new TypeReference<Map<String, IotTopicRuleDestination>>() {}),
                regionResolver);
    }

    IotBillingGroupService(StorageBackend<String, IotBillingGroup> billingGroups,
                           StorageBackend<String, IotRoleAlias> roleAliases,
                           StorageBackend<String, IotTopicRuleDestination> destinations,
                           RegionResolver regionResolver) {
        this.billingGroups = billingGroups;
        this.roleAliases = roleAliases;
        this.destinations = destinations;
        this.regionResolver = regionResolver;
    }

    // ── Billing groups ────────────────────────────────────────────────────────

    public IotBillingGroup createBillingGroup(String name, JsonNode body, String region) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw invalid("Invalid billing group name: " + name);
        }
        IotBillingGroup group = new IotBillingGroup();
        group.setBillingGroupName(name);
        group.setBillingGroupId(UUID.randomUUID().toString());
        group.setBillingGroupArn(regionResolver.buildArn("iot", region, "billinggroup/" + name));
        group.setDescription(text(body.path("billingGroupProperties"), "billingGroupDescription"));
        group.setVersion(1);
        group.setCreationDate(Instant.now());
        group.setTags(IotDomainConfigurationService.parseTags(body.path("tags")));
        synchronized (lock) {
            Optional<IotBillingGroup> existing = billingGroups.get(key(region, name));
            if (existing.isPresent()) {
                if (sameDefinition(existing.get(), group)) {
                    return existing.get();
                }
                throw new AwsException("ResourceAlreadyExistsException",
                        "Billing group " + name + " already exists with different properties.", 409);
            }
            billingGroups.put(key(region, name), group);
        }
        return group;
    }

    public IotBillingGroup describeBillingGroup(String name, String region) {
        return billingGroups.get(key(region, name)).orElseThrow(() ->
                notFound("Billing group " + name + " does not exist."));
    }

    public long updateBillingGroup(String name, JsonNode body, String region) {
        synchronized (lock) {
            IotBillingGroup group = describeBillingGroup(name, region);
            requireVersion(group.getVersion(), body.path("expectedVersion"));
            if (!body.has("billingGroupProperties")) {
                throw invalid("billingGroupProperties is required");
            }
            group.setDescription(text(body.path("billingGroupProperties"), "billingGroupDescription"));
            group.setVersion(group.getVersion() + 1);
            billingGroups.put(key(region, name), group);
            return group.getVersion();
        }
    }

    /** Deleting a billing group that does not exist succeeds, as on AWS. */
    public void deleteBillingGroup(String name, Long expectedVersion, String region) {
        synchronized (lock) {
            Optional<IotBillingGroup> group = billingGroups.get(key(region, name));
            if (group.isEmpty()) {
                return;
            }
            if (expectedVersion != null && expectedVersion != group.get().getVersion()) {
                throw versionConflict(group.get().getVersion());
            }
            billingGroups.delete(key(region, name));
        }
    }

    public IotService.Page<IotBillingGroup> listBillingGroups(String namePrefix, String nextToken, Integer maxResults,
                                                              String region) {
        List<IotBillingGroup> all = billingGroups.scan(k -> k.startsWith(region + ":")).stream()
                .filter(g -> namePrefix == null || g.getBillingGroupName().startsWith(namePrefix))
                .sorted(Comparator.comparing(IotBillingGroup::getBillingGroupName))
                .toList();
        return page(all, IotBillingGroup::getBillingGroupName, nextToken, maxResults, 250);
    }

    // ── Role aliases ──────────────────────────────────────────────────────────

    public IotRoleAlias createRoleAlias(String alias, JsonNode body, String region) {
        if (alias == null || !ROLE_ALIAS_PATTERN.matcher(alias).matches()) {
            throw invalid("Invalid role alias: " + alias);
        }
        String roleArn = text(body, "roleArn");
        if (roleArn == null || roleArn.isBlank()) {
            throw invalid("roleArn is required");
        }
        Instant now = Instant.now();
        IotRoleAlias roleAlias = new IotRoleAlias();
        roleAlias.setRoleAlias(alias);
        roleAlias.setRoleAliasArn(regionResolver.buildArn("iot", region, "rolealias/" + alias));
        roleAlias.setRoleArn(roleArn);
        roleAlias.setOwner(regionResolver.getAccountId());
        roleAlias.setCredentialDurationSeconds(credentialDuration(body, DEFAULT_CREDENTIAL_DURATION));
        roleAlias.setCreationDate(now);
        roleAlias.setLastModifiedDate(now);
        roleAlias.setTags(IotDomainConfigurationService.parseTags(body.path("tags")));
        synchronized (lock) {
            if (roleAliases.get(key(region, alias)).isPresent()) {
                throw new AwsException("ResourceAlreadyExistsException", "Role alias " + alias + " already exists.", 409);
            }
            roleAliases.put(key(region, alias), roleAlias);
        }
        return roleAlias;
    }

    public IotRoleAlias describeRoleAlias(String alias, String region) {
        return roleAliases.get(key(region, alias)).orElseThrow(() ->
                notFound("Role alias " + alias + " does not exist."));
    }

    public IotRoleAlias updateRoleAlias(String alias, JsonNode body, String region) {
        synchronized (lock) {
            IotRoleAlias roleAlias = describeRoleAlias(alias, region);
            String roleArn = text(body, "roleArn");
            if (roleArn != null) {
                roleAlias.setRoleArn(roleArn);
            }
            roleAlias.setCredentialDurationSeconds(credentialDuration(body, roleAlias.getCredentialDurationSeconds()));
            roleAlias.setLastModifiedDate(Instant.now());
            roleAliases.put(key(region, alias), roleAlias);
            return roleAlias;
        }
    }

    public void deleteRoleAlias(String alias, String region) {
        synchronized (lock) {
            describeRoleAlias(alias, region);
            roleAliases.delete(key(region, alias));
        }
    }

    public IotService.Page<IotRoleAlias> listRoleAliases(String marker, Integer pageSize, boolean ascending,
                                                         String region) {
        Comparator<IotRoleAlias> order = Comparator.comparing(IotRoleAlias::getRoleAlias);
        List<IotRoleAlias> all = roleAliases.scan(k -> k.startsWith(region + ":")).stream()
                .sorted(ascending ? order : order.reversed())
                .toList();
        return page(all, IotRoleAlias::getRoleAlias, marker, pageSize, 250);
    }

    // ── Topic rule destinations ───────────────────────────────────────────────

    public IotTopicRuleDestination createTopicRuleDestination(JsonNode body, String region) {
        JsonNode configuration = body.path("destinationConfiguration");
        if (!configuration.isObject()) {
            throw invalid("destinationConfiguration is required");
        }
        IotTopicRuleDestination destination = new IotTopicRuleDestination();
        String kind;
        JsonNode http = configuration.path("httpUrlConfiguration");
        JsonNode vpc = configuration.path("vpcConfiguration");
        JsonNode influx = configuration.path("influxDBConfiguration");
        int configured = (http.isObject() ? 1 : 0) + (vpc.isObject() ? 1 : 0) + (influx.isObject() ? 1 : 0);
        if (configured != 1) {
            throw invalid("destinationConfiguration must set exactly one of httpUrlConfiguration, "
                    + "vpcConfiguration or influxDBConfiguration");
        }
        if (http.isObject()) {
            kind = "http";
            destination.setConfirmationUrl(required(http, "confirmationUrl"));
            destination.setStatus("IN_PROGRESS");
            destination.setStatusReason("Awaiting confirmation of the destination URL.");
        } else if (vpc.isObject()) {
            kind = "vpc";
            destination.setVpcId(required(vpc, "vpcId"));
            destination.setRoleArn(required(vpc, "roleArn"));
            destination.setSubnetIds(textList(vpc.path("subnetIds")));
            if (destination.getSubnetIds().isEmpty()) {
                throw invalid("vpcConfiguration.subnetIds is required");
            }
            destination.setSecurityGroups(textList(vpc.path("securityGroups")));
            destination.setStatus("ENABLED");
        } else {
            kind = "influxdb";
            Map<String, String> properties = new TreeMap<>();
            for (String field : List.of("endpoint", "influxDBVersion", "secretId")) {
                properties.put(field, required(influx, field));
            }
            for (String field : List.of("secretType", "secretKey")) {
                if (text(influx, field) != null) {
                    properties.put(field, text(influx, field));
                }
            }
            destination.setInfluxDb(properties);
            destination.setStatus("ENABLED");
        }
        Instant now = Instant.now();
        destination.setArn(regionResolver.buildArn("iot", region,
                "ruledestination/" + kind + "/" + UUID.randomUUID()));
        destination.setCreatedAt(now);
        destination.setLastUpdatedAt(now);
        synchronized (lock) {
            boolean duplicate = destination.getConfirmationUrl() != null
                    && destinations.scan(k -> k.startsWith(region + ":")).stream()
                    .anyMatch(d -> destination.getConfirmationUrl().equals(d.getConfirmationUrl()));
            if (duplicate) {
                throw new AwsException("ResourceAlreadyExistsException",
                        "A destination for " + destination.getConfirmationUrl() + " already exists.", 409);
            }
            destinations.put(key(region, destination.getArn()), destination);
        }
        return destination;
    }

    public IotTopicRuleDestination getTopicRuleDestination(String arn, String region) {
        return destinations.get(key(region, arn)).orElseThrow(() ->
                invalid("Topic rule destination " + arn + " does not exist."));
    }

    /** Moves a destination between ENABLED and DISABLED; enabling also confirms an HTTP destination. */
    public void updateTopicRuleDestination(String arn, String status, String region) {
        if (!DESTINATION_STATUSES.contains(status)) {
            throw invalid("status must be ENABLED or DISABLED");
        }
        synchronized (lock) {
            IotTopicRuleDestination destination = getTopicRuleDestination(arn, region);
            destination.setStatus(status);
            destination.setStatusReason(null);
            destination.setLastUpdatedAt(Instant.now());
            destinations.put(key(region, arn), destination);
        }
    }

    public void deleteTopicRuleDestination(String arn, String region) {
        synchronized (lock) {
            getTopicRuleDestination(arn, region);
            destinations.delete(key(region, arn));
        }
    }

    public IotService.Page<IotTopicRuleDestination> listTopicRuleDestinations(String nextToken, Integer maxResults,
                                                                              String region) {
        List<IotTopicRuleDestination> all = destinations.scan(k -> k.startsWith(region + ":")).stream()
                .sorted(Comparator.comparing(IotTopicRuleDestination::getArn))
                .toList();
        return page(all, IotTopicRuleDestination::getArn, nextToken, maxResults, 1000);
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    /** Whether this service owns the resource an ARN names. */
    public static boolean owns(String arn) {
        return arn != null && (arn.contains(":billinggroup/") || arn.contains(":rolealias/"));
    }

    public Map<String, String> listTagsForResource(String arn) {
        return new TreeMap<>(tagsOf(arn));
    }

    public void tagResource(String arn, Map<String, String> tags) {
        synchronized (lock) {
            Map<String, String> updated = new TreeMap<>(tagsOf(arn));
            updated.putAll(tags);
            saveTags(arn, updated);
        }
    }

    public void untagResource(String arn, List<String> tagKeys) {
        synchronized (lock) {
            Map<String, String> updated = new TreeMap<>(tagsOf(arn));
            tagKeys.forEach(updated::remove);
            saveTags(arn, updated);
        }
    }

    private Map<String, String> tagsOf(String arn) {
        if (arn.contains(":billinggroup/")) {
            return billingGroupByArn(arn).getTags();
        }
        return roleAliasByArn(arn).getTags();
    }

    private void saveTags(String arn, Map<String, String> tags) {
        String region = regionOf(arn);
        if (arn.contains(":billinggroup/")) {
            IotBillingGroup group = billingGroupByArn(arn);
            group.setTags(tags);
            billingGroups.put(key(region, group.getBillingGroupName()), group);
        } else {
            IotRoleAlias alias = roleAliasByArn(arn);
            alias.setTags(tags);
            roleAliases.put(key(region, alias.getRoleAlias()), alias);
        }
    }

    private IotBillingGroup billingGroupByArn(String arn) {
        return billingGroups.scan(k -> true).stream().filter(g -> arn.equals(g.getBillingGroupArn())).findFirst()
                .orElseThrow(() -> notFound("No resource matches " + arn));
    }

    private IotRoleAlias roleAliasByArn(String arn) {
        return roleAliases.scan(k -> true).stream().filter(a -> arn.equals(a.getRoleAliasArn())).findFirst()
                .orElseThrow(() -> notFound("No resource matches " + arn));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String regionOf(String arn) {
        String[] parts = arn.split(":", 6);
        return parts.length > 3 ? parts[3] : "";
    }

    private static boolean sameDefinition(IotBillingGroup existing, IotBillingGroup requested) {
        return Objects.equals(existing.getDescription(), requested.getDescription());
    }

    private static void requireVersion(long current, JsonNode expected) {
        if (expected != null && expected.isNumber() && expected.asLong() != current) {
            throw versionConflict(current);
        }
    }

    private static AwsException versionConflict(long current) {
        return new AwsException("VersionConflictException",
                "The version for this resource is " + current + ".", 409);
    }

    private static int credentialDuration(JsonNode body, int fallback) {
        if (!body.hasNonNull("credentialDurationSeconds")) {
            return fallback;
        }
        int seconds = body.path("credentialDurationSeconds").asInt();
        if (seconds < MIN_CREDENTIAL_DURATION || seconds > MAX_CREDENTIAL_DURATION) {
            throw invalid("credentialDurationSeconds must be between 900 and 43200");
        }
        return seconds;
    }

    private static <T> IotService.Page<T> page(List<T> all, Function<T, String> cursor, String token,
                                               Integer size, int max) {
        int pageSize = size == null ? max : size;
        if (pageSize < 1 || pageSize > max) {
            throw invalid("Page size must be between 1 and " + max);
        }
        int start = 0;
        if (token != null && !token.isEmpty()) {
            while (start < all.size() && !cursor.apply(all.get(start)).equals(token)) {
                start++;
            }
        }
        int end = Math.min(all.size(), start + pageSize);
        return new IotService.Page<>(new ArrayList<>(all.subList(start, end)),
                end < all.size() ? cursor.apply(all.get(end)) : null);
    }

    private static String text(JsonNode node, String field) {
        return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        return value;
    }

    private static List<String> textList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(v -> values.add(v.asText()));
        return values;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidRequestException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    private static String key(String region, String name) {
        return region + ":" + name;
    }
}
