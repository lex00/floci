package io.github.hectorvent.floci.services.comprehend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ComprehendModelServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ComprehendModelService service = new ComprehendModelService(new InMemoryStorage<>(),
            new RegionResolver("us-east-1", "000000000000"), mapper);

    private ObjectNode classifierRequest(String name) throws Exception {
        return (ObjectNode) mapper.readTree("""
                {"DocumentClassifierName": "%s", "LanguageCode": "en",
                 "DataAccessRoleArn": "arn:aws:iam::000000000000:role/r",
                 "InputDataConfig": {"S3Uri": "s3://b/k"}}
                """.formatted(name));
    }

    @Test
    void createDescribeAndListAreScopedToKindAndRegion() throws Exception {
        String arn = service.create(ComprehendModelService.CLASSIFIER, classifierRequest("c1"), "us-east-1")
                .get("DocumentClassifierArn").asText();

        assertEquals("TRAINED", service.describe(ComprehendModelService.CLASSIFIER, arn)
                .path("DocumentClassifierProperties").path("Status").asText());
        assertEquals(1, service.list(ComprehendModelService.CLASSIFIER, mapper.createObjectNode(), "us-east-1")
                .path("DocumentClassifierPropertiesList").size());
        assertEquals(0, service.list(ComprehendModelService.CLASSIFIER, mapper.createObjectNode(), "eu-west-1")
                .path("DocumentClassifierPropertiesList").size());
        AwsException wrongKind = assertThrows(AwsException.class,
                () -> service.describe(ComprehendModelService.RECOGNIZER, arn));
        assertEquals("ResourceNotFoundException", wrongKind.getErrorCode());
    }

    @Test
    void createRejectsABadNameOrRole() throws Exception {
        ObjectNode badName = classifierRequest("bad_name");
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.create(ComprehendModelService.CLASSIFIER, badName, "us-east-1")).getErrorCode());
        ObjectNode badRole = classifierRequest("ok");
        badRole.put("DataAccessRoleArn", "not-a-role");
        assertEquals("InvalidRequestException", assertThrows(AwsException.class,
                () -> service.create(ComprehendModelService.CLASSIFIER, badRole, "us-east-1")).getErrorCode());
    }

    @Test
    void tagAndUntagRoundTrip() throws Exception {
        String arn = service.create(ComprehendModelService.CLASSIFIER, classifierRequest("c2"), "us-east-1")
                .get("DocumentClassifierArn").asText();

        service.tag(arn, mapper.readTree("[{\"Key\": \"a\", \"Value\": \"1\"}, {\"Key\": \"b\", \"Value\": \"2\"}]"));
        service.untag(arn, mapper.readTree("[\"a\"]"));

        assertEquals("b", service.listTags(arn).path("Tags").get(0).path("Key").asText());
        assertEquals(1, service.listTags(arn).path("Tags").size());
    }
}
