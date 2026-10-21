package io.github.hectorvent.floci.services.datazone;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.datazone.DataZoneClient;
import software.amazon.awssdk.services.datazone.model.AttributeEntityType;
import software.amazon.awssdk.services.datazone.model.AttributeInput;
import software.amazon.awssdk.services.datazone.model.BatchGetAttributesMetadataResponse;
import software.amazon.awssdk.services.datazone.model.EdgeDirection;
import software.amazon.awssdk.services.datazone.model.EntityPattern;
import software.amazon.awssdk.services.datazone.model.FormInput;
import software.amazon.awssdk.services.datazone.model.GetLineageEventResponse;
import software.amazon.awssdk.services.datazone.model.GetLineageNodeResponse;
import software.amazon.awssdk.services.datazone.model.GovernedEntityType;
import software.amazon.awssdk.services.datazone.model.GraphEntityType;
import software.amazon.awssdk.services.datazone.model.LineageEventProcessingStatus;
import software.amazon.awssdk.services.datazone.model.LineageNodeItem;
import software.amazon.awssdk.services.datazone.model.LineageNodeReference;
import software.amazon.awssdk.services.datazone.model.LineageNodeSummary;
import software.amazon.awssdk.services.datazone.model.MatchClause;
import software.amazon.awssdk.services.datazone.model.OpenLineageRunEventSummary;
import software.amazon.awssdk.services.datazone.model.RelationDirection;
import software.amazon.awssdk.services.datazone.model.RelationPattern;
import software.amazon.awssdk.services.datazone.model.RelationType;
import software.amazon.awssdk.services.datazone.model.ResourceNotFoundException;
import software.amazon.awssdk.services.datazone.model.ResultItem;
import software.amazon.awssdk.services.datazone.model.SortOrder;
import software.amazon.awssdk.services.datazone.model.TimeSeriesDataPointFormInput;
import software.amazon.awssdk.services.datazone.model.TimeSeriesDataPointFormOutput;
import software.amazon.awssdk.services.datazone.model.TimeSeriesEntityType;
import software.amazon.awssdk.services.datazone.model.ValidationException;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DataZoneLineageSdkIntegrationTest {

    private static final Instant FIRST = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-01-02T00:00:00Z");

    @TestHTTPResource
    URI endpoint;

    @Test
    void runEventsBuildALineageGraph() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("lineage-" + suffix())).id();
            String first = runEvent("START", FIRST, "orders");
            String firstId = dz.postLineageEvent(r -> r.domainIdentifier(domainId).clientToken("first")
                    .event(SdkBytes.fromUtf8String(first))).id();
            assertEquals(firstId, dz.postLineageEvent(r -> r.domainIdentifier(domainId).clientToken("first")
                    .event(SdkBytes.fromUtf8String(first))).id());
            String secondId = dz.postLineageEvent(r -> r.domainIdentifier(domainId)
                    .event(SdkBytes.fromUtf8String(runEvent("COMPLETE", SECOND, "orders", "summary")))).id();

            GetLineageEventResponse event = dz.getLineageEvent(r -> r.domainIdentifier(domainId).identifier(firstId));
            assertEquals(first, event.event().asUtf8String());
            assertEquals(LineageEventProcessingStatus.SUCCESS, event.processingStatus());
            assertEquals(FIRST, event.eventTime());
            assertEquals(firstId, event.id());

            List<String> events = dz.listLineageEvents(r -> r.domainIdentifier(domainId)).items().stream()
                    .map(e -> e.id()).toList();
            assertEquals(List.of(secondId, firstId), events);
            OpenLineageRunEventSummary summary = dz.listLineageEvents(r -> r.domainIdentifier(domainId)
                    .timestampAfter(FIRST)).items().getFirst().eventSummary().openLineageRunEventSummary();
            assertEquals("daily", summary.job().name());
            assertEquals(2, summary.outputs().size());

            List<ResultItem> downstream = dz.queryGraph(r -> r.domainIdentifier(domainId).match(
                    MatchClause.fromEntityPattern(EntityPattern.builder().entityType(GraphEntityType.LINEAGE_NODE)
                            .identifier("s3://raw/orders").build()),
                    MatchClause.fromRelationPattern(RelationPattern.builder().relationType(RelationType.LINEAGE)
                            .relationDirection(RelationDirection.OUT).maxPathLength(2).build()))).items();
            Map<String, LineageNodeItem> nodes = downstream.stream().map(ResultItem::lineageNode)
                    .collect(Collectors.toMap(LineageNodeItem::sourceIdentifier, n -> n));
            assertEquals(4, nodes.size());
            LineageNodeItem job = nodes.get("etl/daily");
            assertEquals(DataZoneLineageService.JOB_TYPE, job.typeName());
            assertEquals(List.of(nodes.get("s3://raw/orders").id()), job.upstreamLineageNodeIds());
            assertEquals(2, job.downstreamLineageNodeIds().size());
            assertEquals(1, dz.queryGraph(r -> r.domainIdentifier(domainId).match(
                    MatchClause.fromEntityPattern(EntityPattern.builder().entityType(GraphEntityType.LINEAGE_NODE)
                            .identifier("s3://raw/orders").build()),
                    MatchClause.fromRelationPattern(RelationPattern.builder().relationType(RelationType.LINEAGE)
                            .relationDirection(RelationDirection.IN).build()))).items().size());

            GetLineageNodeResponse latest = dz.getLineageNode(r -> r.domainIdentifier(domainId).identifier(job.id()));
            assertEquals(SECOND, latest.eventTimestamp());
            assertEquals(2, latest.downstreamNodes().size());
            LineageNodeReference upstream = latest.upstreamNodes().getFirst();
            assertEquals(nodes.get("s3://raw/orders").id(), upstream.id());
            GetLineageNodeResponse earlier = dz.getLineageNode(r -> r.domainIdentifier(domainId)
                    .identifier(job.id()).eventTimestamp(FIRST.plusSeconds(60)));
            assertEquals(FIRST, earlier.eventTimestamp());
            assertEquals(1, earlier.downstreamNodes().size());
            assertThrows(ResourceNotFoundException.class, () -> dz.getLineageNode(r -> r.domainIdentifier(domainId)
                    .identifier(job.id()).eventTimestamp(FIRST.minusSeconds(60))));

            List<LineageNodeSummary> history = dz.listLineageNodeHistory(r -> r.domainIdentifier(domainId)
                    .identifier(job.id())).nodes();
            assertEquals(List.of(SECOND, FIRST), history.stream().map(LineageNodeSummary::eventTimestamp).toList());
            assertEquals(List.of(FIRST), dz.listLineageNodeHistory(r -> r.domainIdentifier(domainId)
                    .identifier(job.id()).sortOrder(SortOrder.ASCENDING).eventTimestampLTE(FIRST)).nodes().stream()
                    .map(LineageNodeSummary::eventTimestamp).toList());
            assertEquals(1, dz.listLineageNodeHistory(r -> r.domainIdentifier(domainId)
                    .identifier(nodes.get("s3://curated/summary").id()).direction(EdgeDirection.UPSTREAM)).nodes()
                    .size());

            assertEquals(firstId, dz.deleteLineageEvent(r -> r.domainIdentifier(domainId).identifier(firstId)).id());
            assertThrows(ResourceNotFoundException.class, () -> dz.getLineageEvent(r -> r.domainIdentifier(domainId)
                    .identifier(firstId)));
            assertThrows(ValidationException.class, () -> dz.postLineageEvent(r -> r.domainIdentifier(domainId)
                    .event(SdkBytes.fromUtf8String("{\"eventType\": \"START\"}"))));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    @Test
    void assetsKeepDataPointsAttributeFormsAndGovernedTerms() {
        try (DataZoneClient dz = client()) {
            String domainId = dz.createDomain(r -> r.name("metadata-" + suffix())).id();
            String projectId = dz.createProject(r -> r.domainIdentifier(domainId).name("producers")).id();
            String assetId = dz.createAsset(r -> r.domainIdentifier(domainId).name("orders")
                    .typeIdentifier("amazon.datazone.GlueTableAssetType").owningProjectIdentifier(projectId)).id();

            List<TimeSeriesDataPointFormOutput> posted = dz.postTimeSeriesDataPoints(r -> r
                    .domainIdentifier(domainId).entityType(TimeSeriesEntityType.ASSET).entityIdentifier(assetId)
                    .forms(quality(FIRST, 7), quality(SECOND, 9))).forms();
            assertEquals(2, posted.size());
            String newest = posted.get(1).id();
            assertEquals(newest, dz.getAsset(r -> r.domainIdentifier(domainId).identifier(assetId))
                    .latestTimeSeriesDataPointFormsOutput().getFirst().id());
            assertEquals("{\"score\": 9}", dz.getTimeSeriesDataPoint(r -> r.domainIdentifier(domainId)
                    .entityType(TimeSeriesEntityType.ASSET).entityIdentifier(assetId).identifier(newest)
                    .formName("Quality")).form().content());
            assertEquals(2, dz.listTimeSeriesDataPoints(r -> r.domainIdentifier(domainId)
                    .entityType(TimeSeriesEntityType.ASSET).entityIdentifier(assetId).formName("Quality"))
                    .items().size());
            assertEquals(List.of(newest), dz.listTimeSeriesDataPoints(r -> r.domainIdentifier(domainId)
                    .entityType(TimeSeriesEntityType.ASSET).entityIdentifier(assetId).formName("Quality")
                    .startedAt(SECOND)).items().stream().map(p -> p.id()).toList());
            dz.deleteTimeSeriesDataPoints(r -> r.domainIdentifier(domainId).entityType(TimeSeriesEntityType.ASSET)
                    .entityIdentifier(assetId).formName("Quality"));
            assertTrue(dz.listTimeSeriesDataPoints(r -> r.domainIdentifier(domainId)
                    .entityType(TimeSeriesEntityType.ASSET).entityIdentifier(assetId).formName("Quality"))
                    .items().isEmpty());
            assertTrue(dz.getAsset(r -> r.domainIdentifier(domainId).identifier(assetId))
                    .latestTimeSeriesDataPointFormsOutput().isEmpty());
            assertThrows(ResourceNotFoundException.class, () -> dz.postTimeSeriesDataPoints(r -> r
                    .domainIdentifier(domainId).entityType(TimeSeriesEntityType.ASSET).entityIdentifier("missing")
                    .forms(quality(FIRST, 1))));

            dz.batchPutAttributesMetadata(r -> r.domainIdentifier(domainId).entityType(AttributeEntityType.ASSET)
                    .entityIdentifier(assetId).attributes(AttributeInput.builder().attributeIdentifier("customer_id")
                            .forms(FormInput.builder().formName("ColumnBusinessMetadataForm")
                                    .typeIdentifier("amazon.datazone.ColumnBusinessMetadataFormType")
                                    .content("{\"description\": \"Buyer\"}").build()).build()));
            BatchGetAttributesMetadataResponse attributes = dz.batchGetAttributesMetadata(r -> r
                    .domainIdentifier(domainId).entityType(AttributeEntityType.ASSET).entityIdentifier(assetId)
                    .attributeIdentifiers("customer_id", "missing"));
            assertEquals("ColumnBusinessMetadataForm", attributes.attributes().getFirst().forms().getFirst()
                    .formName());
            assertEquals("missing", attributes.errors().getFirst().attributeIdentifier());

            String glossaryId = dz.createGlossary(r -> r.domainIdentifier(domainId).name("governed")
                    .owningProjectIdentifier(projectId)).id();
            String termId = dz.createGlossaryTerm(r -> r.domainIdentifier(domainId).glossaryIdentifier(glossaryId)
                    .name("pii")).id();
            dz.associateGovernedTerms(r -> r.domainIdentifier(domainId).entityType(GovernedEntityType.ASSET)
                    .entityIdentifier(assetId).governedGlossaryTerms(termId));
            assertEquals(List.of(termId), dz.getAsset(r -> r.domainIdentifier(domainId).identifier(assetId))
                    .governedGlossaryTerms());
            dz.disassociateGovernedTerms(r -> r.domainIdentifier(domainId).entityType(GovernedEntityType.ASSET)
                    .entityIdentifier(assetId).governedGlossaryTerms(termId));
            assertTrue(dz.getAsset(r -> r.domainIdentifier(domainId).identifier(assetId)).governedGlossaryTerms()
                    .isEmpty());
            assertThrows(ResourceNotFoundException.class, () -> dz.associateGovernedTerms(r -> r
                    .domainIdentifier(domainId).entityType(GovernedEntityType.ASSET).entityIdentifier(assetId)
                    .governedGlossaryTerms("missing-term")));
            dz.deleteDomain(r -> r.identifier(domainId));
        }
    }

    private static TimeSeriesDataPointFormInput quality(Instant timestamp, int score) {
        return TimeSeriesDataPointFormInput.builder().formName("Quality")
                .typeIdentifier("amazon.datazone.DataQualityResultFormType").timestamp(timestamp)
                .content("{\"score\": " + score + "}").build();
    }

    private static String runEvent(String eventType, Instant eventTime, String... outputs) {
        StringBuilder outputJson = new StringBuilder();
        for (String output : outputs) {
            if (!outputJson.isEmpty()) {
                outputJson.append(", ");
            }
            outputJson.append("{\"namespace\": \"s3://curated\", \"name\": \"").append(output).append("\"}");
        }
        return """
                {"eventType": "%s", "eventTime": "%s", "producer": "https://example.com/etl",
                 "run": {"runId": "0190a0e6-0000-7000-8000-000000000001"},
                 "job": {"namespace": "etl", "name": "daily"},
                 "inputs": [{"namespace": "s3://raw", "name": "orders"}],
                 "outputs": [%s]}""".formatted(eventType, eventTime, outputJson);
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
