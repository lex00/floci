package io.github.hectorvent.floci.services.codecommit;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.codecommit.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/** Stores CodeCommit repository records and tags; {@link CodeCommitGitServer} serves their Git data. */
@ApplicationScoped
public class CodeCommitService implements Resettable {

    private static final Pattern NAME = Pattern.compile("[\\w.-]{1,100}");
    private static final int MAX_DESCRIPTION = 1000;

    private final AccountAwareStorageBackend<Repository> store;
    private final String baseUrl;
    private final CodeCommitGitServer gitServer;

    @Inject
    public CodeCommitService(StorageFactory storageFactory, EmulatorConfig config, CodeCommitGitServer gitServer) {
        this(storageFactory.create("codecommit", "codecommit-repositories.json",
                new TypeReference<Map<String, Repository>>() {}), config.effectiveBaseUrl(), gitServer);
    }

    CodeCommitService(AccountAwareStorageBackend<Repository> store) {
        this(store, "http://localhost:4566", null);
    }

    CodeCommitService(AccountAwareStorageBackend<Repository> store, String baseUrl, CodeCommitGitServer gitServer) {
        this.store = store;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.gitServer = gitServer;
    }

    /** The repository a Git request names, in the account and region the request resolved to. */
    public Optional<Repository> findRepository(String accountId, String region, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        return store.getForAccount(accountId, key(region, name));
    }

    @Override
    public void clear() {
        store.clear();
    }

    public Repository createRepository(String region, String accountId, String name, String description,
                                       String kmsKeyId, Map<String, String> tags) {
        validateName(name);
        validateDescription(description);
        if (store.get(key(region, name)).isPresent()) {
            throw new AwsException("RepositoryNameExistsException",
                    "Repository named " + name + " already exists", 400);
        }
        double now = Instant.now().toEpochMilli() / 1000.0;
        Repository repository = new Repository();
        repository.setAccountId(accountId);
        repository.setRepositoryId(UUID.randomUUID().toString());
        repository.setRepositoryName(name);
        repository.setRepositoryDescription(description);
        repository.setCreationDate(now);
        repository.setLastModifiedDate(now);
        repository.setKmsKeyId(kmsKeyId);
        repository.setRegion(region);
        repository.setArn(AwsArnUtils.Arn.of("codecommit", region, accountId, name).toString());
        applyCloneUrls(repository, region);
        if (tags != null) {
            repository.getTags().putAll(tags);
        }
        store.put(key(region, name), repository);
        return repository;
    }

    public Repository getRepository(String region, String name) {
        validateName(name);
        Repository repository = store.get(key(region, name)).orElseThrow(() -> doesNotExist(name));
        applyCloneUrls(repository, region);
        return repository;
    }

    public void updateRepositoryDescription(String region, String name, String description) {
        validateDescription(description);
        Repository repository = getRepository(region, name);
        repository.setRepositoryDescription(description);
        repository.setLastModifiedDate(Instant.now().toEpochMilli() / 1000.0);
        store.put(key(region, name), repository);
    }

    public void updateRepositoryName(String region, String accountId, String oldName, String newName) {
        validateName(newName);
        Repository repository = getRepository(region, oldName);
        if (!oldName.equals(newName) && store.get(key(region, newName)).isPresent()) {
            throw new AwsException("RepositoryNameExistsException",
                    "Repository named " + newName + " already exists", 400);
        }
        store.delete(key(region, oldName));
        repository.setRepositoryName(newName);
        repository.setArn(AwsArnUtils.Arn.of("codecommit", region, accountId, newName).toString());
        applyCloneUrls(repository, region);
        repository.setLastModifiedDate(Instant.now().toEpochMilli() / 1000.0);
        store.put(key(region, newName), repository);
    }

    /** Returns the deleted repository's id, or {@code null} when nothing had that name, as AWS does. */
    public String deleteRepository(String region, String name) {
        validateName(name);
        Repository repository = store.get(key(region, name)).orElse(null);
        if (repository == null) {
            return null;
        }
        store.delete(key(region, name));
        if (gitServer != null) {
            gitServer.delete(repository.getRepositoryId());
        }
        return repository.getRepositoryId();
    }

