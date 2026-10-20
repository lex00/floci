package io.github.hectorvent.floci.services.datazone;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.datazone.DataZoneClient;
import software.amazon.awssdk.services.datazone.model.AssetFilterConfiguration;
import software.amazon.awssdk.services.datazone.model.ChangeAction;
import software.amazon.awssdk.services.datazone.model.ColumnFilterConfiguration;
import software.amazon.awssdk.services.datazone.model.ConflictException;
import software.amazon.awssdk.services.datazone.model.CreateAssetResponse;
import software.amazon.awssdk.services.datazone.model.CreateDataProductResponse;
import software.amazon.awssdk.services.datazone.model.CreateListingChangeSetResponse;
import software.amazon.awssdk.services.datazone.model.DataProductItem;
import software.amazon.awssdk.services.datazone.model.DataProductItemType;
import software.amazon.awssdk.services.datazone.model.EntityType;
import software.amazon.awssdk.services.datazone.model.Filter;
import software.amazon.awssdk.services.datazone.model.FilterClause;
import software.amazon.awssdk.services.datazone.model.FormInput;
import software.amazon.awssdk.services.datazone.model.GetAssetResponse;
import software.amazon.awssdk.services.datazone.model.GetListingResponse;
import software.amazon.awssdk.services.datazone.model.InventorySearchScope;
import software.amazon.awssdk.services.datazone.model.ListingStatus;
import software.amazon.awssdk.services.datazone.model.Model;
import software.amazon.awssdk.services.datazone.model.ResourceNotFoundException;
import software.amazon.awssdk.services.datazone.model.SearchListingsResponse;
import software.amazon.awssdk.services.datazone.model.SearchResponse;
import software.amazon.awssdk.services.datazone.model.TypesSearchScope;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DataZoneCatalogSdkIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Test
    void assetsAreRevisedFilteredPublishedAndSearched() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("catalog-" + suffix())).id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("producers")).id();
            String glossaryId = dz.createGlossary(r -> r.domainIdentifier(domainId).name("business")
                    .owningProjectIdentifier(projectId)).id();
            String termId = dz.createGlossaryTerm(r -> r.domainIdentifier(domainId).glossaryIdentifier(glossaryId)
                    .name("revenue").shortDescription("Money in")).id();
            dz.createFormType(r -> r.domainIdentifier(domainId).name("QualityForm").owningProjectIdentifier(projectId)
                    .model(Model.fromSmithy("structure QualityForm { score: Integer }")));

            CreateAssetResponse created = dz.createAsset(r -> r.domainIdentifier(domainId).name("orders")
                    .typeIdentifier("amazon.datazone.GlueTableAssetType").owningProjectIdentifier(projectId)
                    .externalIdentifier("arn:aws:glue:us-east-1:000000000000:table/sales/orders")
                    .glossaryTerms(termId)
                    .formsInput(FormInput.builder().formName("QualityForm").typeIdentifier("QualityForm")
                            .typeRevision("1").content("{\"score\": 9}").build()));
            assertEquals("1", created.revision());
            assertNotNull(created.createdAt());
            assertEquals("QualityForm", created.formsOutput().getFirst().typeName());

            dz.createAssetRevision(r -> r.domainIdentifier(domainId).identifier(created.id())
                    .name("orders").description("All orders"));
            assertEquals("2", dz.getAsset(r -> r.domainIdentifier(domainId).identifier(created.id())).revision());
            assertEquals("orders", dz.getAsset(r -> r.domainIdentifier(domainId).identifier(created.id())
                    .revision("1")).name());
            assertEquals(List.of("2", "1"), dz.listAssetRevisions(r -> r.domainIdentifier(domainId)
                    .identifier(created.id())).items().stream().map(i -> i.revision()).toList());
            assertEquals("2", dz.acceptPredictions(r -> r.domainIdentifier(domainId).identifier(created.id()))
                    .revision());

            String filterId = dz.createAssetFilter(r -> r.domainIdentifier(domainId).assetIdentifier(created.id())
                    .name("no-pii").configuration(AssetFilterConfiguration.fromColumnConfiguration(
                            ColumnFilterConfiguration.builder().includedColumnNames("id", "total").build()))).id();
            assertEquals(List.of("id", "total"), dz.getAssetFilter(r -> r.domainIdentifier(domainId)
                    .assetIdentifier(created.id()).identifier(filterId)).effectiveColumnNames());
            assertThrows(ConflictException.class, () -> dz.createAssetFilter(r -> r.domainIdentifier(domainId)
                    .assetIdentifier(created.id()).name("no-pii")
                    .configuration(AssetFilterConfiguration.fromColumnConfiguration(
                            ColumnFilterConfiguration.builder().includedColumnNames("id").build()))));
            assertEquals("Columns", dz.updateAssetFilter(r -> r.domainIdentifier(domainId)
                    .assetIdentifier(created.id()).identifier(filterId).description("Columns")).description());
            assertEquals(1, dz.listAssetFilters(r -> r.domainIdentifier(domainId).assetIdentifier(created.id()))
                    .items().size());

            CreateListingChangeSetResponse published = dz.createListingChangeSet(r -> r.domainIdentifier(domainId)
                    .entityIdentifier(created.id()).entityType(EntityType.ASSET).action(ChangeAction.PUBLISH));
            assertEquals(ListingStatus.ACTIVE, published.status());
            GetAssetResponse listed = dz.getAsset(r -> r.domainIdentifier(domainId).identifier(created.id()));
            assertEquals(published.listingId(), listed.listing().listingId());
            GetListingResponse listing = dz.getListing(r -> r.domainIdentifier(domainId)
                    .identifier(published.listingId()));
            assertEquals("2", listing.item().assetListing().assetRevision());
            assertEquals("revenue", listing.item().assetListing().glossaryTerms().getFirst().name());
            assertTrue(listing.item().assetListing().forms().contains("\"score\":9"));

            SearchListingsResponse found = dz.searchListings(r -> r.domainIdentifier(domainId).searchText("ORDERS"));
            assertEquals(1, found.totalMatchCount());
            assertEquals(created.id(), found.items().getFirst().assetListing().entityId());
            assertEquals(0, dz.searchListings(r -> r.domainIdentifier(domainId).filters(FilterClause.fromFilter(
                    Filter.builder().attribute("owningProjectId").value("other").build()))).totalMatchCount());

            SearchResponse assets = dz.search(r -> r.domainIdentifier(domainId)
                    .searchScope(InventorySearchScope.ASSET).owningProjectIdentifier(projectId));
            assertEquals(created.id(), assets.items().getFirst().assetItem().identifier());
            assertEquals("revenue", dz.search(r -> r.domainIdentifier(domainId)
                    .searchScope(InventorySearchScope.GLOSSARY_TERM).searchText("rev")).items().getFirst()
                    .glossaryTermItem().name());
            assertEquals("QualityForm", dz.searchTypes(r -> r.domainIdentifier(domainId)
                    .searchScope(TypesSearchScope.FORM_TYPE).managed(false)).items().getFirst()
                    .formTypeItem().name());

            assertEquals(ListingStatus.INACTIVE, dz.createListingChangeSet(r -> r.domainIdentifier(domainId)
                    .entityIdentifier(created.id()).entityType(EntityType.ASSET).action(ChangeAction.UNPUBLISH))
                    .status());
            assertEquals(0, dz.searchListings(r -> r.domainIdentifier(domainId)).totalMatchCount());
            dz.deleteListing(r -> r.domainIdentifier(domainId).identifier(published.listingId()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getListing(r -> r.domainIdentifier(domainId)
                    .identifier(published.listingId())));

            dz.deleteAssetFilter(r -> r.domainIdentifier(domainId).assetIdentifier(created.id())
                    .identifier(filterId));
            dz.deleteAsset(r -> r.domainIdentifier(domainId).identifier(created.id()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getAsset(r -> r.domainIdentifier(domainId)
                    .identifier(created.id())));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void dataProductPublishesItsAssets() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("products-" + suffix())).id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("producers")).id();
            String assetId = dz.createAsset(r -> r.domainIdentifier(domainId).name("customers")
                    .typeIdentifier("amazon.datazone.GlueTableAssetType").owningProjectIdentifier(projectId)).id();
            assertThrows(ResourceNotFoundException.class, () -> dz.createAsset(r -> r.domainIdentifier(domainId)
                    .name("x").typeIdentifier("MissingType").owningProjectIdentifier(projectId)));

            CreateDataProductResponse product = dz.createDataProduct(r -> r.domainIdentifier(domainId)
                    .name("customer-360").owningProjectIdentifier(projectId)
                    .items(DataProductItem.builder().itemType(DataProductItemType.ASSET).identifier(assetId).build()));
            assertEquals("1", product.items().getFirst().revision());
            dz.createDataProductRevision(r -> r.domainIdentifier(domainId).identifier(product.id())
                    .name("customer-360").description("Everything about customers"));
            assertEquals(2, dz.listDataProductRevisions(r -> r.domainIdentifier(domainId).identifier(product.id()))
                    .items().size());
            assertEquals("Everything about customers", dz.getDataProduct(r -> r.domainIdentifier(domainId)
                    .identifier(product.id())).description());

            String listingId = dz.createListingChangeSet(r -> r.domainIdentifier(domainId)
                    .entityIdentifier(product.id()).entityType(EntityType.DATA_PRODUCT)
                    .action(ChangeAction.PUBLISH)).listingId();
            GetListingResponse listing = dz.getListing(r -> r.domainIdentifier(domainId).identifier(listingId));
            assertEquals(product.id(), listing.item().dataProductListing().dataProductId());
            String assetListingId = listing.item().dataProductListing().items().getFirst().listingId();
            assertEquals(assetId, dz.getListing(r -> r.domainIdentifier(domainId).identifier(assetListingId))
                    .item().assetListing().assetId());
            assertEquals(2, dz.searchListings(r -> r.domainIdentifier(domainId)).totalMatchCount());

            assertThrows(ConflictException.class, () -> dz.deleteAsset(r -> r.domainIdentifier(domainId)
                    .identifier(assetId)));
            dz.deleteDataProduct(r -> r.domainIdentifier(domainId).identifier(product.id()));
            assertThrows(ResourceNotFoundException.class, () -> dz.getDataProduct(r -> r.domainIdentifier(domainId)
                    .identifier(product.id())));
            dz.deleteAsset(r -> r.domainIdentifier(domainId).identifier(assetId));
            assertEquals(0, dz.searchListings(r -> r.domainIdentifier(domainId)).totalMatchCount());
            dz.deleteDomain(r -> r.identifier(domainId));
        }
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
