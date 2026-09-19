package io.github.hectorvent.floci.services.medialive;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.TagHandler;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.medialive.model.MediaLiveMultiplex;
import io.github.hectorvent.floci.services.medialive.model.MediaLiveMultiplexProgram;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AWS Elemental MediaLive management plane, limited to multiplexes and multiplex
 * programs. A multiplex is IDLE as soon as a create returns, so SDK and Terraform
 * waiters complete on their first poll. A deleted multiplex stays readable in state
 * DELETED because the SDK's MultiplexDeleted waiter polls DescribeMultiplex for that
 * state rather than treating NotFound as success. Channels, inputs and the video
 * transport data plane are not emulated.
 */
@ApplicationScoped
public class MediaLiveService implements TagHandler {

    private static final Logger LOG = Logger.getLogger(MediaLiveService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final long TRANSPORT_STREAM_BITRATE_MIN = 1_000_000L;
    private static final long TRANSPORT_STREAM_BITRATE_MAX = 100_000_000L;
    private static final long TRANSPORT_STREAM_ID_MIN = 0L;
    private static final long TRANSPORT_STREAM_ID_MAX = 65_535L;
    private static final long PROGRAM_NUMBER_MIN = 0L;
    private static final long PROGRAM_NUMBER_MAX = 65_535L;
    private static final int REQUIRED_AVAILABILITY_ZONES = 2;

    private final StorageBackend<String, MediaLiveMultiplex> multiplexes;
    private final StorageBackend<String, MediaLiveMultiplexProgram> programs;
    private final RegionResolver regionResolver;

    @Inject
    public MediaLiveService(StorageFactory storageFactory, RegionResolver regionResolver) {
        this(storageFactory.create("medialive", "medialive-multiplexes.json",
                        new TypeReference<Map<String, MediaLiveMultiplex>>() {}),
                storageFactory.create("medialive", "medialive-multiplex-programs.json",
                        new TypeReference<Map<String, MediaLiveMultiplexProgram>>() {}),
                regionResolver);
    }

    MediaLiveService(StorageBackend<String, MediaLiveMultiplex> multiplexes,
                     StorageBackend<String, MediaLiveMultiplexProgram> programs,
                     RegionResolver regionResolver) {
        this.multiplexes = multiplexes;
        this.programs = programs;
        this.regionResolver = regionResolver;
    }

    /**
     * CreateMultiplex. The model marks RequestId, Name, AvailabilityZones and
     * MultiplexSettings required, says AvailabilityZones must hold exactly two, and
     * describes RequestId as the token that prevents a retry from creating a second
     * multiplex, so a repeat of the same RequestId returns the multiplex it created.
     */
    public MediaLiveMultiplex createMultiplex(String requestId, String name,
                                              List<String> availabilityZones,
                                              JsonNode multiplexSettings, Map<String, String> tags,
                                              String region) {
        requireText(requestId, "requestId");
        requireText(name, "name");
        if (availabilityZones == null || availabilityZones.size() != REQUIRED_AVAILABILITY_ZONES) {
            throw badRequest("availabilityZones must contain exactly two availability zones");
        }
        validateMultiplexSettings(multiplexSettings);

        Optional<MediaLiveMultiplex> alreadyCreated = findByRequestId(requestId);
        if (alreadyCreated.isPresent()) {
            LOG.infov("Returning existing MediaLive multiplex for requestId {0}", requestId);
            return alreadyCreated.get();
        }

        String id = numericId();
        MediaLiveMultiplex multiplex = new MediaLiveMultiplex();
        multiplex.setId(id);
        multiplex.setArn(regionResolver.buildArn("medialive", region, "multiplex:" + id));
        multiplex.setName(name);
        multiplex.setAvailabilityZones(new ArrayList<>(availabilityZones));
        multiplex.setMultiplexSettings(multiplexSettings);
        multiplex.setState("IDLE");
        multiplex.setTags(tags != null ? new HashMap<>(tags) : new HashMap<>());
        multiplex.setAccountId(regionResolver.getAccountId());
        multiplex.setRequestId(requestId);

        multiplexes.put(id, multiplex);
        LOG.infov("Created MediaLive multiplex: {0}", multiplex.getArn());
        return multiplex;
    }

    public MediaLiveMultiplex getMultiplex(String id) {
        return multiplexes.get(id)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Multiplex " + id + " not found", 404));
    }

    /**
     * DeleteMultiplex. The operation documentation states the multiplex must be idle,
     * so a multiplex in any other state (including one already soft-deleted into
     * DELETED) is refused with ConflictException.
     */
    public MediaLiveMultiplex deleteMultiplex(String id) {
        MediaLiveMultiplex multiplex = getMultiplex(id);
        if (!"IDLE".equals(multiplex.getState())) {
            throw new AwsException("ConflictException",
                    "Multiplex " + id + " must be idle before it can be deleted", 409);
        }
        multiplex.setState("DELETED");
        multiplexes.put(id, multiplex);
        LOG.infov("Deleted MediaLive multiplex: {0}", id);
        return multiplex;
    }

    public int programCount(String multiplexId) {
        return programs.scan(k -> k.startsWith(multiplexId + "/")).size();
    }

    /**
     * Every multiplex except those in state DELETED. A deleted multiplex stays
     * readable for the MultiplexDeleted waiter but must not be enumerated as live.
     */
    public List<MediaLiveMultiplex> listMultiplexes() {
        return multiplexes.scan(k -> true).stream()
                .filter(m -> !"DELETED".equals(m.getState()))
                .toList();
    }

    /**
     * CreateMultiplexProgram. MultiplexId, RequestId, ProgramName and
     * MultiplexProgramSettings are required, MultiplexProgramSettings requires
     * ProgramNumber, a ServiceDescriptor requires both ProviderName and ServiceName,
     * and VideoSettings may carry ConstantBitrate or StatmuxSettings but not both.
     */
    public MediaLiveMultiplexProgram createProgram(String multiplexId, String requestId,
                                                   String programName,
                                                   JsonNode multiplexProgramSettings) {
        getMultiplex(multiplexId);
        requireText(requestId, "requestId");
        requireText(programName, "programName");
        validateProgramSettings(multiplexProgramSettings);

        Optional<MediaLiveMultiplexProgram> alreadyCreated = programs.scan(k -> true).stream()
                .filter(p -> requestId.equals(p.getRequestId()))
                .findFirst();
        if (alreadyCreated.isPresent()) {
            LOG.infov("Returning existing MediaLive multiplex program for requestId {0}", requestId);
            return alreadyCreated.get();
        }

        MediaLiveMultiplexProgram program = new MediaLiveMultiplexProgram();
        program.setMultiplexId(multiplexId);
        program.setProgramName(programName);
        program.setMultiplexProgramSettings(multiplexProgramSettings);
        program.setRequestId(requestId);

        programs.put(programKey(multiplexId, programName), program);
        LOG.infov("Created MediaLive multiplex program: {0}/{1}", multiplexId, programName);
        return program;
    }

    public MediaLiveMultiplexProgram getProgram(String multiplexId, String programName) {
        return programs.get(programKey(multiplexId, programName))
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Program " + programName + " not found in multiplex " + multiplexId, 404));
    }

    public MediaLiveMultiplexProgram deleteProgram(String multiplexId, String programName) {
        MediaLiveMultiplexProgram program = getProgram(multiplexId, programName);
        programs.delete(programKey(multiplexId, programName));
        LOG.infov("Deleted MediaLive multiplex program: {0}/{1}", multiplexId, programName);
        return program;
    }

    private void validateMultiplexSettings(JsonNode settings) {
        if (settings == null || !settings.isObject()) {
            throw badRequest("multiplexSettings is required");
        }
        requireIntInRange(settings, "transportStreamBitrate",
                TRANSPORT_STREAM_BITRATE_MIN, TRANSPORT_STREAM_BITRATE_MAX);
        requireIntInRange(settings, "transportStreamId",
                TRANSPORT_STREAM_ID_MIN, TRANSPORT_STREAM_ID_MAX);
    }

    private void validateProgramSettings(JsonNode settings) {
        if (settings == null || !settings.isObject()) {
            throw badRequest("multiplexProgramSettings is required");
        }
        requireIntInRange(settings, "programNumber", PROGRAM_NUMBER_MIN, PROGRAM_NUMBER_MAX);

        JsonNode serviceDescriptor = settings.get("serviceDescriptor");
        if (serviceDescriptor != null && serviceDescriptor.isObject()) {
            requireText(textOrNull(serviceDescriptor, "providerName"),
                    "multiplexProgramSettings.serviceDescriptor.providerName");
            requireText(textOrNull(serviceDescriptor, "serviceName"),
                    "multiplexProgramSettings.serviceDescriptor.serviceName");
        }

        JsonNode videoSettings = settings.get("videoSettings");
        if (videoSettings != null && videoSettings.isObject()
                && videoSettings.hasNonNull("constantBitrate")
                && videoSettings.hasNonNull("statmuxSettings")) {
            throw badRequest("multiplexProgramSettings.videoSettings may set constantBitrate "
                    + "or statmuxSettings, not both");
        }
    }

    private void requireIntInRange(JsonNode parent, String field, long min, long max) {
        JsonNode value = parent.get(field);
        if (value == null || value.isNull()) {
            throw badRequest(field + " is required");
        }
        if (!value.isIntegralNumber()) {
            throw badRequest(field + " must be an integer");
        }
        long number = value.asLong();
        if (number < min || number > max) {
            throw badRequest(field + " must be between " + min + " and " + max);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequestException", message, 400);
    }

    private Optional<MediaLiveMultiplex> findByRequestId(String requestId) {
        return multiplexes.scan(k -> true).stream()
                .filter(m -> requestId.equals(m.getRequestId()))
                .findFirst();
    }

    private static String programKey(String multiplexId, String programName) {
        return multiplexId + "/" + programName;
    }

    // MediaLive's tag operations live under the service's own /prod/tags path, which
    // MediaLiveController routes here directly. Registering as a TagHandler additionally
    // lets the shared /tags dispatcher resolve medialive ARNs, at no cost.

    @Override
    public String serviceKey() {
        return "medialive";
    }

    @Override
    public Map<String, String> listTags(String region, String arn) {
        Map<String, String> tags = findByArn(arn).getTags();
        return tags != null ? tags : Map.of();
    }

    @Override
    public void tagResource(String region, String arn, Map<String, String> tags) {
        MediaLiveMultiplex multiplex = findByArn(arn);
        if (multiplex.getTags() == null) {
            multiplex.setTags(new HashMap<>());
        }
        multiplex.getTags().putAll(tags);
        multiplexes.put(multiplex.getId(), multiplex);
    }

    @Override
    public void untagResource(String region, String arn, List<String> tagKeys) {
        MediaLiveMultiplex multiplex = findByArn(arn);
        if (multiplex.getTags() != null && tagKeys != null) {
            tagKeys.forEach(multiplex.getTags()::remove);
        }
        multiplexes.put(multiplex.getId(), multiplex);
    }

    private MediaLiveMultiplex findByArn(String arn) {
        return multiplexes.scan(k -> true).stream()
                .filter(m -> arn.equals(m.getArn()))
                .findFirst()
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Resource " + arn + " does not exist.", 404));
    }

    private static String numericId() {
        StringBuilder id = new StringBuilder(7);
        id.append(1 + RANDOM.nextInt(9));
        for (int i = 1; i < 7; i++) {
            id.append(RANDOM.nextInt(10));
        }
        return id.toString();
    }
}
