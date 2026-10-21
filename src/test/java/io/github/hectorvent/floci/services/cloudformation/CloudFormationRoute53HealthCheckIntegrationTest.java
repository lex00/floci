package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::Route53::HealthCheck} and a record set's routing policy through the per-service
 * provisioner. Values are read back through SSM parameters the template writes and through the
 * Route 53 API, because an unowned type is stubbed CREATE_COMPLETE with an {@code arn:aws:stub}
 * physical id, so a stack status alone proves nothing.
 */
@QuarkusTest
class CloudFormationRoute53HealthCheckIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String SSM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ssm/aws4_request";
    private static final String ROUTE53_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/route53/aws4_request";

    private static final String TEMPLATE = """
            {
              "Parameters": {
                "CheckType": {"Type": "String"},
                "Threshold": {"Type": "String"}
              },
              "Resources": {
                "Zone": {
                  "Type": "AWS::Route53::HostedZone",
                  "Properties": {"Name": "%1$s"}
                },
                "Check": {
                  "Type": "AWS::Route53::HealthCheck",
                  "Properties": {
                    "HealthCheckConfig": {
                      "Type": {"Ref": "CheckType"},
                      "FullyQualifiedDomainName": "app.example.net",
                      "Port": 443,
                      "ResourcePath": "/healthz",
                      "FailureThreshold": {"Ref": "Threshold"}
                    },
                    "HealthCheckTags": [{"Key": "site", "Value": "aws"}]
                  }
                },
                "Latency": {
                  "Type": "AWS::Route53::RecordSet",
                  "Properties": {
                    "HostedZoneId": {"Ref": "Zone"},
                    "Name": "www.%1$s",
                    "Type": "CNAME",
                    "TTL": "60",
                    "SetIdentifier": "aws",
                    "Region": "us-east-1",
                    "HealthCheckId": {"Ref": "Check"},
                    "ResourceRecords": ["app.example.net"]
                  }
                },
                "Weighted": {
                  "Type": "AWS::Route53::RecordSet",
                  "Properties": {
                    "HostedZoneId": {"Ref": "Zone"},
                    "Name": "api.%1$s",
                    "Type": "A",
                    "TTL": "60",
                    "SetIdentifier": "blue",
                    "Weight": 70,
                    "ResourceRecords": ["192.0.2.1"]
                  }
                },
                "Primary": {
                  "Type": "AWS::Route53::RecordSet",
                  "Properties": {
                    "HostedZoneId": {"Ref": "Zone"},
                    "Name": "app.%1$s",
                    "Type": "A",
                    "TTL": "60",
                    "SetIdentifier": "primary",
                    "Failover": "PRIMARY",
                    "HealthCheckId": {"Fn::GetAtt": ["Check", "HealthCheckId"]},
                    "ResourceRecords": ["192.0.2.2"]
                  }
                },
                "RefParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {"Name": "/r53-hc/%2$s/ref", "Type": "String", "Value": {"Ref": "Check"}}
                },
                "AttParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {"Name": "/r53-hc/%2$s/att", "Type": "String",
                                 "Value": {"Fn::GetAtt": ["Check", "HealthCheckId"]}}
                },
                "ZoneParam": {
                  "Type": "AWS::SSM::Parameter",
                  "Properties": {"Name": "/r53-hc/%2$s/zone", "Type": "String", "Value": {"Ref": "Zone"}}
                }
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void healthCheckAndRoutingPolicyRoundTripAcrossCreateUpdateAndDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-hc-" + suffix;
        String zoneName = suffix + ".cfn-hc-it.example.com";
        String template = TEMPLATE.formatted(zoneName, suffix);

        stackRequest("CreateStack", stackName, template, "HTTPS", "3");
        CfnStackWaits.StackState created = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("CREATE_COMPLETE", created.status(), created.reason());

        String checkId = parameterValue("/r53-hc/" + suffix + "/ref");
        assertEquals(checkId, parameterValue("/r53-hc/" + suffix + "/att"));
        String check = getHealthCheck(checkId);
        assertTrue(check.contains("<Type>HTTPS</Type>"), check);
        assertTrue(check.contains("<FullyQualifiedDomainName>app.example.net</FullyQualifiedDomainName>"), check);
        assertTrue(check.contains("<ResourcePath>/healthz</ResourcePath>"), check);
        assertTrue(check.contains("<FailureThreshold>3</FailureThreshold>"), check);
        String tags = given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/tags/healthcheck/" + checkId)
        .then().statusCode(200)
            .extract().asString();
        assertTrue(tags.contains("<Key>site</Key>") && tags.contains("<Value>aws</Value>"), tags);

        String zoneId = parameterValue("/r53-hc/" + suffix + "/zone");
        String records = listResourceRecordSets(zoneId);
        assertTrue(records.contains("<SetIdentifier>aws</SetIdentifier>"), records);
        assertTrue(records.contains("<Region>us-east-1</Region>"), records);
        assertTrue(records.contains("<Weight>70</Weight>"), records);
        assertTrue(records.contains("<Failover>PRIMARY</Failover>"), records);
        assertEquals(2, records.split("<HealthCheckId>" + checkId + "</HealthCheckId>", -1).length - 1, records);

        // FailureThreshold is not create-only, so the same health check is updated in place.
        stackRequest("UpdateStack", stackName, template, "HTTPS", "5");
        CfnStackWaits.StackState updated = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
        assertEquals(checkId, parameterValue("/r53-hc/" + suffix + "/ref"));
        String inPlace = getHealthCheck(checkId);
        assertTrue(inPlace.contains("<FailureThreshold>5</FailureThreshold>"), inPlace);
        assertTrue(inPlace.contains("<HealthCheckVersion>2</HealthCheckVersion>"), inPlace);

        // Type is create-only: a new health check replaces the old one, and the records follow it.
        stackRequest("UpdateStack", stackName, template, "HTTP", "5");
        CfnStackWaits.StackState replaced = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("UPDATE_COMPLETE", replaced.status(), replaced.reason());
        String newCheckId = parameterValue("/r53-hc/" + suffix + "/ref");
        assertNotEquals(checkId, newCheckId);
        assertTrue(getHealthCheck(newCheckId).contains("<Type>HTTP</Type>"));
        healthCheckStatus(checkId, 404);
        String afterReplace = listResourceRecordSets(zoneId);
        assertTrue(afterReplace.contains("<HealthCheckId>" + newCheckId + "</HealthCheckId>"), afterReplace);

        deleteStack(stackName);
        CfnStackWaits.awaitStackDeleted(stackName);
        healthCheckStatus(newCheckId, 404);
    }

    private String getHealthCheck(String id) {
        return given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/healthcheck/" + id)
        .then().statusCode(200)
            .extract().asString();
    }

    private void healthCheckStatus(String id, int status) {
        given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/healthcheck/" + id)
        .then().statusCode(status);
    }

    private void stackRequest(String action, String stackName, String template, String type, String threshold) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "CheckType")
            .formParam("Parameters.member.1.ParameterValue", type)
            .formParam("Parameters.member.2.ParameterKey", "Threshold")
            .formParam("Parameters.member.2.ParameterValue", threshold)
        .when().post("/").then().statusCode(200);
    }

    private String listResourceRecordSets(String zoneId) {
        return given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
        .then().statusCode(200)
            .extract().asString();
    }

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private String parameterValue(String name) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", SSM_AUTH)
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Parameter.Value");
    }
}
