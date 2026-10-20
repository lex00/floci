package io.github.hectorvent.floci.services.opensearchserverless;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.opensearchserverless.model.SecurityConfig;
import io.github.hectorvent.floci.services.opensearchserverless.model.SecurityPolicy;
import io.github.hectorvent.floci.services.opensearchserverless.model.ServerlessCollection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenSearchServerlessServiceTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenSearchServerlessService service = new OpenSearchServerlessService(
            new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
            new RegionResolver(REGION, "000000000000"), mapper);

    @Test
    void securityConfigIdCarriesTypeAccountAndName() throws Exception {
        SecurityConfig config = service.createSecurityConfig(REGION, "saml", "my-config", "desc",
                mapper.readTree("{\"metadata\":\"<x/>\"}"), null, null);

        assertEquals("saml/000000000000/my-config", config.getId());
        assertEquals(config.getConfigVersion(), service.getSecurityConfig(REGION, config.getId()).getConfigVersion());
        assertEquals(1, service.listSecurityConfigs(REGION, "saml").size());
        assertEquals(0, service.listSecurityConfigs(REGION, "iamfederation").size());
    }

    @Test
    void samlConfigRequiresMetadata() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createSecurityConfig(REGION, "saml", "no-meta", null, null, null, null));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void duplicateSecurityConfigConflicts() throws Exception {
        service.createSecurityConfig(REGION, "iamfederation", "fed", null, null, null,
                mapper.readTree("{\"userAttribute\":\"sub\"}"));
        AwsException e = assertThrows(AwsException.class, () -> service.createSecurityConfig(
                REGION, "iamfederation", "fed", null, null, null, null));
        assertEquals("ConflictException", e.getErrorCode());
    }

    @Test
    void updateRequiresTheCurrentConfigVersionAndIssuesANewOne() throws Exception {
        SecurityConfig config = service.createSecurityConfig(REGION, "saml", "versioned", null,
                mapper.readTree("{\"metadata\":\"<x/>\"}"), null, null);
        String first = config.getConfigVersion();

        SecurityConfig updated = service.updateSecurityConfig(REGION, config.getId(), first, "new", null, null, null);
        assertEquals("new", updated.getDescription());
        assertNotEquals(first, updated.getConfigVersion());

        AwsException stale = assertThrows(AwsException.class, () -> service.updateSecurityConfig(
                REGION, config.getId(), first, "again", null, null, null));
        assertEquals("ValidationException", stale.getErrorCode());
    }

    @Test
    void deletedSecurityConfigIsNotFound() throws Exception {
        SecurityConfig config = service.createSecurityConfig(REGION, "saml", "gone", null,
                mapper.readTree("{\"metadata\":\"<x/>\"}"), null, null);
        service.deleteSecurityConfig(REGION, config.getId());

        AwsException e = assertThrows(AwsException.class, () -> service.getSecurityConfig(REGION, config.getId()));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
    }

    @Test
    void collectionNeedsAMatchingEncryptionPolicy() {
        AwsException e = assertThrows(AwsException.class, () -> service.createCollection(
                REGION, "logs", null, null, Map.of(), null, null, null));
        assertEquals("ValidationException", e.getErrorCode());

        SecurityPolicy policy = service.createSecurityPolicy(REGION, "encryption", "logs-enc", null,
                "{\"Rules\":[{\"ResourceType\":\"collection\",\"Resource\":[\"collection/log*\"]}],\"AWSOwnedKey\":true}");
        ServerlessCollection collection = service.createCollection(
                REGION, "logs", null, null, Map.of("env", "test"), null, null, null);

        assertEquals("auto", collection.getKmsKeyArn());
        assertEquals("SEARCH", collection.getType());
        assertTrue(collection.getArn().endsWith(":aoss:us-east-1:000000000000:collection/" + collection.getId()));
        assertEquals(1, service.listSecurityPolicies(REGION, "encryption", List.of("collection/logs")).size());
        assertEquals("test", service.listTags(REGION, collection.getArn()).get("env"));
        assertEquals(policy.getName(), "logs-enc");
    }
}
