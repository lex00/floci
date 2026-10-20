package io.github.hectorvent.floci.services.workspaces;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class WorkSpacesIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/workspaces/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static ValidatableResponse call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "WorkspacesService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/")
            .then();
    }

    @Test
    void ipGroupLifecycleWithTags() {
        String groupId = call("CreateIpGroup", """
                {"GroupName":"it-group","GroupDesc":"office",
                 "UserRules":[{"ipRule":"10.0.0.0/16","ruleDesc":"vpn"}],
                 "Tags":[{"Key":"team","Value":"euc"}]}""")
                .statusCode(200)
                .body("GroupId", startsWith("wsipg-"))
                .extract().path("GroupId");

        call("AuthorizeIpRules", "{\"GroupId\":\"" + groupId + "\",\"UserRules\":[{\"ipRule\":\"10.1.0.0/16\"}]}")
                .statusCode(200);
        call("DescribeIpGroups", "{\"GroupIds\":[\"" + groupId + "\"]}")
                .statusCode(200)
                .body("Result[0].groupName", equalTo("it-group"))
                .body("Result[0].userRules", hasSize(2));
        call("DescribeTags", "{\"ResourceId\":\"" + groupId + "\"}")
                .statusCode(200)
                .body("TagList[0].Key", equalTo("team"));
        call("DeleteIpGroup", "{\"GroupId\":\"" + groupId + "\"}").statusCode(200);
        call("DeleteIpGroup", "{\"GroupId\":\"" + groupId + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void connectionAliasIsCreatedAtOnce() {
        String aliasId = call("CreateConnectionAlias", "{\"ConnectionString\":\"it.example.com\"}")
                .statusCode(200)
                .body("AliasId", startsWith("wsca-"))
                .extract().path("AliasId");
        call("DescribeConnectionAliases", "{\"AliasIds\":[\"" + aliasId + "\"]}")
                .statusCode(200)
                .body("ConnectionAliases[0].State", equalTo("CREATED"))
                .body("ConnectionAliases[0].OwnerAccountId", equalTo("000000000000"));
        call("DeleteConnectionAlias", "{\"AliasId\":\"" + aliasId + "\"}").statusCode(200);
    }

    @Test
    void workspaceIsAvailableAfterCreate() {
        String workspaceId = call("CreateWorkspaces", """
                {"Workspaces":[{"DirectoryId":"d-9067000000","UserName":"it-user","BundleId":"wsb-bh8rsxt14",
                 "WorkspaceProperties":{"RunningMode":"ALWAYS_ON"}}]}""")
                .statusCode(200)
                .body("FailedRequests", empty())
                .body("PendingRequests[0].State", equalTo("PENDING"))
                .extract().path("PendingRequests[0].WorkspaceId");

        call("DescribeWorkspaces", "{\"WorkspaceIds\":[\"" + workspaceId + "\"]}")
                .statusCode(200)
                .body("Workspaces[0].State", equalTo("AVAILABLE"))
                .body("Workspaces[0].WorkspaceProperties.RunningMode", equalTo("ALWAYS_ON"));
        call("TerminateWorkspaces", "{\"TerminateWorkspaceRequests\":[{\"WorkspaceId\":\"" + workspaceId + "\"}]}")
                .statusCode(200)
                .body("FailedRequests", empty());
    }

    @Test
    void poolLifecycle() {
        String poolId = call("CreateWorkspacesPool", """
                {"PoolName":"itpool","Description":"pool","BundleId":"wsb-1","DirectoryId":"wsd-1",
                 "Capacity":{"DesiredUserSessions":2},"RunningMode":"AUTO_STOP"}""")
                .statusCode(200)
                .body("WorkspacesPool.State", equalTo("STOPPED"))
                .body("WorkspacesPool.PoolArn", startsWith("arn:aws:workspaces:us-east-1:000000000000:workspacespool/"))
                .extract().path("WorkspacesPool.PoolId");
        call("UpdateWorkspacesPool", "{\"PoolId\":\"" + poolId + "\",\"Description\":\"changed\"}")
                .statusCode(200)
                .body("WorkspacesPool.Description", equalTo("changed"));
        call("DescribeWorkspacesPools", "{\"PoolIds\":[\"" + poolId + "\"]}")
                .statusCode(200)
                .body("WorkspacesPools[0].CapacityStatus.DesiredUserSessions", equalTo(2));
        call("TerminateWorkspacesPool", "{\"PoolId\":\"" + poolId + "\"}").statusCode(200);
    }

    @Test
    void directoryAndBundleLifecycle() {
        call("RegisterWorkspaceDirectory", """
                {"DirectoryId":"d-9067a1b2c3","SubnetIds":["subnet-1","subnet-2"],"EnableSelfService":true,
                 "Tenancy":"SHARED","Tags":[{"Key":"team","Value":"euc"}]}""")
                .statusCode(200)
                .body("State", equalTo("REGISTERED"));
        call("ModifyWorkspaceCreationProperties", """
                {"ResourceId":"d-9067a1b2c3","WorkspaceCreationProperties":{"EnableInternetAccess":true}}""")
                .statusCode(200);
        call("ModifySelfservicePermissions", """
                {"ResourceId":"d-9067a1b2c3","SelfservicePermissions":{"RebuildWorkspace":"DISABLED"}}""")
                .statusCode(200);
        call("DescribeWorkspaceDirectories", "{\"DirectoryIds\":[\"d-9067a1b2c3\"]}")
                .statusCode(200)
                .body("Directories", hasSize(1))
                .body("Directories[0].WorkspaceCreationProperties.EnableInternetAccess", equalTo(true))
                .body("Directories[0].SelfservicePermissions.RebuildWorkspace", equalTo("DISABLED"))
                .body("Directories[0].SelfservicePermissions.IncreaseVolumeSize", equalTo("ENABLED"));
        call("DescribeTags", "{\"ResourceId\":\"d-9067a1b2c3\"}")
                .statusCode(200)
                .body("TagList[0].Value", equalTo("euc"));
        call("DeregisterWorkspaceDirectory", "{\"DirectoryId\":\"d-9067a1b2c3\"}").statusCode(200);
        call("DescribeWorkspaceDirectories", "{\"DirectoryIds\":[\"d-9067a1b2c3\"]}")
                .statusCode(200)
                .body("Directories", empty());

        call("DescribeWorkspaceBundles", "{\"Owner\":\"AMAZON\"}")
                .statusCode(200)
                .body("Bundles[0].Owner", equalTo("Amazon"));
        String bundleId = call("CreateWorkspaceBundle", """
                {"BundleName":"it-bundle","BundleDescription":"custom","ImageId":"wsi-123456789",
                 "ComputeType":{"Name":"VALUE"},"UserStorage":{"Capacity":"10"},"RootStorage":{"Capacity":"80"}}""")
                .statusCode(200)
                .body("WorkspaceBundle.State", equalTo("AVAILABLE"))
                .body("WorkspaceBundle.BundleId", startsWith("wsb-"))
                .extract().path("WorkspaceBundle.BundleId");
        call("DescribeWorkspaceBundles", "{\"BundleIds\":[\"" + bundleId + "\"]}")
                .statusCode(200)
                .body("Bundles[0].Name", equalTo("it-bundle"));
        call("DeleteWorkspaceBundle", "{\"BundleId\":\"" + bundleId + "\"}").statusCode(200);
        call("DescribeWorkspaceBundles", "{\"BundleIds\":[\"" + bundleId + "\"]}")
                .statusCode(200)
                .body("Bundles", empty());
    }
}
