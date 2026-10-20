package io.github.hectorvent.floci.services.fsx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.fsx.model.FsxFileSystem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Amazon FSx over JSON 1.1 ({@code X-Amz-Target: AWSSimbaAPIService_v20180301.<Action>}),
 * covering file systems, volumes, storage virtual machines, backups, data repository associations
 * and their tags.
 */
@ApplicationScoped
public class FsxJsonHandler {

    private static final int DEFAULT_PAGE_SIZE = 2147483647;

    private final FsxService service;
    private final FsxResourceService resources;
    private final ObjectMapper mapper;

    @Inject
    public FsxJsonHandler(FsxService service, FsxResourceService resources, ObjectMapper mapper) {
        this.service = service;
        this.resources = resources;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateFileSystem" -> {
                FsxFileSystem created = service.createFileSystem(request, region);
                if (FsxService.OPENZFS.equals(created.getFileSystemType())) {
                    resources.createRootVolume(created, region);
                }
                yield ok(mapper.createObjectNode().set("FileSystem", fileSystemNode(created)));
            }
            case "DescribeFileSystems" -> describeFileSystems(request, region);
            case "UpdateFileSystem" -> ok(mapper.createObjectNode()
                    .set("FileSystem", fileSystemNode(service.updateFileSystem(request, region))));
            case "DeleteFileSystem" -> {
                FsxFileSystem target = service.getFileSystem(text(request, "FileSystemId"), region);
                resources.onFileSystemDelete(target.getFileSystemId(), region);
                FsxFileSystem deleted = service.deleteFileSystem(target.getFileSystemId(), region);
                yield ok(mapper.createObjectNode()
                        .put("FileSystemId", deleted.getFileSystemId())
                        .put("Lifecycle", deleted.getLifecycle()));
            }
            case "CreateStorageVirtualMachine" -> ok(mapper.createObjectNode()
                    .set("StorageVirtualMachine", resources.createStorageVirtualMachine(request, region)));
            case "DescribeStorageVirtualMachines" -> page(request, "StorageVirtualMachines",
                    resources.describeStorageVirtualMachines(request, region));
            case "UpdateStorageVirtualMachine" -> ok(mapper.createObjectNode()
                    .set("StorageVirtualMachine", resources.updateStorageVirtualMachine(request, region)));
            case "DeleteStorageVirtualMachine" -> {
                ObjectNode deleted = resources.deleteStorageVirtualMachine(
                        text(request, "StorageVirtualMachineId"), region);
                yield ok(mapper.createObjectNode()
                        .put("StorageVirtualMachineId", deleted.path("StorageVirtualMachineId").asText())
                        .put("Lifecycle", deleted.path("Lifecycle").asText()));
            }
            case "CreateVolume" -> ok(mapper.createObjectNode().set("Volume", resources.createVolume(request, region)));
            case "DescribeVolumes" -> page(request, "Volumes", resources.describeVolumes(request, region));
            case "UpdateVolume" -> ok(mapper.createObjectNode().set("Volume", resources.updateVolume(request, region)));
            case "DeleteVolume" -> ok(resources.deleteVolume(request, region));
            case "CreateBackup" -> ok(mapper.createObjectNode().set("Backup", resources.createBackup(request, region)));
            case "DescribeBackups" -> page(request, "Backups", resources.describeBackups(request, region));
            case "CopyBackup" -> ok(mapper.createObjectNode().set("Backup", resources.copyBackup(request, region)));
            case "DeleteBackup" -> ok(resources.deleteBackup(text(request, "BackupId"), region));
            case "CreateDataRepositoryAssociation" -> ok(mapper.createObjectNode()
                    .set("Association", resources.createDataRepositoryAssociation(request, region)));
            case "DescribeDataRepositoryAssociations" -> page(request, "Associations",
                    resources.describeDataRepositoryAssociations(request, region));
            case "UpdateDataRepositoryAssociation" -> ok(mapper.createObjectNode()
                    .set("Association", resources.updateDataRepositoryAssociation(request, region)));
            case "DeleteDataRepositoryAssociation" -> ok(resources.deleteDataRepositoryAssociation(request, region));
            case "TagResource" -> {
                Map<String, String> tags = FsxService.tagMap(request.path("Tags"));
                if (tags.isEmpty()) {
                    throw new AwsException("BadRequest", "Tags is required.", 400);
                }
                String arn = text(request, "ResourceARN");
                Optional<ObjectNode> resource = resources.findByArn(arn, region);
                if (resource.isPresent()) {
                    Map<String, String> merged = new LinkedHashMap<>(FsxResourceService.tagsOf(resource.get()));
                    merged.putAll(tags);
                    resources.saveTags(resource.get(), merged, region);
                } else {
                    service.tagResource(arn, tags, region);
                }
                yield ok(mapper.createObjectNode());
            }
            case "UntagResource" -> {
                String arn = text(request, "ResourceARN");
                List<String> keys = FsxService.strings(request.path("TagKeys"));
                Optional<ObjectNode> resource = resources.findByArn(arn, region);
                if (resource.isPresent()) {
                    Map<String, String> remaining = new LinkedHashMap<>(FsxResourceService.tagsOf(resource.get()));
                    keys.forEach(remaining::remove);
                    resources.saveTags(resource.get(), remaining, region);
                } else {
                    service.untagResource(arn, keys, region);
                }
                yield ok(mapper.createObjectNode());
            }
            case "ListTagsForResource" -> {
                String arn = text(request, "ResourceARN");
                Optional<ObjectNode> resource = resources.findByArn(arn, region);
                ObjectNode response = mapper.createObjectNode();
                response.set("Tags", tagList(resource.isPresent()
                        ? FsxResourceService.tagsOf(resource.get()) : service.listTags(arn, region)));
                yield ok(response);
            }
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    private Response describeFileSystems(JsonNode request, String region) {
        List<FsxFileSystem> all = service.describeFileSystems(FsxService.strings(request.path("FileSystemIds")),
                region);
        List<ObjectNode> nodes = all.stream().map(this::fileSystemNode).toList();
        return page(request, "FileSystems", nodes);
    }

