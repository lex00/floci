package io.github.hectorvent.floci.services.ivschat;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ivschat.model.IvschatLoggingConfiguration;
import io.github.hectorvent.floci.services.ivschat.model.IvschatRoom;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Amazon IVS Chat management plane. Logging configurations are ACTIVE as soon as a create
 * returns, so SDK and Terraform waiters complete on their first poll. The chat message data
 * plane is not emulated.
 */
@ApplicationScoped
public class IvschatService implements TagHandler, Resettable {

    private static final Logger LOG = Logger.getLogger(IvschatService.class);
    private static final String ID_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    // RoomID and LoggingConfigurationID are both exactly 12 characters of [a-zA-Z0-9].
    private static final int ID_LENGTH = 12;
    private static final Pattern RESOURCE_NAME = Pattern.compile("[a-zA-Z0-9\\-_]*");
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_TAGS = 50;

    private static final int DEFAULT_MESSAGE_RATE_PER_SECOND = 10;
    private static final int MIN_MESSAGE_RATE_PER_SECOND = 1;
    private static final int MAX_MESSAGE_RATE_PER_SECOND = 100;
    private static final int DEFAULT_MESSAGE_LENGTH = 500;
    private static final int MIN_MESSAGE_LENGTH = 1;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final int MAX_LOGGING_CONFIGURATION_IDENTIFIERS = 3;

    private static final Set<String> FALLBACK_RESULTS = Set.of("ALLOW", "DENY");
    private static final Set<String> DESTINATION_TYPES = Set.of("s3", "cloudWatchLogs", "firehose");
    private static final Map<String, String> DESTINATION_REQUIRED_FIELD = Map.of(
            "s3", "bucketName",
            "cloudWatchLogs", "logGroupName",
            "firehose", "deliveryStreamName");

    private final StorageBackend<String, IvschatRoom> rooms;
    private final StorageBackend<String, IvschatLoggingConfiguration> loggingConfigurations;
    private final RegionResolver regionResolver;

