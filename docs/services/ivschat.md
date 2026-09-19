# Amazon IVS Chat

**Protocol:** REST JSON

**Endpoint:** `POST /{OperationName}` (e.g. `POST /CreateRoom`), SigV4 service `ivschat`

Floci emulates the Amazon IVS Chat management plane: chat rooms and logging
configurations. The chat message data plane (`CreateChatToken`, `SendEvent`,
`DisconnectUser`, `DeleteMessage`) is not emulated.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRoom` | Create a chat room |
| `GetRoom` | Get room details by ARN or id |
| `DeleteRoom` | Delete a room |
| `CreateLoggingConfiguration` | Create a logging configuration; ACTIVE immediately |
| `GetLoggingConfiguration` | Get a logging configuration by ARN or id |
| `DeleteLoggingConfiguration` | Delete a logging configuration |
<!-- floci:actions:end -->

Tagging rides the shared `/tags/{resourceArn}` routes, so `ListTagsForResource`,
`TagResource` and `UntagResource` work against any room or logging configuration ARN.

AWS documents room and logging configuration identifiers as ARNs. Floci accepts the bare
12-character resource id as well, which keeps hand-written scripts working without changing
what the SDK sends. Logging configurations come back `ACTIVE` from the create call, so SDK
and Terraform waiters complete on their first poll.

## AWS-compatible failures

`CreateRoom` defaults `maximumMessageRatePerSecond` to 10 and `maximumMessageLength` to 500
and bounds them to the modelled ranges of 1 to 100 and 1 to 500. A room accepts at most
three `loggingConfigurationIdentifiers`. A `messageReviewHandler` supplied without a
`fallbackResult` comes back carrying the documented default of `ALLOW`.

`CreateLoggingConfiguration` requires `destinationConfiguration` to name one and only one of
`cloudWatchLogs`, `firehose` or `s3`, each with its own required field (`logGroupName`,
`deliveryStreamName`, `bucketName`).

Reads and deletes of a missing room or logging configuration return
`ResourceNotFoundException`; malformed input returns `ValidationException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_IVSCHAT_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws ivschat create-room --name my-room

aws ivschat get-room --identifier arn:aws:ivschat:us-east-1:000000000000:room/...

aws ivschat create-logging-configuration \
  --destination-configuration '{"cloudWatchLogs":{"logGroupName":"chat-logs"}}'
```
