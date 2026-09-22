package io.github.hectorvent.floci.services.resourcegroupstagging;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.storage.StorageBackedMap;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.resourcegroupstagging.model.ResourceTagMapping;
import io.github.hectorvent.floci.core.common.Resettable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class ResourceGroupsTaggingService implements Resettable {

    /**
     * The one region whose tag index holds IAM resources. IAM is global and indexes in
     * {@code us-east-1} only, so an account's tagged IAM policies are invisible to
     * {@code GetResources} called against any other region.
     */
    private static final String IAM_INDEX_REGION = "us-east-1";

    /**
     * The IAM resource types the index actually serves, as an allowlist of measured types rather
     * than a denylist of excluded ones. Measured against a live account on 2026-09-14: a tagged
     * {@code policy} and a tagged {@code instance-profile} come back from {@code GetResources} in
     * {@code us-east-1}, a tagged {@code role} comes back nowhere, and the remaining types
     * {@code TagResources} accepts (mfa, oidc-provider, saml-provider, server-certificate, user)
     * have not been probed. An unprobed type stays out until someone measures it, which is the
     * lesson of the role generalisation this list replaces.
     */
    private static final Set<String> IAM_TYPES_IN_TAG_INDEX = Set.of("policy", "instance-profile");

    private final StorageFactory storageFactory;
    private final Instance<TaggedResourceProvider> taggedResourceProviders;
    private final Instance<TaggedResourceWriter> taggedResourceWriters;

    // region::arn → ResourceTagMapping
    private Map<String, ResourceTagMapping> store = new ConcurrentHashMap<>();

    @Inject
    public ResourceGroupsTaggingService(StorageFactory storageFactory,
                                        Instance<TaggedResourceProvider> taggedResourceProviders,
                                        Instance<TaggedResourceWriter> taggedResourceWriters) {
        this.storageFactory = storageFactory;
        this.taggedResourceProviders = taggedResourceProviders;
        this.taggedResourceWriters = taggedResourceWriters;
    }

    /** Constructor for unit tests that exercise the store alone. */
    public ResourceGroupsTaggingService(StorageFactory storageFactory) {
        this(storageFactory, null, null);
    }

    @PostConstruct
    void initializeStorage() {
        if (storageFactory == null) {
            return; // keeps non-CDI unit tests working
        }
        this.store = new StorageBackedMap<>(storageFactory.create("tagging",
                "tagging-resource-mappings.json", new TypeReference<Map<String, ResourceTagMapping>>() {}));
    }

    private String key(String region, String arn) {
        return region + "::" + arn;
    }

    /**
     * Returns the tags for a single resource, or an empty map if no tags have been applied.
     */
    public Map<String, String> getTagsForResource(String region, String arn) {
        ResourceTagMapping mapping = store.get(key(region, arn));
        return mapping != null ? Collections.unmodifiableMap(mapping.getTags()) : Map.of();
    }

    // ─── TagResources ──────────────────────────────────────────────────────────

    // Mutators are synchronized: StorageBackedMap has no atomic computeIfAbsent, so
    // the get-mutate-put sequence would otherwise lose updates under concurrent calls.
    public synchronized void tagResources(List<String> resourceArns, Map<String, String> tags, String region) {
        for (String arn : resourceArns) {
            if (writtenThrough(w -> w.tag(arn, tags))) {
                // The owning service holds the tags now, as it does on AWS; the read side sees
                // them through its TaggedResourceProvider.
                continue;
            }
            String storeKey = key(region, arn);
            ResourceTagMapping mapping = store.get(storeKey);
            if (mapping == null) {
                mapping = new ResourceTagMapping(arn);
            }
            mapping.getTags().putAll(tags);
            // Re-put so StorageBackedMap routes the mutation through the backend
            store.put(storeKey, mapping);
        }
    }

    // ─── UntagResources ────────────────────────────────────────────────────────

    public synchronized void untagResources(List<String> resourceArns, List<String> tagKeys, String region) {
        for (String arn : resourceArns) {
            // Both, not either: a tag written before the owning service took its ARNs over may
            // still sit in this service's store, and an untag has to clear it from there too.
            writtenThrough(w -> w.untag(arn, tagKeys));
            String storeKey = key(region, arn);
            ResourceTagMapping mapping = store.get(storeKey);
            if (mapping != null) {
                tagKeys.forEach(mapping.getTags()::remove);
                store.put(storeKey, mapping);
            }
        }
    }

    /**
     * Offers a write to each {@link TaggedResourceWriter}, and reports whether one of them owned
     * the ARN and applied it.
     */
    private boolean writtenThrough(java.util.function.Predicate<TaggedResourceWriter> write) {
        if (taggedResourceWriters == null) {
            return false;
        }
        for (TaggedResourceWriter writer : taggedResourceWriters) {
            if (write.test(writer)) {
                return true;
            }
        }
        return false;
    }

    public synchronized void deleteResources(List<String> resourceArns, String region) {
        for (String arn : resourceArns) {
            store.remove(key(region, arn));
        }
    }

    public void clear() {
        store.clear();
    }

    // ─── The served view ───────────────────────────────────────────────────────

    /**
     * Everything {@code GetResources}, {@code GetTagKeys} and {@code GetTagValues} may show a
     * caller in {@code region}: this service's own store, merged with the tags each
     * {@link TaggedResourceProvider} holds on the resources it owns.
     *
     * <p>On an ARN present in both, the value written through {@code TagResources} wins, since it
     * is the more recent statement of intent.
     */
    private Collection<ResourceTagMapping> servedMappings(String region) {
        Map<String, ResourceTagMapping> merged = new LinkedHashMap<>();
        if (taggedResourceProviders != null) {
            taggedResourceProviders.forEach(provider -> provider.taggedResources().forEach((arn, tags) -> {
                if (tags.isEmpty() || !servedInRegion(arn, region)) {
                    return;
                }
                merged.computeIfAbsent(arn, ResourceTagMapping::new).getTags().putAll(tags);
            }));
        }
        for (ResourceTagMapping explicit : store.values()) {
            if (!servedInRegion(explicit.getResourceArn(), region)) {
                continue;
            }
            merged.computeIfAbsent(explicit.getResourceArn(), ResourceTagMapping::new)
                    .getTags().putAll(explicit.getTags());
        }
        return merged.values();
    }

    /**
     * Whether the tag index serves {@code arn} to a caller in {@code region}.
     *
     * <p>For most services the ARN answers it: a regional ARN is served in its own region, and an
     * ARN with an empty region segment (an S3 bucket, say) is served everywhere.
     *
     * <p>IAM is the exception, and an empty region segment is exactly why it needs one. IAM ARNs
     * carry no region, so the rule above would serve them from every region. Measured against a
     * live account on 2026-09-14, real AWS serves a tagged IAM policy and a tagged IAM instance
     * profile from {@code us-east-1} alone, and never serves a tagged IAM role from anywhere,
     * however the tag was written. Serving the two types in every region would be the cheaper fix
     * and a smaller lie, but a consumer written against it would read the index from eu-west-1,
     * pass here, and find nothing on AWS. The write side is untouched: {@code TagResources} and
     * {@code UntagResources} accept IAM ARNs of all eight types, as AWS does, and only the read
     * side filters.
     *
     * <p>Known unfaithful in one respect: on real AWS a freshly created policy or instance profile
     * takes roughly 500 seconds to appear in the index, and floci serves it at once. A consumer
     * that creates one of these and reads the index in the same breath passes here and can still
     * fail against AWS. Only the steady state is emulated, because freezing one observation of an
     * eventually consistent index into a contract would be its own kind of wrong.
     *
     * <p>Malformed input is left alone rather than silently dropped: a string too short to carry a
     * region segment is served, as it was before this rule existed.
     */
    static boolean servedInRegion(String arn, String region) {
        if (arn == null) {
            return false;
        }
        String[] parts = arn.split(":", 6);
        if (parts.length < 4) {
            return true;
        }
        if ("iam".equalsIgnoreCase(parts[2])) {
            return IAM_INDEX_REGION.equals(region) && IAM_TYPES_IN_TAG_INDEX.contains(iamResourceType(parts));
        }
        String arnRegion = parts[3];
        return arnRegion.isEmpty() || arnRegion.equals(region);
    }

    /**
     * The resource type of a split IAM ARN: {@code policy} for
     * {@code arn:aws:iam::0:policy/team/a/ReadOnly}, whose path segments are part of the resource
     * id and not of the type, and {@code role} for {@code arn:aws:iam::0:role/service-role/web}.
     */
    private static String iamResourceType(String[] parts) {
        if (parts.length < 6) {
            return "";
        }
        String resource = parts[5];
        int slash = resource.indexOf('/');
        return slash < 0 ? resource : resource.substring(0, slash);
    }

    // ─── GetResources ──────────────────────────────────────────────────────────

    public record TagFilter(String key, List<String> values) {}

    public record PageResult(List<ResourceTagMapping> items, String nextPaginationToken) {}

    public PageResult getResources(List<String> resourceArnList,
                                   List<TagFilter> tagFilters,
                                   List<String> resourceTypeFilters,
                                   String paginationToken,
                                   int resourcesPerPage,
                                   String region) {
        List<ResourceTagMapping> all = servedMappings(region).stream()
                .filter(m -> resourceArnList == null || resourceArnList.isEmpty()
                        || resourceArnList.contains(m.getResourceArn()))
                .filter(m -> matchesTagFilters(m, tagFilters))
                .filter(m -> matchesResourceTypeFilters(m, resourceTypeFilters))
                .sorted(Comparator.comparing(ResourceTagMapping::getResourceArn))
                .collect(Collectors.toList());

        int offset = decodePaginationToken(paginationToken);
        int pageSize = (resourcesPerPage > 0) ? resourcesPerPage : 100;
        int end = Math.min(offset + pageSize, all.size());
        List<ResourceTagMapping> page = all.subList(offset, end);
        String nextToken = (end < all.size()) ? encodePaginationToken(end) : null;
        return new PageResult(page, nextToken);
    }

    private boolean matchesTagFilters(ResourceTagMapping m, List<TagFilter> tagFilters) {
        if (tagFilters == null || tagFilters.isEmpty()) return true;
        Map<String, String> tags = m.getTags();
        for (TagFilter filter : tagFilters) {
            String tagValue = tags.get(filter.key());
            if (tagValue == null) return false;
            if (!filter.values().isEmpty() && !filter.values().contains(tagValue)) return false;
        }
        return true;
    }

    private boolean matchesResourceTypeFilters(ResourceTagMapping m, List<String> resourceTypeFilters) {
        if (resourceTypeFilters == null || resourceTypeFilters.isEmpty()) return true;
        // ARN: arn:aws:<service>:<region>:<account>:<type>/<id>  or  arn:aws:<service>:::...
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(m.getResourceArn());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String service = arn.service();
        String resourcePart = arn.resource(); // "type/id" or just "type"
        String resourceType = resourcePart.contains("/")
                ? resourcePart.substring(0, resourcePart.indexOf('/'))
                : resourcePart;
        // filter format is "service:resourceType" (e.g. "ec2:instance")
        for (String filter : resourceTypeFilters) {
            String[] filterParts = filter.split(":", 2);
            if (filterParts.length == 2) {
                if (filterParts[0].equalsIgnoreCase(service)
                        && filterParts[1].equalsIgnoreCase(resourceType)) {
                    return true;
                }
            } else {
                if (filterParts[0].equalsIgnoreCase(service)) return true;
            }
        }
        return false;
    }

    // ─── GetTagKeys ────────────────────────────────────────────────────────────

    public PageResult getTagKeys(String paginationToken, int maxResults, String region) {
        List<String> keys = servedMappings(region).stream()
                .flatMap(m -> m.getTags().keySet().stream())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        int offset = decodePaginationToken(paginationToken);
        int pageSize = (maxResults > 0) ? maxResults : 100;
        int end = Math.min(offset + pageSize, keys.size());
        // Return as ResourceTagMapping with just the key in the ARN field (repurposed for keys)
        List<ResourceTagMapping> page = keys.subList(offset, end).stream()
                .map(k -> new ResourceTagMapping(k))
                .collect(Collectors.toList());
        String nextToken = (end < keys.size()) ? encodePaginationToken(end) : null;
        return new PageResult(page, nextToken);
    }

    // ─── GetTagValues ──────────────────────────────────────────────────────────

    public PageResult getTagValues(String tagKey, String paginationToken, int maxResults, String region) {
        List<String> values = servedMappings(region).stream()
                .map(m -> m.getTags().get(tagKey))
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        int offset = decodePaginationToken(paginationToken);
        int pageSize = (maxResults > 0) ? maxResults : 100;
        int end = Math.min(offset + pageSize, values.size());
        List<ResourceTagMapping> page = values.subList(offset, end).stream()
                .map(v -> new ResourceTagMapping(v))
                .collect(Collectors.toList());
        String nextToken = (end < values.size()) ? encodePaginationToken(end) : null;
        return new PageResult(page, nextToken);
    }

    // ─── Pagination helpers ────────────────────────────────────────────────────

    private static String encodePaginationToken(int offset) {
        return Base64.getEncoder().encodeToString(String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }

    private static int decodePaginationToken(String token) {
        if (token == null || token.isBlank()) return 0;
        try {
            return Integer.parseInt(new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8));
        } catch (Exception e) {
            return 0;
        }
    }
}
