package io.github.hectorvent.floci.services.auditmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditManagerAssessmentServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AuditManagerAssessmentService service = new AuditManagerAssessmentService(new InMemoryStorage<>(),
            null, new RegionResolver("us-east-1", "000000000000"));

    @Test
    void accountRegistrationToggles() throws Exception {
        assertEquals("INACTIVE", service.accountStatus());
        assertEquals("ACTIVE", service.registerAccount(mapper.readTree("{}")));
        assertEquals("ACTIVE", service.accountStatus());
        assertEquals("INACTIVE", service.deregisterAccount());
    }

    @Test
    void createNeedsReportsDestinationScopeAndRoles() throws Exception {
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.createAssessment(
                mapper.readTree("{\"name\": \"a\", \"scope\": {}, \"roles\": []}"), "us-east-1")).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.createAssessment(
                mapper.readTree("""
                        {"name": "a", "scope": {}, "assessmentReportsDestination": {},
                         "roles": [{"roleType": "BOSS", "roleArn": "arn"}]}"""), "us-east-1")).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> service.getAssessment("11111111-1111-1111-1111-111111111111", "us-east-1")).getErrorCode());
    }
}
