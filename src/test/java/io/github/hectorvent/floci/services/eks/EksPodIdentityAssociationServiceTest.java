package io.github.hectorvent.floci.services.eks;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import io.github.hectorvent.floci.services.eks.model.ClusterStatus;
import io.github.hectorvent.floci.services.eks.model.CreatePodIdentityAssociationRequest;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociation;
import io.github.hectorvent.floci.services.eks.model.PodIdentityAssociationSummary;
import io.github.hectorvent.floci.services.eks.model.UpdatePodIdentityAssociationRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EksPodIdentityAssociationServiceTest {
    private static final String ROLE = "arn:aws:iam::123456789012:role/app";
    private static final String OTHER_ROLE = "arn:aws:iam::123456789012:role/other";

    private final InMemoryStorage<String, EksPodIdentityAssociationService.StoredAssociation> storage =
            new InMemoryStorage<>();
    private final EksPodIdentityAssociationService service = new EksPodIdentityAssociationService(storage);
    private final Cluster cluster = cluster();

    @Test
    void createReturnsAwsShapedAssociation() throws Exception {
        PodIdentityAssociation association = service.create(cluster, request("default", "app", "token"));
        assertEquals("pods", association.clusterName());
        assertEquals("default", association.namespace());
        assertEquals("app", association.serviceAccount());
        assertEquals(ROLE, association.roleArn());
        assertTrue(association.associationId().matches("a-[0-9a-z]{17}"), association.associationId());
        assertEquals("arn:aws:eks:us-east-1:123456789012:podidentityassociation/pods/"
                + association.associationId(), association.associationArn());
        assertEquals(Map.of("team", "platform"), association.tags());
        assertFalse(association.disableSessionTags());
        assertNull(association.targetRoleArn());
        assertNull(association.externalId());
        assertTrue(association.createdAt() > 0);
        assertEquals(association.createdAt(), association.modifiedAt());
        String json = new ObjectMapper().writeValueAsString(association);
        assertFalse(json.contains("ownerArn"));
        assertFalse(json.contains("targetRoleArn"));
        assertEquals(association, service.describe(cluster, association.associationId()));
    }

    @Test
    void duplicateNamespaceAndServiceAccountIsResourceInUse() {
        PodIdentityAssociation first = service.create(cluster, request("default", "app", null));
        AwsException duplicate = assertThrows(AwsException.class,
                () -> service.create(cluster, request("default", "app", null)));
        assertEquals("ResourceInUseException", duplicate.getErrorCode());
        assertEquals(409, duplicate.getHttpStatus());
        assertTrue(duplicate.getMessage().contains(first.associationId()));
        service.create(cluster, request("default", "other", null));
        service.create(cluster, request("kube-system", "app", null));
    }

    @Test
    void retryWithSameTokenIsIdempotentButDifferentParametersFail() {
        PodIdentityAssociation first = service.create(cluster, request("default", "app", "retry"));
        assertEquals(first, service.create(cluster, request("default", "app", "retry")));
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.create(cluster, request("default", "other", "retry"))).getErrorCode());
    }

    @Test
    void missingOrMalformedParametersAreInvalid() {
        for (CreatePodIdentityAssociationRequest bad : List.of(
                new CreatePodIdentityAssociationRequest(null, "app", ROLE, null, null, null, null),
                new CreatePodIdentityAssociationRequest("default", "", ROLE, null, null, null, null),
                new CreatePodIdentityAssociationRequest("default", "app", null, null, null, null, null),
                new CreatePodIdentityAssociationRequest("default", "app", "arn:aws:iam::123456789012:user/u",
                        null, null, null, null),
                new CreatePodIdentityAssociationRequest("default", "app", ROLE, null, null, null, "not-an-arn"))) {
            assertEquals("InvalidParameterException",
                    assertThrows(AwsException.class, () -> service.create(cluster, bad)).getErrorCode());
        }
        assertEquals("InvalidParameterException",
                assertThrows(AwsException.class, () -> service.create(cluster, null)).getErrorCode());
    }

    @Test
    void targetRoleGetsAnExternalId() {
        PodIdentityAssociation association = service.create(cluster,
                new CreatePodIdentityAssociationRequest("default", "app", ROLE, null, null, true, OTHER_ROLE));
        assertEquals(OTHER_ROLE, association.targetRoleArn());
        assertNotNull(association.externalId());
        assertTrue(association.disableSessionTags());
    }

    @Test
    void unknownAssociationIsResourceNotFound() {
        for (Runnable call : List.<Runnable>of(
                () -> service.describe(cluster, "a-00000000000000000"),
                () -> service.delete(cluster, "a-00000000000000000"),
                () -> service.update(cluster, "a-00000000000000000",
                        new UpdatePodIdentityAssociationRequest(ROLE, null, null, null)))) {
            AwsException missing = assertThrows(AwsException.class, call::run);
            assertEquals("ResourceNotFoundException", missing.getErrorCode());
            assertEquals(404, missing.getHttpStatus());
        }
    }

    @Test
    void updateChangesOnlySuppliedFields() {
        PodIdentityAssociation created = service.create(cluster, request("default", "app", null));
        PodIdentityAssociation updated = service.update(cluster, created.associationId(),
                new UpdatePodIdentityAssociationRequest(OTHER_ROLE, null, null, null));
        assertEquals(OTHER_ROLE, updated.roleArn());
        assertEquals(created.associationArn(), updated.associationArn());
        assertEquals(created.tags(), updated.tags());
        assertEquals(created.createdAt(), updated.createdAt());
        assertFalse(updated.disableSessionTags());
        assertEquals(updated, service.describe(cluster, created.associationId()));

        PodIdentityAssociation targeted = service.update(cluster, created.associationId(),
                new UpdatePodIdentityAssociationRequest(null, null, true, ROLE));
        assertEquals(OTHER_ROLE, targeted.roleArn());
        assertEquals(ROLE, targeted.targetRoleArn());
        assertNotNull(targeted.externalId());
        assertTrue(targeted.disableSessionTags());

        PodIdentityAssociation cleared = service.update(cluster, created.associationId(),
                new UpdatePodIdentityAssociationRequest(null, null, null, ""));
        assertNull(cleared.targetRoleArn());
        assertNull(cleared.externalId());
        assertTrue(cleared.disableSessionTags());

        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.update(cluster, created.associationId(),
                        new UpdatePodIdentityAssociationRequest("bad", null, null, null))).getErrorCode());
    }

    @Test
    void deleteReturnsTheAssociationAndFreesTheServiceAccount() {
        PodIdentityAssociation created = service.create(cluster, request("default", "app", null));
        assertEquals(created, service.delete(cluster, created.associationId()));
        assertThrows(AwsException.class, () -> service.describe(cluster, created.associationId()));
        assertNotEquals(created.associationId(),
                service.create(cluster, request("default", "app", null)).associationId());
    }

    @Test
    void listFiltersAndPaginates() {
        PodIdentityAssociation a = service.create(cluster, request("default", "a", null));
        PodIdentityAssociation b = service.create(cluster, request("default", "b", null));
        PodIdentityAssociation c = service.create(cluster, request("system", "a", null));

        List<String> all = service.list(cluster, null, null, null, null).items().stream()
                .map(PodIdentityAssociationSummary::associationId).sorted().toList();
        assertEquals(List.of(a.associationId(), b.associationId(), c.associationId()).stream().sorted().toList(), all);

        assertEquals(List.of(PodIdentityAssociationSummary.of(c)),
                service.list(cluster, "system", null, null, null).items());
        assertEquals(List.of(PodIdentityAssociationSummary.of(b)),
                service.list(cluster, "default", "b", null, null).items());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.list(cluster, null, "a", null, null)).getErrorCode());

        PaginatedResult<PodIdentityAssociationSummary> first = service.list(cluster, null, null, 2, null);
        assertEquals(2, first.items().size());
        assertNotNull(first.nextToken());
        PaginatedResult<PodIdentityAssociationSummary> second = service.list(cluster, null, null, 2, first.nextToken());
        assertEquals(1, second.items().size());
        assertNull(second.nextToken());
        assertThrows(AwsException.class, () -> service.list(cluster, null, null, 0, null));
        assertThrows(AwsException.class, () -> service.list(cluster, null, null, 101, null));
    }

    @Test
    void tagsRoundTripThroughTheAssociationArn() {
        PodIdentityAssociation created = service.create(cluster, request("default", "app", null));
        String arn = created.associationArn();
        assertArrayEquals(new String[] {"pods", created.associationId()},
                EksPodIdentityAssociationService.parseAssociationArn(arn));
        assertNull(EksPodIdentityAssociationService.parseAssociationArn(cluster.getArn()));

        service.tagResource(cluster, arn, Map.of("owner", "choudoufu"));
        assertEquals(Map.of("team", "platform", "owner", "choudoufu"), service.listTags(cluster, arn));
        service.untagResource(cluster, arn, List.of("team"));
        assertEquals(Map.of("owner", "choudoufu"), service.describe(cluster, created.associationId()).tags());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.listTags(cluster, arn.replace(created.associationId(), "a-00000000000000000")))
                .getErrorCode());
    }

    @Test
    void clusterDeletionCleansAssociationsAndRecreationCannotInheritThem() {
        PodIdentityAssociation created = service.create(cluster, request("default", "app", null));
        service.deleteClusterAssociations(cluster);
        assertTrue(storage.scan(key -> true).isEmpty());
        service.create(cluster, request("default", "app", null));
        cluster.setCreatedAt(cluster.getCreatedAt().plusSeconds(1));
        assertTrue(service.list(cluster, null, null, null, null).items().isEmpty());
        assertThrows(AwsException.class, () -> service.describe(cluster, created.associationId()));
        service.create(cluster, request("default", "app", null));
    }

    @Test
    void storedAssociationSurvivesJsonRoundTrip() throws Exception {
        service.create(cluster, request("default", "app", "token"));
        EksPodIdentityAssociationService.StoredAssociation stored = storage.scan(key -> true).getFirst();
        ObjectMapper mapper = new ObjectMapper();
        assertEquals(stored, mapper.readValue(mapper.writeValueAsBytes(stored),
                EksPodIdentityAssociationService.StoredAssociation.class));
    }

    private static CreatePodIdentityAssociationRequest request(String namespace, String serviceAccount, String token) {
        return new CreatePodIdentityAssociationRequest(namespace, serviceAccount, ROLE, token,
                Map.of("team", "platform"), null, null);
    }

    private static Cluster cluster() {
        Cluster cluster = new Cluster();
        cluster.setName("pods");
        cluster.setArn("arn:aws:eks:us-east-1:123456789012:cluster/pods");
        cluster.setCreatedAt(Instant.parse("2026-10-01T10:00:00Z"));
        cluster.setStatus(ClusterStatus.ACTIVE);
        return cluster;
    }
}
