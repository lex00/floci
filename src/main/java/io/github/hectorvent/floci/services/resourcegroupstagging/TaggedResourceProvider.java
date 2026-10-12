package io.github.hectorvent.floci.services.resourcegroupstagging;

import java.util.Map;

/**
 * Supplies tagged resources to the Resource Groups Tagging API only, for services whose resources
 * the tag index serves but which are not {@code ResourceProvider}s, or are listed there as global.
 *
 * <p>{@code ResourceProvider} feeds Resource Explorer as well, and the tagging API hides every
 * global resource it reports. IAM policies and instance profiles and Route 53 zones and health
 * checks are indexed by the tagging API all the same, so they reach it through this seam instead,
 * leaving Resource Explorer's view unchanged.
 *
 * <p>A provider reports what its service holds. Which of those ARNs a read may serve in a region
 * is decided by {@link ResourceGroupsTaggingService#servedInRegion}, not here.
 */
public interface TaggedResourceProvider {

    /**
     * Tagged resources this service holds for the calling account, keyed by ARN. Resources with
     * no tags may be included or left out, and the caller drops them either way.
     */
    Map<String, Map<String, String>> taggedResources();
}
