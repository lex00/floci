package io.github.hectorvent.floci.services.rds;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestProfile(RdsListenOnRequestedPortIntegrationTest.ListenProfile.class)
class RdsListenOnRequestedPortIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/rds/aws4_request";

    public static class ListenProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.rds.mock", "true",
                    "floci.services.rds.listen-on-requested-port", "true");
        }
    }

    @Test
    void requestedPortOutsideTheProxyRangeIsReportedThenFallsBackWhenTaken() {
        createInstance("listen-first")
                .then().statusCode(200)
                .body(containsString("<Port>5432</Port>"));
        createInstance("listen-second")
                .then().statusCode(200)
                .body(not(containsString("<Port>5432</Port>")));
    }

    private Response createInstance(String id) {
        return rds("CreateDBInstance")
                .formParam("DBInstanceIdentifier", id)
                .formParam("Engine", "postgres")
                .formParam("MasterUsername", "admin")
                .formParam("MasterUserPassword", "password123")
                .formParam("DBInstanceClass", "db.t3.micro")
                .formParam("Port", "5432")
                .when().post("/");
    }

    private static RequestSpecification rds(String action) {
        return given()
                .header("Authorization", AUTH)
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", action)
                .formParam("Version", "2014-10-31");
    }
}
