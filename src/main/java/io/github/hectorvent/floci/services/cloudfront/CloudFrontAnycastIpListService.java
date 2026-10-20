package io.github.hectorvent.floci.services.cloudfront;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.cloudfront.model.AnycastIpList;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFront Anycast static IP lists. A list is assigned addresses from the documentation
 * ranges (RFC 5737 and RFC 3849) and is {@code Deployed} as soon as it is created.
 */
@ApplicationScoped
public class CloudFrontAnycastIpListService {

    private static final Set<Integer> IP_COUNTS = Set.of(3, 21);
    private static final Set<String> IP_ADDRESS_TYPES = Set.of("ipv4", "ipv6", "dualstack");
    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final StorageBackend<String, AnycastIpList> store;
    private final CloudFrontService cloudFront;

    @Inject
    public CloudFrontAnycastIpListService(StorageFactory factory, CloudFrontService cloudFront) {
        this(factory.create("cloudfront", "cloudfront-anycast-ip-lists.json",
                new TypeReference<Map<String, AnycastIpList>>() {}), cloudFront);
    }

    CloudFrontAnycastIpListService(StorageBackend<String, AnycastIpList> store, CloudFrontService cloudFront) {
        this.store = store;
        this.cloudFront = cloudFront;
    }

    public synchronized AnycastIpList create(AnycastIpList list, Map<String, String> tags) {
        if (list.getName() == null || list.getName().isBlank()) {
            throw new AwsException("InvalidArgument", "Name is required.", 400);
        }
        if (!IP_COUNTS.contains(list.getIpCount())) {
            throw new AwsException("InvalidArgument", "IpCount must be 3 or 21.", 400);
        }
        String type = list.getIpAddressType() == null ? "ipv4" : list.getIpAddressType();
        if (!IP_ADDRESS_TYPES.contains(type)) {
            throw new AwsException("InvalidArgument", "IpAddressType must be ipv4, ipv6 or dualstack.", 400);
        }
        boolean nameTaken = store.scan(k -> true).stream().anyMatch(l -> l.getName().equals(list.getName()));
        if (nameTaken) {
            throw new AwsException("EntityAlreadyExists",
                    "An Anycast static IP list with the name " + list.getName() + " already exists.", 409);
        }
        String id = newId();
        list.setId(id);
        list.setIpAddressType(type);
        list.setArn(cloudFront.arn("anycast-ip-list/" + id));
        list.setStatus("Deployed");
        list.setAnycastIps(addresses(type, list.getIpCount(), store.keys().size()));
        list.setLastModifiedTime(Instant.now());
        list.setEtag(UUID.randomUUID().toString());
        store.put(id, list);
        if (tags != null && !tags.isEmpty()) {
            cloudFront.tagResource(list.getArn(), tags);
        }
        return list;
    }

    public AnycastIpList get(String id) {
        return store.get(id).orElseThrow(() ->
                new AwsException("EntityNotFound", "The Anycast static IP list " + id + " does not exist.", 404));
    }

    public List<AnycastIpList> list() {
        return store.scan(k -> true).stream().sorted(Comparator.comparing(AnycastIpList::getId)).toList();
    }

    public synchronized AnycastIpList update(String id, String ifMatch, String ipAddressType,
                                             List<AnycastIpList.IpamCidrConfig> ipamCidrConfigs) {
        AnycastIpList list = get(id);
        requireEtag(list, ifMatch);
        if (ipAddressType != null) {
            if (!IP_ADDRESS_TYPES.contains(ipAddressType)) {
                throw new AwsException("InvalidArgument", "IpAddressType must be ipv4, ipv6 or dualstack.", 400);
            }
            list.setIpAddressType(ipAddressType);
        }
        if (ipamCidrConfigs != null) {
            list.setIpamCidrConfigs(ipamCidrConfigs);
        }
        list.setLastModifiedTime(Instant.now());
        list.setEtag(UUID.randomUUID().toString());
        store.put(id, list);
        return list;
    }

    public synchronized void delete(String id, String ifMatch) {
        AnycastIpList list = get(id);
        requireEtag(list, ifMatch);
        store.delete(id);
        cloudFront.untagResource(list.getArn(), List.copyOf(cloudFront.listTagsForResource(list.getArn()).keySet()));
    }

    private static void requireEtag(AnycastIpList list, String ifMatch) {
        if (ifMatch == null || ifMatch.isEmpty()) {
            throw new AwsException("InvalidIfMatchVersion",
                    "The If-Match version is missing or not valid for the resource.", 400);
        }
        if (!ifMatch.equals(list.getEtag())) {
            throw new AwsException("PreconditionFailed",
                    "The precondition given in one or more of the request-header fields evaluated to false.", 412);
        }
    }

    /** Addresses from 198.51.100.0/24 and 2001:db8::/32, offset per list so lists do not overlap. */
    private static List<String> addresses(String type, int count, int listIndex) {
        List<String> ips = new ArrayList<>();
        int base = (listIndex * 21) % 230;
        for (int i = 0; i < count; i++) {
            if (!"ipv6".equals(type)) {
                ips.add("198.51.100." + (base + i + 1));
            }
            if (!"ipv4".equals(type)) {
                ips.add("2001:db8::" + Integer.toHexString(listIndex * 21 + i + 1));
            }
        }
        return ips;
    }

    private static String newId() {
        String uuid = UUID.randomUUID().toString().replace("-", "").toUpperCase();
        StringBuilder id = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            id.append(ID_CHARS.charAt(Character.digit(uuid.charAt(i), 16) % ID_CHARS.length()));
        }
        return id.toString();
    }
}
