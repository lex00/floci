package io.github.hectorvent.floci.services.fsx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.fsx.model.FsxFileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Amazon FSx file systems of every type. A file system is {@code AVAILABLE} as soon as it is
 * created and is gone once deleted; no file server runs behind it.
 */
@ApplicationScoped
public class FsxService {

    private static final Logger LOG = Logger.getLogger(FsxService.class);

    static final String LUSTRE = "LUSTRE";
    static final String WINDOWS = "WINDOWS";
    static final String ONTAP = "ONTAP";
    static final String OPENZFS = "OPENZFS";

    private static final Map<String, String> CONFIGURATION_MEMBERS = Map.of(
            LUSTRE, "LustreConfiguration",
            WINDOWS, "WindowsConfiguration",
            ONTAP, "OntapConfiguration",
            OPENZFS, "OpenZFSConfiguration");

    private static final Map<String, Integer> DEFAULT_STORAGE_CAPACITY = Map.of(
            LUSTRE, 1200, WINDOWS, 32, ONTAP, 1024, OPENZFS, 64);

    /** Members a CreateFileSystem configuration carries that its response shape does not. */
    private static final Set<String> CREATE_ONLY_MEMBERS = Set.of(
            "ImportPath", "ExportPath", "ImportedFileChunkSize", "AutoImportPolicy", "RootVolumeConfiguration");

    // region|fileSystemId -> file system
    private final StorageBackend<String, FsxFileSystem> fileSystems;
    private final Ec2Service ec2Service;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public FsxService(StorageFactory storageFactory, Ec2Service ec2Service, RegionResolver regionResolver,
                      ObjectMapper mapper) {
        this(storageFactory.create("fsx", "fsx-file-systems.json",
                new TypeReference<Map<String, FsxFileSystem>>() {}), ec2Service, regionResolver, mapper);
    }

