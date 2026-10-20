package io.github.hectorvent.floci.services.codeconnections;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codeconnections.model.Connection;
import io.github.hectorvent.floci.services.codeconnections.model.Host;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stores CodeConnections connections and hosts. A connection stays {@code PENDING} because the
 * provider handshake that makes it {@code AVAILABLE} in AWS has no local equivalent.
 */
@ApplicationScoped
public class CodeConnectionsService implements Resettable {

    static final Set<String> PROVIDER_TYPES = Set.of(
            "Bitbucket", "GitHub", "GitHubEnterpriseServer", "GitLab", "GitLabSelfManaged", "AzureDevOps");

    private final AccountAwareStorageBackend<Connection> connections;
    private final AccountAwareStorageBackend<Host> hosts;

    @Inject
    public CodeConnectionsService(StorageFactory storageFactory) {
        this(storageFactory.create("codeconnections", "codeconnections-connections.json",
                        new TypeReference<Map<String, Connection>>() {}),
                storageFactory.create("codeconnections", "codeconnections-hosts.json",
                        new TypeReference<Map<String, Host>>() {}));
    }

    CodeConnectionsService(AccountAwareStorageBackend<Connection> connections,
                           AccountAwareStorageBackend<Host> hosts) {
        this.connections = connections;
        this.hosts = hosts;
    }

    @Override
    public void clear() {
        connections.clear();
        hosts.clear();
    }

    public Connection createConnection(String region, String accountId, String name, String providerType,
                                       String hostArn, List<Map<String, String>> tags) {
        if (name == null || name.isBlank() || name.length() > 32) {
            throw validation("ConnectionName must be between 1 and 32 characters.");
        }
        String resolvedProvider = providerType;
        if (hostArn != null) {
            resolvedProvider = getHost(hostArn).getProviderType();
        } else if (providerType == null) {
            throw validation("Either ProviderType or HostArn must be specified.");
        }
        validateProvider(resolvedProvider);
        Connection connection = new Connection();
        connection.setConnectionName(name);
        connection.setConnectionArn(AwsArnUtils.Arn.of("codeconnections", region, accountId,
                "connection/" + UUID.randomUUID()).toString());
        connection.setProviderType(resolvedProvider);
        connection.setOwnerAccountId(accountId);
        connection.setConnectionStatus("PENDING");
        connection.setHostArn(hostArn);
        connection.setTags(tags == null ? new ArrayList<>() : new ArrayList<>(tags));
        connections.put(connection.getConnectionArn(), connection);
        return connection;
    }

    public Connection getConnection(String arn) {
        return connections.get(requireArn(arn, "ConnectionArn"))
                .orElseThrow(() -> notFound("Connection " + arn + " not found"));
    }

    public List<Connection> listConnections(String region, String providerFilter, String hostArnFilter) {
        return connections.scan(k -> regionOf(k).equals(region)).stream()
                .filter(c -> providerFilter == null || providerFilter.equals(c.getProviderType()))
                .filter(c -> hostArnFilter == null || hostArnFilter.equals(c.getHostArn()))
                .sorted(Comparator.comparing(Connection::getConnectionName))
                .toList();
    }

    public void deleteConnection(String arn) {
        getConnection(arn);
        connections.delete(arn);
    }

    public Host createHost(String region, String accountId, String name, String providerType,
                           String providerEndpoint, Map<String, Object> vpcConfiguration,
                           List<Map<String, String>> tags) {
        if (name == null || name.isBlank() || name.length() > 64) {
            throw validation("Name must be between 1 and 64 characters.");
        }
        if (providerEndpoint == null || providerEndpoint.isBlank()) {
            throw validation("ProviderEndpoint is required.");
        }
        validateProvider(providerType);
        Host host = new Host();
        host.setName(name);
        host.setHostArn(AwsArnUtils.Arn.of("codeconnections", region, accountId,
                "host/" + name + "-" + UUID.randomUUID().toString().substring(0, 8)).toString());
        host.setProviderType(providerType);
        host.setProviderEndpoint(providerEndpoint);
        host.setVpcConfiguration(vpcConfiguration);
        host.setStatus("PENDING");
        host.setTags(tags == null ? new ArrayList<>() : new ArrayList<>(tags));
        hosts.put(host.getHostArn(), host);
        return host;
    }

    public Host getHost(String arn) {
        return hosts.get(requireArn(arn, "HostArn")).orElseThrow(() -> notFound("Host " + arn + " not found"));
    }

    public List<Host> listHosts(String region) {
        return hosts.scan(k -> regionOf(k).equals(region)).stream()
                .sorted(Comparator.comparing(Host::getName))
                .toList();
    }

    public void deleteHost(String arn) {
        getHost(arn);
        hosts.delete(arn);
    }

    public List<Map<String, String>> listTags(String arn) {
        return new ArrayList<>(tagsOf(arn));
    }

    public void tagResource(String arn, List<Map<String, String>> tags) {
        List<Map<String, String>> current = tagsOf(arn);
        Map<String, String> merged = new LinkedHashMap<>();
        current.forEach(t -> merged.put(t.get("Key"), t.get("Value")));
        if (tags != null) {
            tags.forEach(t -> merged.put(t.get("Key"), t.get("Value")));
        }
        if (merged.size() > 200) {
            throw new AwsException("LimitExceededException", "A resource can have at most 200 tags.", 400);
        }
        saveTags(arn, toTagList(merged));
    }

    public void untagResource(String arn, List<String> keys) {
        Set<String> removed = keys == null ? Set.of() : new HashSet<>(keys);
        List<Map<String, String>> remaining = tagsOf(arn).stream()
                .filter(t -> !removed.contains(t.get("Key")))
                .toList();
        saveTags(arn, new ArrayList<>(remaining));
    }

    private List<Map<String, String>> tagsOf(String arn) {
        requireArn(arn, "ResourceArn");
        Connection connection = connections.get(arn).orElse(null);
        if (connection != null) {
            return connection.getTags();
        }
        Host host = hosts.get(arn).orElse(null);
        if (host != null) {
            return host.getTags();
        }
        throw notFound("Resource " + arn + " not found");
    }

    private void saveTags(String arn, List<Map<String, String>> tags) {
        Connection connection = connections.get(arn).orElse(null);
        if (connection != null) {
            connection.setTags(tags);
            connections.put(arn, connection);
            return;
        }
        Host host = hosts.get(arn).orElseThrow(() -> notFound("Resource " + arn + " not found"));
        host.setTags(tags);
        hosts.put(arn, host);
    }

    private static List<Map<String, String>> toTagList(Map<String, String> tags) {
        List<Map<String, String>> list = new ArrayList<>();
        tags.forEach((k, v) -> {
            Map<String, String> tag = new LinkedHashMap<>();
            tag.put("Key", k);
            tag.put("Value", v);
            list.add(tag);
        });
        return list;
    }

    private static String regionOf(String arn) {
        String[] parts = arn.split(":", 6);
        return parts.length > 3 ? parts[3] : "";
    }

    private static void validateProvider(String providerType) {
        if (!PROVIDER_TYPES.contains(providerType)) {
            throw validation("Value '" + providerType + "' at 'providerType' failed to satisfy constraint: "
                    + "Member must satisfy enum value set: " + PROVIDER_TYPES);
        }
    }

    private static String requireArn(String arn, String member) {
        if (arn == null || arn.isBlank()) {
            throw validation(member + " is required.");
        }
        return arn;
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationException", message, 400);
    }

    private static AwsException notFound(String message) {
        return new AwsException("ResourceNotFoundException", message, 400);
    }
}
