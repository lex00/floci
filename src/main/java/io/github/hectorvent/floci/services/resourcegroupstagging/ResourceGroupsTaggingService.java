package io.github.hectorvent.floci.services.resourcegroupstagging;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.TaggedResourceScanner;
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

    private final StorageFactory storageFactory;
    private final TaggedResourceScanner scanner;
    private final Instance<TaggedResourceWriter> taggedResourceWriters;

    // region::arn → ResourceTagMapping
    private Map<String, ResourceTagMapping> store = new ConcurrentHashMap<>();

    @Inject
    public ResourceGroupsTaggingService(StorageFactory storageFactory, TaggedResourceScanner scanner,
                                        Instance<TaggedResourceWriter> taggedResourceWriters) {
        this.storageFactory = storageFactory;
        this.scanner = scanner;
        this.taggedResourceWriters = taggedResourceWriters;
    }

    /**
     * Constructor for unit tests that exercise only the {@code TagResources}/{@code GetResources}
     * round-trip through this service's own store, with no estate-wide scan.
     */
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

    // ─── The estate-wide view ──────────────────────────────────────────────────

    /**
     * Every tagged resource this API can see: the ones tagged through {@code TagResources}, which
     * live in this service's own store, plus every resource any other service holds with tags on
     * it, read live by {@link TaggedResourceScanner}.
     *
     * <p>Without the second source this API answers "no resources" for an estate full of tagged
     * ones, because floci stores a resource's tags on that resource's own model — an EC2 volume's
     * tags on the {@code Volume}, an IAM role's on the {@code IamRole} — and nothing wrote them
     * here. The scan is done per call rather than cached: a stale tagging index is exactly the
     * failure this method exists to fix.
     *
     * <p>On an ARN present in both, the explicitly-tagged value wins, since a {@code TagResources}
     * call is the more recent statement of intent.
     *
     * <p>What the read side may then hand back out of this merge is decided per caller region by
     * {@link #servedInRegion}, which is where IAM's exclusions live: they depend on the request
     * region, and this merge does not know it.
     */
    private Collection<ResourceTagMapping> allMappings() {
        Map<String, ResourceTagMapping> merged = new LinkedHashMap<>();
        if (scanner != null) {
            scanner.scan().forEach((arn, tags) -> {
                ResourceTagMapping mapping = new ResourceTagMapping(arn);
                mapping.getTags().putAll(tags);
                merged.put(arn, mapping);
            });
        }
        for (ResourceTagMapping explicit : store.values()) {
            merged.computeIfAbsent(explicit.getResourceArn(), ResourceTagMapping::new)
                    .getTags().putAll(explicit.getTags());
        }
        return merged.values();
    }

    /**
     * The one region in which the Resource Groups Tagging API indexes IAM. IAM is a global
     * service — its ARNs carry no region — and real AWS indexes the part of it that is indexed
     * at all in us-east-1 alone, so a caller in any other region gets nothing back for it.
     */
    private static final String IAM_INDEX_REGION = "us-east-1";

    /**
     * The IAM resource types the tagging API's read side serves. An allowlist, not a denylist,
     * and every entry is a measurement: real AWS was probed at scale against a live account on
     * 2026-09-14 (INTENTIUS/choudoufu#1134, filed here as #205) with 550 tagged roles, 500 tagged
     * policies and 500 tagged instance profiles in the account.
     *
     * <ul>
     *   <li>{@code iam:policy} — served, us-east-1 only.</li>
     *   <li>{@code iam:instance-profile} — served, us-east-1 only.</li>
     *   <li>{@code iam:role} — served nowhere, in any region, though the IAM API confirms every
     *       one of the 550 was tagged. Not an oversight in this list: it is the measured
     *       behaviour, and #202 was right about it.</li>
     * </ul>
     *
     * <p>The other five IAM types {@code TagResources} accepts (mfa, oidc-provider,
     * saml-provider, server-certificate, user) are absent because nobody has measured them, not
     * because they are known to be unserved. Adding one needs a probe against real AWS, for the
     * same reason this list exists: #202 generalised a single {@code iam:role} probe to the whole
     * service and got two of the three types wrong in the other direction.
     */
    private static final Set<String> IAM_TYPES_IN_THE_TAG_INDEX = Set.of("policy", "instance-profile");

    /**
     * Whether {@code GetResources}/{@code GetTagKeys}/{@code GetTagValues} may serve this ARN to a
     * caller whose request region is {@code region}.
     *
     * <p>For everything but IAM this is the rule floci has always applied, unchanged: a resource
     * belongs to the region in its ARN, and an ARN with an empty region segment
     * ({@code arn:aws:s3:::bucket}) is served everywhere.
     *
     * <p>IAM cannot use that rule, because an IAM ARN's region segment is always empty and the
     * answer is neither "everywhere" nor "nowhere": see {@link #IAM_TYPES_IN_THE_TAG_INDEX} for
     * the measured split. Both halves matter to a consumer. Serving the whole service would put
     * {@code iam:role} in the index, which real AWS never does — the shape #202 removed. Serving
     * none of it, which is what #202 left behind, hides {@code iam:policy} and
     * {@code iam:instance-profile}, and an empty answer is then indistinguishable between a
     * consumer that routes those two correctly and one that does not. Serving them in every
     * region instead of only us-east-1 would be a smaller lie and still a lie: a consumer written
     * against it would read the index from eu-west-1, pass here, and find nothing on AWS.
     *
     * <p>Note on timing, deliberately not emulated: on real AWS a freshly created IAM policy or
     * instance profile takes roughly 500 seconds to appear here, because the tag index is
     * populated asynchronously. floci serves it immediately. A consumer that creates one of these
     * and reads the index in the same breath therefore passes against floci and can still fail
     * against AWS; only the steady state is emulated. Emulating the delay would need a persisted
     * first-seen clock and a way to switch it off, and would hard-code one observation of an
     * eventually-consistent index as a contract, while making every estate test that touches IAM
     * either sleep for eight minutes or see the same empty list this change exists to fix.
     *
     * <p>{@code TagResources}/{@code UntagResources} are unaffected, as they were under #202:
     * AWS's docs list eight IAM resource types as valid input to those two, IAM ARNs tagged that
     * way stay in {@link #store}, and untagging must still find them. Only the read side filters.
     *
     * <p>Malformed ARNs are left alone (served as before) rather than silently dropped; a bad ARN
     * is a different problem from this one.
     */
    static boolean servedInRegion(String arn, String region) {
        String iamResourceType = iamResourceTypeOrNull(arn);
        if (iamResourceType != null) {
            return IAM_TYPES_IN_THE_TAG_INDEX.contains(iamResourceType)
                    && IAM_INDEX_REGION.equals(region);
        }
        // Derive the region from the ARN (arn:aws:svc:region:acct:type/id)
        String[] parts = arn.split(":", 6);
        if (parts.length >= 4) {
            String arnRegion = parts[3];
            if (!arnRegion.isEmpty() && !arnRegion.equals(region)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The IAM resource type in {@code arn} — {@code role}, {@code policy},
     * {@code instance-profile}, ... — or {@code null} when the ARN is not IAM's or cannot be
     * parsed. A path is part of the resource segment ({@code policy/team/a/ReadOnly}), so only
     * the segment before the first slash is the type.
     */
    private static String iamResourceTypeOrNull(String arn) {
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        if (!"iam".equalsIgnoreCase(parsed.service())) {
            return null;
        }
        String resource = parsed.resource();
        int slash = resource.indexOf('/');
        return (slash >= 0 ? resource.substring(0, slash) : resource).toLowerCase(Locale.ROOT);
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
        List<ResourceTagMapping> all = allMappings().stream()
                .filter(m -> servedInRegion(m.getResourceArn(), region))
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
        List<String> keys = allMappings().stream()
                .filter(m -> servedInRegion(m.getResourceArn(), region))
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
        List<String> values = allMappings().stream()
                .filter(m -> servedInRegion(m.getResourceArn(), region))
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