    public List<Repository> listRepositories(String region, String sortBy, String order) {
        if (sortBy != null && !"repositoryName".equals(sortBy) && !"lastModifiedDate".equals(sortBy)) {
            throw new AwsException("InvalidSortByException", "The specified sort by value is not valid.", 400);
        }
        if (order != null && !"ascending".equals(order) && !"descending".equals(order)) {
            throw new AwsException("InvalidOrderException", "The specified sort order is not valid.", 400);
        }
        Comparator<Repository> comparator = "lastModifiedDate".equals(sortBy)
                ? Comparator.comparing(Repository::getLastModifiedDate)
                : Comparator.comparing(Repository::getRepositoryName);
        if ("descending".equals(order)) {
            comparator = comparator.reversed();
        }
        String prefix = region + "::";
        return store.scan(k -> k.startsWith(prefix)).stream().sorted(comparator).toList();
    }

    public Map<String, String> listTags(String region, String arn) {
        return new LinkedHashMap<>(byArn(region, arn).getTags());
    }

    public void tagResource(String region, String arn, Map<String, String> tags) {
        if (tags == null) {
            throw new AwsException("TagsMapRequiredException", "A map of tags is required.", 400);
        }
        Repository repository = byArn(region, arn);
        repository.getTags().putAll(tags);
        store.put(key(region, repository.getRepositoryName()), repository);
    }

    public void untagResource(String region, String arn, List<String> tagKeys) {
        if (tagKeys == null) {
            throw new AwsException("TagKeysListRequiredException", "A list of tag keys is required.", 400);
        }
        Repository repository = byArn(region, arn);
        tagKeys.forEach(repository.getTags()::remove);
        store.put(key(region, repository.getRepositoryName()), repository);
    }

    private Repository byArn(String region, String arn) {
        if (arn == null || arn.isBlank()) {
            throw new AwsException("ResourceArnRequiredException", "A valid Amazon Resource Name (ARN) is required.", 400);
        }
        AwsArnUtils.Arn parsed;
        try {
            parsed = AwsArnUtils.parse(arn);
        } catch (IllegalArgumentException e) {
            throw new AwsException("InvalidResourceArnException", "The value for the resource ARN is not valid.", 400);
        }
        if (!"codecommit".equals(parsed.service())) {
            throw new AwsException("InvalidResourceArnException", "The value for the resource ARN is not valid.", 400);
        }
        return store.get(key(region, parsed.resource()))
                .filter(r -> arn.equals(r.getArn()))
                .orElseThrow(() -> doesNotExist(parsed.resource()));
    }

    /**
     * The HTTP clone URL is Floci's Git endpoint, {@code http://git-codecommit.<region>.localhost:<port>/v1/repos/<name>}
     * when Floci answers on localhost, so the host names the region as AWS's does; another base URL
     * is used as it stands, and its repositories are in the default region. The SSH URL keeps the
     * AWS form: Floci runs no SSH server.
     */
    private void applyCloneUrls(Repository repository, String region) {
        URI base = URI.create(baseUrl);
        String path = "/v1/repos/" + repository.getRepositoryName();
        String httpUrl = "localhost".equals(base.getHost())
                ? base.getScheme() + "://git-codecommit." + region + ".localhost"
                        + (base.getPort() > 0 ? ":" + base.getPort() : "") + path
                : baseUrl + path;
        repository.setCloneUrlHttp(httpUrl);
        String host = "git-codecommit." + region + "." + AwsRegions.dnsSuffixFor(region);
        repository.setCloneUrlSsh("ssh://" + host + path);
    }

    private static void validateName(String name) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("RepositoryNameRequiredException", "A repository name is required.", 400);
        }
        if (!NAME.matcher(name).matches() || name.endsWith(".git")) {
            throw new AwsException("InvalidRepositoryNameException",
                    "The repository name is not valid. Repository names can be any valid combination of "
                            + "letters, numbers, periods, underscores, and dashes between 1 and 100 characters "
                            + "in length, and cannot end in .git.", 400);
        }
    }

    private static void validateDescription(String description) {
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw new AwsException("InvalidRepositoryDescriptionException",
                    "The repository description is not valid.", 400);
        }
    }

    private static AwsException doesNotExist(String name) {
        return new AwsException("RepositoryDoesNotExistException", name + " does not exist", 400);
    }

    private static String key(String region, String name) {
        return region + "::" + name;
    }
}
