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
import io.github.hectorvent.floci.services.fsx.model.FsxFileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * FSx volumes, storage virtual machines, backups and data repository associations. Each is kept in
 * its response shape and reaches its terminal lifecycle state when created; no file server backs
 * any of them.
 */
@ApplicationScoped
public class FsxResourceService {

    private static final Logger LOG = Logger.getLogger(FsxResourceService.class);

    static final String VOLUME = "volume";
    static final String SVM = "storage-virtual-machine";
    static final String BACKUP = "backup";
    static final String ASSOCIATION = "association";

    private static final Set<String> VOLUME_TYPES = Set.of(FsxService.ONTAP, FsxService.OPENZFS);

    // region|kind|id -> resource in its response shape
    private final StorageBackend<String, ObjectNode> resources;
    private final FsxService fileSystems;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;

    @Inject
    public FsxResourceService(StorageFactory storageFactory, FsxService fileSystems, RegionResolver regionResolver,
                              ObjectMapper mapper) {
        this(storageFactory.create("fsx", "fsx-resources.json", new TypeReference<Map<String, ObjectNode>>() {}),
                fileSystems, regionResolver, mapper);
    }

    FsxResourceService(StorageBackend<String, ObjectNode> resources, FsxService fileSystems,
                       RegionResolver regionResolver, ObjectMapper mapper) {
        this.resources = resources;
        this.fileSystems = fileSystems;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    // ── Storage virtual machines ─────────────────────────────────────────────

    public ObjectNode createStorageVirtualMachine(JsonNode request, String region) {
        FsxFileSystem fileSystem = fileSystems.getFileSystem(text(request, "FileSystemId"), region);
        if (!FsxService.ONTAP.equals(fileSystem.getFileSystemType())) {
            throw badRequest("Storage virtual machines are only supported on ONTAP file systems.");
        }
        String name = required(request, "Name");
        String id = "svm-" + randomHex(17);
        String host = id + "." + fileSystem.getFileSystemId() + ".fsx." + region + "." + AwsRegions.dnsSuffixFor(region);
        ObjectNode svm = mapper.createObjectNode();
        JsonNode directory = request.path("ActiveDirectoryConfiguration");
        if (directory.isObject()) {
            ObjectNode stored = ((ObjectNode) directory).deepCopy();
            if (stored.path("SelfManagedActiveDirectoryConfiguration").isObject()) {
                ((ObjectNode) stored.get("SelfManagedActiveDirectoryConfiguration")).remove("Password");
            }
            svm.set("ActiveDirectoryConfiguration", stored);
        }
        svm.put("CreationTime", Instant.now().getEpochSecond());
        ObjectNode endpoints = svm.putObject("Endpoints");
        for (String protocol : directory.isObject()
                ? List.of("Iscsi", "Management", "Nfs", "Smb") : List.of("Iscsi", "Management", "Nfs")) {
            ObjectNode endpoint = endpoints.putObject(protocol);
            endpoint.put("DNSName", protocol.toLowerCase() + "." + host);
            endpoint.putArray("IpAddresses").add(randomPrivateIp());
        }
        svm.put("FileSystemId", fileSystem.getFileSystemId());
        svm.put("Lifecycle", "CREATED");
        svm.put("Name", name);
        svm.put("ResourceARN", regionResolver.buildArn("fsx", region,
                SVM + "/" + fileSystem.getFileSystemId() + "/" + id));
        svm.put("StorageVirtualMachineId", id);
        svm.put("Subtype", "DEFAULT");
        svm.put("UUID", UUID.randomUUID().toString());
        svm.set("Tags", tags(request.path("Tags")));
        svm.put("RootVolumeSecurityStyle", text(request, "RootVolumeSecurityStyle") != null
                ? text(request, "RootVolumeSecurityStyle") : "UNIX");
        put(region, SVM, id, svm);

        ObjectNode rootVolume = ontapVolume(fileSystem.getFileSystemId(), id, name + "_root", region, true,
                mapper.createObjectNode().put("SecurityStyle", svm.path("RootVolumeSecurityStyle").asText())
                        .put("JunctionPath", "/"), mapper.createArrayNode());
        put(region, VOLUME, rootVolume.path("VolumeId").asText(), rootVolume);
        LOG.infov("Created FSx storage virtual machine {0} on {1}", id, fileSystem.getFileSystemId());
        return svm;
    }

    public List<ObjectNode> describeStorageVirtualMachines(JsonNode request, String region) {
        return describe(region, SVM, FsxService.strings(request.path("StorageVirtualMachineIds")),
                "StorageVirtualMachineNotFound", "Storage virtual machine", filter(request.path("Filters"), Map.of(
                        "file-system-id", "FileSystemId")));
    }

    public ObjectNode updateStorageVirtualMachine(JsonNode request, String region) {
        String id = text(request, "StorageVirtualMachineId");
        ObjectNode svm = get(region, SVM, id, "StorageVirtualMachineNotFound", "Storage virtual machine");
        JsonNode directory = request.path("ActiveDirectoryConfiguration");
        if (directory.isObject()) {
            ObjectNode current = svm.has("ActiveDirectoryConfiguration")
                    ? (ObjectNode) svm.get("ActiveDirectoryConfiguration") : svm.putObject("ActiveDirectoryConfiguration");
            merge(current, (ObjectNode) directory, Set.of("Password"));
        }
        put(region, SVM, id, svm);
        return svm;
    }

    public ObjectNode deleteStorageVirtualMachine(String id, String region) {
        ObjectNode svm = get(region, SVM, id, "StorageVirtualMachineNotFound", "Storage virtual machine");
        List<ObjectNode> volumes = scan(region, VOLUME, volume ->
                id.equals(volume.path("OntapConfiguration").path("StorageVirtualMachineId").asText(null)));
        if (volumes.stream().anyMatch(volume ->
                !volume.path("OntapConfiguration").path("StorageVirtualMachineRoot").asBoolean())) {
            throw badRequest("Storage virtual machine " + id + " has volumes. Delete them first.");
        }
        volumes.forEach(volume -> resources.delete(key(region, VOLUME, volume.path("VolumeId").asText())));
        resources.delete(key(region, SVM, id));
        svm.put("Lifecycle", "DELETING");
        return svm;
    }

    // ── Volumes ──────────────────────────────────────────────────────────────

    public ObjectNode createVolume(JsonNode request, String region) {
        String type = required(request, "VolumeType");
        if (!VOLUME_TYPES.contains(type)) {
            throw badRequest("Invalid VolumeType: " + type);
        }
        String name = required(request, "Name");
        String member = FsxService.ONTAP.equals(type) ? "OntapConfiguration" : "OpenZFSConfiguration";
        String other = FsxService.ONTAP.equals(type) ? "OpenZFSConfiguration" : "OntapConfiguration";
        if (request.hasNonNull(other)) {
            throw new AwsException("IncompatibleParameterError",
                    other + " is not valid for volume type " + type + ".", 400);
        }
        JsonNode configuration = request.path(member);
        if (!configuration.isObject()) {
            throw new AwsException("MissingVolumeConfiguration", member + " is required.", 400);
        }
        ObjectNode volume;
        if (FsxService.ONTAP.equals(type)) {
            String svmId = required(configuration, "StorageVirtualMachineId");
            ObjectNode svm = get(region, SVM, svmId, "StorageVirtualMachineNotFound", "Storage virtual machine");
            volume = ontapVolume(svm.path("FileSystemId").asText(), svmId, name, region, false,
                    (ObjectNode) configuration, tags(request.path("Tags")));
        } else {
            String parentId = required(configuration, "ParentVolumeId");
            ObjectNode parent = get(region, VOLUME, parentId, "VolumeNotFound", "Volume");
            if (!FsxService.OPENZFS.equals(parent.path("VolumeType").asText())) {
                throw badRequest("ParentVolumeId must name an OpenZFS volume.");
            }
            volume = openZfsVolume(parent.path("FileSystemId").asText(), name, region,
                    parent.path("OpenZFSConfiguration").path("VolumePath").asText() + "/" + name,
                    (ObjectNode) configuration, tags(request.path("Tags")));
        }
        put(region, VOLUME, volume.path("VolumeId").asText(), volume);
        LOG.infov("Created FSx {0} volume {1}", type, volume.path("VolumeId").asText());
        return volume;
    }

    /** The root volume an OpenZFS file system is created with, which DescribeVolumes lists. */
    public void createRootVolume(FsxFileSystem fileSystem, String region) {
        String rootId = fileSystem.getConfiguration().path("RootVolumeId").asText(null);
        if (rootId == null) {
            return;
        }
        ObjectNode configuration = mapper.createObjectNode();
        JsonNode requested = fileSystem.getConfiguration().path("RootVolumeConfiguration");
        if (requested.isObject()) {
            configuration.setAll((ObjectNode) requested.deepCopy());
        }
        ObjectNode volume = openZfsVolume(fileSystem.getFileSystemId(), "fsx", region, "/fsx", configuration,
                mapper.createArrayNode());
        volume.put("VolumeId", rootId);
        volume.put("ResourceARN", regionResolver.buildArn("fsx", region,
                VOLUME + "/" + fileSystem.getFileSystemId() + "/" + rootId));
        ((ObjectNode) volume.get("OpenZFSConfiguration")).remove("ParentVolumeId");
        put(region, VOLUME, rootId, volume);
    }

    public List<ObjectNode> describeVolumes(JsonNode request, String region) {
        return describe(region, VOLUME, FsxService.strings(request.path("VolumeIds")), "VolumeNotFound", "Volume",
                filter(request.path("Filters"), Map.of(
                        "file-system-id", "FileSystemId",
                        "storage-virtual-machine-id", "OntapConfiguration.StorageVirtualMachineId")));
    }

    public ObjectNode updateVolume(JsonNode request, String region) {
        String id = text(request, "VolumeId");
        ObjectNode volume = get(region, VOLUME, id, "VolumeNotFound", "Volume");
        if (request.hasNonNull("Name")) {
            volume.put("Name", text(request, "Name"));
        }
        String member = FsxService.ONTAP.equals(volume.path("VolumeType").asText())
                ? "OntapConfiguration" : "OpenZFSConfiguration";
        JsonNode update = request.path(member);
        if (update.isObject()) {
            merge((ObjectNode) volume.get(member), (ObjectNode) update, Set.of());
        }
        put(region, VOLUME, id, volume);
        return volume;
    }

    public ObjectNode deleteVolume(JsonNode request, String region) {
        String id = text(request, "VolumeId");
        ObjectNode volume = get(region, VOLUME, id, "VolumeNotFound", "Volume");
        ObjectNode response = mapper.createObjectNode().put("VolumeId", id).put("Lifecycle", "DELETING");
        if (FsxService.ONTAP.equals(volume.path("VolumeType").asText())) {
            if (volume.path("OntapConfiguration").path("StorageVirtualMachineRoot").asBoolean()) {
                throw badRequest("The root volume of a storage virtual machine cannot be deleted.");
            }
            JsonNode options = request.path("OntapConfiguration");
            if (!options.path("SkipFinalBackup").asBoolean(false)) {
                ArrayNode finalTags = options.path("FinalBackupTags").isArray()
                        ? ((ArrayNode) options.get("FinalBackupTags")).deepCopy() : mapper.createArrayNode();
                ObjectNode backup = backupOf(null, volume, region, finalTags);
                put(region, BACKUP, backup.path("BackupId").asText(), backup);
                response.putObject("OntapResponse").put("FinalBackupId", backup.path("BackupId").asText())
                        .set("FinalBackupTags", finalTags);
            }
        } else {
            if (volume.path("OpenZFSConfiguration").path("ParentVolumeId").isMissingNode()) {
                throw badRequest("The root volume of a file system cannot be deleted.");
            }
            String path = volume.path("OpenZFSConfiguration").path("VolumePath").asText() + "/";
            boolean hasChildren = !scan(region, VOLUME, other ->
                    other.path("OpenZFSConfiguration").path("VolumePath").asText("").startsWith(path)).isEmpty();
            boolean deleteChildren = FsxService.strings(request.path("OpenZFSConfiguration").path("Options"))
                    .contains("DELETE_CHILD_VOLUMES_AND_SNAPSHOTS");
            if (hasChildren && !deleteChildren) {
                throw badRequest("Volume " + id + " has child volumes. Delete them first, or pass "
                        + "DELETE_CHILD_VOLUMES_AND_SNAPSHOTS.");
            }
            scan(region, VOLUME, other -> other.path("OpenZFSConfiguration").path("VolumePath").asText("")
                    .startsWith(path)).forEach(child ->
                    resources.delete(key(region, VOLUME, child.path("VolumeId").asText())));
        }
        resources.delete(key(region, VOLUME, id));
        return response;
    }

    private ObjectNode ontapVolume(String fileSystemId, String svmId, String name, String region, boolean root,
                                   ObjectNode requested, ArrayNode tags) {
        String id = "fsvol-" + randomHex(17);
        ObjectNode volume = baseVolume(fileSystemId, id, name, FsxService.ONTAP, "CREATED", region, tags);
        ObjectNode configuration = requested.deepCopy();
        configuration.remove(List.of("SizeInBytes", "AggregateConfiguration"));
        if (requested.path("AggregateConfiguration").isObject()) {
            ObjectNode aggregates = configuration.putObject("AggregateConfiguration");
            aggregates.set("Aggregates", requested.path("AggregateConfiguration").path("Aggregates").deepCopy());
            aggregates.put("TotalConstituents",
                    requested.path("AggregateConfiguration").path("ConstituentsPerAggregate").asInt(1));
        }
        configuration.put("StorageVirtualMachineId", svmId);
        configuration.put("StorageVirtualMachineRoot", root);
        configuration.put("FlexCacheEndpointType", "NONE");
        configuration.put("UUID", UUID.randomUUID().toString());
        putDefault(configuration, "JunctionPath", "/" + name);
        putDefault(configuration, "SecurityStyle", "UNIX");
        putDefault(configuration, "OntapVolumeType", "RW");
        putDefault(configuration, "VolumeStyle", "FLEXVOL");
        putDefault(configuration, "SnapshotPolicy", "default");
        if (!configuration.has("StorageEfficiencyEnabled")) {
            configuration.put("StorageEfficiencyEnabled", false);
        }
        long bytes = requested.hasNonNull("SizeInBytes") ? requested.path("SizeInBytes").asLong()
                : requested.path("SizeInMegabytes").asLong(root ? 1 : 0) * 1024 * 1024;
        configuration.put("SizeInMegabytes", (int) (bytes / (1024 * 1024)));
        configuration.put("SizeInBytes", bytes);
        if (!configuration.has("TieringPolicy")) {
            configuration.putObject("TieringPolicy").put("Name", "SNAPSHOT_ONLY").put("CoolingPeriod", 2);
        }
        volume.set("OntapConfiguration", configuration);
        return volume;
    }

    private ObjectNode openZfsVolume(String fileSystemId, String name, String region, String path,
                                     ObjectNode requested, ArrayNode tags) {
        String id = "fsvol-" + randomHex(17);
        ObjectNode volume = baseVolume(fileSystemId, id, name, FsxService.OPENZFS, "AVAILABLE", region, tags);
        ObjectNode configuration = requested.deepCopy();
        configuration.put("VolumePath", path);
        if (!configuration.has("StorageCapacityReservationGiB")) {
            configuration.put("StorageCapacityReservationGiB", -1);
        }
        if (!configuration.has("StorageCapacityQuotaGiB")) {
            configuration.put("StorageCapacityQuotaGiB", -1);
        }
        if (!configuration.has("RecordSizeKiB")) {
            configuration.put("RecordSizeKiB", 128);
        }
        putDefault(configuration, "DataCompressionType", "NONE");
        if (!configuration.has("ReadOnly")) {
            configuration.put("ReadOnly", false);
        }
        if (!configuration.has("CopyTagsToSnapshots")) {
            configuration.put("CopyTagsToSnapshots", false);
        }
        if (!configuration.has("NfsExports")) {
            configuration.putArray("NfsExports");
        }
        if (!configuration.has("UserAndGroupQuotas")) {
            configuration.putArray("UserAndGroupQuotas");
        }
        volume.set("OpenZFSConfiguration", configuration);
        return volume;
    }

    private ObjectNode baseVolume(String fileSystemId, String id, String name, String type, String lifecycle,
                                  String region, ArrayNode tags) {
        ObjectNode volume = mapper.createObjectNode();
        volume.put("CreationTime", Instant.now().getEpochSecond());
        volume.put("FileSystemId", fileSystemId);
        volume.put("Lifecycle", lifecycle);
        volume.put("Name", name);
        volume.put("ResourceARN", regionResolver.buildArn("fsx", region, VOLUME + "/" + fileSystemId + "/" + id));
        volume.set("Tags", tags);
        volume.put("VolumeId", id);
        volume.put("VolumeType", type);
        return volume;
    }

    // ── Backups ──────────────────────────────────────────────────────────────

    public ObjectNode createBackup(JsonNode request, String region) {
        String fileSystemId = text(request, "FileSystemId");
        String volumeId = text(request, "VolumeId");
        if ((fileSystemId == null) == (volumeId == null)) {
            throw badRequest("Specify exactly one of FileSystemId and VolumeId.");
        }
        ObjectNode backup;
        if (volumeId != null) {
            ObjectNode volume = get(region, VOLUME, volumeId, "VolumeNotFound", "Volume");
            if (!FsxService.ONTAP.equals(volume.path("VolumeType").asText())) {
                throw new AwsException("UnsupportedOperation", "Volume backups are supported for ONTAP volumes.", 400);
            }
            backup = backupOf(null, volume, region, tags(request.path("Tags")));
        } else {
            FsxFileSystem fileSystem = fileSystems.getFileSystem(fileSystemId, region);
            if (FsxService.ONTAP.equals(fileSystem.getFileSystemType())) {
                throw new AwsException("UnsupportedOperation",
                        "Back up an ONTAP file system by backing up its volumes.", 400);
            }
            if (fileSystem.getConfiguration() != null
                    && fileSystem.getConfiguration().path("DeploymentType").asText("").startsWith("SCRATCH")) {
                throw badRequest("Backups are not supported for scratch file systems.");
            }
            backup = backupOf(fileSystem, null, region, tags(request.path("Tags")));
        }
        put(region, BACKUP, backup.path("BackupId").asText(), backup);
        LOG.infov("Created FSx backup {0}", backup.path("BackupId").asText());
        return backup;
    }

    private ObjectNode backupOf(FsxFileSystem fileSystem, ObjectNode volume, String region, ArrayNode tags) {
        FsxFileSystem source = fileSystem != null ? fileSystem
                : fileSystems.getFileSystem(volume.path("FileSystemId").asText(), region);
        String id = "backup-" + randomHex(17);
        ObjectNode backup = mapper.createObjectNode();
        backup.put("BackupId", id);
        backup.put("Lifecycle", "AVAILABLE");
        backup.put("Type", "USER_INITIATED");
        backup.put("ProgressPercent", 100);
        backup.put("CreationTime", Instant.now().getEpochSecond());
        if (source.getKmsKeyId() != null) {
            backup.put("KmsKeyId", source.getKmsKeyId());
        }
        backup.put("ResourceARN", regionResolver.buildArn("fsx", region, BACKUP + "/" + id));
        backup.set("Tags", tags);
        ObjectNode summary = backup.putObject("FileSystem");
        summary.put("OwnerId", source.getOwnerId());
        summary.put("CreationTime", source.getCreationTime());
        summary.put("FileSystemId", source.getFileSystemId());
        summary.put("FileSystemType", source.getFileSystemType());
        summary.put("Lifecycle", source.getLifecycle());
        if (source.getStorageCapacity() != null) {
            summary.put("StorageCapacity", source.getStorageCapacity());
        }
        summary.put("StorageType", source.getStorageType());
        summary.put("VpcId", source.getVpcId());
        summary.put("ResourceARN", source.getResourceArn());
        if (source.getFileSystemTypeVersion() != null) {
            summary.put("FileSystemTypeVersion", source.getFileSystemTypeVersion());
        }
        backup.put("OwnerId", regionResolver.getAccountId());
        backup.put("ResourceType", volume != null ? "VOLUME" : "FILE_SYSTEM");
        if (volume != null) {
            backup.set("Volume", volume.deepCopy());
        }
        return backup;
    }

    public List<ObjectNode> describeBackups(JsonNode request, String region) {
        return describe(region, BACKUP, FsxService.strings(request.path("BackupIds")), "BackupNotFound", "Backup",
                filter(request.path("Filters"), Map.of(
                        "file-system-id", "FileSystem.FileSystemId",
                        "backup-type", "Type",
                        "file-system-type", "FileSystem.FileSystemType",
                        "volume-id", "Volume.VolumeId")));
    }

    public ObjectNode copyBackup(JsonNode request, String region) {
        String sourceId = required(request, "SourceBackupId");
        String sourceRegion = text(request, "SourceRegion") != null ? text(request, "SourceRegion") : region;
        ObjectNode source = get(sourceRegion, BACKUP, sourceId, "BackupNotFound", "Backup");
        String id = "backup-" + randomHex(17);
        ObjectNode copy = source.deepCopy();
        copy.put("BackupId", id);
        copy.put("CreationTime", Instant.now().getEpochSecond());
        copy.put("Type", "USER_INITIATED");
        copy.put("ResourceARN", regionResolver.buildArn("fsx", region, BACKUP + "/" + id));
        copy.put("SourceBackupId", sourceId);
        copy.put("SourceBackupRegion", sourceRegion);
        if (text(request, "KmsKeyId") != null) {
            copy.put("KmsKeyId", text(request, "KmsKeyId"));
        }
        ArrayNode tags = request.path("CopyTags").asBoolean(false) ? (ArrayNode) source.path("Tags").deepCopy()
                : mapper.createArrayNode();
        tags.addAll(tags(request.path("Tags")));
        copy.set("Tags", tags);
        put(region, BACKUP, id, copy);
        return copy;
    }

    public ObjectNode deleteBackup(String id, String region) {
        get(region, BACKUP, id, "BackupNotFound", "Backup");
        resources.delete(key(region, BACKUP, id));
        return mapper.createObjectNode().put("BackupId", id).put("Lifecycle", "DELETED");
    }

    // ── Data repository associations ─────────────────────────────────────────

    public ObjectNode createDataRepositoryAssociation(JsonNode request, String region) {
        FsxFileSystem fileSystem = fileSystems.getFileSystem(text(request, "FileSystemId"), region);
        if (!FsxService.LUSTRE.equals(fileSystem.getFileSystemType())) {
            throw new AwsException("UnsupportedOperation",
                    "Data repository associations are supported on Lustre file systems.", 400);
        }
        String repositoryPath = required(request, "DataRepositoryPath");
        if (!repositoryPath.startsWith("s3://")) {
            throw badRequest("DataRepositoryPath must be an S3 path starting with s3://.");
        }
        String fileSystemPath = text(request, "FileSystemPath") != null ? text(request, "FileSystemPath") : "/";
        boolean overlaps = !scan(region, ASSOCIATION, association ->
                fileSystem.getFileSystemId().equals(association.path("FileSystemId").asText())
                        && fileSystemPath.equals(association.path("FileSystemPath").asText())).isEmpty();
        if (overlaps) {
            throw badRequest("A data repository association already exists for " + fileSystemPath + ".");
        }
        String id = "dra-" + randomHex(17);
        ObjectNode association = mapper.createObjectNode();
        association.put("AssociationId", id);
        association.put("ResourceARN", regionResolver.buildArn("fsx", region,
                ASSOCIATION + "/" + fileSystem.getFileSystemId() + "/" + id));
        association.put("FileSystemId", fileSystem.getFileSystemId());
        association.put("Lifecycle", "AVAILABLE");
        association.put("FileSystemPath", fileSystemPath);
        association.put("DataRepositoryPath", repositoryPath);
        association.put("BatchImportMetaDataOnCreate", request.path("BatchImportMetaDataOnCreate").asBoolean(false));
        association.put("ImportedFileChunkSize", request.path("ImportedFileChunkSize").asInt(1024));
        if (request.path("S3").isObject()) {
            association.set("S3", request.path("S3").deepCopy());
        }
        association.set("Tags", tags(request.path("Tags")));
        association.put("CreationTime", Instant.now().getEpochSecond());
        put(region, ASSOCIATION, id, association);
        LOG.infov("Created FSx data repository association {0} on {1}", id, fileSystem.getFileSystemId());
        return association;
    }

    public List<ObjectNode> describeDataRepositoryAssociations(JsonNode request, String region) {
        return describe(region, ASSOCIATION, FsxService.strings(request.path("AssociationIds")),
                "DataRepositoryAssociationNotFound", "Data repository association",
                filter(request.path("Filters"), Map.of("file-system-id", "FileSystemId")));
    }

    public ObjectNode updateDataRepositoryAssociation(JsonNode request, String region) {
        String id = text(request, "AssociationId");
        ObjectNode association = get(region, ASSOCIATION, id, "DataRepositoryAssociationNotFound",
                "Data repository association");
        if (request.hasNonNull("ImportedFileChunkSize")) {
            association.put("ImportedFileChunkSize", request.path("ImportedFileChunkSize").asInt());
        }
        if (request.path("S3").isObject()) {
            association.set("S3", request.path("S3").deepCopy());
        }
        put(region, ASSOCIATION, id, association);
        return association;
    }

    public ObjectNode deleteDataRepositoryAssociation(JsonNode request, String region) {
        String id = text(request, "AssociationId");
        get(region, ASSOCIATION, id, "DataRepositoryAssociationNotFound", "Data repository association");
        resources.delete(key(region, ASSOCIATION, id));
        return mapper.createObjectNode().put("AssociationId", id).put("Lifecycle", "DELETING")
                .put("DeleteDataInFileSystem", request.path("DeleteDataInFileSystem").asBoolean(false));
    }

    // ── File system deletion and tags ────────────────────────────────────────

    /**
     * Refuses to delete an ONTAP file system that still has storage virtual machines, and removes
     * the volumes and data repository associations that go with the file system.
     */
    public void onFileSystemDelete(String fileSystemId, String region) {
        boolean hasSvms = !scan(region, SVM, svm -> fileSystemId.equals(svm.path("FileSystemId").asText())).isEmpty();
        if (hasSvms) {
            throw badRequest("File system " + fileSystemId + " has storage virtual machines. Delete them first.");
        }
        for (String kind : List.of(VOLUME, ASSOCIATION)) {
            String idMember = VOLUME.equals(kind) ? "VolumeId" : "AssociationId";
            scan(region, kind, resource -> fileSystemId.equals(resource.path("FileSystemId").asText()))
                    .forEach(resource -> resources.delete(key(region, kind, resource.path(idMember).asText())));
        }
    }

    /** The volume, storage virtual machine, backup or association an ARN names, if this service holds it. */
    public Optional<ObjectNode> findByArn(String resourceArn, String region) {
        if (resourceArn == null) {
            return Optional.empty();
        }
        return resources.scan(k -> k.startsWith(region + "|")).stream()
                .filter(resource -> resourceArn.equals(resource.path("ResourceARN").asText()))
                .findFirst();
    }

    public void saveTags(ObjectNode resource, Map<String, String> tags, String region) {
        ArrayNode list = mapper.createArrayNode();
        tags.forEach((tagKey, value) -> list.addObject().put("Key", tagKey).put("Value", value));
        resource.set("Tags", list);
        String kind = resourceKind(resource);
        put(region, kind, resource.path(idMember(kind)).asText(), resource);
    }

    static Map<String, String> tagsOf(ObjectNode resource) {
        return FsxService.tagMap(resource.path("Tags"));
    }

    private static String resourceKind(ObjectNode resource) {
        if (resource.has("AssociationId")) {
            return ASSOCIATION;
        }
        if (resource.has("BackupId")) {
            return BACKUP;
        }
        if (resource.has("StorageVirtualMachineId") && !resource.has("VolumeId")) {
            return SVM;
        }
        return VOLUME;
    }

    private static String idMember(String kind) {
        return switch (kind) {
            case VOLUME -> "VolumeId";
            case SVM -> "StorageVirtualMachineId";
            case BACKUP -> "BackupId";
            default -> "AssociationId";
        };
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<ObjectNode> describe(String region, String kind, List<String> ids, String notFoundCode,
                                      String label, Predicate<ObjectNode> filter) {
        if (!ids.isEmpty()) {
            List<ObjectNode> found = new ArrayList<>();
            for (String id : ids) {
                found.add(get(region, kind, id, notFoundCode, label));
            }
            return found;
        }
        return scan(region, kind, filter).stream()
                .sorted(Comparator.comparingLong((ObjectNode resource) -> resource.path("CreationTime").asLong())
                        .thenComparing(resource -> resource.path(idMember(kind)).asText()))
                .toList();
    }

    /** A Describe* filter: every named filter must match one of its values. */
    private static Predicate<ObjectNode> filter(JsonNode filters, Map<String, String> members) {
        Predicate<ObjectNode> predicate = resource -> true;
        if (filters == null || !filters.isArray()) {
            return predicate;
        }
        for (JsonNode filter : filters) {
            String name = filter.path("Name").asText();
            String member = members.get(name);
            if (member == null) {
                throw badRequest("Invalid filter name: " + name);
            }
            List<String> values = FsxService.strings(filter.path("Values"));
            predicate = predicate.and(resource -> {
                JsonNode node = resource;
                for (String segment : member.split("\\.")) {
                    node = node.path(segment);
                }
                return values.contains(node.asText(null));
            });
        }
        return predicate;
    }

    private ObjectNode get(String region, String kind, String id, String notFoundCode, String label) {
        if (id == null || id.isBlank()) {
            throw badRequest(idMember(kind) + " is required.");
        }
        return resources.get(key(region, kind, id)).orElseThrow(() ->
                new AwsException(notFoundCode, label + " '" + id + "' does not exist.", 400));
    }

    private List<ObjectNode> scan(String region, String kind, Predicate<ObjectNode> filter) {
        String prefix = region + "|" + kind + "|";
        return resources.scan(k -> k.startsWith(prefix)).stream().filter(filter).toList();
    }

    private void put(String region, String kind, String id, ObjectNode resource) {
        resources.put(key(region, kind, id), resource);
    }

    private static String key(String region, String kind, String id) {
        return region + "|" + kind + "|" + id;
    }

    private ArrayNode tags(JsonNode tags) {
        ArrayNode list = mapper.createArrayNode();
        FsxService.tagMap(tags).forEach((tagKey, value) -> list.addObject().put("Key", tagKey).put("Value", value));
        return list;
    }

    private static void merge(ObjectNode target, ObjectNode update, Set<String> skipped) {
        Iterator<Map.Entry<String, JsonNode>> fields = update.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (skipped.contains(field.getKey())) {
                continue;
            }
            JsonNode current = target.get(field.getKey());
            if (current instanceof ObjectNode currentObject && field.getValue() instanceof ObjectNode nested) {
                merge(currentObject, nested, skipped);
            } else {
                target.set(field.getKey(), field.getValue().deepCopy());
            }
        }
    }

    private static void putDefault(ObjectNode node, String field, String value) {
        if (!node.hasNonNull(field)) {
            node.put(field, value);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required.");
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static AwsException badRequest(String message) {
        return new AwsException("BadRequest", message, 400);
    }

    private static String randomHex(int length) {
        StringBuilder sb = new StringBuilder(length);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < length; i++) {
            sb.append(Character.forDigit(random.nextInt(16), 16));
        }
        return sb.toString();
    }

    private static String randomPrivateIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "10.0." + random.nextInt(256) + "." + (1 + random.nextInt(254));
    }
}
