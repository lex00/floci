package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.apigateway.model.ClientCertificate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * API Gateway client certificates. Each one carries a freshly generated self-signed certificate,
 * valid for 365 days as AWS issues them; Floci keeps no private key and presents the certificate
 * to no backend.
 */
@ApplicationScoped
public class ApiGatewayClientCertificateService {

    private static final long VALIDITY_DAYS = 365;

    private final StorageBackend<String, ClientCertificate> store;
    private final CertificateGenerator certificateGenerator;

    @Inject
    public ApiGatewayClientCertificateService(StorageFactory storageFactory,
                                              CertificateGenerator certificateGenerator) {
        this(storageFactory.create("apigateway", "apigateway-clientcertificates.json",
                new TypeReference<Map<String, ClientCertificate>>() {}), certificateGenerator);
    }

    ApiGatewayClientCertificateService(StorageBackend<String, ClientCertificate> store,
                                       CertificateGenerator certificateGenerator) {
        this.store = store;
        this.certificateGenerator = certificateGenerator;
    }

    public ClientCertificate generate(String region, String description, Map<String, String> tags) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        Instant created = Instant.now();
        Instant expires = created.plus(VALIDITY_DAYS, ChronoUnit.DAYS);
        ClientCertificate certificate = new ClientCertificate();
        certificate.setClientCertificateId(id);
        certificate.setDescription(description);
        certificate.setPemEncodedCertificate(
                certificateGenerator.generateCaCertificate(id, expires).certificatePem());
        certificate.setCreatedDate(created.getEpochSecond());
        certificate.setExpirationDate(expires.getEpochSecond());
        certificate.setTags(tags == null ? new HashMap<>() : new HashMap<>(tags));
        store.put(key(region, id), certificate);
        return certificate;
    }

    public ClientCertificate get(String region, String id) {
        return store.get(key(region, id)).orElseThrow(() ->
                new AwsException("NotFoundException", "Invalid Client Certificate identifier specified", 404));
    }

    public List<ClientCertificate> list(String region) {
        return store.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(ClientCertificate::getClientCertificateId))
                .toList();
    }

    /** Applies the {@code replace /description} patch, the one mutable member. */
    public ClientCertificate update(String region, String id, List<Map<String, String>> patchOperations) {
        ClientCertificate certificate = get(region, id);
        if (patchOperations != null) {
            for (Map<String, String> operation : patchOperations) {
                if (!"/description".equals(operation.get("path"))) {
                    throw new AwsException("BadRequestException",
                            "Invalid patch path '" + operation.get("path") + "' specified for op '"
                                    + operation.get("op") + "'. Must be one of: [/description]", 400);
                }
                certificate.setDescription("remove".equals(operation.get("op")) ? null : operation.get("value"));
            }
        }
        store.put(key(region, id), certificate);
        return certificate;
    }

    public void delete(String region, String id) {
        get(region, id);
        store.delete(key(region, id));
    }

    public void tag(String region, String id, Map<String, String> tags) {
        ClientCertificate certificate = get(region, id);
        certificate.getTags().putAll(tags);
        store.put(key(region, id), certificate);
    }

    public void untag(String region, String id, List<String> tagKeys) {
        ClientCertificate certificate = get(region, id);
        tagKeys.forEach(certificate.getTags()::remove);
        store.put(key(region, id), certificate);
    }

    private static String key(String region, String id) {
        return region + "::" + id;
    }
}