    /** A Describe* page: NextToken is the offset of the next item. */
    private Response page(JsonNode request, String member, List<ObjectNode> all) {
        int start = 0;
        String nextToken = text(request, "NextToken");
        if (nextToken != null) {
            try {
                start = Integer.parseInt(nextToken);
            } catch (NumberFormatException e) {
                throw new AwsException("BadRequest", "Invalid NextToken.", 400);
            }
        }
        int maxResults = request.hasNonNull("MaxResults") ? request.path("MaxResults").asInt() : DEFAULT_PAGE_SIZE;
        if (maxResults < 1) {
            throw new AwsException("BadRequest", "MaxResults must be at least 1.", 400);
        }
        int end = (int) Math.min((long) start + maxResults, all.size());
        ObjectNode response = mapper.createObjectNode();
        ArrayNode items = response.putArray(member);
        for (ObjectNode item : all.subList(Math.min(start, all.size()), end)) {
            items.add(item);
        }
        if (end < all.size()) {
            response.put("NextToken", String.valueOf(end));
        }
        return ok(response);
    }

    private ObjectNode fileSystemNode(FsxFileSystem fileSystem) {
        ObjectNode node = mapper.createObjectNode();
        node.put("OwnerId", fileSystem.getOwnerId());
        node.put("CreationTime", fileSystem.getCreationTime());
        node.put("FileSystemId", fileSystem.getFileSystemId());
        node.put("FileSystemType", fileSystem.getFileSystemType());
        node.put("Lifecycle", fileSystem.getLifecycle());
        if (fileSystem.getStorageCapacity() != null) {
            node.put("StorageCapacity", fileSystem.getStorageCapacity());
        }
        node.put("StorageType", fileSystem.getStorageType());
        node.put("VpcId", fileSystem.getVpcId());
        ArrayNode subnets = node.putArray("SubnetIds");
        fileSystem.getSubnetIds().forEach(subnets::add);
        node.putArray("NetworkInterfaceIds");
        node.put("DNSName", fileSystem.getDnsName());
        if (fileSystem.getKmsKeyId() != null) {
            node.put("KmsKeyId", fileSystem.getKmsKeyId());
        }
        node.put("ResourceARN", fileSystem.getResourceArn());
        node.set("Tags", tagList(fileSystem.getTags()));
        if (fileSystem.getFileSystemTypeVersion() != null) {
            node.put("FileSystemTypeVersion", fileSystem.getFileSystemTypeVersion());
        }
        if (fileSystem.getNetworkType() != null) {
            node.put("NetworkType", fileSystem.getNetworkType());
        }
        if (fileSystem.getConfiguration() != null) {
            node.set(configurationMember(fileSystem.getFileSystemType()), fileSystem.getConfiguration().deepCopy());
        }
        return node;
    }

    private static String configurationMember(String type) {
        return switch (type) {
            case FsxService.LUSTRE -> "LustreConfiguration";
            case FsxService.WINDOWS -> "WindowsConfiguration";
            case FsxService.ONTAP -> "OntapConfiguration";
            default -> "OpenZFSConfiguration";
        };
    }

    private ArrayNode tagList(Map<String, String> tags) {
        ArrayNode list = mapper.createArrayNode();
        tags.forEach((key, value) -> list.addObject().put("Key", key).put("Value", value));
        return list;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Response ok(ObjectNode body) {
        return Response.ok(body).build();
    }
}
