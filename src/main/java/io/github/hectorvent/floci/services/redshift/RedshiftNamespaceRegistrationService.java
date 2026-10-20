package io.github.hectorvent.floci.services.redshift;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import io.github.hectorvent.floci.services.redshift.model.NamespaceRegistration;
import io.github.hectorvent.floci.services.redshiftserverless.RedshiftServerlessService;
import io.github.hectorvent.floci.services.redshiftserverless.model.Namespace;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * RegisterNamespace and DeregisterNamespace for provisioned clusters and serverless namespaces,
 * with each registration listed by DescribeDataShares as the namespace's internal data share.
 */
@ApplicationScoped
public class RedshiftNamespaceRegistrationService {

    static final String INTERNAL_DATA_SHARE = "ds_internal_namespace";

    // region|namespaceId -> registration
    private final StorageBackend<String, NamespaceRegistration> registrations;
    private final RedshiftService redshiftService;
    private final RedshiftServerlessService serverlessService;
    private final RegionResolver regionResolver;

    @Inject
    public RedshiftNamespaceRegistrationService(StorageFactory storageFactory, RedshiftService redshiftService,
                                                RedshiftServerlessService serverlessService,
                                                RegionResolver regionResolver) {
        this(storageFactory.create("redshift", "redshift-namespace-registrations.json",
                new TypeReference<Map<String, NamespaceRegistration>>() {}),
                redshiftService, serverlessService, regionResolver);
    }

    RedshiftNamespaceRegistrationService(StorageBackend<String, NamespaceRegistration> registrations,
                                         RedshiftService redshiftService,
                                         RedshiftServerlessService serverlessService,
                                         RegionResolver regionResolver) {
        this.registrations = registrations;
        this.redshiftService = redshiftService;
        this.serverlessService = serverlessService;
        this.regionResolver = regionResolver;
    }

    /** The namespace ARN a provisioned cluster reports as ClusterNamespaceArn. */
    public String clusterNamespaceArn(Cluster cluster, String region) {
        return regionResolver.buildArn("redshift", region, "namespace:" + cluster.getClusterNamespaceId());
    }

    public synchronized void register(String region, String clusterIdentifier, String serverlessNamespace,
                                      List<String> consumers) {
        if (consumers == null || consumers.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "ConsumerIdentifiers is required.", 400);
        }
        NamespaceRegistration registration = resolve(region, clusterIdentifier, serverlessNamespace);
        String key = key(region, registration.getNamespaceId());
        NamespaceRegistration stored = registrations.get(key).orElse(registration);
        Instant now = Instant.now();
        consumers.forEach(consumer -> stored.getConsumers().putIfAbsent(consumer, now));
        registrations.put(key, stored);
    }

    public synchronized void deregister(String region, String clusterIdentifier, String serverlessNamespace,
                                        List<String> consumers) {
        if (consumers == null || consumers.isEmpty()) {
            throw new AwsException("InvalidParameterValue", "ConsumerIdentifiers is required.", 400);
        }
        NamespaceRegistration resolved = resolve(region, clusterIdentifier, serverlessNamespace);
        String key = key(region, resolved.getNamespaceId());
        NamespaceRegistration stored = registrations.get(key).orElseThrow(() -> new AwsException(
                "InvalidNamespaceFault", "Namespace " + resolved.getNamespaceArn() + " is not registered.", 400));
        consumers.forEach(stored.getConsumers()::remove);
        if (stored.getConsumers().isEmpty()) {
            registrations.delete(key);
        } else {
            registrations.put(key, stored);
        }
    }

    public List<NamespaceRegistration> dataShares(String region, String dataShareArn) {
        return registrations.scan(k -> k.startsWith(region + "|")).stream()
                .filter(r -> dataShareArn == null || dataShareArn.equals(r.getDataShareArn()))
                .sorted(Comparator.comparing(NamespaceRegistration::getDataShareArn))
                .toList();
    }

    private NamespaceRegistration resolve(String region, String clusterIdentifier, String serverlessNamespace) {
        boolean provisioned = clusterIdentifier != null && !clusterIdentifier.isBlank();
        boolean serverless = serverlessNamespace != null && !serverlessNamespace.isBlank();
        if (provisioned == serverless) {
            throw new AwsException("InvalidParameterValue",
                    "Specify exactly one of ProvisionedIdentifier and ServerlessIdentifier.", 400);
        }
        String namespaceId;
        String namespaceArn;
        if (provisioned) {
            Cluster cluster = redshiftService.describeClusters(clusterIdentifier).getFirst();
            namespaceId = cluster.getClusterNamespaceId();
            namespaceArn = clusterNamespaceArn(cluster, region);
        } else {
            Namespace namespace = serverlessService.findNamespace(serverlessNamespace, region)
                    .orElseThrow(() -> new AwsException("InvalidNamespaceFault",
                            "Namespace " + serverlessNamespace + " does not exist.", 400));
            namespaceId = namespace.getNamespaceId();
            namespaceArn = namespace.getNamespaceArn();
        }
        NamespaceRegistration registration = new NamespaceRegistration();
        registration.setNamespaceId(namespaceId);
        registration.setNamespaceArn(namespaceArn);
        registration.setDataShareArn(regionResolver.buildArn("redshift", region,
                "datashare:" + namespaceId + "/" + INTERNAL_DATA_SHARE));
        registration.setRegion(region);
        return registration;
    }

    private static String key(String region, String namespaceId) {
        return region + "|" + namespaceId;
    }
}
