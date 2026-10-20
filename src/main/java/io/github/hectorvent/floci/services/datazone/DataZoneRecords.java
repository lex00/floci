package io.github.hectorvent.floci.services.datazone;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.common.SmithyValidationErrors;
import io.github.hectorvent.floci.core.storage.StorageBackend;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Shared storage for DataZone resources kept as JSON documents. Records are keyed by type,
 * region, domain and id, so a domain's records are found and dropped by key prefix.
 */
abstract class DataZoneRecords implements Resettable {

    static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    final StorageBackend<String, ObjectNode> records;
    final DataZoneService domains;
    final RegionResolver regionResolver;

    /** For the client proxy of a normal-scoped subclass. */
    DataZoneRecords() {
        this(null, null, null);
    }

    DataZoneRecords(StorageBackend<String, ObjectNode> records, DataZoneService domains,
                    RegionResolver regionResolver) {
        this.records = records;
        this.domains = domains;
        this.regionResolver = regionResolver;
    }

    @Override
    public void clear() {
        records.clear();
    }

    public synchronized void deleteDomainResources(String region, String domainId) {
        for (String key : List.copyOf(records.keys())) {
            if (key.contains("::" + region + "::" + domainId + "::")) {
                records.delete(key);
            }
        }
    }

    String domainId(String region, String domainIdentifier) {
        return domains.getDomain(region, domainIdentifier).getId();
    }

    Optional<ObjectNode> find(String type, String region, String domainId, String id) {
        return records.get(key(type, region, domainId, id));
    }

    ObjectNode require(String type, String region, String domainId, String id, String label) {
        return find(type, region, domainId, id).orElseThrow(() -> notFound(label + " " + id + " not found."));
    }

    void put(String type, String region, String domainId, String id, ObjectNode record) {
        records.put(key(type, region, domainId, id), record);
    }

    void delete(String type, String region, String domainId, String id) {
        records.delete(key(type, region, domainId, id));
    }

    /** The domain's records of one type, oldest first. */
    List<ObjectNode> list(String type, String region, String domainId) {
        String prefix = key(type, region, domainId, "");
        return records.scan(k -> k.startsWith(prefix)).stream()
                .map(ObjectNode::deepCopy)
                .sorted(Comparator.comparing((ObjectNode n) -> n.path("createdAt").decimalValue())
                        .thenComparing(n -> n.path("id").asText()))
                .toList();
    }

    String account() {
        return regionResolver.getAccountId();
    }

    static String key(String type, String region, String domainId, String id) {
        return type + "::" + region + "::" + domainId + "::" + id;
    }

    static BigDecimal now() {
        return BigDecimal.valueOf(Instant.now().toEpochMilli()).movePointLeft(3);
    }

    static void copy(JsonNode from, ObjectNode to, String... members) {
        for (String member : members) {
            if (from.hasNonNull(member)) {
                to.set(member, from.get(member).deepCopy());
            }
        }
    }

    static ObjectNode pick(ObjectNode from, String... members) {
        ObjectNode out = JSON.objectNode();
        copy(from, out, members);
        return out;
    }

    static String requiredText(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || value.isNull() || value.asText().isEmpty()) {
            throw required(member);
        }
        return value.asText();
    }

    static JsonNode requiredNode(JsonNode request, String member) {
        JsonNode value = request.get(member);
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw required(member);
        }
        return value;
    }

    static String text(JsonNode request, String member) {
        JsonNode value = request.get(member);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String requireEnum(String value, String member, Collection<String> allowed) {
        if (value != null && !allowed.contains(value)) {
            List<String> sorted = new ArrayList<>(allowed);
            sorted.sort(null);
            throw SmithyValidationErrors.validationException("Value '" + value + "' at '" + member
                    + "' failed to satisfy constraint: Member must satisfy enum value set: " + sorted);
        }
        return value;
    }

    /** The single member set on a union value, or a ValidationException. */
    static String unionMember(JsonNode union, String member) {
        if (union == null || !union.isObject() || union.size() != 1) {
            throw SmithyValidationErrors.validationException(
                    "Exactly one member of '" + member + "' must be set.");
        }
        return union.fieldNames().next();
    }

    static AwsException required(String member) {
        return SmithyValidationErrors.validationException(
                "Value null at '" + member + "' failed to satisfy constraint: Member must not be null");
    }

    static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 404);
    }

    static AwsException conflict(String message) {
        return new AwsException("ConflictException", message, 409);
    }
}
