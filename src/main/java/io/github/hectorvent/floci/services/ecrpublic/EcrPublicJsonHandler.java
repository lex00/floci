package io.github.hectorvent.floci.services.ecrpublic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import io.github.hectorvent.floci.services.ecrpublic.model.PublicRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON 1.1 handler for ECR Public, {@code X-Amz-Target: SpencerFrontendService.*}. */
@ApplicationScoped
public class EcrPublicJsonHandler {

    private final EcrPublicService service;
    private final EcrPublicImageService images;
    private final ObjectMapper mapper;

    @Inject
    public EcrPublicJsonHandler(EcrPublicService service, EcrPublicImageService images, ObjectMapper mapper) {
        this.service = service;
        this.images = images;
        this.mapper = mapper;
    }

    public Response handle(String action, JsonNode request, String region, String accountId) throws Exception {
        String registryId = text(request, "registryId");
        String name = text(request, "repositoryName");
        return switch (action) {
            case "CreateRepository" -> {
                PublicRepository repository = service.createRepository(region, accountId, name,
                        map(request.get("catalogData")), tags(request.get("tags")));
                yield ok(Map.of("repository", repository(repository), "catalogData", repository.getCatalogData()));
            }
            case "GetAuthorizationToken" -> {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("authorizationToken", Base64.getEncoder()
                        .encodeToString("AWS:floci".getBytes(StandardCharsets.UTF_8)));
                data.put("expiresAt", Instant.now().plusSeconds(12 * 60 * 60).getEpochSecond());
                yield ok(Map.of("authorizationData", data));
            }
            case "DescribeRegistries" -> {
                Map<String, Object> alias = new LinkedHashMap<>();
                alias.put("name", accountId);
                alias.put("status", "ACTIVE");
                alias.put("primaryRegistryAlias", true);
                alias.put("defaultRegistryAlias", true);
                Map<String, Object> registry = new LinkedHashMap<>();
                registry.put("registryId", accountId);
                registry.put("registryArn", service.registryArn(region, accountId));
                registry.put("registryUri", images.registryUri(accountId));
                registry.put("verified", false);
                registry.put("aliases", List.of(alias));
                yield ok(Map.of("registries", List.of(registry)));
            }
            case "GetRegistryCatalogData" -> ok(Map.of("registryCatalogData", service.getRegistryCatalogData()));
            case "PutRegistryCatalogData" -> ok(Map.of("registryCatalogData",
                    service.putRegistryCatalogData(text(request, "displayName"))));
            case "DescribeImages" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                List<EcrPublicImageService.ImageRecord> found = new ArrayList<>();
                if (request.path("imageIds").isArray() && !request.path("imageIds").isEmpty()) {
                    for (JsonNode id : request.get("imageIds")) {
                        String reference = id.hasNonNull("imageDigest")
                                ? id.get("imageDigest").asText() : text(id, "imageTag");
                        EcrPublicImageService.ImageRecord image = reference == null ? null
                                : images.image(repository, reference);
                        if (image == null) {
                            throw new AwsException("ImageNotFoundException", "The image with imageId "
                                    + id + " does not exist within the repository with name '" + name
                                    + "' in the registry with id '" + accountId + "'", 400);
                        }
                        found.add(image);
                    }
                } else {
                    found.addAll(images.images(repository));
                }
                List<Map<String, Object>> details = new ArrayList<>();
                for (EcrPublicImageService.ImageRecord image : found) {
                    Map<String, Object> detail = imageDetail(image);
                    detail.put("registryId", accountId);
                    detail.put("repositoryName", name);
                    if (!image.tags().isEmpty()) {
                        detail.put("imageTags", image.tags());
                    }
                    details.add(detail);
                }
                yield ok(Map.of("imageDetails", details));
            }
            case "DescribeImageTags" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                List<Map<String, Object>> details = new ArrayList<>();
                for (EcrPublicImageService.ImageRecord image : images.images(repository)) {
                    for (String tag : image.tags()) {
                        Map<String, Object> detail = new LinkedHashMap<>();
                        detail.put("imageTag", tag);
                        detail.put("createdAt", image.pushedAt().getEpochSecond());
                        detail.put("imageDetail", imageDetail(image));
                        details.add(detail);
                    }
                }
                yield ok(Map.of("imageTagDetails", details));
            }
            case "BatchDeleteImage" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                List<Map<String, Object>> deleted = new ArrayList<>();
                List<Map<String, Object>> failures = new ArrayList<>();
                for (JsonNode id : request.path("imageIds")) {
                    String tag = text(id, "imageTag");
                    String digest = text(id, "imageDigest");
                    if (tag == null && digest == null) {
                        failures.add(imageFailure(tag, digest, "MissingDigestAndTag",
                                "Invalid request parameters: both tag and digest cannot be null"));
                        continue;
                    }
                    String removed = images.deleteImage(repository, tag, digest);
                    if (removed == null) {
                        failures.add(imageFailure(tag, digest, "ImageNotFound", "Requested image not found"));
                    } else {
                        deleted.add(imageId(tag, removed));
                    }
                }
                yield ok(Map.of("imageIds", deleted, "failures", failures));
            }
            case "PutImage" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                String manifest = text(request, "imageManifest");
                String mediaType = text(request, "imageManifestMediaType");
                String tag = text(request, "imageTag");
                String digest = images.putImage(repository, manifest, mediaType, tag, text(request, "imageDigest"));
                Map<String, Object> image = new LinkedHashMap<>();
                image.put("registryId", accountId);
                image.put("repositoryName", name);
                image.put("imageId", imageId(tag, digest));
                image.put("imageManifest", manifest);
                if (mediaType != null) {
                    image.put("imageManifestMediaType", mediaType);
                }
                yield ok(Map.of("image", image));
            }
            case "BatchCheckLayerAvailability" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                List<String> digests = new ArrayList<>();
                request.path("layerDigests").forEach(d -> digests.add(d.asText()));
                List<Map<String, Object>> layers = new ArrayList<>();
                List<Map<String, Object>> failures = new ArrayList<>();
                for (Map.Entry<String, Long> layer : images.layerSizes(repository, digests).entrySet()) {
                    if (!layer.getKey().startsWith("sha256:")) {
                        Map<String, Object> failure = new LinkedHashMap<>();
                        failure.put("layerDigest", layer.getKey());
                        failure.put("failureCode", "InvalidLayerDigest");
                        failure.put("failureReason", "Invalid layer digest");
                        failures.add(failure);
                        continue;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("layerDigest", layer.getKey());
                    entry.put("layerAvailability", layer.getValue() != null ? "AVAILABLE" : "UNAVAILABLE");
                    if (layer.getValue() != null) {
                        entry.put("layerSize", layer.getValue());
                    }
                    layers.add(entry);
                }
                yield ok(Map.of("layers", layers, "failures", failures));
            }
            case "InitiateLayerUpload" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                yield ok(Map.of("uploadId", images.initiateLayerUpload(repository), "partSize", images.partSize()));
            }
            case "UploadLayerPart" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                String uploadId = text(request, "uploadId");
                long last = images.uploadLayerPart(repository, uploadId, request.path("partFirstByte").asLong(),
                        request.path("partLastByte").asLong(),
                        EcrPublicImageService.decodePart(text(request, "layerPartBlob")));
                yield ok(Map.of("registryId", accountId, "repositoryName", name, "uploadId", uploadId,
                        "lastByteReceived", last));
            }
            case "CompleteLayerUpload" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                String uploadId = text(request, "uploadId");
                JsonNode layerDigests = request.path("layerDigests");
                String digest = images.completeLayerUpload(repository, uploadId,
                        layerDigests.isArray() && layerDigests.size() == 1 ? layerDigests.get(0).asText() : null);
                yield ok(Map.of("registryId", accountId, "repositoryName", name, "uploadId", uploadId,
                        "layerDigest", digest));
            }
            case "DescribeRepositories" -> {
                List<String> names = null;
                if (request.hasNonNull("repositoryNames")) {
                    names = new ArrayList<>();
                    for (JsonNode n : request.get("repositoryNames")) {
                        names.add(n.asText());
                    }
                }
                List<Map<String, Object>> items = new ArrayList<>();
                for (PublicRepository r : service.describeRepositories(accountId, registryId, names)) {
                    items.add(repository(r));
                }
                yield ok(Map.of("repositories", items));
            }
            case "DeleteRepository" -> {
                PublicRepository repository = service.getRepository(accountId, registryId, name);
                if (images.hasImages(repository)) {
                    if (!request.path("force").asBoolean(false)) {
                        throw new AwsException("RepositoryNotEmptyException", "The repository with name '" + name
                                + "' in registry with id '" + accountId + "' cannot be deleted because it still "
                                + "contains images", 400);
                    }
                    images.deleteAll(repository);
                }
                yield ok(Map.of("repository", repository(service.deleteRepository(accountId, registryId, name))));
            }
            case "PutRepositoryCatalogData" -> ok(Map.of("catalogData",
                    service.putCatalogData(accountId, registryId, name, map(request.get("catalogData")))));
            case "GetRepositoryCatalogData" -> ok(Map.of("catalogData",
                    service.getRepository(accountId, registryId, name).getCatalogData()));
            case "SetRepositoryPolicy" -> {
                PublicRepository repository = service.setPolicy(accountId, registryId, name,
                        text(request, "policyText"));
                yield ok(policy(repository.getRegistryId(), name, repository.getPolicyText()));
            }
            case "GetRepositoryPolicy" -> ok(policy(accountId, name,
                    service.getPolicy(accountId, registryId, name)));
            case "DeleteRepositoryPolicy" -> ok(policy(accountId, name,
                    service.deletePolicy(accountId, registryId, name)));
            case "TagResource" -> {
                service.tagResource(accountId, text(request, "resourceArn"), tags(request.get("tags")));
                yield ok(Map.of());
            }
            case "UntagResource" -> {
                List<String> keys = new ArrayList<>();
                request.path("tagKeys").forEach(k -> keys.add(k.asText()));
                service.untagResource(accountId, text(request, "resourceArn"), keys);
                yield ok(Map.of());
            }
            case "ListTagsForResource" -> ok(Map.of("tags",
                    service.listTags(accountId, text(request, "resourceArn"))));
            default -> throw new AwsException("UnsupportedCommandException",
                    "The action " + action + " is not supported by this release.", 400);
        };
    }

    private static Map<String, Object> imageDetail(EcrPublicImageService.ImageRecord image) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("imageDigest", image.digest());
        detail.put("imageSizeInBytes", image.size());
        detail.put("imagePushedAt", image.pushedAt().getEpochSecond());
        if (image.mediaType() != null) {
            detail.put("imageManifestMediaType", image.mediaType());
        }
        String artifact = RegistryHttpClient.artifactMediaTypeFromManifest(image.manifest());
        if (artifact != null) {
            detail.put("artifactMediaType", artifact);
        }
        return detail;
    }

    private static Map<String, Object> imageId(String tag, String digest) {
        Map<String, Object> id = new LinkedHashMap<>();
        if (digest != null) {
            id.put("imageDigest", digest);
        }
        if (tag != null) {
            id.put("imageTag", tag);
        }
        return id;
    }

    private static Map<String, Object> imageFailure(String tag, String digest, String code, String reason) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("imageId", imageId(tag, digest));
        failure.put("failureCode", code);
        failure.put("failureReason", reason);
        return failure;
    }

    private Map<String, Object> repository(PublicRepository repository) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("repositoryArn", repository.getRepositoryArn());
        body.put("registryId", repository.getRegistryId());
        body.put("repositoryName", repository.getRepositoryName());
        body.put("repositoryUri", images.repositoryUri(repository));
        body.put("createdAt", repository.getCreatedAt());
        return body;
    }

    private static Map<String, Object> policy(String registryId, String name, String policyText) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("registryId", registryId);
        body.put("repositoryName", name);
        body.put("policyText", policyText);
        return body;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(JsonNode node) throws Exception {
        return node == null || node.isNull() ? null : mapper.treeToValue(node, Map.class);
    }

    private static List<Map<String, String>> tags(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        List<Map<String, String>> tags = new ArrayList<>();
        for (JsonNode tag : node) {
            Map<String, String> t = new LinkedHashMap<>();
            t.put("Key", tag.path("Key").asText());
            t.put("Value", tag.path("Value").asText());
            tags.add(t);
        }
        return tags;
    }

    private static String text(JsonNode request, String field) {
        JsonNode node = request.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Response ok(Object body) {
        return Response.ok(body).build();
    }
}
