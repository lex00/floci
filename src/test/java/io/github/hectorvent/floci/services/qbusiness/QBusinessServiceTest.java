package io.github.hectorvent.floci.services.qbusiness;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class QBusinessServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QBusinessService service = new QBusinessService(new InMemoryStorage<>(),
            new RegionResolver("us-east-1", "000000000000"), mapper);

    @Test
    void createRejectsAnInvalidNameOrIdentityType() throws Exception {
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.createApplication(
                mapper.readTree("{\"displayName\": \"-bad\"}"), "us-east-1")).getErrorCode());
        assertEquals("ValidationException", assertThrows(AwsException.class, () -> service.createApplication(
                mapper.readTree("{\"displayName\": \"ok\", \"identityType\": \"NOPE\"}"), "us-east-1")).getErrorCode());
    }

    @Test
    void tagsRoundTripByArn() throws Exception {
        String arn = service.createApplication(mapper.readTree("{\"displayName\": \"app\"}"), "us-east-1")
                .get("applicationArn").asText();

        service.tagResource(arn, Map.of("a", "1", "b", "2"));
        service.untagResource(arn, List.of("a"));

        assertEquals(Map.of("b", "2"), service.listTags(arn));
    }

    @Test
    void listApplicationsPages() throws Exception {
        for (int i = 0; i < 3; i++) {
            service.createApplication(mapper.readTree("{\"displayName\": \"app" + i + "\"}"), "us-east-1");
        }
        String token = service.listApplications(2, null, "us-east-1").get("nextToken").asText();

        assertEquals(1, service.listApplications(2, token, "us-east-1").get("applications").size());
    }
}
