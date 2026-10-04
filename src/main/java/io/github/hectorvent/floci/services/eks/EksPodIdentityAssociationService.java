package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociationSummary;
import io.github.hectorvent.floci.services.eks.model.UpdatePodIdentityAssociationRequest;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * EKS Pod Identity association metadata. Associations are management-plane records only:
 * no credentials are vended to pods by this service.
 */
@ApplicationScoped
public class EksPodIdentityAssociationService {
    private static final Pattern ROLE_ARN = Pattern.compile("arn:aws[a-z-]*:iam::[0-9]{12}:role/.+");
    private static final String ARN_MARKER = ":podidentityassociation/";

    private final StorageBackend<String, StoredAssociation> associations;

    @Inject
    public EksPodIdentityAssociationService(StorageFactory factory) {
        this(factory.create("eks", "eks-pod-identity-associations.json",
                new TypeReference<Map<String, StoredAssociation>>() {}));
    }

    EksPodIdentityAssociationService(StorageBackend<String, StoredAssociation> associations) {
        this.associations = associations;
    }

    @RegisterForReflection
    public record StoredAssociation(PodIdentityAssociation association, String clientRequestToken) {}

    public synchronized PodIdentityAssociation create(Cluster cluster, CreatePodIdentityAssociationRequest request) {
        if (request == null) {
            throw invalid("namespace, serviceAccount and roleArn are required");
        }
        String namespace = required(request.namespace(), "namespace");
        String serviceAccount = required(request.serviceAccount(), "serviceAccount");
        String roleArn = roleArn(required(request.roleArn(), "roleArn"), "roleArn");
        String targetRoleArn = blankToNull(request.targetRoleArn());
        if (targetRoleArn != null) {
            roleArn(targetRoleArn, "targetRoleArn");
        }
        boolean disableSessionTags = Boolean.TRUE.equals(request.disableSessionTags());
        Map<String, String> tags = tags(request.tags());
        String prefix = prefix(cluster);
        String token = request.clientRequestToken();
        if (token != null) {
            for (StoredAssociation stored : associations.scan(key -> key.startsWith(prefix))) {
                if (token.equals(stored.clientRequestToken())) {
                    PodIdentityAssociation previous = stored.association();
                    if (namespace.equals(previous.namespace()) && serviceAccount.equals(previous.serviceAccount())
                            && roleArn.equals(previous.roleArn()) && tags.equals(previous.tags())
                            && disableSessionTags == previous.disableSessionTags()
                            && Objects.equals(targetRoleArn, previous.targetRoleArn())) {
                        return previous;
                    }
                    throw invalid("clientRequestToken was already used with different parameters");
                }
            }
        }
        for (StoredAssociation stored : associations.scan(key -> key.startsWith(prefix))) {
            PodIdentityAssociation existing = stored.association();
            if (namespace.equals(existing.namespace()) && serviceAccount.equals(existing.serviceAccount())) {
                throw new AwsException("ResourceInUseException",
                        "Association already exists: " + existing.associationId(), 409);
            }
        }
        String associationId = "a-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17);
        String[] clusterArn = cluster.getArn().split(":", 6);
        double now = Instant.now().toEpochMilli() / 1000.0;
        PodIdentityAssociation association = new PodIdentityAssociation(cluster.getName(), namespace,
                serviceAccount, roleArn, "arn:" + clusterArn[1] + ":eks:" + clusterArn[3] + ":" + clusterArn[4]
                + ARN_MARKER + cluster.getName() + "/" + associationId, associationId, Map.copyOf(tags), now, now,
                null, disableSessionTags, targetRoleArn, targetRoleArn == null ? null : UUID.randomUUID().toString());
        associations.put(prefix + associationId, new StoredAssociation(association, token));
        return association;
    }

    public synchronized PodIdentityAssociation describe(Cluster cluster, String associationId) {
        return find(cluster, associationId).association();
    }

    public synchronized PodIdentityAssociation update(Cluster cluster, String associationId,
                                                      UpdatePodIdentityAssociationRequest request) {
        StoredAssociation stored = find(cluster, associationId);
        PodIdentityAssociation current = stored.association();
        if (request == null) {
            return current;
        }
        String roleArn = request.roleArn() == null ? current.roleArn() : roleArn(request.roleArn(), "roleArn");
        String targetRoleArn = current.targetRoleArn();
        String externalId = current.externalId();
        if (request.targetRoleArn() != null) {
            targetRoleArn = blankToNull(request.targetRoleArn());
            if (targetRoleArn == null) {
                externalId = null;
            } else {
                roleArn(targetRoleArn, "targetRoleArn");
                if (externalId == null) {
                    externalId = UUID.randomUUID().toString();
                }
            }
        }
        boolean disableSessionTags = request.disableSessionTags() == null
                ? current.disableSessionTags() : request.disableSessionTags();
        PodIdentityAssociation updated = new PodIdentityAssociation(current.clusterName(), current.namespace(),
                current.serviceAccount(), roleArn, current.associationArn(), current.associationId(),
                current.tags(), current.createdAt(), Instant.now().toEpochMilli() / 1000.0, current.ownerArn(),
                disableSessionTags, targetRoleArn, externalId);
        associations.put(prefix(cluster) + associationId, new StoredAssociation(updated, stored.clientRequestToken()));
        return updated;
    }

