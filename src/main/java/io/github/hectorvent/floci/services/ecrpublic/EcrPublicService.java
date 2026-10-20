package io.github.hectorvent.floci.services.ecrpublic;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecrpublic.model.PublicRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stores ECR Public repositories without images. The registry alias in {@code repositoryUri} is
 * the account id.
 */
@ApplicationScoped
public class EcrPublicService implements Resettable {

    private static final Pattern NAME = Pattern.compile("(?:[a-z0-9]+(?:[._-][a-z0-9]+)*/)*[a-z0-9]+(?:[._-][a-z0-9]+)*");
    private static final String PUBLIC_REGISTRY_HOST = "public.ecr.aws"; // partition-literal: ECR Public has one gallery host

    private final AccountAwareStorageBackend<PublicRepository> store;

    @Inject
    public EcrPublicService(StorageFactory storageFactory) {
        this(storageFactory.create("ecr-public", "ecr-public-repositories.json",
                new TypeReference<Map<String, PublicRepository>>() {}));
    }

    EcrPublicService(AccountAwareStorageBackend<PublicRepository> store) {
        this.store = store;
    }

    @Override
    public void clear() {
        store.clear();
    }

    public PublicRepository createRepository(String region, String accountId, String name,
                                             Map<String, Object> catalogData, List<Map<String, String>> tags) {
        validateName(name);
        if (store.get(name).isPresent()) {
            throw new AwsException("RepositoryAlreadyExistsException",
                    "The repository with name '" + name + "' already exists in the registry with id '"
                            + accountId + "'", 400);
        }
        PublicRepository repository = new PublicRepository();
        repository.setRepositoryName(name);
        repository.setRegistryId(accountId);
        repository.setRepositoryArn(AwsArnUtils.Arn.global(AwsRegions.partitionFor(region), "ecr-public",
                accountId, "repository/" + name).toString());
        repository.setRepositoryUri(PUBLIC_REGISTRY_HOST + "/" + accountId + "/" + name);
        repository.setCreatedAt(Instant.now().toEpochMilli() / 1000.0);
        repository.setCatalogData(catalogOutput(catalogData));
        repository.setTags(tags == null ? new ArrayList<>() : new ArrayList<>(tags));
        store.put(name, repository);
        return repository;
    }

    public PublicRepository getRepository(String accountId, String registryId, String name) {
        checkRegistry(accountId, registryId);
        if (name == null || name.isBlank()) {
            throw new AwsException("InvalidParameterException", "repositoryName is required", 400);
        }
        return store.get(name).orElseThrow(() -> notFound(accountId, name));
    }

    public List<PublicRepository> describeRepositories(String accountId, String registryId, List<String> names) {
        checkRegistry(accountId, registryId);
        if (names == null || names.isEmpty()) {
            return store.scan(k -> true).stream()
                    .sorted(Comparator.comparing(PublicRepository::getRepositoryName))
                    .toList();
        }
        List<PublicRepository> found = new ArrayList<>();
        for (String name : names) {
            found.add(getRepository(accountId, registryId, name));
        }
        return found;
    }

    public PublicRepository deleteRepository(String accountId, String registryId, String name) {
        PublicRepository repository = getRepository(accountId, registryId, name);
        store.delete(name);
        return repository;
    }

    public Map<String, Object> putCatalogData(String accountId, String registryId, String name,
                                              Map<String, Object> catalogData) {
        PublicRepository repository = getRepository(accountId, registryId, name);
        repository.setCatalogData(catalogOutput(catalogData));
        store.put(name, repository);
        return repository.getCatalogData();
    }

    public PublicRepository setPolicy(String accountId, String registryId, String name, String policyText) {
        if (policyText == null || policyText.isBlank()) {
            throw new AwsException("InvalidParameterException", "policyText is required", 400);
        }
        PublicRepository repository = getRepository(accountId, registryId, name);
        repository.setPolicyText(policyText);
        store.put(name, repository);
        return repository;
    }

