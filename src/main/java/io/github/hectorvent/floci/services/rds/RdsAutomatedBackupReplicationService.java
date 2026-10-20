package io.github.hectorvent.floci.services.rds;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbInstanceAutomatedBackup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Replicates DB instance automated backups across regions. Both replication operations run in
 * the destination region, and a replication reads {@code replicating} until a stop removes it.
 */
@ApplicationScoped
public class RdsAutomatedBackupReplicationService {

    private static final int DEFAULT_RETENTION_DAYS = 7;

    // destinationRegion|sourceDbInstanceArn -> replicated backup
    private final StorageBackend<String, DbInstanceAutomatedBackup> backups;
    private final RdsService rdsService;
    private final RegionResolver regionResolver;

    @Inject
    public RdsAutomatedBackupReplicationService(StorageFactory storageFactory, RdsService rdsService,
                                                RegionResolver regionResolver) {
        this(storageFactory.create("rds", "rds-automated-backup-replications.json",
                new TypeReference<Map<String, DbInstanceAutomatedBackup>>() {}), rdsService, regionResolver);
    }

    RdsAutomatedBackupReplicationService(StorageBackend<String, DbInstanceAutomatedBackup> backups,
                                         RdsService rdsService, RegionResolver regionResolver) {
        this.backups = backups;
        this.rdsService = rdsService;
        this.regionResolver = regionResolver;
    }

    public synchronized DbInstanceAutomatedBackup start(String region, String sourceDbInstanceArn,
                                                        Integer retentionPeriod, String kmsKeyId) {
        AwsArnUtils.Arn source = parseSource(sourceDbInstanceArn);
        if (region.equals(source.region())) {
            throw new AwsException("InvalidParameterValue",
                    "The source DB instance must be in a different region from the replicated backups.", 400);
        }
        if (retentionPeriod != null && (retentionPeriod < 1 || retentionPeriod > 35)) {
            throw new AwsException("InvalidParameterValue",
                    "BackupRetentionPeriod must be between 1 and 35.", 400);
        }
        DbInstance instance = rdsService.getDbInstance(source.resource().substring("db:".length()), source.region());
        if (instance.getBackupRetentionPeriod() <= 0) {
            throw new AwsException("InvalidDBInstanceState",
                    "Automated backups are not enabled for DB instance " + instance.getDbInstanceIdentifier() + ".",
                    400);
        }
        String key = key(region, sourceDbInstanceArn);
        if (backups.get(key).isPresent()) {
            throw new AwsException("DBInstanceAutomatedBackupQuotaExceeded",
                    "Automated backups of " + sourceDbInstanceArn + " are already replicating to " + region + ".",
                    400);
        }
        DbInstanceAutomatedBackup backup = new DbInstanceAutomatedBackup();
        backup.setDbInstanceAutomatedBackupsArn(regionResolver.buildArn("rds", region,
                "auto-backup:ab-" + UUID.randomUUID().toString().replace("-", "").substring(0, 26)
                        .toLowerCase(Locale.ROOT)));
        backup.setRegion(region);
        backup.setDbInstanceArn(sourceDbInstanceArn);
        backup.setSourceRegion(source.region());
        backup.setDbInstanceIdentifier(instance.getDbInstanceIdentifier());
        backup.setDbiResourceId(instance.getDbiResourceId());
        backup.setAllocatedStorage(instance.getAllocatedStorage());
        backup.setPort(instance.getEndpoint() != null ? instance.getEndpoint().port() : null);
        backup.setAvailabilityZone(instance.getAvailabilityZone());
        backup.setInstanceCreateTime(instance.getCreatedAt());
        backup.setRestoreWindowEarliest(Instant.now());
        backup.setMasterUsername(instance.getMasterUsername());
        backup.setEngine(instance.getEngineIdentifier() != null ? instance.getEngineIdentifier()
                : instance.getEngine() != null ? instance.getEngine().name().toLowerCase(Locale.ROOT) : null);
        backup.setEngineVersion(instance.getEngineVersion());
        backup.setEncrypted(instance.isStorageEncrypted() || kmsKeyId != null);
        backup.setKmsKeyId(kmsKeyId);
        backup.setIamDatabaseAuthenticationEnabled(instance.isIamDatabaseAuthenticationEnabled());
        backup.setBackupRetentionPeriod(retentionPeriod != null ? retentionPeriod : DEFAULT_RETENTION_DAYS);
        backups.put(key, backup);
        return backup;
    }

    public synchronized DbInstanceAutomatedBackup stop(String region, String sourceDbInstanceArn) {
        parseSource(sourceDbInstanceArn);
        String key = key(region, sourceDbInstanceArn);
        DbInstanceAutomatedBackup backup = backups.get(key).orElseThrow(() -> new AwsException(
                "InvalidDBInstanceState",
                "Automated backups of " + sourceDbInstanceArn + " are not replicating to " + region + ".", 400));
        backups.delete(key);
        backup.setStatus("deleting");
        return backup;
    }

    public List<DbInstanceAutomatedBackup> describe(String region, String automatedBackupsArn,
                                                    String dbInstanceIdentifier, String dbiResourceId) {
        List<DbInstanceAutomatedBackup> found = backups.scan(k -> k.startsWith(region + "|")).stream()
                .filter(b -> automatedBackupsArn == null || automatedBackupsArn.equals(b.getDbInstanceAutomatedBackupsArn()))
                .filter(b -> dbInstanceIdentifier == null || dbInstanceIdentifier.equals(b.getDbInstanceIdentifier()))
                .filter(b -> dbiResourceId == null || dbiResourceId.equals(b.getDbiResourceId()))
                .sorted(Comparator.comparing(DbInstanceAutomatedBackup::getDbInstanceAutomatedBackupsArn))
                .toList();
        if (found.isEmpty() && (automatedBackupsArn != null || dbiResourceId != null)) {
            throw new AwsException("DBInstanceAutomatedBackupNotFound",
                    "No automated backup found for " + (automatedBackupsArn != null ? automatedBackupsArn
                            : dbiResourceId) + ".", 404);
        }
        return found;
    }

    /** The replications of one source instance, across every destination region. */
    public List<DbInstanceAutomatedBackup> replicationsOf(String sourceDbInstanceArn) {
        if (sourceDbInstanceArn == null) {
            return List.of();
        }
        return backups.scan(k -> k.endsWith("|" + sourceDbInstanceArn));
    }

    private static AwsArnUtils.Arn parseSource(String sourceDbInstanceArn) {
        if (sourceDbInstanceArn == null || sourceDbInstanceArn.isBlank()) {
            throw new AwsException("MissingParameter",
                    "The request must contain the parameter SourceDBInstanceArn", 400);
        }
        AwsArnUtils.Arn arn;
        try {
            arn = AwsArnUtils.parse(sourceDbInstanceArn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid SourceDBInstanceArn: " + sourceDbInstanceArn, 400);
        }
        if (!"rds".equals(arn.service()) || arn.resource() == null || !arn.resource().startsWith("db:")) {
            throw new AwsException("InvalidParameterValue",
                    "Invalid SourceDBInstanceArn: " + sourceDbInstanceArn, 400);
        }
        return arn;
    }

    private static String key(String region, String sourceDbInstanceArn) {
        return region + "|" + sourceDbInstanceArn;
    }
}
