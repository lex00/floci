package io.github.hectorvent.floci.services.codecommit;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.codecommit.model.Repository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeCommitServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final CodeCommitService service = new CodeCommitService(AccountAwareStorageBackend.inMemory(ACCOUNT));

    @Test
    void createRepositoryMintsArnAndCloneUrls() {
        Repository repository = service.createRepository(REGION, ACCOUNT, "app", "the app", null, Map.of("team", "a"));

        assertEquals("arn:aws:codecommit:us-east-1:000000000000:app", repository.getArn());
        assertEquals("https://git-codecommit.us-east-1.amazonaws.com/v1/repos/app", repository.getCloneUrlHttp());
        assertEquals("ssh://git-codecommit.us-east-1.amazonaws.com/v1/repos/app", repository.getCloneUrlSsh());
        assertEquals(Map.of("team", "a"), service.listTags(REGION, repository.getArn()));
    }

    @Test
    void duplicateAndInvalidNamesAreRefused() {
        service.createRepository(REGION, ACCOUNT, "dup", null, null, null);

        assertEquals("RepositoryNameExistsException", assertThrows(AwsException.class,
                () -> service.createRepository(REGION, ACCOUNT, "dup", null, null, null)).getErrorCode());
        assertEquals("InvalidRepositoryNameException", assertThrows(AwsException.class,
                () -> service.createRepository(REGION, ACCOUNT, "bad.git", null, null, null)).getErrorCode());
        assertEquals("RepositoryNameRequiredException", assertThrows(AwsException.class,
                () -> service.getRepository(REGION, "")).getErrorCode());
    }

    @Test
    void renameMovesTagsAndRecomputesArn() {
        Repository repository = service.createRepository(REGION, ACCOUNT, "old", null, null, Map.of("k", "v"));

        service.updateRepositoryName(REGION, ACCOUNT, "old", "new");

        Repository renamed = service.getRepository(REGION, "new");
        assertEquals(repository.getRepositoryId(), renamed.getRepositoryId());
        assertEquals("arn:aws:codecommit:us-east-1:000000000000:new", renamed.getArn());
        assertEquals(Map.of("k", "v"), service.listTags(REGION, renamed.getArn()));
        assertEquals("RepositoryDoesNotExistException", assertThrows(AwsException.class,
                () -> service.getRepository(REGION, "old")).getErrorCode());
    }

    @Test
    void deleteOfUnknownRepositoryReturnsNoId() {
        assertNull(service.deleteRepository(REGION, "never-created"));
    }

    @Test
    void listSortsByNameAndOrder() {
        service.createRepository(REGION, ACCOUNT, "b-repo", null, null, null);
        service.createRepository(REGION, ACCOUNT, "a-repo", null, null, null);

        assertEquals(List.of("a-repo", "b-repo"), service.listRepositories(REGION, null, null).stream()
                .map(Repository::getRepositoryName).toList());
        assertEquals(List.of("b-repo", "a-repo"), service.listRepositories(REGION, "repositoryName", "descending")
                .stream().map(Repository::getRepositoryName).toList());
        assertTrue(service.listRepositories("eu-west-1", null, null).isEmpty());
    }

    @Test
    void untagRemovesKeys() {
        Repository repository = service.createRepository(REGION, ACCOUNT, "tagged", null, null, Map.of("a", "1"));
        service.tagResource(REGION, repository.getArn(), Map.of("b", "2"));
        service.untagResource(REGION, repository.getArn(), List.of("a"));

        assertEquals(Map.of("b", "2"), service.listTags(REGION, repository.getArn()));
    }
}