    public synchronized PodIdentityAssociation delete(Cluster cluster, String associationId) {
        PodIdentityAssociation association = find(cluster, associationId).association();
        associations.delete(prefix(cluster) + associationId);
        return association;
    }

    public synchronized PaginatedResult<PodIdentityAssociationSummary> list(Cluster cluster, String namespace,
                                                                            String serviceAccount,
                                                                            Integer maxResults, String nextToken) {
        if (serviceAccount != null && namespace == null) {
            throw invalid("namespace is required when filtering by serviceAccount");
        }
        String prefix = prefix(cluster);
        List<PodIdentityAssociationSummary> matching = associations.scan(key -> key.startsWith(prefix)).stream()
                .map(StoredAssociation::association)
                .filter(association -> namespace == null || namespace.equals(association.namespace()))
                .filter(association -> serviceAccount == null || serviceAccount.equals(association.serviceAccount()))
                .map(PodIdentityAssociationSummary::of)
                .toList();
        return Pagination.paginate(matching, PodIdentityAssociationSummary::associationId, maxResults, nextToken,
                100, "InvalidParameterException");
    }

    public synchronized void deleteClusterAssociations(Cluster cluster) {
        String prefix = prefix(cluster);
        for (StoredAssociation stored : associations.scan(key -> key.startsWith(prefix))) {
            associations.delete(prefix + stored.association().associationId());
        }
    }

    /** Returns the cluster name and association ID of an association ARN, or null for any other ARN. */
    public static String[] parseAssociationArn(String resourceArn) {
        int marker = resourceArn == null ? -1 : resourceArn.indexOf(ARN_MARKER);
        if (marker < 0) {
            return null;
        }
        String[] parts = resourceArn.substring(marker + ARN_MARKER.length()).split("/");
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw invalid("Invalid resource ARN: " + resourceArn);
        }
        return parts;
    }

    public synchronized Map<String, String> listTags(Cluster cluster, String resourceArn) {
        return findByArn(cluster, resourceArn).association().tags();
    }

    public synchronized void tagResource(Cluster cluster, String resourceArn, Map<String, String> tags) {
        Map<String, String> merged = new HashMap<>(listTags(cluster, resourceArn));
        if (tags != null) {
            merged.putAll(tags);
        }
        replaceTags(cluster, resourceArn, tags(merged));
    }

    public synchronized void untagResource(Cluster cluster, String resourceArn, List<String> tagKeys) {
        Map<String, String> remaining = new HashMap<>(listTags(cluster, resourceArn));
        if (tagKeys != null) {
            tagKeys.forEach(remaining::remove);
        }
        replaceTags(cluster, resourceArn, remaining);
    }

    private void replaceTags(Cluster cluster, String resourceArn, Map<String, String> tags) {
        StoredAssociation stored = findByArn(cluster, resourceArn);
        PodIdentityAssociation current = stored.association();
        PodIdentityAssociation updated = new PodIdentityAssociation(current.clusterName(), current.namespace(),
                current.serviceAccount(), current.roleArn(), current.associationArn(), current.associationId(),
                Map.copyOf(tags), current.createdAt(), current.modifiedAt(), current.ownerArn(),
                current.disableSessionTags(), current.targetRoleArn(), current.externalId());
        associations.put(prefix(cluster) + current.associationId(),
                new StoredAssociation(updated, stored.clientRequestToken()));
    }

    private StoredAssociation findByArn(Cluster cluster, String resourceArn) {
        String[] parts = parseAssociationArn(resourceArn);
        return associations.get(prefix(cluster) + parts[1])
                .filter(stored -> stored.association().associationArn().equals(resourceArn))
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "Resource not found: " + resourceArn, 404));
    }

    private StoredAssociation find(Cluster cluster, String associationId) {
        return associations.get(prefix(cluster) + associationId)
                .orElseThrow(() -> new AwsException("ResourceNotFoundException",
                        "No pod identity association found for id: " + associationId, 404));
    }

    private static String prefix(Cluster cluster) {
        // A recreated cluster must not inherit associations from its predecessor.
        return cluster.getArn() + "/" + Objects.toString(cluster.getCreatedAt()) + "/";
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required");
        }
        return value;
    }

    private static String roleArn(String value, String name) {
        if (!ROLE_ARN.matcher(value).matches()) {
            throw invalid(name + " must be an IAM role ARN");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static Map<String, String> tags(Map<String, String> tags) {
        Map<String, String> result = tags == null ? Map.of() : tags;
        if (result.size() > 50 || result.entrySet().stream().anyMatch(tag -> tag.getKey() == null
                || tag.getKey().isEmpty() || tag.getKey().length() > 128 || tag.getValue() == null
                || tag.getValue().length() > 256)) {
            throw invalid("Invalid pod identity association tags");
        }
        return result;
    }

    private static AwsException invalid(String message) {
        return new AwsException("InvalidParameterException", message, 400);
    }
}
