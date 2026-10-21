package io.github.hectorvent.floci.services.datazone;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.datazone.DataZoneClient;
import software.amazon.awssdk.services.datazone.model.ChangeAction;
import software.amazon.awssdk.services.datazone.model.ConflictException;
import software.amazon.awssdk.services.datazone.model.CreateSubscriptionRequestResponse;
import software.amazon.awssdk.services.datazone.model.EntityType;
import software.amazon.awssdk.services.datazone.model.FailureCause;
import software.amazon.awssdk.services.datazone.model.GrantedEntityInput;
import software.amazon.awssdk.services.datazone.model.ListingRevisionInput;
import software.amazon.awssdk.services.datazone.model.ResourceNotFoundException;
import software.amazon.awssdk.services.datazone.model.SubscribedListingInput;
import software.amazon.awssdk.services.datazone.model.SubscribedPrincipalInput;
import software.amazon.awssdk.services.datazone.model.SubscribedProjectInput;
import software.amazon.awssdk.services.datazone.model.SubscriptionGrantCreationMode;
import software.amazon.awssdk.services.datazone.model.SubscriptionGrantOverallStatus;
import software.amazon.awssdk.services.datazone.model.SubscriptionGrantStatus;
import software.amazon.awssdk.services.datazone.model.SubscriptionGrantSummary;
import software.amazon.awssdk.services.datazone.model.SubscriptionRequestStatus;
import software.amazon.awssdk.services.datazone.model.SubscriptionStatus;
import software.amazon.awssdk.services.datazone.model.SubscriptionSummary;
import software.amazon.awssdk.services.datazone.model.SubscriptionTargetForm;