    FsxService(StorageBackend<String, FsxFileSystem> fileSystems, Ec2Service ec2Service,
               RegionResolver regionResolver, ObjectMapper mapper) {
        this.fileSystems = fileSystems;
        this.ec2Service = ec2Service;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    public FsxFileSystem createFileSystem(JsonNode request, String region) {
        String type = text(request, "FileSystemType");
        if (type == null) {
            throw badRequest("FileSystemType is required.");
        }
        String configurationMember = CONFIGURATION_MEMBERS.get(type);
        if (configurationMember == null) {
            throw badRequest("Invalid FileSystemType: " + type);
        }
        for (Map.Entry<String, String> entry : CONFIGURATION_MEMBERS.entrySet()) {
            if (!entry.getKey().equals(type) && request.hasNonNull(entry.getValue())) {
                throw new AwsException("IncompatibleParameterError",
                        entry.getValue() + " is not valid for file system type " + type + ".", 400);
            }
        }
        List<String> subnetIds = strings(request.path("SubnetIds"));
        if (subnetIds.isEmpty()) {
            throw badRequest("SubnetIds is required.");
        }
        JsonNode requested = request.path(configurationMember);
        ObjectNode configuration = requested.isObject() ? ((ObjectNode) requested).deepCopy() : mapper.createObjectNode();
        String id = "fs-" + randomHex(17);
        String dnsName = id + ".fsx." + region + "." + AwsRegions.dnsSuffixFor(region);
        buildConfiguration(type, configuration, requested, id, dnsName, region);

        List<Subnet> subnets = ec2Service.describeSubnets(region, subnetIds, Map.of());
        if (subnets.size() != subnetIds.size()) {
            throw new AwsException("InvalidNetworkSettings",
                    "One or more of the subnets in " + subnetIds + " does not exist.", 400);
        }

        FsxFileSystem fileSystem = new FsxFileSystem();
        fileSystem.setFileSystemId(id);
        fileSystem.setOwnerId(regionResolver.getAccountId());
        fileSystem.setCreationTime(Instant.now().getEpochSecond());
        fileSystem.setFileSystemType(type);
        fileSystem.setStorageCapacity(request.hasNonNull("StorageCapacity")
                ? request.path("StorageCapacity").asInt() : DEFAULT_STORAGE_CAPACITY.get(type));
        fileSystem.setStorageType(request.hasNonNull("StorageType") ? text(request, "StorageType") : "SSD");
        fileSystem.setVpcId(subnets.getFirst().getVpcId());
        fileSystem.setSubnetIds(subnetIds);
        fileSystem.setSecurityGroupIds(strings(request.path("SecurityGroupIds")));
        fileSystem.setDnsName(dnsName);
        fileSystem.setKmsKeyId(text(request, "KmsKeyId"));
        fileSystem.setResourceArn(regionResolver.buildArn("fsx", region, "file-system/" + id));
        fileSystem.setFileSystemTypeVersion(text(request, "FileSystemTypeVersion"));
        fileSystem.setNetworkType(text(request, "NetworkType"));
        fileSystem.setRegion(region);
        fileSystem.setConfiguration(configuration);
        fileSystem.setTags(tagMap(request.path("Tags")));
        fileSystems.put(key(region, id), fileSystem);
        LOG.infov("Created FSx {0} file system {1}", type, id);
        return fileSystem;
    }

    private void buildConfiguration(String type, ObjectNode configuration, JsonNode requested, String id,
                                    String dnsName, String region) {
        switch (type) {
            case LUSTRE -> {
                String importPath = text(requested, "ImportPath");
                String exportPath = text(requested, "ExportPath");
                if (importPath != null && !importPath.startsWith("s3://")) {
                    throw new AwsException("InvalidImportPath", "The import path " + importPath
                            + " must be an S3 path starting with s3://.", 400);
                }
                if (exportPath != null && (!exportPath.startsWith("s3://") || importPath == null)) {
                    throw new AwsException("InvalidExportPath", "The export path " + exportPath
                            + " must be an S3 path starting with s3:// alongside an ImportPath.", 400);
                }
                configuration.put("DeploymentType", text(requested, "DeploymentType") != null
                        ? text(requested, "DeploymentType") : "SCRATCH_1");
                configuration.put("MountName", configuration.path("DeploymentType").asText().equals("SCRATCH_1")
                        ? "fsx" : randomAlphanumeric(8));
                if (importPath != null) {
                    ObjectNode repository = configuration.putObject("DataRepositoryConfiguration");
                    repository.put("Lifecycle", "AVAILABLE");
                    repository.put("ImportPath", importPath);
                    repository.put("ExportPath", exportPath != null ? exportPath
                            : importPath + "/FSxLustre" + Instant.now().toString().replaceAll("[^0-9]", "").substring(0, 14));
                    repository.put("ImportedFileChunkSize", requested.path("ImportedFileChunkSize").asInt(1024));
                    repository.put("AutoImportPolicy", text(requested, "AutoImportPolicy") != null
                            ? text(requested, "AutoImportPolicy") : "NONE");
                }
            }
            case WINDOWS -> {
                requireConfiguration(requested, "WindowsConfiguration", "ThroughputCapacity");
                configuration.put("DeploymentType", text(requested, "DeploymentType") != null
                        ? text(requested, "DeploymentType") : "SINGLE_AZ_1");
                configuration.put("RemoteAdministrationEndpoint", "amznfsx" + randomAlphanumeric(8) + "."
                        + dnsName.substring(id.length() + 1));
            }
            case ONTAP -> {
                requireConfiguration(requested, "OntapConfiguration", "DeploymentType");
                ObjectNode endpoints = configuration.putObject("Endpoints");
                endpoints.putObject("Intercluster").put("DNSName", "intercluster." + dnsName);
                endpoints.putObject("Management").put("DNSName", "management." + dnsName);
                configuration.remove("FsxAdminPassword");
            }
            case OPENZFS -> {
                requireConfiguration(requested, "OpenZFSConfiguration", "DeploymentType");
                requireConfiguration(requested, "OpenZFSConfiguration", "ThroughputCapacity");
                configuration.put("RootVolumeId", "fsvol-" + randomHex(17));
            }
            default -> throw badRequest("Invalid FileSystemType: " + type);
        }
        CREATE_ONLY_MEMBERS.forEach(configuration::remove);
        configuration.put("WeeklyMaintenanceStartTime", text(requested, "WeeklyMaintenanceStartTime") != null
                ? text(requested, "WeeklyMaintenanceStartTime") : "1:00:00");
        if (!LUSTRE.equals(type) || requested.has("AutomaticBackupRetentionDays")) {
            configuration.put("AutomaticBackupRetentionDays", requested.path("AutomaticBackupRetentionDays")
                    .asInt(LUSTRE.equals(type) ? 0 : 7));
        }
        LOG.debugv("Built {0} configuration for {1} in {2}", type, id, region);
    }

    private static void requireConfiguration(JsonNode requested, String member, String field) {
        if (!requested.isObject()) {
            throw new AwsException("MissingFileSystemConfiguration", member + " is required.", 400);
        }
        if (!requested.hasNonNull(field)) {
            throw badRequest(member + "." + field + " is required.");
        }
    }

    public FsxFileSystem getFileSystem(String fileSystemId, String region) {
        if (fileSystemId == null || fileSystemId.isBlank()) {
            throw badRequest("FileSystemId is required.");
        }
        return fileSystems.get(key(region, fileSystemId)).orElseThrow(() ->
                new AwsException("FileSystemNotFound", "File system '" + fileSystemId + "' does not exist.", 400));
    }

    public List<FsxFileSystem> describeFileSystems(List<String> ids, String region) {
        if (!ids.isEmpty()) {
            return ids.stream().map(id -> getFileSystem(id, region)).toList();
        }
        return fileSystems.scan(k -> k.startsWith(region + "|")).stream()
                .sorted(Comparator.comparing(FsxFileSystem::getCreationTime)
                        .thenComparing(FsxFileSystem::getFileSystemId))
                .toList();
    }

    public FsxFileSystem updateFileSystem(JsonNode request, String region) {
        FsxFileSystem fileSystem = getFileSystem(text(request, "FileSystemId"), region);
        String member = CONFIGURATION_MEMBERS.get(fileSystem.getFileSystemType());
        for (String other : CONFIGURATION_MEMBERS.values()) {
            if (!other.equals(member) && request.hasNonNull(other)) {
                throw new AwsException("IncompatibleParameterError",
                        other + " is not valid for file system type " + fileSystem.getFileSystemType() + ".", 400);
            }
        }
        if (request.hasNonNull("StorageCapacity")) {
            int capacity = request.path("StorageCapacity").asInt();
            if (fileSystem.getStorageCapacity() != null && capacity < fileSystem.getStorageCapacity()) {
                throw badRequest("StorageCapacity can only be increased.");
            }
            fileSystem.setStorageCapacity(capacity);
        }
        if (request.hasNonNull("StorageType")) {
            fileSystem.setStorageType(text(request, "StorageType"));
        }
        if (request.hasNonNull("FileSystemTypeVersion")) {
            fileSystem.setFileSystemTypeVersion(text(request, "FileSystemTypeVersion"));
        }
        JsonNode update = request.path(member);
        if (update.isObject()) {
            ObjectNode configuration = fileSystem.getConfiguration();
            Iterator<Map.Entry<String, JsonNode>> fields = update.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                switch (field.getKey()) {
                    case "AddRouteTableIds" -> mergeRouteTables(configuration, field.getValue(), true);
                    case "RemoveRouteTableIds" -> mergeRouteTables(configuration, field.getValue(), false);
                    case "FsxAdminPassword" -> LOG.debugv("FSx admin password updated for {0}",
                            fileSystem.getFileSystemId());
                    default -> configuration.set(field.getKey(), field.getValue().deepCopy());
                }
            }
        }
        fileSystems.put(key(region, fileSystem.getFileSystemId()), fileSystem);
        return fileSystem;
    }

