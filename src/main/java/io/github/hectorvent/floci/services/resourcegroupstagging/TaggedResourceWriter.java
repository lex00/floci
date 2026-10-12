package io.github.hectorvent.floci.services.resourcegroupstagging;

import java.util.List;
import java.util.Map;

/**
 * The write side of {@link TaggedResourceProvider}. A service that owns some ARNs takes
 * {@code TagResources} and {@code UntagResources} for them onto the resource itself.
 *
 * <p>On AWS the Resource Groups Tagging API keeps no tags of its own for a resource its service
 * tags natively. {@code TagResources} on a Route 53 hosted zone changes the zone's tags, and
 * Route 53's {@code ListTagsForResource} reads them back.
 *
 * <p>A writer answers {@code false} for an ARN it does not own, or owns but cannot find, and the
 * tagging service keeps its own behaviour for that ARN.
 */
public interface TaggedResourceWriter {

    /** Adds or overwrites {@code tags} on the resource {@code arn} names, if this writer owns it. */
    boolean tag(String arn, Map<String, String> tags);

    /** Removes {@code tagKeys} from the resource {@code arn} names, if this writer owns it. */
    boolean untag(String arn, List<String> tagKeys);
}
