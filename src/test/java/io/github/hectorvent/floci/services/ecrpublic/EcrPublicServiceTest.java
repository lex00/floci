package io.github.hectorvent.floci.services.ecrpublic;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.services.ecrpublic.model.PublicRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EcrPublicServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private final EcrPublicService service = new EcrPublicService(AccountAwareStorageBackend.inMemory(ACCOUNT));

    @Test
    void createRepositoryMintsRegionlessArnAndUri() {
        PublicRepository repository = service.createRepository(REGION, ACCOUNT, "team/app",
                Map.of("description", "an app", "aboutText", "about"), null);

        assertEquals("arn:aws:ecr-public::000000000000:repository/team/app", repository.getRepositoryArn());
        assertEquals("an app", repository.getCatalogData().get("description"));
        assertEquals(false, repository.getCatalogData().get("marketplaceCertified"));
    }

    @Test
    void invalidAndDuplicateNamesAreRefused() {
        service.createRepository(REGION, ACCOUNT, "dup", null, null);

        assertEquals("RepositoryAlreadyExistsException", assertThrows(AwsException.class,
                () -> service.createRepository(REGION, ACCOUNT, "dup", null, null)).getErrorCode());
        assertEquals("InvalidParameterException", assertThrows(AwsException.class,
                () -> service.createRepository(REGION, ACCOUNT, "Upper", null, null)).getErrorCode());
    }

    @Test
    void policyRoundTrip() {
        service.createRepository(REGION, ACCOUNT, "policy-repo", null, null);

        assertEquals("RepositoryPolicyNotFoundException", assertThrows(AwsException.class,
                () -> service.getPolicy(ACCOUNT, null, "policy-repo")).getErrorCode());
        service.setPolicy(ACCOUNT, null, "policy-repo", "{\"Version\":\"2012-10-17\"}");
        assertEquals("{\"Version\":\"2012-10-17\"}", service.getPolicy(ACCOUNT, null, "policy-repo"));
        service.deletePolicy(ACCOUNT, null, "policy-repo");
        assertThrows(AwsException.class, () -> service.getPolicy(ACCOUNT, null, "policy-repo"));
    }

    @Test
    void describeNamedMissingRepositoryFails() {
        assertEquals("RepositoryNotFoundException", assertThrows(AwsException.class,
                () -> service.describeRepositories(ACCOUNT, null, List.of("missing"))).getErrorCode());
    }

    @Test
    void tagsMergeAndRemove() {
        PublicRepository repository = service.createRepository(REGION, ACCOUNT, "tags",
                null, List.of(Map.of("Key", "a", "Value", "1")));
        service.tagResource(ACCOUNT, repository.getRepositoryArn(), List.of(Map.of("Key", "a", "Value", "2")));
        service.untagResource(ACCOUNT, repository.getRepositoryArn(), List.of("missing"));

        assertEquals(List.of(Map.of("Key", "a", "Value", "2")),
                service.listTags(ACCOUNT, repository.getRepositoryArn()));
        service.deleteRepository(ACCOUNT, null, "tags");
        assertTrue(service.describeRepositories(ACCOUNT, null, null).stream()
                .noneMatch(r -> "tags".equals(r.getRepositoryName())));
    }
}
