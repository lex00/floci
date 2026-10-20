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

import java.util.List;
import java.util.Map;

/**
 * Amazon FSx over JSON 1.1 ({@code X-Amz-Target: AWSSimbaAPIService_v20180301.<Action>}),
 * covering file systems and their tags.
 */
@ApplicationScoped
public class FsxJsonHandler {

    private static final int DEFAULT_PAGE_SIZE = 2147483647;

    private final FsxService service;
    private final ObjectMapper mapper;

    @Inject
    public FsxJsonHandler(FsxService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateFileSystem" -> ok(mapper.createObjectNode()
                    .set("FileSystem", fileSystemNode(service.createFileSystem(request, region))));
            case "DescribeFileSystems" -> describeFileSystems(request, region);
            case "UpdateFileSystem" -> ok(mapper.createObjectNode()
                    .set("FileSystem", fileSystemNode(service.updateFileSystem(request, region))));
            case "DeleteFileSystem" -> {
                FsxFileSystem deleted = service.deleteFileSystem(text(request, "FileSystemId"), region);
                yield ok(mapper.createObjectNode()
                        .put("FileSystemId", deleted.getFileSystemId())
                        .put("Lifecycle", deleted.getLifecycle()));
            }
            case "TagResource" -> {
                Map<String, String> tags = FsxService.tagMap(request.path("Tags"));
                if (tags.isEmpty()) {
                    throw new AwsException("BadRequest", "Tags is required.", 400);
                }
                service.tagResource(text(request, "ResourceARN"), tags, region);
                yield ok(mapper.createObjectNode());
            }
            case "UntagResource" -> {
                service.untagResource(text(request, "ResourceARN"), FsxService.strings(request.path("TagKeys")),
                        region);
                yield ok(mapper.createObjectNode());
            }
            case "ListTagsForResource" -> {
                ObjectNode response = mapper.createObjectNode();
                response.set("Tags", tagList(service.listTags(text(request, "ResourceARN"), region)));
                yield ok(response);
            }
            default -> throw new AwsException("UnknownOperationException",
                    "Operation " + action + " is not supported by floci", 400);
        };
    }

    private Response describeFileSystems(JsonNode request, String region) {
        List<FsxFileSystem> all = service.describeFileSystems(FsxService.strings(request.path("FileSystemIds")),
                region);
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
        ArrayNode items = response.putArray("FileSystems");
        for (FsxFileSystem fileSystem : all.subList(Math.min(start, all.size()), end)) {
            items.add(fileSystemNode(fileSystem));
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