    private void mergeRouteTables(ObjectNode configuration, JsonNode ids, boolean add) {
        ArrayNode current = configuration.has("RouteTableIds") && configuration.get("RouteTableIds").isArray()
                ? (ArrayNode) configuration.get("RouteTableIds") : configuration.putArray("RouteTableIds");
        List<String> values = strings(current);
        for (String id : strings(ids)) {
            if (add && !values.contains(id)) {
                values.add(id);
            } else if (!add) {
                values.remove(id);
            }
        }
        ArrayNode replaced = configuration.putArray("RouteTableIds");
        values.forEach(replaced::add);
    }

    public FsxFileSystem deleteFileSystem(String fileSystemId, String region) {
        FsxFileSystem fileSystem = getFileSystem(fileSystemId, region);
        fileSystems.delete(key(region, fileSystemId));
        fileSystem.setLifecycle("DELETING");
        LOG.infov("Deleted FSx file system {0}", fileSystemId);
        return fileSystem;
    }

    public Map<String, String> listTags(String resourceArn, String region) {
        return findByArn(resourceArn, region).getTags();
    }

    public void tagResource(String resourceArn, Map<String, String> tags, String region) {
        FsxFileSystem fileSystem = findByArn(resourceArn, region);
        fileSystem.getTags().putAll(tags);
        fileSystems.put(key(region, fileSystem.getFileSystemId()), fileSystem);
    }

    public void untagResource(String resourceArn, List<String> keys, String region) {
        FsxFileSystem fileSystem = findByArn(resourceArn, region);
        keys.forEach(fileSystem.getTags()::remove);
        fileSystems.put(key(region, fileSystem.getFileSystemId()), fileSystem);
    }

    private FsxFileSystem findByArn(String resourceArn, String region) {
        if (resourceArn == null || resourceArn.isBlank()) {
            throw badRequest("ResourceARN is required.");
        }
        Optional<FsxFileSystem> found = fileSystems.scan(k -> k.startsWith(region + "|")).stream()
                .filter(fs -> resourceArn.equals(fs.getResourceArn()))
                .findFirst();
        return found.orElseThrow(() -> new AwsException("ResourceNotFound",
                "The resource ARN " + resourceArn + " does not exist.", 400));
    }

    static Map<String, String> tagMap(JsonNode tags) {
        Map<String, String> result = new LinkedHashMap<>();
        if (tags != null && tags.isArray()) {
            for (JsonNode tag : tags) {
                String tagKey = tag.path("Key").asText(null);
                if (tagKey == null || tagKey.isBlank()) {
                    throw badRequest("Every tag needs a Key.");
                }
                result.put(tagKey, tag.path("Value").asText(""));
            }
        }
        return result;
    }

    static List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> values.add(value.asText()));
        }
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequest", message, 400);
    }

    private static String key(String region, String fileSystemId) {
        return region + "|" + fileSystemId;
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit(random.nextInt(16), 16));
        }
        return sb.toString();
    }

    private static String randomAlphanumeric(int length) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