import java.net.URI;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DataZoneSubscriptionSdkIntegrationTest {

    private static final String GLUE_TABLE = "amazon.datazone.GlueTableAssetType";

    @TestHTTPResource
    URI endpoint;

    @Test
    void acceptedRequestIsGrantedToTheAutomaticTargetAndRevoked() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("subscriptions-" + suffix())).id();
            String producerId = dz.createProject(r -> r.domainIdentifier(domainId).name("producers")).id();
            String consumerId = dz.createProject(r -> r.domainIdentifier(domainId).name("consumers")).id();
            String assetId = dz.createAsset(r -> r.domainIdentifier(domainId).name("orders")
                    .typeIdentifier(GLUE_TABLE).owningProjectIdentifier(producerId)).id();
            String listingId = publish(dz, domainId, assetId);
            String environmentId = environment(dz, domainId, consumerId);

            String targetId = dz.createSubscriptionTarget(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).name("lake").type("GlueSubscriptionTargetType")
                    .manageAccessRole("arn:aws:iam::000000000000:role/manage-access")
                    .applicableAssetTypes(GLUE_TABLE).authorizedPrincipals("arn:aws:iam::000000000000:root")
                    .subscriptionTargetConfig(SubscriptionTargetForm.builder().formName("GlueSubscriptionTargetConfigForm")
                            .content("{\"databaseName\": \"consumer_db\"}").build())).id();
            assertEquals(consumerId, dz.getSubscriptionTarget(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).identifier(targetId)).projectId());
            assertThrows(ConflictException.class, () -> dz.createSubscriptionTarget(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).name("lake").type("GlueSubscriptionTargetType")
                    .manageAccessRole("arn:aws:iam::000000000000:role/manage-access")
                    .applicableAssetTypes(GLUE_TABLE).authorizedPrincipals("arn:aws:iam::000000000000:root")
                    .subscriptionTargetConfig(SubscriptionTargetForm.builder().formName("f").content("{}").build())));

            CreateSubscriptionRequestResponse requested = request(dz, domainId, consumerId, listingId);
            assertEquals(SubscriptionRequestStatus.PENDING, requested.status());
            assertEquals(listingId, requested.subscribedListings().getFirst().id());
            assertEquals(producerId, requested.subscribedListings().getFirst().ownerProjectId());
            assertEquals("consumers", requested.subscribedPrincipals().getFirst().project().name());
            assertEquals("Quarterly report", dz.updateSubscriptionRequest(r -> r.domainIdentifier(domainId)
                    .identifier(requested.id()).requestReason("Quarterly report")).requestReason());
            assertEquals(1, dz.listSubscriptionRequests(r -> r.domainIdentifier(domainId)
                    .status(SubscriptionRequestStatus.PENDING).approverProjectId(producerId)).items().size());

            assertEquals(SubscriptionRequestStatus.ACCEPTED, dz.acceptSubscriptionRequest(r -> r
                    .domainIdentifier(domainId).identifier(requested.id()).decisionComment("ok")).status());
            assertThrows(ConflictException.class, () -> dz.acceptSubscriptionRequest(r -> r
                    .domainIdentifier(domainId).identifier(requested.id())));
            assertThrows(ConflictException.class, () -> request(dz, domainId, consumerId, listingId));

            SubscriptionSummary subscription = dz.listSubscriptions(r -> r.domainIdentifier(domainId)
                    .subscriptionRequestIdentifier(requested.id())).items().getFirst();
            assertEquals(SubscriptionStatus.APPROVED, subscription.status());
            assertEquals(consumerId, subscription.subscribedPrincipal().project().id());

            SubscriptionGrantSummary grant = dz.listSubscriptionGrants(r -> r.domainIdentifier(domainId)
                    .subscriptionId(subscription.id())).items().getFirst();
            assertEquals(targetId, grant.subscriptionTargetId());
            assertEquals(SubscriptionGrantOverallStatus.COMPLETED, grant.status());
            assertEquals(assetId, grant.assets().getFirst().assetId());
            assertEquals(SubscriptionGrantStatus.GRANTED, grant.assets().getFirst().status());

            assertEquals(SubscriptionGrantOverallStatus.GRANT_FAILED, dz.updateSubscriptionGrantStatus(r -> r
                    .domainIdentifier(domainId).identifier(grant.id()).assetIdentifier(assetId)
                    .status(SubscriptionGrantStatus.GRANT_FAILED)
                    .failureCause(FailureCause.builder().message("no lake formation").build())).status());
            assertNotNull(dz.getSubscriptionGrant(r -> r.domainIdentifier(domainId).identifier(grant.id()))
                    .assets().getFirst().failureTimestamp());

            assertEquals(SubscriptionStatus.REVOKED, dz.revokeSubscription(r -> r.domainIdentifier(domainId)
                    .identifier(subscription.id())).status());
            assertEquals(SubscriptionGrantStatus.REVOKED, dz.getSubscriptionGrant(r -> r.domainIdentifier(domainId)
                    .identifier(grant.id())).assets().getFirst().status());
            assertThrows(ConflictException.class, () -> dz.cancelSubscription(r -> r.domainIdentifier(domainId)
                    .identifier(subscription.id())));

            assertThrows(ConflictException.class, () -> dz.deleteSubscriptionTarget(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).identifier(targetId)));
            dz.deleteSubscriptionGrant(r -> r.domainIdentifier(domainId).identifier(grant.id()));
            dz.deleteSubscriptionTarget(r -> r.domainIdentifier(domainId).environmentIdentifier(environmentId)
                    .identifier(targetId));
            assertThrows(ResourceNotFoundException.class, () -> dz.getSubscriptionTarget(r -> r
                    .domainIdentifier(domainId).environmentIdentifier(environmentId).identifier(targetId)));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void manualTargetIsGrantedOnRequestAndRejectedRequestsCreateNothing() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("manual-" + suffix())).id();
            String producerId = dz.createProject(r -> r.domainIdentifier(domainId).name("producers")).id();
            String consumerId = dz.createProject(r -> r.domainIdentifier(domainId).name("consumers")).id();
            String assetId = dz.createAsset(r -> r.domainIdentifier(domainId).name("customers")
                    .typeIdentifier(GLUE_TABLE).owningProjectIdentifier(producerId)).id();
            String listingId = publish(dz, domainId, assetId);
            String environmentId = environment(dz, domainId, consumerId);
            String targetId = dz.createSubscriptionTarget(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).name("manual").type("GlueSubscriptionTargetType")
                    .manageAccessRole("arn:aws:iam::000000000000:role/manage-access")
                    .applicableAssetTypes(GLUE_TABLE).authorizedPrincipals("arn:aws:iam::000000000000:root")
                    .subscriptionGrantCreationMode(SubscriptionGrantCreationMode.MANUAL)
                    .subscriptionTargetConfig(SubscriptionTargetForm.builder().formName("f").content("{}").build()))
                    .id();

            String rejectedId = request(dz, domainId, consumerId, listingId).id();
            assertEquals(SubscriptionRequestStatus.REJECTED, dz.rejectSubscriptionRequest(r -> r
                    .domainIdentifier(domainId).identifier(rejectedId).decisionComment("no")).status());
            assertEquals("no", dz.getSubscriptionRequestDetails(r -> r.domainIdentifier(domainId)
                    .identifier(rejectedId)).decisionComment());
            assertTrue(dz.listSubscriptions(r -> r.domainIdentifier(domainId)).items().isEmpty());
            dz.deleteSubscriptionRequest(r -> r.domainIdentifier(domainId).identifier(rejectedId));
            assertThrows(ResourceNotFoundException.class, () -> dz.getSubscriptionRequestDetails(r -> r
                    .domainIdentifier(domainId).identifier(rejectedId)));

            String acceptedId = request(dz, domainId, consumerId, listingId).id();
            dz.acceptSubscriptionRequest(r -> r.domainIdentifier(domainId).identifier(acceptedId));
            assertTrue(dz.listSubscriptionGrants(r -> r.domainIdentifier(domainId)).items().isEmpty());

            String revision = dz.getListing(r -> r.domainIdentifier(domainId).identifier(listingId)).listingRevision();
            String grantId = dz.createSubscriptionGrant(r -> r.domainIdentifier(domainId)
                    .environmentIdentifier(environmentId).subscriptionTargetIdentifier(targetId)
                    .grantedEntity(GrantedEntityInput.fromListing(ListingRevisionInput.builder()
                            .identifier(listingId).revision(revision).build()))).id();
            String subscriptionId = dz.listSubscriptions(r -> r.domainIdentifier(domainId)).items().getFirst().id();
            assertEquals(subscriptionId, dz.getSubscriptionGrant(r -> r.domainIdentifier(domainId)
                    .identifier(grantId)).subscriptionId());

            assertEquals(SubscriptionStatus.CANCELLED, dz.cancelSubscription(r -> r.domainIdentifier(domainId)
                    .identifier(subscriptionId)).status());
            assertFalse(dz.getSubscription(r -> r.domainIdentifier(domainId).identifier(subscriptionId))
                    .retainPermissions());
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    private static CreateSubscriptionRequestResponse request(DataZoneClient dz, String domainId, String projectId,
                                                             String listingId) {
        return dz.createSubscriptionRequest(r -> r.domainIdentifier(domainId).requestReason("Monthly report")
                .subscribedPrincipals(SubscribedPrincipalInput.fromProject(SubscribedProjectInput.builder()
                        .identifier(projectId).build()))
                .subscribedListings(SubscribedListingInput.builder().identifier(listingId).build()));
    }

    private static String publish(DataZoneClient dz, String domainId, String assetId) {
        return dz.createListingChangeSet(r -> r.domainIdentifier(domainId).entityIdentifier(assetId)
                .entityType(EntityType.ASSET).action(ChangeAction.PUBLISH)).listingId();
    }

    private static String environment(DataZoneClient dz, String domainId, String projectId) {
        String blueprintId = dz.listEnvironmentBlueprints(r -> r.domainIdentifier(domainId).managed(true)
                .name("DefaultDataLake")).items().getFirst().id();
        dz.putEnvironmentBlueprintConfiguration(r -> r.domainIdentifier(domainId)
                .environmentBlueprintIdentifier(blueprintId).enabledRegions("us-east-1")
                .provisioningRoleArn("arn:aws:iam::000000000000:role/provisioning"));
        String profileId = dz.createEnvironmentProfile(r -> r.domainIdentifier(domainId).name("lake")
                .environmentBlueprintIdentifier(blueprintId).projectIdentifier(projectId)
                .awsAccountId("000000000000").awsAccountRegion("us-east-1")).id();
        return dz.createEnvironment(r -> r.domainIdentifier(domainId).name("consumer-env")
                .projectIdentifier(projectId).environmentProfileIdentifier(profileId)).id();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private DataZoneClient client() {
        return DataZoneClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(Apache5HttpClient.builder()).build();
    }
}
