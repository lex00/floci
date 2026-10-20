package io.github.hectorvent.floci.services.transfer;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class TransferConnectorWorkflowIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/transfer/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sftpConnectorLifecycle() {
        String connectorId = transfer("CreateConnector")
                .body("""
                    {"Url":"sftp://example.com","AccessRole":"arn:aws:iam::000000000000:role/probe",
                     "SftpConfig":{"UserSecretId":"probe","TrustedHostKeys":["ssh-rsa AAAA"]},
                     "Tags":[{"Key":"env","Value":"test"}]}""")
            .when().post("/")
            .then().statusCode(200).body("ConnectorId", startsWith("c-"))
            .extract().path("ConnectorId");

        transfer("DescribeConnector").body("{\"ConnectorId\":\"" + connectorId + "\"}")
            .when().post("/")
            .then().statusCode(200)
                .body("Connector.Url", equalTo("sftp://example.com"))
                .body("Connector.SftpConfig.UserSecretId", equalTo("probe"))
                .body("Connector.Status", equalTo("ACTIVE"))
                .body("Connector.Tags[0].Value", equalTo("test"))
                .body("Connector.Arn", containsString(":connector/" + connectorId));

        transfer("UpdateConnector")
                .body("{\"ConnectorId\":\"" + connectorId + "\",\"Url\":\"sftp://changed.example.com\"}")
            .when().post("/")
            .then().statusCode(200).body("ConnectorId", equalTo(connectorId));

        transfer("ListConnectors").body("{}")
            .when().post("/")
            .then().statusCode(200).body("Connectors.Url", hasItem("sftp://changed.example.com"));

        transfer("DeleteConnector").body("{\"ConnectorId\":\"" + connectorId + "\"}")
            .when().post("/").then().statusCode(200);
        transfer("DescribeConnector").body("{\"ConnectorId\":\"" + connectorId + "\"}")
            .when().post("/").then().statusCode(404)
            .body(containsString("ResourceNotFoundException"));
    }

    @Test
    void connectorNeedsExactlyOneProtocolConfig() {
        transfer("CreateConnector")
                .body("{\"Url\":\"https://example.com\",\"AccessRole\":\"arn:aws:iam::000000000000:role/probe\"}")
            .when().post("/")
            .then().statusCode(400).body(containsString("InvalidRequestException"));
    }

    @Test
    void workflowLifecycle() {
        String workflowId = transfer("CreateWorkflow")
                .body("""
                    {"Description":"probe","Steps":[{"Type":"DELETE","DeleteStepDetails":{"Name":"remove"}}],
                     "Tags":[{"Key":"env","Value":"test"}]}""")
            .when().post("/")
            .then().statusCode(200).body("WorkflowId", startsWith("w-"))
            .extract().path("WorkflowId");

        transfer("DescribeWorkflow").body("{\"WorkflowId\":\"" + workflowId + "\"}")
            .when().post("/")
            .then().statusCode(200)
                .body("Workflow.Description", equalTo("probe"))
                .body("Workflow.Steps[0].DeleteStepDetails.Name", equalTo("remove"))
                .body("Workflow.Tags[0].Key", equalTo("env"));

        transfer("ListWorkflows").body("{}")
            .when().post("/")
            .then().statusCode(200).body("Workflows.WorkflowId", hasItem(workflowId));

        transfer("DeleteWorkflow").body("{\"WorkflowId\":\"" + workflowId + "\"}")
            .when().post("/").then().statusCode(200);
        transfer("DescribeWorkflow").body("{\"WorkflowId\":\"" + workflowId + "\"}")
            .when().post("/").then().statusCode(404);
    }

    private static RequestSpecification transfer(String action) {
        return given()
                .header("X-Amz-Target", "TransferService." + action)
                .header("Authorization", AUTH)
                .contentType(CONTENT_TYPE);
    }
}
