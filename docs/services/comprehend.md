# Comprehend

**Protocol:** JSON 1.1 (`X-Amz-Target: Comprehend_20171127.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `DetectSentiment` | Detect the overall sentiment of a text (POSITIVE/NEGATIVE/NEUTRAL/MIXED) |
| `DetectKeyPhrases` | Detect key noun-phrases in a text |
| `DetectDominantLanguage` | Detect the dominant language of a text |
| `DetectPiiEntities` | Detect personally identifiable information (PII) in a text |
| `ContainsPiiEntities` | Report whether a text contains PII, without locating it |
| `CreateDocumentClassifier` | Create a custom classifier; it reports `TRAINED` from the first read |
| `DescribeDocumentClassifier` | Read a classifier by ARN, with canned `ClassifierMetadata` |
| `ListDocumentClassifiers` | List classifiers, filtered by name or status |
| `DeleteDocumentClassifier` | Delete a classifier |
| `StopTrainingDocumentClassifier` | Accepted; a trained classifier keeps its status |
| `CreateEntityRecognizer` | Create a custom entity recognizer; it reports `TRAINED` from the first read |
| `DescribeEntityRecognizer` | Read a recognizer by ARN, with its entity types in `RecognizerMetadata` |
| `ListEntityRecognizers` | List recognizers, filtered by name or status |
| `DeleteEntityRecognizer` | Delete a recognizer |
| `StopTrainingEntityRecognizer` | Accepted; a trained recognizer keeps its status |
| `TagResource` | Tag a classifier or recognizer |
| `UntagResource` | Remove tags from a classifier or recognizer |
| `ListTagsForResource` | List a classifier's or recognizer's tags |
<!-- floci:actions:end -->

## Emulation Behavior

- **Stub responses:** every action returns a fixed, AWS-shaped response — no real NLP/ML
  is performed. `DetectSentiment` always returns `NEUTRAL`, `DetectKeyPhrases` always
  returns one stub phrase, `DetectDominantLanguage` always reports `en`, and
  `DetectPiiEntities`/`ContainsPiiEntities` always report no PII found.
- **Real input validation:** `Text` and `LanguageCode` (where applicable) are still
  required and `LanguageCode` is validated against Comprehend's supported set —
  this is protocol compatibility, not content analysis. `DetectSentiment` and
  `DetectKeyPhrases` accept the general set (`en`, `es`, `fr`, `de`, `it`, `pt`,
  `ar`, `hi`, `ja`, `ko`, `zh`, `zh-TW`); `DetectPiiEntities` and
  `ContainsPiiEntities` accept only `en`/`es`, matching AWS's narrower support
  for PII detection.
- **Custom models:** document classifiers and entity recognizers are stored per region
  and report `TRAINED` as soon as they are created, with fixed training metrics. No
  training data is read. The ARN is `document-classifier/<name>` or
  `entity-recognizer/<name>`, with `/version/<VersionName>` appended when a version is
  given. A second create with the same name and version fails with
  `ResourceInUseException`.
- **Out of scope:** async jobs, flywheels, datasets, endpoints, `DetectEntities` and
  `DetectSyntax` are not implemented.
- **Intentional deviation:** real Comprehend enforces a maximum input size on `Text`
  and returns `TextSizeLimitExceededException` when exceeded. This is not enforced
  here — any non-empty `Text` is accepted regardless of length.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_COMPREHEND_ENABLED` | `true` | Enable or disable the service |
| `AI_MOCK_CONFIG` | unset | Path to a shared mock-response config file — see "Mock Responses" below |

## Mock Responses

The default stub responses above are the same for every call. To exercise application
logic that branches on detection results (e.g. "escalate if sentiment is NEGATIVE"), point
`AI_MOCK_CONFIG` at a JSON file shared across Comprehend, Textract, and Rekognition:

```json
{
  "comprehend": {
    "I am furious about this outage": {
      "DetectSentiment": { "Sentiment": "NEGATIVE", "SentimentScore": { "Positive": 0.0, "Negative": 0.95, "Neutral": 0.05, "Mixed": 0.0 } }
    }
  }
}
```

The lookup key is the **exact `Text` value** sent in the request. The file is re-read when
its modification time changes, so it can be edited without restarting the emulator. A
missing file, an unset `AI_MOCK_CONFIG`, or no matching entry all fall back to the default
stub — mocking is opt-in and never breaks a call that isn't using it.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws comprehend detect-sentiment \
  --text "Floci makes local AWS testing painless" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-key-phrases \
  --text "Floci makes local AWS testing painless" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-dominant-language \
  --text "Floci makes local AWS testing painless" \
  --endpoint-url $AWS_ENDPOINT_URL

aws comprehend detect-pii-entities \
  --text "My SSN is 123-45-6789" \
  --language-code en \
  --endpoint-url $AWS_ENDPOINT_URL
```

## SDK Example (Java)

```java
ComprehendClient comprehend = ComprehendClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create("test", "test")))
    .build();

DetectSentimentResponse response = comprehend.detectSentiment(req -> req
    .text("Floci makes local AWS testing painless")
    .languageCode("en"));

System.out.println("Sentiment: " + response.sentiment());
```
