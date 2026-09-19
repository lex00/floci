package io.github.hectorvent.floci.services.resourcegroupstagging;

import java.util.Map;

/**
 * Supplies resources that carry tags written through their owning service rather than through
 * {@code TagResources}.
 *
 * <p>The Resource Groups Tagging API is the one AWS API that has to see resources it does not
 * own, because AWS indexes a tag no matter which API wrote it. Floci keeps a resource's tags on
 * the resource itself (an IAM policy's tags live on the {@code IamPolicy}), so a tagging service
 * reading only its own store answers "no resources" for an estate that is fully tagged.
 *
 * <p>Implemented by the services whose resources real AWS indexes; consumed lazily by
 * {@link ResourceGroupsTaggingService} through {@code Instance<TaggedResourceProvider>}, so the
 * tagging service never depends on a resource-owning service directly. Mirrors the
 * {@code ResourcePolicyProvider} seam IAM enforcement already uses in the other direction.
 *
 * <p>A provider reports what its service holds. Which of those ARNs a read operation may serve is
 * decided by {@link ResourceGroupsTaggingService#servedInRegion}, not here.
 */
public interface TaggedResourceProvider {

    /**
     * Tagged resources this service holds for the calling account, keyed by ARN. Resources with
     * no tags may be included or left out; the caller drops them either way.
     */
    Map<String, Map<String, String>> taggedResources();
}
