package io.github.hectorvent.floci.services.ivs;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ivs.model.IvsChannel;
import io.github.hectorvent.floci.services.ivs.model.IvsPlaybackKeyPair;
import io.github.hectorvent.floci.services.ivs.model.IvsRecordingConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Amazon Interactive Video Service management plane.
 *
 * <p>Recording configurations are ACTIVE as soon as a create returns, so SDK and Terraform
 * waiters complete on their first poll. Ingest endpoints and playback URLs are plausible but
 * non-functional: the video data plane is not emulated.
 */
@ApplicationScoped
public class IvsService implements TagHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(IvsService.class);
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    // ChannelName, PlaybackKeyPairName and RecordingConfigurationName share one shape in the
    // model: max 128, pattern [a-zA-Z0-9-_]*.
    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-zA-Z0-9\\-_]*");
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_TAGS = 50;

    private static final Set<String> LATENCY_MODES = Set.of("NORMAL", "LOW");
    private static final Set<String> CHANNEL_TYPES = Set.of("BASIC", "STANDARD", "ADVANCED_SD", "ADVANCED_HD");
    private static final Set<String> ADVANCED_CHANNEL_TYPES = Set.of("ADVANCED_SD", "ADVANCED_HD");
    private static final Set<String> TRANSCODE_PRESETS =
            Set.of("HIGHER_BANDWIDTH_DELIVERY", "CONSTRAINED_BANDWIDTH_DELIVERY");
    private static final Set<String> THUMBNAIL_RECORDING_MODES = Set.of("DISABLED", "INTERVAL");
    private static final Set<String> THUMBNAIL_STORAGE = Set.of("SEQUENTIAL", "LATEST");
    private static final Set<String> RESOLUTIONS = Set.of("SD", "HD", "FULL_HD", "LOWEST_RESOLUTION");
    private static final Set<String> RENDITION_SELECTIONS = Set.of("ALL", "NONE", "CUSTOM");

    private static final int MIN_RECONNECT_WINDOW_SECONDS = 0;
    private static final int MAX_RECONNECT_WINDOW_SECONDS = 300;
    private static final int DEFAULT_TARGET_INTERVAL_SECONDS = 60;
    private static final int MIN_TARGET_INTERVAL_SECONDS = 1;
    private static final int MAX_TARGET_INTERVAL_SECONDS = 60;
    private static final int MIN_BUCKET_NAME_LENGTH = 3;
    private static final int MAX_BUCKET_NAME_LENGTH = 63;

    private final StorageBackend<String, IvsChannel> channels;
    private final StorageBackend<String, IvsPlaybackKeyPair> keyPairs;
    private final StorageBackend<String, IvsRecordingConfiguration> recordingConfigurations;
    private final RegionResolver regionResolver;

    @Inject
    public IvsService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.channels = storageFactory.create("ivs", "ivs-channels.json",
                new TypeReference<Map<String, IvsChannel>>() {});
        this.keyPairs = storageFactory.create("ivs", "ivs-playback-key-pairs.json",
                new TypeReference<Map<String, IvsPlaybackKeyPair>>() {});
        this.recordingConfigurations = storageFactory.create("ivs", "ivs-recording-configurations.json",
                new TypeReference<Map<String, IvsRecordingConfiguration>>() {});
        this.regionResolver = regionResolver;
    }

    // Channels

    /**
     * CreateChannel also mints the channel's stream key, as the AWS operation documents
     * ("Creates a new channel and an associated stream key to start streaming"). The key lives on
     * the channel record, so DeleteChannel removes it with the channel.
     */
    public IvsChannel createChannel(String name, String latencyMode, String type, String preset,
                                    boolean authorized, boolean insecureIngest,
                                    String recordingConfigurationArn, Map<String, String> tags,
                                    String region) {
        validateName("name", name);
        validateTags(tags);
        String resolvedLatencyMode = enumOrDefault("latencyMode", latencyMode, LATENCY_MODES, "LOW");
        String resolvedType = enumOrDefault("type", type, CHANNEL_TYPES, "STANDARD");
        String resolvedPreset = resolvePreset(preset, resolvedType);
        if (recordingConfigurationArn != null && !recordingConfigurationArn.isEmpty()) {
            getRecordingConfiguration(recordingConfigurationArn, region);
        }

        String channelId = randomId();
        IvsChannel channel = new IvsChannel();
        channel.setArn(regionResolver.buildArn("ivs", region, "channel/" + channelId));
        channel.setName(name != null ? name : "");
        channel.setLatencyMode(resolvedLatencyMode);
        channel.setType(resolvedType);
        channel.setPreset(resolvedPreset);
        channel.setAuthorized(authorized);
        channel.setInsecureIngest(insecureIngest);
        channel.setRecordingConfigurationArn(recordingConfigurationArn != null ? recordingConfigurationArn : "");
        channel.setIngestEndpoint(channelId + ".global-contribute.live-video.net");
        channel.setPlaybackUrl("https://" + channelId + "." + region
                + ".playback.live-video.net/api/video/v1/" + region + ".channel." + channelId + ".m3u8");
        channel.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        channel.setStreamKeyArn(regionResolver.buildArn("ivs", region, "stream-key/" + randomId()));
        channel.setStreamKeyValue("sk_" + region + "_" + randomId() + randomId());
        channel.setAccountId(regionResolver.getAccountId());

        channels.put(channel.getArn(), channel);
        LOG.infov("Created IVS channel: {0}", channel.getArn());
        return channel;
    }

    public IvsChannel getChannel(String arn, String region) {
        requireArn(arn);
        return channels.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Channel " + arn + " does not exist.", 404));
    }

    public void deleteChannel(String arn, String region) {
        getChannel(arn, region);
        channels.delete(arn);
        LOG.infov("Deleted IVS channel and its stream key: {0}", arn);
    }

    // Playback key pairs

    public IvsPlaybackKeyPair importPlaybackKeyPair(String publicKeyMaterial, String name,
                                                    Map<String, String> tags, String region) {
        if (publicKeyMaterial == null || publicKeyMaterial.isBlank()) {
            throw new AwsException("ValidationException", "publicKeyMaterial is required", 400);
        }
        validateName("name", name);
        validateTags(tags);

        IvsPlaybackKeyPair keyPair = new IvsPlaybackKeyPair();
        keyPair.setArn(regionResolver.buildArn("ivs", region, "playback-key/" + randomId()));
        keyPair.setName(name != null ? name : "");
        keyPair.setFingerprint(fingerprint(publicKeyMaterial));
        keyPair.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        keyPair.setAccountId(regionResolver.getAccountId());

        keyPairs.put(keyPair.getArn(), keyPair);
        LOG.infov("Imported IVS playback key pair: {0}", keyPair.getArn());
        return keyPair;
    }

    public IvsPlaybackKeyPair getPlaybackKeyPair(String arn, String region) {
        requireArn(arn);
        return keyPairs.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "PlaybackKeyPair " + arn + " does not exist.", 404));
    }

    public void deletePlaybackKeyPair(String arn, String region) {
        getPlaybackKeyPair(arn, region);
        keyPairs.delete(arn);
        LOG.infov("Deleted IVS playback key pair: {0}", arn);
    }

    // Recording configurations

    public IvsRecordingConfiguration createRecordingConfiguration(String name, JsonNode destinationConfiguration,
                                                                  JsonNode thumbnailConfiguration,
                                                                  JsonNode renditionConfiguration,
                                                                  Integer recordingReconnectWindowSeconds,
                                                                  Map<String, String> tags, String region) {
        validateName("name", name);
        validateTags(tags);
        validateDestinationConfiguration(destinationConfiguration);
        JsonNode resolvedThumbnail = validateThumbnailConfiguration(thumbnailConfiguration);
        validateRenditionConfiguration(renditionConfiguration);
        int reconnectWindow = recordingReconnectWindowSeconds != null ? recordingReconnectWindowSeconds : 0;
        if (reconnectWindow < MIN_RECONNECT_WINDOW_SECONDS || reconnectWindow > MAX_RECONNECT_WINDOW_SECONDS) {
            throw new AwsException("ValidationException",
                    "recordingReconnectWindowSeconds must be between " + MIN_RECONNECT_WINDOW_SECONDS
                            + " and " + MAX_RECONNECT_WINDOW_SECONDS, 400);
        }

        IvsRecordingConfiguration configuration = new IvsRecordingConfiguration();
        configuration.setArn(regionResolver.buildArn("ivs", region, "recording-configuration/" + randomId()));
        configuration.setName(name != null ? name : "");
        configuration.setDestinationConfiguration(destinationConfiguration);
        configuration.setThumbnailConfiguration(resolvedThumbnail);
        configuration.setRenditionConfiguration(renditionConfiguration);
        configuration.setRecordingReconnectWindowSeconds(reconnectWindow);
        configuration.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        configuration.setAccountId(regionResolver.getAccountId());

        recordingConfigurations.put(configuration.getArn(), configuration);
        LOG.infov("Created IVS recording configuration: {0}", configuration.getArn());
        return configuration;
    }

    public IvsRecordingConfiguration getRecordingConfiguration(String arn, String region) {
        requireArn(arn);
        return recordingConfigurations.get(arn)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "RecordingConfiguration " + arn + " does not exist.", 404));
    }

    public void deleteRecordingConfiguration(String arn, String region) {
        getRecordingConfiguration(arn, region);
        recordingConfigurations.delete(arn);
        LOG.infov("Deleted IVS recording configuration: {0}", arn);
    }

    // Listings

    public List<IvsChannel> listChannels() {
        return channels.scan(key -> true);
    }

    public List<IvsPlaybackKeyPair> listPlaybackKeyPairs() {
        return keyPairs.scan(key -> true);
    }

    public List<IvsRecordingConfiguration> listRecordingConfigurations() {
        return recordingConfigurations.scan(key -> true);
    }

    // Tags

    @Override
    public String serviceKey() {
        return "ivs";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = tagsOf(findByArn(arn));
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        Object resource = findByArn(arn);
        Map<String, String> existing = tagsOf(resource);
        existing.putAll(tags);
        validateTags(existing);
        persist(arn, resource);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        Object resource = findByArn(arn);
        Map<String, String> existing = tagsOf(resource);
        if (tagKeys != null) {
            tagKeys.forEach(existing::remove);
        }
        persist(arn, resource);
    }

    @Override
    public void clear() {
        channels.clear();
        keyPairs.clear();
        recordingConfigurations.clear();
    }

    private Object findByArn(String arn) {
        return channels.get(arn).<Object>map(channel -> channel)
                .or(() -> keyPairs.get(arn).map(keyPair -> keyPair))
                .or(() -> recordingConfigurations.get(arn).map(configuration -> configuration))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private Map<String, String> tagsOf(Object resource) {
        if (resource instanceof IvsChannel channel) {
            if (channel.getTags() == null) {
                channel.setTags(new HashMap<>());
            }
            return channel.getTags();
        }
        if (resource instanceof IvsPlaybackKeyPair keyPair) {
            if (keyPair.getTags() == null) {
                keyPair.setTags(new HashMap<>());
            }
            return keyPair.getTags();
        }
        IvsRecordingConfiguration configuration = (IvsRecordingConfiguration) resource;
        if (configuration.getTags() == null) {
            configuration.setTags(new HashMap<>());
        }
        return configuration.getTags();
    }

    private void persist(String arn, Object resource) {
        if (resource instanceof IvsChannel channel) {
            channels.put(arn, channel);
        } else if (resource instanceof IvsPlaybackKeyPair keyPair) {
            keyPairs.put(arn, keyPair);
        } else {
            recordingConfigurations.put(arn, (IvsRecordingConfiguration) resource);
        }
    }

    /**
     * The model documents preset as selectable only for the ADVANCED_HD and ADVANCED_SD channel
     * types, defaulting to HIGHER_BANDWIDTH_DELIVERY there and to the empty string for BASIC and
     * STANDARD.
     */
    private static String resolvePreset(String preset, String channelType) {
        boolean advanced = ADVANCED_CHANNEL_TYPES.contains(channelType);
        if (preset == null || preset.isEmpty()) {
            return advanced ? "HIGHER_BANDWIDTH_DELIVERY" : "";
        }
        if (!advanced) {
            throw new AwsException("ValidationException",
                    "preset is selectable only for the ADVANCED_SD and ADVANCED_HD channel types", 400);
        }
        if (!TRANSCODE_PRESETS.contains(preset)) {
            throw new AwsException("ValidationException",
                    "preset must be one of " + TRANSCODE_PRESETS, 400);
        }
        return preset;
    }

    /**
     * IVS models one and only one destination type per recording configuration, and its single
     * modelled destination (s3) requires bucketName.
     */
    private static void validateDestinationConfiguration(JsonNode destinationConfiguration) {
        if (destinationConfiguration == null || destinationConfiguration.isNull()
                || !destinationConfiguration.isObject()) {
            throw new AwsException("ValidationException", "destinationConfiguration is required", 400);
        }
        if (destinationConfiguration.size() != 1) {
            throw new AwsException("ValidationException",
                    "destinationConfiguration must define one and only one destination type", 400);
        }
        JsonNode s3 = destinationConfiguration.get("s3");
        if (s3 == null || !s3.isObject()) {
            throw new AwsException("ValidationException",
                    "destinationConfiguration supports only the s3 destination type", 400);
        }
        String bucketName = s3.path("bucketName").asText(null);
        if (bucketName == null || bucketName.isBlank()) {
            throw new AwsException("ValidationException",
                    "destinationConfiguration.s3.bucketName is required", 400);
        }
        if (bucketName.length() < MIN_BUCKET_NAME_LENGTH || bucketName.length() > MAX_BUCKET_NAME_LENGTH) {
            throw new AwsException("ValidationException",
                    "destinationConfiguration.s3.bucketName must be between " + MIN_BUCKET_NAME_LENGTH
                            + " and " + MAX_BUCKET_NAME_LENGTH + " characters", 400);
        }
    }

    /**
     * targetIntervalSeconds is configurable, and required, only when recordingMode is INTERVAL;
     * the model defaults recordingMode to INTERVAL and targetIntervalSeconds to 60, so an INTERVAL
     * thumbnail configuration that omits it comes back carrying the default.
     */
    private static JsonNode validateThumbnailConfiguration(JsonNode thumbnailConfiguration) {
        if (thumbnailConfiguration == null || thumbnailConfiguration.isNull()) {
            return null;
        }
        if (!thumbnailConfiguration.isObject()) {
            throw new AwsException("ValidationException", "thumbnailConfiguration must be an object", 400);
        }
        String recordingMode = thumbnailConfiguration.path("recordingMode").asText("INTERVAL");
        if (!THUMBNAIL_RECORDING_MODES.contains(recordingMode)) {
            throw new AwsException("ValidationException",
                    "thumbnailConfiguration.recordingMode must be one of " + THUMBNAIL_RECORDING_MODES, 400);
        }
        boolean hasTargetInterval = thumbnailConfiguration.hasNonNull("targetIntervalSeconds");
        if (!"INTERVAL".equals(recordingMode) && hasTargetInterval) {
            throw new AwsException("ValidationException",
                    "thumbnailConfiguration.targetIntervalSeconds is configurable only when "
                            + "recordingMode is INTERVAL", 400);
        }
        if (thumbnailConfiguration.hasNonNull("resolution")
                && !RESOLUTIONS.contains(thumbnailConfiguration.get("resolution").asText())) {
            throw new AwsException("ValidationException",
                    "thumbnailConfiguration.resolution must be one of " + RESOLUTIONS, 400);
        }
        JsonNode storage = thumbnailConfiguration.get("storage");
        if (storage != null && storage.isArray()) {
            for (JsonNode entry : storage) {
                if (!THUMBNAIL_STORAGE.contains(entry.asText())) {
                    throw new AwsException("ValidationException",
                            "thumbnailConfiguration.storage entries must be one of " + THUMBNAIL_STORAGE, 400);
                }
            }
        }

        com.fasterxml.jackson.databind.node.ObjectNode resolved =
                ((com.fasterxml.jackson.databind.node.ObjectNode) thumbnailConfiguration).deepCopy();
        resolved.put("recordingMode", recordingMode);
        if ("INTERVAL".equals(recordingMode)) {
            int targetInterval = hasTargetInterval
                    ? thumbnailConfiguration.get("targetIntervalSeconds").asInt()
                    : DEFAULT_TARGET_INTERVAL_SECONDS;
            if (targetInterval < MIN_TARGET_INTERVAL_SECONDS || targetInterval > MAX_TARGET_INTERVAL_SECONDS) {
                throw new AwsException("ValidationException",
                        "thumbnailConfiguration.targetIntervalSeconds must be between "
                                + MIN_TARGET_INTERVAL_SECONDS + " and " + MAX_TARGET_INTERVAL_SECONDS, 400);
            }
            resolved.put("targetIntervalSeconds", targetInterval);
        }
        if (!resolved.hasNonNull("storage")) {
            resolved.putArray("storage").add("SEQUENTIAL");
        }
        return resolved;
    }

    /** A CUSTOM renditionSelection requires an explicit set of renditions. */
    private static void validateRenditionConfiguration(JsonNode renditionConfiguration) {
        if (renditionConfiguration == null || renditionConfiguration.isNull()) {
            return;
        }
        if (!renditionConfiguration.isObject()) {
            throw new AwsException("ValidationException", "renditionConfiguration must be an object", 400);
        }
        String selection = renditionConfiguration.path("renditionSelection").asText("ALL");
        if (!RENDITION_SELECTIONS.contains(selection)) {
            throw new AwsException("ValidationException",
                    "renditionConfiguration.renditionSelection must be one of " + RENDITION_SELECTIONS, 400);
        }
        JsonNode renditions = renditionConfiguration.get("renditions");
        if ("CUSTOM".equals(selection) && (renditions == null || !renditions.isArray() || renditions.isEmpty())) {
            throw new AwsException("ValidationException",
                    "renditionConfiguration.renditions is required when renditionSelection is CUSTOM", 400);
        }
        if (renditions != null && renditions.isArray()) {
            for (JsonNode entry : renditions) {
                if (!RESOLUTIONS.contains(entry.asText())) {
                    throw new AwsException("ValidationException",
                            "renditionConfiguration.renditions entries must be one of " + RESOLUTIONS, 400);
                }
            }
        }
    }

    private static String enumOrDefault(String field, String value, Set<String> allowed, String fallback) {
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        if (!allowed.contains(value)) {
            throw new AwsException("ValidationException", field + " must be one of " + allowed, 400);
        }
        return value;
    }

    private static void validateName(String field, String name) {
        if (name == null) {
            return;
        }
        if (name.length() > MAX_NAME_LENGTH || !RESOURCE_NAME.matcher(name).matches()) {
            throw new AwsException("ValidationException",
                    field + " must match [a-zA-Z0-9-_]* and be at most " + MAX_NAME_LENGTH + " characters", 400);
        }
    }

    private static void validateTags(Map<String, String> tags) {
        if (tags != null && tags.size() > MAX_TAGS) {
            throw new AwsException("ValidationException",
                    "a resource accepts at most " + MAX_TAGS + " tags", 400);
        }
    }

    private static void requireArn(String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ValidationException", "arn is required", 400);
        }
    }

    private static String randomId() {
        StringBuilder id = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }

    private static String fingerprint(String publicKeyMaterial) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5")
                    .digest(publicKeyMaterial.getBytes(StandardCharsets.UTF_8));
            StringJoiner joiner = new StringJoiner(":");
            for (byte b : digest) {
                joiner.add(String.format("%02x", b));
            }
            return joiner.toString();
        } catch (Exception e) {
            LOG.error("Failed to compute IVS playback key fingerprint", e);
            throw new AwsException("InternalServerException", "Failed to compute key fingerprint", 500);
        }
    }
}
