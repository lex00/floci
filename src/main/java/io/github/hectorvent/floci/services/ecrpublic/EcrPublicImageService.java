package io.github.hectorvent.floci.services.ecrpublic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecr.registry.RegistryHttpClient;
import io.github.hectorvent.floci.services.ecrpublic.model.PublicRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ECR Public images, kept in the {@code registry:2} container ECR uses under
 * {@code <account>/public/<name>}. Docker pushes reach it through the ECR data plane on
 * {@code public.ecr.localhost}; these operations read and write the same repositories.
 */
@ApplicationScoped
public class EcrPublicImageService {

    private static final Logger LOG = Logger.getLogger(EcrPublicImageService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long PART_SIZE = 10L * 1024 * 1024;

    private final EcrRegistryManager registryManager;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();
    private final Map<String, Instant> pushedAt = new ConcurrentHashMap<>();

    private record Upload(String internalName, String location, long nextByte) {}

    /** One image: its digest, the tags on it, and its manifest. */
    public record ImageRecord(String digest, List<String> tags, String manifest, String mediaType, long size,
                              Instant pushedAt) {}

    @Inject
    public EcrPublicImageService(EcrRegistryManager registryManager) {
        this.registryManager = registryManager;
    }

    public String repositoryUri(PublicRepository repository) {
        return registryManager.getPublicRepositoryUri(repository.getRegistryId(), repository.getRepositoryName());
    }

    public String registryUri(String accountId) {
        return registryManager.getPublicRegistryUri(accountId);
    }

    /** The images in a repository, one per digest with every tag that names it. */
    public List<ImageRecord> images(PublicRepository repository) {
        RegistryHttpClient http = registry();
        String internal = internalName(repository);
        Map<String, ImageRecord> byDigest = new LinkedHashMap<>();
        try {
            for (String tag : http.listTags(internal)) {
                RegistryHttpClient.ManifestResult manifest = http.getManifest(internal, tag, null);
                if (manifest == null || manifest.digest() == null) {
                    continue;
                }
                ImageRecord existing = byDigest.get(manifest.digest());
                List<String> tags = existing != null ? new ArrayList<>(existing.tags()) : new ArrayList<>();
                tags.add(tag);
                byDigest.put(manifest.digest(), new ImageRecord(manifest.digest(), tags, manifest.body(),
                        manifest.mediaType(), RegistryHttpClient.sizeFromManifest(manifest.body()),
                        pushedAt(internal, manifest.digest())));
            }
        } catch (IOException e) {
            throw registryFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryFailure(e);
        }
        return new ArrayList<>(byDigest.values());
    }

    /** The image a tag or digest names, or {@code null}. */
    public ImageRecord image(PublicRepository repository, String reference) {
        String internal = internalName(repository);
        try {
            RegistryHttpClient.ManifestResult manifest = registry().getManifest(internal, reference, null);
            if (manifest == null) {
                return null;
            }
            String digest = manifest.digest() != null ? manifest.digest() : reference;
            List<String> tags = images(repository).stream()
                    .filter(image -> image.digest().equals(digest))
                    .findFirst().map(ImageRecord::tags).orElse(List.of());
            return new ImageRecord(digest, tags, manifest.body(), manifest.mediaType(),
                    RegistryHttpClient.sizeFromManifest(manifest.body()), pushedAt(internal, digest));
        } catch (IOException e) {
            throw registryFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryFailure(e);
        }
    }

    /** Whether the repository holds an image. Without a reachable registry it holds none. */
    public boolean hasImages(PublicRepository repository) {
        return registryManager.tryEnsureStarted() && !images(repository).isEmpty();
    }

    /** Deletes the manifest a digest or tag names, returning its digest, or {@code null} when absent. */
    public String deleteImage(PublicRepository repository, String tag, String digest) {
        String internal = internalName(repository);
        try {
            RegistryHttpClient http = registry();
            String resolved = digest != null ? digest : http.headManifestDigest(internal, tag, null);
            if (resolved == null || !http.deleteManifest(internal, resolved)) {
                return null;
            }
            pushedAt.remove(internal + "@" + resolved);
            return resolved;
        } catch (IOException e) {
            throw registryFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryFailure(e);
        }
    }

    /** Removes every image of a repository, for a forced DeleteRepository. */
    public void deleteAll(PublicRepository repository) {
        if (registryManager.tryEnsureStarted()) {
            registryManager.deleteRepositoryStorageByInternalName(internalName(repository));
        }
    }

    /** Stores a manifest under its tag, or under its digest when no tag is given. */
    public String putImage(PublicRepository repository, String manifest, String mediaType, String tag,
                           String digest) {
        if (manifest == null || manifest.isBlank()) {
            throw new AwsException("InvalidParameterException", "imageManifest is required", 400);
        }
        String resolvedType = mediaType != null ? mediaType : manifestMediaType(manifest);
        String internal = internalName(repository);
        try {
            RegistryHttpClient http = registry();
            if (tag != null && digestOf(http.headManifestDigest(internal, tag, null), digest, manifest)) {
                throw new AwsException("ImageAlreadyExistsException", "Image with digest '"
                        + http.headManifestDigest(internal, tag, null) + "' and tag '" + tag
                        + "' already exists in the repository with name '" + repository.getRepositoryName()
                        + "' in registry with id '" + repository.getRegistryId() + "'", 400);
            }
            RegistryHttpClient.PutManifestResult result = http.putManifest(internal,
                    tag != null ? tag : digest, manifest, resolvedType);
            if (!result.created()) {
                if (result.error() != null && result.error().contains("BLOB_UNKNOWN")) {
                    throw new AwsException("LayersNotFoundException",
                            "The image manifest references layers the repository does not hold.", 400);
                }
                throw new AwsException("InvalidParameterException",
                        "The image manifest was rejected: " + result.error(), 400);
            }
            pushedAt.putIfAbsent(internal + "@" + result.digest(), Instant.now());
            return result.digest();
        } catch (IOException e) {
            throw registryFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryFailure(e);
        }
    }

    /** The size of each layer the repository holds, keyed by digest, {@code null} for one it does not. */
    public Map<String, Long> layerSizes(PublicRepository repository, List<String> digests) {
        String internal = internalName(repository);
        Map<String, Long> sizes = new LinkedHashMap<>();
        try {
            RegistryHttpClient http = registry();
            for (String digest : digests) {
                sizes.put(digest, digest.startsWith("sha256:") ? http.headBlobSize(internal, digest) : null);
            }
        } catch (IOException e) {
            throw registryFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryFailure(e);
        }
        return sizes;
    }

    public String initiateLayerUpload(PublicRepository repository) {
        String internal = internalName(repository);
        try {
            String location = registry().startBlobUpload(internal);
            String uploadId = UUID.randomUUID().toString();
            uploads.put(uploadId, new Upload(internal, location, 0));
            return uploadId;
        } catch (IOException e) {
            throw registryFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryFailure(e);
        }
    }

    public long partSize() {
        return PART_SIZE;
    }

    /** Appends one part and returns the last byte received. */
    public long uploadLayerPart(PublicRepository repository, String uploadId, long firstByte, long lastByte,
                                byte[] part) {
        Upload upload = upload(repository, uploadId);
        if (firstByte != upload.nextByte() || lastByte - firstByte + 1 != part.length) {
            throw new AwsException("InvalidLayerPartException", "The layer part does not follow the bytes "
                    + "already received, or its size does not match its byte range.", 400);
        }
        try {
            String location = registry().uploadBlobChunk(upload.location(), part, firstByte);
            uploads.put(uploadId, new Upload(upload.internalName(), location, lastByte + 1));
            return lastByte;
        } catch (IOException e) {
            throw registryFailure(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryFailure(e);
        }
    }

    public String completeLayerUpload(PublicRepository repository, String uploadId, String digest) {
        Upload upload = upload(repository, uploadId);
        if (digest == null || !digest.startsWith("sha256:")) {
            throw new AwsException("InvalidLayerException", "layerDigests must name one sha256 digest.", 400);
        }
        try {
            if (registry().headBlobSize(upload.internalName(), digest) != null) {
                uploads.remove(uploadId);
                throw new AwsException("LayerAlreadyExistsException",
                        "The layer " + digest + " already exists in the repository.", 400);
            }
            String stored = registry().completeBlobUpload(upload.location(), digest);
            uploads.remove(uploadId);
            return stored;
        } catch (IOException e) {
            uploads.remove(uploadId);
            throw new AwsException("InvalidLayerException",
                    "The layer digest does not match the uploaded bytes: " + e.getMessage(), 400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw registryFailure(e);
        }
    }

    private Upload upload(PublicRepository repository, String uploadId) {
        Upload upload = uploadId == null ? null : uploads.get(uploadId);
        if (upload == null || !upload.internalName().equals(internalName(repository))) {
            throw new AwsException("UploadNotFoundException", "The upload " + uploadId + " does not exist.", 400);
        }
        return upload;
    }

    private RegistryHttpClient registry() {
        if (!registryManager.tryEnsureStarted()) {
            throw new AwsException("ServerException", "The image registry is unavailable because no Docker "
                    + "daemon is reachable from Floci. Repository operations still work; images need Docker.", 500);
        }
        return registryManager.httpClient();
    }

    private Instant pushedAt(String internal, String digest) {
        return pushedAt.computeIfAbsent(internal + "@" + digest, ignored -> Instant.now());
    }

    private static String internalName(PublicRepository repository) {
        return repository.getRegistryId() + "/" + EcrRegistryManager.PUBLIC_NAMESPACE + "/"
                + repository.getRepositoryName();
    }

    /** Whether the tag already names the manifest being put, so the put changes nothing. */
    private static boolean digestOf(String existing, String requestedDigest, String manifest) {
        if (existing == null) {
            return false;
        }
        return existing.equals(requestedDigest) || existing.equals(sha256(manifest));
    }

    private static String sha256(String manifest) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(manifest.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String manifestMediaType(String manifest) {
        try {
            JsonNode node = MAPPER.readTree(manifest);
            if (node.hasNonNull("mediaType")) {
                return node.get("mediaType").asText();
            }
        } catch (IOException e) {
            throw new AwsException("InvalidParameterException", "imageManifest is not valid JSON", 400);
        }
        return "application/vnd.oci.image.manifest.v1+json";
    }

    public static byte[] decodePart(String base64) {
        if (base64 == null) {
            throw new AwsException("InvalidParameterException", "layerPartBlob is required", 400);
        }
        return Base64.getDecoder().decode(base64);
    }

    private static AwsException registryFailure(Exception e) {
        LOG.warnv("ECR Public registry call failed: {0}", e.getMessage());
        return new AwsException("ServerException", "The image registry could not be reached: " + e.getMessage(), 500);
    }
}