    @Inject
    public IvschatService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this.rooms = storageFactory.create("ivschat", "ivschat-rooms.json",
                new TypeReference<Map<String, IvschatRoom>>() {});
        this.loggingConfigurations = storageFactory.create("ivschat", "ivschat-logging-configurations.json",
                new TypeReference<Map<String, IvschatLoggingConfiguration>>() {});
        this.regionResolver = regionResolver;
    }

    // Rooms

    public IvschatRoom createRoom(String name, Integer maximumMessageRatePerSecond,
                                  Integer maximumMessageLength, JsonNode messageReviewHandler,
                                  List<String> loggingConfigurationIdentifiers,
                                  Map<String, String> tags, String region) {
        validateName("name", name);
        validateTags(tags);
        int messageRate = boundedOrDefault("maximumMessageRatePerSecond", maximumMessageRatePerSecond,
                MIN_MESSAGE_RATE_PER_SECOND, MAX_MESSAGE_RATE_PER_SECOND, DEFAULT_MESSAGE_RATE_PER_SECOND);
        int messageLength = boundedOrDefault("maximumMessageLength", maximumMessageLength,
                MIN_MESSAGE_LENGTH, MAX_MESSAGE_LENGTH, DEFAULT_MESSAGE_LENGTH);
        JsonNode resolvedHandler = resolveMessageReviewHandler(messageReviewHandler);
        List<String> identifiers = validateLoggingConfigurationIdentifiers(loggingConfigurationIdentifiers);

        String roomId = randomId();
        IvschatRoom room = new IvschatRoom();
        room.setId(roomId);
        room.setArn(regionResolver.buildArn("ivschat", region, "room/" + roomId));
        room.setName(name != null ? name : "");
        Instant now = Instant.now();
        room.setCreateTime(now);
        room.setUpdateTime(now);
        room.setMaximumMessageRatePerSecond(messageRate);
        room.setMaximumMessageLength(messageLength);
        room.setMessageReviewHandler(resolvedHandler);
        room.setLoggingConfigurationIdentifiers(identifiers);
        room.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        room.setAccountId(regionResolver.getAccountId());

        rooms.put(room.getArn(), room);
        LOG.infov("Created IVS Chat room: {0}", room.getArn());
        return room;
    }

    public IvschatRoom getRoom(String identifier, String region) {
        return findRoom(identifier)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Room " + identifier + " does not exist.", 404));
    }

    public void deleteRoom(String identifier, String region) {
        IvschatRoom room = getRoom(identifier, region);
        rooms.delete(room.getArn());
        LOG.infov("Deleted IVS Chat room: {0}", room.getArn());
    }

    private Optional<IvschatRoom> findRoom(String identifier) {
        requireIdentifier(identifier);
        if (identifier.startsWith("arn:")) {
            return rooms.get(identifier);
        }
        return rooms.scan(key -> true).stream()
                .filter(room -> identifier.equals(room.getId()))
                .findFirst();
    }

    // Logging configurations

    public IvschatLoggingConfiguration createLoggingConfiguration(String name, JsonNode destinationConfiguration,
                                                                  Map<String, String> tags, String region) {
        validateName("name", name);
        validateTags(tags);
        validateDestinationConfiguration(destinationConfiguration);

        String configurationId = randomId();
        IvschatLoggingConfiguration configuration = new IvschatLoggingConfiguration();
        configuration.setId(configurationId);
        configuration.setArn(regionResolver.buildArn("ivschat", region, "logging-configuration/" + configurationId));
        configuration.setName(name != null ? name : "");
        Instant now = Instant.now();
        configuration.setCreateTime(now);
        configuration.setUpdateTime(now);
        configuration.setDestinationConfiguration(destinationConfiguration);
        configuration.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        configuration.setAccountId(regionResolver.getAccountId());

        loggingConfigurations.put(configuration.getArn(), configuration);
        LOG.infov("Created IVS Chat logging configuration: {0}", configuration.getArn());
        return configuration;
    }

    public IvschatLoggingConfiguration getLoggingConfiguration(String identifier, String region) {
        return findLoggingConfiguration(identifier)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "LoggingConfiguration " + identifier + " does not exist.", 404));
    }

    public void deleteLoggingConfiguration(String identifier, String region) {
        IvschatLoggingConfiguration configuration = getLoggingConfiguration(identifier, region);
        loggingConfigurations.delete(configuration.getArn());
        LOG.infov("Deleted IVS Chat logging configuration: {0}", configuration.getArn());
    }

    private Optional<IvschatLoggingConfiguration> findLoggingConfiguration(String identifier) {
        requireIdentifier(identifier);
        if (identifier.startsWith("arn:")) {
            return loggingConfigurations.get(identifier);
        }
        return loggingConfigurations.scan(key -> true).stream()
                .filter(configuration -> identifier.equals(configuration.getId()))
                .findFirst();
    }

    // Listings

    public List<IvschatRoom> listRooms() {
        return rooms.scan(key -> true);
    }

    public List<IvschatLoggingConfiguration> listLoggingConfigurations() {
        return loggingConfigurations.scan(key -> true);
    }

    // Tags

    @Override
    public String serviceKey() {
        return "ivschat";
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
        rooms.clear();
        loggingConfigurations.clear();
    }

    private Object findByArn(String arn) {
        return rooms.get(arn).<Object>map(room -> room)
                .or(() -> loggingConfigurations.get(arn).map(configuration -> configuration))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private Map<String, String> tagsOf(Object resource) {
        if (resource instanceof IvschatRoom room) {
            if (room.getTags() == null) {
                room.setTags(new HashMap<>());
            }
            return room.getTags();
        }
        IvschatLoggingConfiguration configuration = (IvschatLoggingConfiguration) resource;
        if (configuration.getTags() == null) {
            configuration.setTags(new HashMap<>());
        }
        return configuration.getTags();
    }

    private void persist(String arn, Object resource) {
        if (resource instanceof IvschatRoom room) {
            rooms.put(arn, room);
        } else {
            loggingConfigurations.put(arn, (IvschatLoggingConfiguration) resource);
        }
    }

    /**
     * A logging configuration names one and only one destination, and each destination type has
     * its own required field.
     */
    private static void validateDestinationConfiguration(JsonNode destinationConfiguration) {
        if (destinationConfiguration == null || destinationConfiguration.isNull()
                || !destinationConfiguration.isObject()) {
            throw new AwsException("ValidationException", "destinationConfiguration is required", 400);
        }
        if (destinationConfiguration.size() != 1) {
            throw new AwsException("ValidationException",
                    "destinationConfiguration must define one and only one of " + DESTINATION_TYPES, 400);
        }
        String type = destinationConfiguration.fieldNames().next();
        if (!DESTINATION_TYPES.contains(type)) {
            throw new AwsException("ValidationException",
                    "destinationConfiguration must define one and only one of " + DESTINATION_TYPES, 400);
        }
        JsonNode destination = destinationConfiguration.get(type);
        String requiredField = DESTINATION_REQUIRED_FIELD.get(type);
        if (destination == null || !destination.isObject() || !destination.hasNonNull(requiredField)
                || destination.get(requiredField).asText().isBlank()) {
            throw new AwsException("ValidationException",
                    "destinationConfiguration." + type + "." + requiredField + " is required", 400);
        }
    }

    /** fallbackResult defaults to ALLOW, so a handler supplied without one comes back carrying it. */
    private static JsonNode resolveMessageReviewHandler(JsonNode messageReviewHandler) {
        if (messageReviewHandler == null || messageReviewHandler.isNull()) {
            return null;
        }
        if (!messageReviewHandler.isObject()) {
            throw new AwsException("ValidationException", "messageReviewHandler must be an object", 400);
        }
        String fallbackResult = messageReviewHandler.path("fallbackResult").asText("ALLOW");
        if (!FALLBACK_RESULTS.contains(fallbackResult)) {
            throw new AwsException("ValidationException",
                    "messageReviewHandler.fallbackResult must be one of " + FALLBACK_RESULTS, 400);
        }
        ObjectNode resolved = ((ObjectNode) messageReviewHandler).deepCopy();
        resolved.put("fallbackResult", fallbackResult);
        return resolved;
    }

    private static List<String> validateLoggingConfigurationIdentifiers(List<String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return List.of();
        }
        if (identifiers.size() > MAX_LOGGING_CONFIGURATION_IDENTIFIERS) {
            throw new AwsException("ValidationException",
                    "loggingConfigurationIdentifiers accepts at most "
                            + MAX_LOGGING_CONFIGURATION_IDENTIFIERS + " entries", 400);
        }
        for (String identifier : identifiers) {
            if (identifier == null || identifier.isBlank()) {
                throw new AwsException("ValidationException",
                        "loggingConfigurationIdentifiers entries must not be empty", 400);
            }
        }
        return List.copyOf(identifiers);
    }

    private static int boundedOrDefault(String field, Integer value, int min, int max, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value < min || value > max) {
            throw new AwsException("ValidationException",
                    field + " must be between " + min + " and " + max, 400);
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

    private static void requireIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new AwsException("ValidationException", "identifier is required", 400);
        }
    }

    private static String randomId() {
        StringBuilder id = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            id.append(ID_ALPHABET.charAt(RANDOM.nextInt(ID_ALPHABET.length())));
        }
        return id.toString();
    }
}