    public String getPolicy(String accountId, String registryId, String name) {
        PublicRepository repository = getRepository(accountId, registryId, name);
        if (repository.getPolicyText() == null) {
            throw policyNotFound(accountId, name);
        }
        return repository.getPolicyText();
    }

    public String deletePolicy(String accountId, String registryId, String name) {
        PublicRepository repository = getRepository(accountId, registryId, name);
        String policy = repository.getPolicyText();
        if (policy == null) {
            throw policyNotFound(accountId, name);
        }
        repository.setPolicyText(null);
        store.put(name, repository);
        return policy;
    }

    public List<Map<String, String>> listTags(String accountId, String arn) {
        return new ArrayList<>(byArn(accountId, arn).getTags());
    }

    public void tagResource(String accountId, String arn, List<Map<String, String>> tags) {
        PublicRepository repository = byArn(accountId, arn);
        Map<String, String> merged = new LinkedHashMap<>();
        repository.getTags().forEach(t -> merged.put(t.get("Key"), t.get("Value")));
        if (tags != null) {
            tags.forEach(t -> merged.put(t.get("Key"), t.get("Value")));
        }
        if (merged.size() > 50) {
            throw new AwsException("TooManyTagsException", "A repository can have at most 50 tags.", 400);
        }
        List<Map<String, String>> list = new ArrayList<>();
        merged.forEach((k, v) -> {
            Map<String, String> tag = new LinkedHashMap<>();
            tag.put("Key", k);
            tag.put("Value", v);
            list.add(tag);
        });
        repository.setTags(list);
        store.put(repository.getRepositoryName(), repository);
    }

    public void untagResource(String accountId, String arn, List<String> keys) {
        PublicRepository repository = byArn(accountId, arn);
        Set<String> removed = keys == null ? Set.of() : new HashSet<>(keys);
        repository.setTags(new ArrayList<>(repository.getTags().stream()
                .filter(t -> !removed.contains(t.get("Key")))
                .toList()));
        store.put(repository.getRepositoryName(), repository);
    }

    private PublicRepository byArn(String accountId, String arn) {
        if (arn == null || !arn.contains(":repository/")) {
            throw new AwsException("InvalidParameterException", "Invalid parameter at 'resourceArn'", 400);
        }
        String name = arn.substring(arn.indexOf(":repository/") + ":repository/".length());
        return store.get(name)
                .filter(r -> arn.equals(r.getRepositoryArn()))
                .orElseThrow(() -> notFound(accountId, name));
    }

    /** The output shape has {@code logoUrl} and {@code marketplaceCertified} where the input has a logo blob. */
    private static Map<String, Object> catalogOutput(Map<String, Object> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (input != null) {
            for (String key : List.of("description", "architectures", "operatingSystems", "aboutText", "usageText")) {
                if (input.get(key) != null) {
                    output.put(key, input.get(key));
                }
            }
        }
        output.put("marketplaceCertified", false);
        return output;
    }

    private static void checkRegistry(String accountId, String registryId) {
        if (registryId != null && !registryId.equals(accountId)) {
            throw new AwsException("InvalidParameterException",
                    "Invalid parameter at 'registryId' failed to satisfy constraint: 'must be the caller's account'", 400);
        }
    }

    private static void validateName(String name) {
        if (name == null || name.length() < 2 || name.length() > 205 || !NAME.matcher(name).matches()) {
            throw new AwsException("InvalidParameterException",
                    "Invalid parameter at 'repositoryName' failed to satisfy constraint: "
                            + "'must satisfy regular expression '(?:[a-z0-9]+(?:[._-][a-z0-9]+)*/)*[a-z0-9]+(?:[._-][a-z0-9]+)*''",
                    400);
        }
    }

    private static AwsException notFound(String accountId, String name) {
        return new AwsException("RepositoryNotFoundException",
                "The repository with name '" + name + "' does not exist in the registry with id '" + accountId + "'",
                400);
    }

    private static AwsException policyNotFound(String accountId, String name) {
        return new AwsException("RepositoryPolicyNotFoundException",
                "Repository policy does not exist for the repository with name '" + name
                        + "' in the registry with id '" + accountId + "'", 400);
    }
}
