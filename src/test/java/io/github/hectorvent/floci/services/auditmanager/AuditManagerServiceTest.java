package io.github.hectorvent.floci.services.auditmanager;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.auditmanager.model.Control;
import io.github.hectorvent.floci.services.auditmanager.model.Framework;
import io.github.hectorvent.floci.services.auditmanager.model.FrameworkControlSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuditManagerServiceTest {

    private static final String REGION = "us-east-1";
    private static final String UNKNOWN_CONTROL = "11111111-1111-1111-1111-111111111111";

    private AuditManagerService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any(TypeReference.class)))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new AuditManagerService(storageFactory, new RegionResolver(REGION, "000000000000"));
    }

    @Test
    void createFrameworkAssignsIdsAndKeepsUnknownControlIds() {
        Framework framework = service.createAssessmentFramework("fw", "desc", "SOC2",
                List.of(new FrameworkControlSet(null, "cs1", List.of(UNKNOWN_CONTROL))),
                Map.of("team", "audit"), REGION);

        assertNotNull(framework.getControlSets().getFirst().getId());
        assertEquals(List.of(UNKNOWN_CONTROL), framework.getControlSets().getFirst().getControlIds());
        assertTrue(framework.getArn().startsWith("arn:aws:auditmanager:us-east-1:000000000000:assessmentFramework/"));
        assertEquals("audit", service.listTags(REGION, framework.getArn()).get("team"));
    }

    @Test
    void createFrameworkRequiresAControlSet() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createAssessmentFramework("fw", null, null, List.of(), Map.of(), REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void controlSetNameRejectsUnderscore() {
        AwsException e = assertThrows(AwsException.class, () -> service.createAssessmentFramework("fw", null, null,
                List.of(new FrameworkControlSet(null, "bad_name", List.of())), Map.of(), REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }

    @Test
    void updateKeepsAControlSetIdTheCallerSends() {
        Framework framework = service.createAssessmentFramework("fw", null, null,
                List.of(new FrameworkControlSet(null, "cs1", List.of())), Map.of(), REGION);
        String setId = framework.getControlSets().getFirst().getId();

        Framework updated = service.updateAssessmentFramework(framework.getId(), "fw2", "d", null,
                List.of(new FrameworkControlSet(setId, "cs1", List.of(UNKNOWN_CONTROL))), REGION);

        assertEquals("fw2", updated.getName());
        assertEquals(setId, updated.getControlSets().getFirst().getId());
    }

    @Test
    void listFiltersByFrameworkType() {
        service.createAssessmentFramework("fw", null, null,
                List.of(new FrameworkControlSet(null, "cs1", List.of())), Map.of(), REGION);

        assertEquals(1, service.listAssessmentFrameworks("Custom", null, null, REGION).items().size());
        assertTrue(service.listAssessmentFrameworks("Standard", null, null, REGION).items().isEmpty());
        assertThrows(AwsException.class, () -> service.listAssessmentFrameworks("Other", null, null, REGION));
    }

    @Test
    void deleteThenGetIsNotFound() {
        Framework framework = service.createAssessmentFramework("fw", null, null,
                List.of(new FrameworkControlSet(null, "cs1", List.of())), Map.of(), REGION);
        service.deleteAssessmentFramework(framework.getId(), REGION);

        AwsException e = assertThrows(AwsException.class,
                () -> service.getAssessmentFramework(framework.getId(), REGION));
        assertEquals("ResourceNotFoundException", e.getErrorCode());
        assertEquals(404, e.getHttpStatus());
    }

    @Test
    void controlGetsSourceIdsAndTags() {
        Control control = service.createControl("ctl", null, null, null, null,
                List.of(Map.of("sourceName", "src", "sourceSetUpOption", "Procedural_Controls_Mapping",
                        "sourceType", "MANUAL")),
                Map.of(), REGION);
        assertNotNull(control.getControlMappingSources().getFirst().get("sourceId"));

        service.tagResource(REGION, control.getArn(), Map.of("k", "v"));
        assertEquals("v", service.getControl(control.getId(), REGION).getTags().get("k"));
        service.untagResource(REGION, control.getArn(), List.of("k"));
        assertTrue(service.listTags(REGION, control.getArn()).isEmpty());
    }

    @Test
    void getWithMalformedIdIsValidationException() {
        AwsException e = assertThrows(AwsException.class, () -> service.getAssessmentFramework("nope", REGION));
        assertEquals("ValidationException", e.getErrorCode());
    }
}
