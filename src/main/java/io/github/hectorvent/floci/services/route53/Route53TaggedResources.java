package io.github.hectorvent.floci.services.route53;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.resourcegroupstagging.TaggedResourceProvider;
import io.github.hectorvent.floci.services.resourcegroupstagging.TaggedResourceWriter;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Route 53's hosted zones and health checks, seen from the Resource Groups Tagging API.
 *
 * <p>Both directions go through Route 53's own tag store, the one {@code ChangeTagsForResource}
 * writes and {@code ListTagsForResource} reads, because that is where AWS keeps them.
 * {@code TagResources} on {@code arn:aws:route53:::hostedzone/Z123} is a change to the zone's tags,
 * and a zone tagged through Route 53's own API is in the tag index.
 *
 * <p>The ARNs carry no region and no account, as on AWS.
 */
@ApplicationScoped
public class Route53TaggedResources implements TaggedResourceProvider, TaggedResourceWriter {

    static final String ARN_PREFIX = "arn:aws:route53:::";
    static final String HOSTED_ZONE = "hostedzone";
    static final String HEALTH_CHECK = "healthcheck";

    private final Route53Service route53;

    @Inject
    public Route53TaggedResources(Route53Service route53) {
        this.route53 = route53;
    }

    @Override
    public Map<String, Map<String, String>> taggedResources() {
        Map<String, Map<String, String>> byArn = new LinkedHashMap<>();
        route53.listHostedZones(null, 0).forEach(z -> put(byArn, HOSTED_ZONE, z.getId()));
        route53.listHealthChecks(null, 0).forEach(h -> put(byArn, HEALTH_CHECK, h.getId()));
        return byArn;
    }

    @Override
    public boolean tag(String arn, Map<String, String> tags) {
        String[] target = existing(arn);
        if (target == null) {
            return false;
        }
        List<Map<String, String>> add = tags.entrySet().stream()
                .map(e -> Map.of("Key", e.getKey(), "Value", e.getValue() == null ? "" : e.getValue()))
                .toList();
        route53.changeTagsForResource(target[0], target[1], add, null);
        return true;
    }

    @Override
    public boolean untag(String arn, List<String> tagKeys) {
        String[] target = existing(arn);
        if (target == null) {
            return false;
        }
        route53.changeTagsForResource(target[0], target[1], null, tagKeys);
        return true;
    }

    private void put(Map<String, Map<String, String>> byArn, String type, String id) {
        Map<String, String> tags = route53.listTagsForResource(type, id);
        if (tags != null && !tags.isEmpty()) {
            byArn.put(ARN_PREFIX + type + "/" + id, Map.copyOf(tags));
        }
    }

    /**
     * {@code [type, id]} for a Route 53 ARN naming a zone or health check that exists, or
     * {@code null}. A resource that does not exist is left to the caller's previous behaviour
     * rather than failed here, so this change moves nothing but the ARNs Route 53 owns.
     */
    private String[] existing(String arn) {
        if (arn == null || !arn.startsWith(ARN_PREFIX)) {
            return null;
        }
        String resource = arn.substring(ARN_PREFIX.length());
        int slash = resource.indexOf('/');
        if (slash <= 0 || slash == resource.length() - 1) {
            return null;
        }
        String type = resource.substring(0, slash);
        String id = resource.substring(slash + 1);
        try {
            switch (type) {
                case HOSTED_ZONE -> route53.getHostedZone(id);
                case HEALTH_CHECK -> route53.getHealthCheck(id);
                default -> {
                    return null;
                }
            }
        } catch (AwsException notFound) {
            return null;
        }
        return new String[] {type, id};
    }
}
