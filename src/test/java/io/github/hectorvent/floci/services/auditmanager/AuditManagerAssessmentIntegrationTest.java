package io.github.hectorvent.floci.services.auditmanager;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class AuditManagerAssessmentIntegrationTest {

    private static final String ROLE = "arn:aws:iam::000000000000:role/auditor";

    private static RequestSpecification am() {
        return given().contentType("application/json");
    }

    @Test
    void assessmentEvidenceDelegationsAndReports() {
        am().body("{}").when().post("/account/registerAccount")
            .then().statusCode(200).body("status", equalTo("ACTIVE"));
        am().when().get("/account/status").then().statusCode(200).body("status", equalTo("ACTIVE"));

        String controlId = am().body("""
                {"name": "assess-control", "controlMappingSources": [{"sourceName": "manual",
                 "sourceSetUpOption": "Procedural_Controls_Mapping", "sourceType": "MANUAL"}]}""")
            .when().post("/controls").then().statusCode(200).extract().path("control.id");
        String frameworkId = am().body("""
                {"name": "assess-framework", "controlSets": [{"name": "Access", "controls": [{"id": "%s"}]}]}"""
                .formatted(controlId))
            .when().post("/assessmentFrameworks").then().statusCode(200).extract().path("framework.id");

        String assessmentId = am().body("""
                {"name": "quarterly", "frameworkId": "%s",
                 "assessmentReportsDestination": {"destinationType": "S3", "destination": "s3://audit-reports"},
                 "scope": {"awsAccounts": [{"id": "000000000000"}]},
                 "roles": [{"roleType": "PROCESS_OWNER", "roleArn": "%s"}], "tags": {"team": "audit"}}"""
                .formatted(frameworkId, ROLE))
            .when().post("/assessments").then().statusCode(200)
            .body("assessment.arn", startsWith("arn:aws:auditmanager:us-east-1:000000000000:assessment/"))
            .body("assessment.metadata.status", equalTo("ACTIVE"))
            .body("assessment.framework.controlSets[0].id", equalTo("Access"))
            .body("assessment.framework.controlSets[0].controls[0].id", equalTo(controlId))
            .extract().path("assessment.metadata.id");
        am().when().get("/assessments").then().statusCode(200).body("assessmentMetadata.id", hasItem(assessmentId));
        am().urlEncodingEnabled(false)
            .when().get("/tags/arn:aws:auditmanager:us-east-1:000000000000:assessment/" + assessmentId)
            .then().statusCode(200).body("tags.team", equalTo("audit"));

        String base = "/assessments/" + assessmentId;
        am().body("{\"manualEvidence\": [{\"textResponse\": \"MFA is on\"}, {\"s3ResourcePath\": \"s3://b/k.pdf\"}]}")
            .when().post(base + "/controlSets/Access/controls/" + controlId + "/evidence")
            .then().statusCode(200).body("errors", hasSize(0));
        String folderId = am().when().get(base + "/evidenceFolders")
            .then().statusCode(200)
            .body("evidenceFolders", hasSize(1))
            .body("evidenceFolders[0].totalEvidence", equalTo(2))
            .extract().path("evidenceFolders[0].id");
        String evidenceId = am().when().get(base + "/controlSets/Access/evidenceFolders/" + folderId + "/evidence")
            .then().statusCode(200).body("evidence", hasSize(2)).extract().path("evidence[0].id");
        am().body("{\"evidenceFolderId\": \"" + folderId + "\", \"evidenceIds\": [\"" + evidenceId + "\"]}")
            .when().put(base + "/batchAssociateToAssessmentReport")
            .then().statusCode(200).body("errors", hasSize(0));
        am().when().get(base + "/controlSets/Access/evidenceFolders/" + folderId + "/evidence/" + evidenceId)
            .then().statusCode(200).body("evidence.assessmentReportSelection", equalTo("Yes"));

        String delegationId = am().body("""
                {"createDelegationRequests": [{"controlSetId": "Access", "roleArn": "%s", "roleType": "RESOURCE_OWNER",
                 "comment": "please review"}]}""".formatted(ROLE))
            .when().post(base + "/delegations").then().statusCode(200)
            .body("delegations[0].status", equalTo("IN_PROGRESS"))
            .extract().path("delegations[0].id");
        am().when().get("/delegations").then().statusCode(200).body("delegations.id", hasItem(delegationId));
        am().body("{\"delegationIds\": [\"" + delegationId + "\"]}")
            .when().put(base + "/delegations").then().statusCode(200).body("errors", hasSize(0));

        am().body("{\"controlStatus\": \"REVIEWED\", \"commentBody\": \"looks good\"}")
            .when().put(base + "/controlSets/Access/controls/" + controlId)
            .then().statusCode(200)
            .body("control.status", equalTo("REVIEWED"))
            .body("control.comments[0].commentBody", equalTo("looks good"));
        String reportId = am().body("{\"name\": \"q1\"}").when().post(base + "/reports")
            .then().statusCode(200).body("assessmentReport.status", equalTo("COMPLETE"))
            .extract().path("assessmentReport.id");
        am().when().get(base + "/reports/" + reportId + "/url")
            .then().statusCode(200).body("preSignedUrl.link", startsWith("https://audit-reports.s3.us-east-1."));

        am().body("{\"status\": \"INACTIVE\"}").when().put(base + "/status")
            .then().statusCode(200).body("assessment.metadata.status", equalTo("INACTIVE"));
        am().when().delete(base).then().statusCode(200);
        am().when().get(base).then().statusCode(404).body("__type", equalTo("ResourceNotFoundException"));
        am().when().delete("/assessmentFrameworks/" + frameworkId).then().statusCode(200);
        am().when().delete("/controls/" + controlId).then().statusCode(200);
        am().when().post("/account/deregisterAccount").then().statusCode(200).body("status", equalTo("INACTIVE"));
    }
}
