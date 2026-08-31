package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Concurrent PUTs of different configuration ids on the SAME bucket must all survive.
 *
 * <p>The four per-bucket sub-resources ({@code ?inventory}, {@code ?analytics},
 * {@code ?metrics}, {@code ?intelligent-tiering}) are stored in a per-bucket map that
 * {@code putBucketConfiguration} read-modify-writes. A {@code LinkedHashMap} mutated from
 * several request threads at once drops entries: sometimes from the table, so neither
 * {@code Get} nor {@code List} finds them, and sometimes only from the linked list that
 * {@code values()} walks, so {@code Get} still finds what {@code List} has lost.
 *
 * <p>This is not a hypothetical. terraform-aws-modules/s3-bucket's complete example puts
 * three metrics configurations and two intelligent-tiering configurations on one bucket, and
 * terraform issues each set concurrently; the provider then fails its create-waiter with
 * "couldn't find resource" for whichever one was dropped. Measured against the emulator
 * before this fix, a plain AWS CLI probe lost a configuration in 6/40 rounds for metrics,
 * 3/40 for intelligent-tiering and 9/40 for analytics.
 *
 * <p>Each test below races {@link #WRITERS} writers released together off a barrier, so it
 * fails on the unsynchronised implementation essentially every run rather than occasionally.
 * See lex00/floci#186.
 */
class S3BucketConfigurationConcurrencyTest {

    /** Enough concurrent writers that a dropped entry is near-certain when the write is racy. */
    private static final int WRITERS = 24;

    /** Repeats per kind: a fresh bucket each time, because the race is per-bucket. */
    private static final int ROUNDS = 20;

    @TempDir
    Path tempDir;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(new InMemoryStorage<>(), new InMemoryStorage<>(),
                tempDir.resolve("s3"), false);
    }

    @Test
    void concurrentMetricsConfigurationPutsAllSurvive() {
        raceKind("metrics",
                id -> "<MetricsConfiguration><Id>" + id + "</Id></MetricsConfiguration>",
                (bucket, id, xml) -> s3Service.putBucketMetricsConfiguration(bucket, id, xml),
                (bucket, id) -> s3Service.getBucketMetricsConfiguration(bucket, id),
                bucket -> s3Service.listBucketMetricsConfigurations(bucket));
    }

    @Test
    void concurrentAnalyticsConfigurationPutsAllSurvive() {
        raceKind("analytics",
                id -> "<AnalyticsConfiguration><Id>" + id + "</Id>"
                        + "<StorageClassAnalysis></StorageClassAnalysis></AnalyticsConfiguration>",
                (bucket, id, xml) -> s3Service.putBucketAnalyticsConfiguration(bucket, id, xml),
                (bucket, id) -> s3Service.getBucketAnalyticsConfiguration(bucket, id),
                bucket -> s3Service.listBucketAnalyticsConfigurations(bucket, null));
    }

    @Test
    void concurrentIntelligentTieringConfigurationPutsAllSurvive() {
        raceKind("tiering",
                id -> "<IntelligentTieringConfiguration><Id>" + id + "</Id><Status>Enabled</Status>"
                        + "<Tiering><Days>90</Days><AccessTier>ARCHIVE_ACCESS</AccessTier></Tiering>"
                        + "</IntelligentTieringConfiguration>",
                (bucket, id, xml) -> s3Service.putBucketIntelligentTieringConfiguration(bucket, id, xml),
                (bucket, id) -> s3Service.getBucketIntelligentTieringConfiguration(bucket, id),
                bucket -> s3Service.listBucketIntelligentTieringConfigurations(bucket, null));
    }

    @Test
    void concurrentInventoryConfigurationPutsAllSurvive() {
        raceKind("inventory",
                id -> "<InventoryConfiguration><Id>" + id + "</Id><IsEnabled>true</IsEnabled>"
                        + "</InventoryConfiguration>",
                (bucket, id, xml) -> s3Service.putBucketInventoryConfiguration(bucket, id, xml),
                (bucket, id) -> s3Service.getBucketInventoryConfiguration(bucket, id),
                bucket -> s3Service.listBucketInventoryConfigurations(bucket, null));
    }

    /**
     * A concurrent DELETE of one id must not take an unrelated concurrent PUT with it: the
     * delete is the same read-modify-write of the same map.
     */
    @Test
    void aConcurrentDeleteDoesNotSwallowAnUnrelatedPut() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String bucket = "delete-race-" + round;
            s3Service.createBucket(bucket, "eu-west-1");
            // Pre-seed the ids the deleters will remove, so only the racing puts are in doubt.
            for (int i = 0; i < WRITERS / 2; i++) {
                s3Service.putBucketMetricsConfiguration(bucket, "doomed-" + i,
                        "<MetricsConfiguration><Id>doomed-" + i + "</Id></MetricsConfiguration>");
            }

            CyclicBarrier gate = new CyclicBarrier(WRITERS);
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < WRITERS / 2; i++) {
                String keepId = "keep-" + i;
                String doomedId = "doomed-" + i;
                work.add(() -> {
                    gate.await(30, TimeUnit.SECONDS);
                    s3Service.putBucketMetricsConfiguration(bucket, keepId,
                            "<MetricsConfiguration><Id>" + keepId + "</Id></MetricsConfiguration>");
                    return null;
                });
                work.add(() -> {
                    gate.await(30, TimeUnit.SECONDS);
                    s3Service.deleteBucketMetricsConfiguration(bucket, doomedId);
                    return null;
                });
            }
            runAll(work);

            String listing = s3Service.listBucketMetricsConfigurations(bucket);
            for (int i = 0; i < WRITERS / 2; i++) {
                assertTrue(listing.contains("<Id>keep-" + i + "</Id>"),
                        "round " + round + ": a concurrent delete swallowed the put of keep-" + i);
                assertTrue(!listing.contains("<Id>doomed-" + i + "</Id>"),
                        "round " + round + ": doomed-" + i + " survived its delete");
            }
        }
    }

    // --- harness ---------------------------------------------------------------------

    private interface Document {
        String of(String id);
    }

    private interface Put {
        void run(String bucket, String id, String xml);
    }

    private interface Get {
        String run(String bucket, String id);
    }

    private interface Listing {
        String run(String bucket);
    }

    private void raceKind(String kind, Document document, Put put, Get get, Listing listing) {
        for (int round = 0; round < ROUNDS; round++) {
            String bucket = kind + "-race-" + round;
            s3Service.createBucket(bucket, "eu-west-1");

            CyclicBarrier gate = new CyclicBarrier(WRITERS);
            List<Callable<Void>> work = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                String id = "id-" + i;
                work.add(() -> {
                    // Release every writer at the same instant: the window is the
                    // read-modify-write itself, which is a handful of instructions wide.
                    gate.await(30, TimeUnit.SECONDS);
                    put.run(bucket, id, document.of(id));
                    return null;
                });
            }
            try {
                runAll(work);
            } catch (Exception e) {
                throw new AssertionError(kind + " round " + round + ": a put failed outright", e);
            }

            // Every put returned success, so every id must be readable BOTH ways. Checking
            // List and Get separately matters: a raced LinkedHashMap can drop a node from
            // the iteration order while the hash lookup still resolves it, and a listing that
            // silently omits a configuration is the failure terraform's waiter actually sees.
            String listed = listing.run(bucket);
            List<String> missingFromList = new ArrayList<>();
            List<String> missingFromGet = new ArrayList<>();
            for (int i = 0; i < WRITERS; i++) {
                String id = "id-" + i;
                if (!listed.contains("<Id>" + id + "</Id>")) {
                    missingFromList.add(id);
                }
                try {
                    get.run(bucket, id);
                } catch (RuntimeException e) {
                    missingFromGet.add(id);
                }
            }
            assertEquals(List.of(), missingFromList,
                    kind + " round " + round + ": " + WRITERS
                            + " puts returned success but these are absent from the listing");
            assertEquals(List.of(), missingFromGet,
                    kind + " round " + round + ": " + WRITERS
                            + " puts returned success but these are absent from Get");
        }
    }

    private void runAll(List<Callable<Void>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(work.size());
        try {
            List<Future<Void>> futures = pool.invokeAll(work, 60, TimeUnit.SECONDS);
            for (Future<Void> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
