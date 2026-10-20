package io.github.hectorvent.floci.services.rds.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A DB instance's automated backups replicated into another region by
 * StartDBInstanceAutomatedBackupsReplication. It lives in the destination region and describes
 * the source instance.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DbInstanceAutomatedBackup {

    private String dbInstanceAutomatedBackupsArn;
    private String region;
    private String dbInstanceArn;
    private String sourceRegion;
    private String dbInstanceIdentifier;
    private String dbiResourceId;
    private String status = "replicating";
    private int allocatedStorage;
    private Integer port;
    private String availabilityZone;
    private Instant instanceCreateTime;
    private Instant restoreWindowEarliest;
    private String masterUsername;
    private String engine;
    private String engineVersion;
    private boolean encrypted;
    private String kmsKeyId;
    private boolean iamDatabaseAuthenticationEnabled;
    private Integer backupRetentionPeriod;

    public DbInstanceAutomatedBackup() {}

    public String getDbInstanceAutomatedBackupsArn() { return dbInstanceAutomatedBackupsArn; }
    public void setDbInstanceAutomatedBackupsArn(String v) { this.dbInstanceAutomatedBackupsArn = v; }
    public String getRegion() { return region; }
    public void setRegion(String v) { this.region = v; }
    public String getDbInstanceArn() { return dbInstanceArn; }
    public void setDbInstanceArn(String v) { this.dbInstanceArn = v; }
    public String getSourceRegion() { return sourceRegion; }
    public void setSourceRegion(String v) { this.sourceRegion = v; }
    public String getDbInstanceIdentifier() { return dbInstanceIdentifier; }
    public void setDbInstanceIdentifier(String v) { this.dbInstanceIdentifier = v; }
    public String getDbiResourceId() { return dbiResourceId; }
    public void setDbiResourceId(String v) { this.dbiResourceId = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public int getAllocatedStorage() { return allocatedStorage; }
    public void setAllocatedStorage(int v) { this.allocatedStorage = v; }
    public Integer getPort() { return port; }
    public void setPort(Integer v) { this.port = v; }
    public String getAvailabilityZone() { return availabilityZone; }
    public void setAvailabilityZone(String v) { this.availabilityZone = v; }
    public Instant getInstanceCreateTime() { return instanceCreateTime; }
    public void setInstanceCreateTime(Instant v) { this.instanceCreateTime = v; }
    public Instant getRestoreWindowEarliest() { return restoreWindowEarliest; }
    public void setRestoreWindowEarliest(Instant v) { this.restoreWindowEarliest = v; }
    public String getMasterUsername() { return masterUsername; }
    public void setMasterUsername(String v) { this.masterUsername = v; }
    public String getEngine() { return engine; }
    public void setEngine(String v) { this.engine = v; }
    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String v) { this.engineVersion = v; }
    public boolean isEncrypted() { return encrypted; }
    public void setEncrypted(boolean v) { this.encrypted = v; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String v) { this.kmsKeyId = v; }
    public boolean isIamDatabaseAuthenticationEnabled() { return iamDatabaseAuthenticationEnabled; }
    public void setIamDatabaseAuthenticationEnabled(boolean v) { this.iamDatabaseAuthenticationEnabled = v; }
    public Integer getBackupRetentionPeriod() { return backupRetentionPeriod; }
    public void setBackupRetentionPeriod(Integer v) { this.backupRetentionPeriod = v; }
}
