package io.github.hectorvent.floci.services.redshift;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedshiftNamespaceRegistrationTest {

    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=test/20260101/us-east-1/redshift/aws4_request";

    private RedshiftService redshift;
    private RedshiftServerlessService serverless;
    private RedshiftNamespaceRegistrationService registrations;
    private RedshiftQueryHandler handler;
    private Cluster cluster;

    @BeforeEach
    void setUp() {
        redshift = mock(RedshiftService.class);
        serverless = mock(RedshiftServerlessService.class);
        RegionResolver regionResolver = new RegionResolver("us-east-1", "000000000000");
        registrations = new RedshiftNamespaceRegistrationService(new InMemoryStorage<>(), redshift, serverless,
                regionResolver);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        handler = new RedshiftQueryHandler(redshift, new RedshiftCredentialBroker(), config,
                mock(RedshiftIamDbUserResolver.class), regionResolver, null, registrations);
        cluster = new Cluster();
        cluster.setClusterIdentifier("producer");
        cluster.setClusterStatus("available");
        cluster.setClusterNamespaceId("11111111-2222-3333-4444-555555555555");
        when(redshift.describeClusters("producer")).thenReturn(List.of(cluster));
        when(redshift.describeClusters(null)).thenReturn(List.of(cluster));
    }

    @Test
    void provisionedRegistrationIsListedAsTheInternalDataShare() {
        String shareArn = "arn:aws:redshift:us-east-1:000000000000:datashare:"
                + "11111111-2222-3333-4444-555555555555/ds_internal_namespace";

        String register = body(handler.handle("RegisterNamespace", provisioned("DataCatalog/000000000000"), AUTH));
        assertTrue(register.contains("<Status>Registering</Status>"), register);

        String shares = body(handler.handle("DescribeDataShares", params("DataShareArn", shareArn), AUTH));
        assertTrue(shares.contains("<DataShareArn>" + shareArn + "</DataShareArn>"), shares);
        assertTrue(shares.contains("<ProducerArn>arn:aws:redshift:us-east-1:000000000000:namespace:"
                + "11111111-2222-3333-4444-555555555555</ProducerArn>"), shares);
        assertTrue(shares.contains("<ConsumerIdentifier>DataCatalog/000000000000</ConsumerIdentifier>"), shares);

        String clusters = body(handler.handle("DescribeClusters", new MultivaluedHashMap<>(), AUTH));
        assertTrue(clusters.contains("<ClusterNamespaceArn>arn:aws:redshift:us-east-1:000000000000:namespace:"
                + "11111111-2222-3333-4444-555555555555</ClusterNamespaceArn>"), clusters);

        String deregister = body(handler.handle("DeregisterNamespace", provisioned("DataCatalog/000000000000"), AUTH));
        assertTrue(deregister.contains("<Status>Deregistering</Status>"), deregister);
        String after = body(handler.handle("DescribeDataShares", params("DataShareArn", shareArn), AUTH));
        assertFalse(after.contains("<DataShareArn>"), after);
    }

    @Test
    void serverlessRegistrationResolvesTheNamespace() {
        Namespace namespace = mock(Namespace.class);
        when(namespace.getNamespaceId()).thenReturn("ns-id");
        when(namespace.getNamespaceArn()).thenReturn("arn:aws:redshift-serverless:us-east-1:000000000000:namespace/ns-id");
        when(serverless.findNamespace(anyString(), anyString())).thenReturn(Optional.empty());
        when(serverless.findNamespace("analytics", "us-east-1")).thenReturn(Optional.of(namespace));

        registrations.register("us-east-1", null, "analytics", List.of("consumer-1"));

        assertEquals(1, registrations.dataShares("us-east-1", null).size());
        assertThrows(AwsException.class,
                () -> registrations.register("us-east-1", null, "missing", List.of("consumer-1")));
        assertThrows(AwsException.class,
                () -> registrations.register("us-east-1", "producer", "analytics", List.of("consumer-1")));
    }

    private static MultivaluedMap<String, String> provisioned(String consumer) {
        MultivaluedMap<String, String> params =
                params("NamespaceIdentifier.ProvisionedIdentifier.ClusterIdentifier", "producer");
        params.putSingle("ConsumerIdentifiers.member.1", consumer);
        return params;
    }

    private static MultivaluedMap<String, String> params(String key, String value) {
        MultivaluedMap<String, String> params = new MultivaluedHashMap<>();
        params.putSingle(key, value);
        return params;
    }

    private static String body(Response response) {
        assertEquals(200, response.getStatus());
        return String.valueOf(response.getEntity());
    }
}
