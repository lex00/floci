package io.github.hectorvent.floci.services.workspacesweb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.workspacesweb.WorkSpacesWebService.Kind;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkSpacesWebServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final WorkSpacesWebService service = new WorkSpacesWebService(name -> new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"));

    private JsonNode json(String text) throws Exception {
        return mapper.readTree(text);
    }

    @Test
    void userSettingsRoundTripAndListSummary() throws Exception {
        Kind kind = WorkSpacesWebService.KINDS.get("userSettings");
        String arn = service.create(kind, json("""
                {"copyAllowed":"Enabled","pasteAllowed":"Enabled","downloadAllowed":"Disabled",
                 "uploadAllowed":"Disabled","printAllowed":"Enabled","customerManagedKey":"k",
                 "tags":[{"Key":"team","Value":"euc"}],"clientToken":"t"}"""), REGION);

        assertTrue(arn.startsWith("arn:aws:workspaces-web:us-east-1:000000000000:userSettings/"));
        ObjectNode settings = service.get(kind, arn);
        assertEquals("Disabled", settings.path("downloadAllowed").asText());
        assertFalse(settings.has("clientToken"));
        ObjectNode summary = service.list(kind, REGION).getFirst();
        assertFalse(summary.has("customerManagedKey"));
        assertEquals(Map.of("team", "euc"), service.listTags(REGION, arn));
    }

    @Test
    void createRequiresModelRequiredMembers() {
        AwsException invalid = assertThrows(AwsException.class,
                () -> service.create(WorkSpacesWebService.KINDS.get("networkSettings"), json("{\"vpcId\":\"v\"}"),
                        REGION));
        assertEquals("ValidationException", invalid.getErrorCode());
    }

    @Test
    void portalBecomesActiveWithRequiredAssociationsAndBlocksSettingsDelete() throws Exception {
        String portalArn = service.createPortal(json("{\"displayName\":\"p\"}"), REGION).path("portalArn").asText();
        assertEquals("Incomplete", service.getPortal(portalArn).path("portalStatus").asText());

        Kind browser = WorkSpacesWebService.KINDS.get("browserSettings");
        Kind network = WorkSpacesWebService.KINDS.get("networkSettings");
        Kind user = WorkSpacesWebService.KINDS.get("userSettings");
        String browserArn = service.create(browser, json("{\"browserPolicy\":\"{}\"}"), REGION);
        String networkArn = service.create(network,
                json("{\"vpcId\":\"vpc-1\",\"subnetIds\":[\"s-1\",\"s-2\"],\"securityGroupIds\":[\"sg-1\"]}"), REGION);
        String userArn = service.create(user, json("""
                {"copyAllowed":"Enabled","pasteAllowed":"Enabled","downloadAllowed":"Enabled",
                 "uploadAllowed":"Enabled","printAllowed":"Enabled"}"""), REGION);
        service.associate(portalArn, browser, browserArn);
        service.associate(portalArn, network, networkArn);
        service.associate(portalArn, user, userArn);

        assertEquals("Active", service.getPortal(portalArn).path("portalStatus").asText());
        assertEquals(List.of(portalArn),
                List.of(service.get(browser, browserArn).path("associatedPortalArns").get(0).asText()));
        AwsException conflict = assertThrows(AwsException.class, () -> service.delete(browser, browserArn));
        assertEquals("ConflictException", conflict.getErrorCode());

        service.disassociate(portalArn, browser);
        assertEquals("Incomplete", service.getPortal(portalArn).path("portalStatus").asText());
        service.delete(browser, browserArn);
        service.deletePortal(portalArn);
        assertTrue(service.get(network, networkArn).path("associatedPortalArns").isEmpty());
    }

    @Test
    void unknownArnIsNotFound() {
        AwsException missing = assertThrows(AwsException.class,
                () -> service.get(WorkSpacesWebService.KINDS.get("trustStores"), "arn:aws:workspaces-web:x"));
        assertEquals("ResourceNotFoundException", missing.getErrorCode());
    }
}
