package io.github.hectorvent.floci.services.comprehend;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
/**
 * JSON 1.1 handler for Amazon Comprehend API operations.
 * Dispatches X-Amz-Target: Comprehend_20171127.* actions to {@link ComprehendService}.
 *
 * @see <a href="https://docs.aws.amazon.com/comprehend/latest/APIReference/Welcome.html">Comprehend API Reference</a>
 */
@ApplicationScoped
public class ComprehendJsonHandler {
    private static final Logger LOG = Logger.getLogger(ComprehendJsonHandler.class);
    private final ComprehendService comprehendService;
    private final ComprehendModelService modelService;
    @Inject
    public ComprehendJsonHandler(ComprehendService comprehendService, ComprehendModelService modelService) {
        this.comprehendService = comprehendService;
        this.modelService = modelService;
    }
    /**
     * Dispatches Comprehend actions received via the AwsJson11Controller.
     * The sync Detect and ContainsPiiEntities actions, custom document classifiers and
     * entity recognizers, and tagging are implemented. Async jobs, flywheels, datasets and
     * endpoints are out of scope.
     */
    public Response handle(String action, JsonNode request, String region) {
        LOG.debugv("Comprehend action: {0}", action);
        return switch (action) {
            case "DetectSentiment" -> comprehendService.detectSentiment(
                    getStringField(request, "Text"), getStringField(request, "LanguageCode"));
            case "DetectKeyPhrases" -> comprehendService.detectKeyPhrases(
                    getStringField(request, "Text"), getStringField(request, "LanguageCode"));
            case "DetectDominantLanguage" -> comprehendService.detectDominantLanguage(
                    getStringField(request, "Text"));
            case "DetectPiiEntities" -> comprehendService.detectPiiEntities(
                    getStringField(request, "Text"), getStringField(request, "LanguageCode"));
            case "ContainsPiiEntities" -> comprehendService.containsPiiEntities(
                    getStringField(request, "Text"), getStringField(request, "LanguageCode"));
            case "CreateDocumentClassifier" -> Response.ok(
                    modelService.create(ComprehendModelService.CLASSIFIER, request, region)).build();
            case "DescribeDocumentClassifier" -> Response.ok(modelService.describe(
                    ComprehendModelService.CLASSIFIER, getStringField(request, "DocumentClassifierArn"))).build();
            case "ListDocumentClassifiers" -> Response.ok(
                    modelService.list(ComprehendModelService.CLASSIFIER, request, region)).build();
            case "DeleteDocumentClassifier" -> Response.ok(modelService.delete(
                    ComprehendModelService.CLASSIFIER, getStringField(request, "DocumentClassifierArn"))).build();
            case "StopTrainingDocumentClassifier" -> Response.ok(modelService.stopTraining(
                    ComprehendModelService.CLASSIFIER, getStringField(request, "DocumentClassifierArn"))).build();
            case "CreateEntityRecognizer" -> Response.ok(
                    modelService.create(ComprehendModelService.RECOGNIZER, request, region)).build();
            case "DescribeEntityRecognizer" -> Response.ok(modelService.describe(
                    ComprehendModelService.RECOGNIZER, getStringField(request, "EntityRecognizerArn"))).build();
            case "ListEntityRecognizers" -> Response.ok(
                    modelService.list(ComprehendModelService.RECOGNIZER, request, region)).build();
            case "DeleteEntityRecognizer" -> Response.ok(modelService.delete(
                    ComprehendModelService.RECOGNIZER, getStringField(request, "EntityRecognizerArn"))).build();
            case "StopTrainingEntityRecognizer" -> Response.ok(modelService.stopTraining(
                    ComprehendModelService.RECOGNIZER, getStringField(request, "EntityRecognizerArn"))).build();
            case "TagResource" -> Response.ok(modelService.tag(
                    getStringField(request, "ResourceArn"), request.get("Tags"))).build();
            case "UntagResource" -> Response.ok(modelService.untag(
                    getStringField(request, "ResourceArn"), request.get("TagKeys"))).build();
            case "ListTagsForResource" -> Response.ok(
                    modelService.listTags(getStringField(request, "ResourceArn"))).build();
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: Comprehend_20171127." + action))
                    .build();
        };
    }
    /**
     * Extracts a string-typed field. A present-but-wrong-typed value (e.g. a number
     * or boolean where the modeled shape is a string) is a shape/marshalling
     * mismatch caught at the protocol layer, before any operation-specific business
     * validation runs — matching {@code JsonErrorResponseUtils.createSerializationErrorResponse()}'s
     * treatment of a malformed JSON body. It must not be silently coerced via
     * {@code asText()}, nor conflated with a missing field (a modeled business error
     * specific to each operation, handled downstream in {@link ComprehendService}).
     */
    private String getStringField(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new AwsException("SerializationException",
                    "Unable to unmarshal request; the value for '" + field + "' is not a string.", 400);
        }
        return value.asText();
    }
}
