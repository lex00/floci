package io.github.hectorvent.floci.services.mediapackagev2;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.mediapackagev2.model.MediaPackageV2ChannelGroup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * AWS Elemental MediaPackage V2 management plane, limited to channel groups. The
 * egress domain is plausible but non-functional, and the packaging data plane is not
 * emulated.
 */
@ApplicationScoped
public class MediaPackageV2Service implements TagHandler {

    private static final Logger LOG = Logger.getLogger(MediaPackageV2Service.class);
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-zA-Z0-9_-]+");
    private static final int RESOURCE_NAME_MAX = 256;
    private static final int DESCRIPTION_MAX = 1024;

    private final StorageBackend<String, MediaPackageV2ChannelGroup> channelGroups;
    private final RegionResolver regionResolver;

    @Inject
    public MediaPackageV2Service(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("mediapackagev2", "mediapackagev2-channel-groups.json",
                        new TypeReference<Map<String, MediaPackageV2ChannelGroup>>() {}),
                regionResolver);
    }

    MediaPackageV2Service(StorageBackend<String, MediaPackageV2ChannelGroup> channelGroups,
                          RegionResolver regionResolver) {
        this.channelGroups = channelGroups;
        this.regionResolver = regionResolver;
    }

    /**
     * CreateChannelGroup. ChannelGroupName is required, is constrained to
     * {@code [a-zA-Z0-9_-]+} over 1 to 256 characters (the model spells out that spaces
     * are not allowed), must be unique for the account and Region, and cannot be
     * changed afterwards. Description is capped at 1024 characters.
     */
    public MediaPackageV2ChannelGroup createChannelGroup(String name, String description,
                                                         Map<String, String> tags, String region) {
        validateResourceName(name, "ChannelGroupName");
        validateDescription(description);
        if (channelGroups.get(name).isPresent()) {
            throw new AwsException("ConflictException",
                    "ChannelGroup " + name + " already exists", 409);
        }
        long now = Instant.now().getEpochSecond();
        MediaPackageV2ChannelGroup group = new MediaPackageV2ChannelGroup();
        group.setChannelGroupName(name);
        group.setArn(regionResolver.buildArn("mediapackagev2", region, "channelGroup/" + name));
        group.setEgressDomain(randomId() + ".egress." + randomId() + ".mediapackagev2." + region
                + ".amazonaws.com");
        // Null stays null. Terraform's provider rejects a create that echoes "" for a
        // description it never sent, reporting ".description: was null, but now
        // cty.StringVal(\"\")" as an inconsistent-result apply error.
        group.setDescription(description);
        group.setCreatedAt(now);
        group.setModifiedAt(now);
        group.setETag(UUID.randomUUID().toString().replace("-", ""));
        group.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        group.setAccountId(regionResolver.getAccountId());

        channelGroups.put(name, group);
        LOG.infov("Created MediaPackage V2 channel group: {0}", group.getArn());
        return group;
    }

    public MediaPackageV2ChannelGroup getChannelGroup(String name) {
        validateResourceName(name, "ChannelGroupName");
        return channelGroups.get(name)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "ChannelGroup " + name + " does not exist.", 404));
    }

    public void deleteChannelGroup(String name) {
        getChannelGroup(name);
        channelGroups.delete(name);
        LOG.infov("Deleted MediaPackage V2 channel group: {0}", name);
    }

    private static void validateResourceName(String name, String field) {
        if (name == null || name.isEmpty() || name.length() > RESOURCE_NAME_MAX
                || !RESOURCE_NAME.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    field + " must match [a-zA-Z0-9_-]+ and be 1 to " + RESOURCE_NAME_MAX
                            + " characters long", 400);
        }
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > DESCRIPTION_MAX) {
            throw new AwsException("ValidationException",
                    "Description must be at most " + DESCRIPTION_MAX + " characters long", 400);
        }
    }

    @Override
    public String serviceKey() {
        return "mediapackagev2";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = findByArn(arn).getTags();
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        MediaPackageV2ChannelGroup group = findByArn(arn);
        if (group.getTags() == null) {
            group.setTags(new HashMap<>());
        }
        group.getTags().putAll(tags);
        channelGroups.put(group.getChannelGroupName(), group);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        MediaPackageV2ChannelGroup group = findByArn(arn);
        if (group.getTags() != null && tagKeys != null) {
            tagKeys.forEach(group.getTags()::remove);
        }
        channelGroups.put(group.getChannelGroupName(), group);
    }

    private MediaPackageV2ChannelGroup findByArn(String arn) {
        return channelGroups.scan(k -> true).stream()
                .filter(g -> arn.equals(g.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private static String randomId() {
        StringBuilder id = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }
}
