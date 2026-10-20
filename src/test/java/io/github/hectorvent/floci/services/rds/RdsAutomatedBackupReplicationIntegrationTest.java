package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestProfile(RdsAutomatedBackupReplicationIntegrationTest.MockProfile.class)
class RdsAutomatedBackupReplicationIntegrationTest {

    private static final String SOURCE_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/rds/aws4_request";
    private static final String DESTINATION_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-west-2/rds/aws4_request";
    private static final String RESULT =
            "StartDBInstanceAutomatedBackupsReplicationResponse.StartDBInstanceAutomatedBackupsReplicationResult"
                    + ".DBInstanceAutomatedBackup";

    public static class MockProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.rds.mock", "true");
        }
    }

    @Test
    void replicateDescribeAndStop() {
        String sourceArn = rds(SOURCE_AUTH, "CreateDBInstance")
                .formParam("DBInstanceIdentifier", "backup-source")
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("BackupRetentionPeriod", "7")
                .when().post("/").then().statusCode(200)
                .extract().xmlPath()
                .getString("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.DBInstanceArn");

        String backupArn = rds(DESTINATION_AUTH, "StartDBInstanceAutomatedBackupsReplication")
                .formParam("SourceDBInstanceArn", sourceArn)
                .formParam("BackupRetentionPeriod", "14")
                .when().post("/").then().statusCode(200)
                .body(RESULT + ".Status", equalTo("replicating"))
                .body(RESULT + ".DBInstanceArn", equalTo(sourceArn))
                .body(RESULT + ".Region", equalTo("us-east-1"))
                .body(RESULT + ".BackupRetentionPeriod", equalTo("14"))
                .body(RESULT + ".DBInstanceAutomatedBackupsArn", startsWith("arn:aws:rds:us-west-2:"))
                .extract().xmlPath().getString(RESULT + ".DBInstanceAutomatedBackupsArn");

        rds(DESTINATION_AUTH, "DescribeDBInstanceAutomatedBackups")
                .formParam("DBInstanceAutomatedBackupsArn", backupArn)
                .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceIdentifier>backup-source</DBInstanceIdentifier>"))
                .body(containsString("<Status>replicating</Status>"));

        rds(SOURCE_AUTH, "DescribeDBInstances")
                .formParam("DBInstanceIdentifier", "backup-source")
                .when().post("/").then().statusCode(200)
                .body(containsString("<DBInstanceAutomatedBackupsArn>" + backupArn
                        + "</DBInstanceAutomatedBackupsArn>"));

        rds(DESTINATION_AUTH, "StartDBInstanceAutomatedBackupsReplication")
                .formParam("SourceDBInstanceArn", sourceArn)
                .when().post("/").then().statusCode(400);

        rds(DESTINATION_AUTH, "StopDBInstanceAutomatedBackupsReplication")
                .formParam("SourceDBInstanceArn", sourceArn)
                .when().post("/").then().statusCode(200);

        rds(SOURCE_AUTH, "DescribeDBInstances")
                .formParam("DBInstanceIdentifier", "backup-source")
                .when().post("/").then().statusCode(200)
                .body(not(containsString("DBInstanceAutomatedBackupsReplications")));
        rds(DESTINATION_AUTH, "DescribeDBInstanceAutomatedBackups")
                .formParam("DBInstanceAutomatedBackupsArn", backupArn)
                .when().post("/").then().statusCode(404)
                .body(containsString("DBInstanceAutomatedBackupNotFound"));
    }

    @Test
    void replicationIntoTheSourceRegionIsRejected() {
        String sourceArn = rds(SOURCE_AUTH, "CreateDBInstance")
                .formParam("DBInstanceIdentifier", "backup-same-region")
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
                .when().post("/").then().statusCode(200)
                .extract().xmlPath()
                .getString("CreateDBInstanceResponse.CreateDBInstanceResult.DBInstance.DBInstanceArn");

        rds(SOURCE_AUTH, "StartDBInstanceAutomatedBackupsReplication")
                .formParam("SourceDBInstanceArn", sourceArn)
                .when().post("/").then().statusCode(400)
                .body(containsString("InvalidParameterValue"));
    }

    private static RequestSpecification rds(String auth, String action) {
        return given()
                .header("Authorization", auth)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }
}
