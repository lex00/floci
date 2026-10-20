package io.github.hectorvent.floci.services.codeconnections;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.codeconnections.model.Connection;
import io.github.hectorvent.floci.services.codeconnections.model.Host;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeConnectionsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final CodeConnectionsService service = new CodeConnectionsService(
            AccountAwareStorageBackend.inMemory(ACCOUNT), AccountAwareStorageBackend.inMemory(ACCOUNT));

    @Test
    void connectionStartsPending() {
        Connection connection = service.createConnection(REGION, ACCOUNT, "gh", "GitHub", null,
                List.of(Map.of("Key", "team", "Value", "a")));

        assertTrue(connection.getConnectionArn().startsWith("arn:aws:codeconnections:us-east-1:000000000000:connection/"));
        assertEquals("PENDING", service.getConnection(connection.getConnectionArn()).getConnectionStatus());
        assertEquals(List.of(Map.of("Key", "team", "Value", "a")), service.listTags(connection.getConnectionArn()));
    }

    @Test
    void connectionOnHostTakesTheHostProvider() {
        Host host = service.createHost(REGION, ACCOUNT, "ghes", "GitHubEnterpriseServer",
                "https://ghes.example.com", null, null);

        Connection connection = service.createConnection(REGION, ACCOUNT, "c", null, host.getHostArn(), null);

        assertEquals("GitHubEnterpriseServer", connection.getProviderType());
        assertEquals(List.of(connection), service.listConnections(REGION, null, host.getHostArn()));
    }

    @Test
    void missingProviderAndUnknownArnAreRefused() {
        assertEquals("ValidationException", assertThrows(AwsException.class,
                () -> service.createConnection(REGION, ACCOUNT, "c", null, null, null)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getConnection("arn:aws:codeconnections:us-east-1:000000000000:connection/x"))
                .getErrorCode());
    }

    @Test
    void tagAndUntag() {
        Connection connection = service.createConnection(REGION, ACCOUNT, "c", "GitLab", null, null);
        service.tagResource(connection.getConnectionArn(),
                List.of(Map.of("Key", "a", "Value", "1"), Map.of("Key", "b", "Value", "2")));
        service.untagResource(connection.getConnectionArn(), List.of("a"));

        assertEquals(List.of(Map.of("Key", "b", "Value", "2")), service.listTags(connection.getConnectionArn()));
    }

    @Test
    void deleteRemovesTheConnection() {
        Connection connection = service.createConnection(REGION, ACCOUNT, "c", "Bitbucket", null, null);
        service.deleteConnection(connection.getConnectionArn());

        assertTrue(service.listConnections(REGION, null, null).isEmpty());
    }
}
