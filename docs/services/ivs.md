# Amazon IVS

**Protocol:** REST JSON

**Endpoint:** `POST /{OperationName}` (e.g. `POST /CreateChannel`), SigV4 service `ivs`

Floci emulates the Amazon Interactive Video Service management plane: channels and their
stream keys, playback key pairs, and recording configurations. The video data plane is not
emulated, so ingest endpoints and playback URLs are plausible but non-functional.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateChannel` | Create a channel and its associated stream key |
| `GetChannel` | Get channel details by ARN |
| `DeleteChannel` | Delete a channel and its stream key |
| `ImportPlaybackKeyPair` | Import a playback public key; returns a computed fingerprint |
| `GetPlaybackKeyPair` | Get a playback key pair by ARN |
| `DeletePlaybackKeyPair` | Delete a playback key pair |
| `CreateRecordingConfiguration` | Create a recording configuration; ACTIVE immediately |
| `GetRecordingConfiguration` | Get a recording configuration by ARN |
| `DeleteRecordingConfiguration` | Delete a recording configuration |
<!-- floci:actions:end -->

Tagging rides the shared `/tags/{resourceArn}` routes, so `ListTagsForResource`,
`TagResource` and `UntagResource` work against any IVS channel, playback key pair or
recording configuration ARN.

`CreateChannel` also mints the channel's stream key, as the AWS operation documents, and
`DeleteChannel` removes the key with the channel. Recording configurations come back
`ACTIVE` from the create call, so SDK and Terraform waiters complete on their first poll.

## AWS-compatible failures

`CreateChannel` validates `latencyMode` and `type` against their enums, defaults them to
`LOW` and `STANDARD`, and resolves `preset` the way the model documents it: selectable only
for the `ADVANCED_SD` and `ADVANCED_HD` channel types, defaulting to
`HIGHER_BANDWIDTH_DELIVERY` there and to the empty string for `BASIC` and `STANDARD`. A
`recordingConfigurationArn` naming a configuration that does not exist raises
`ResourceNotFoundException`.

`CreateRecordingConfiguration` requires `destinationConfiguration` and rejects a body that
names more than one destination type. `recordingReconnectWindowSeconds` defaults to 0 and is
bounded to 300. A thumbnail configuration whose `recordingMode` is not `INTERVAL` may not
carry `targetIntervalSeconds`, and an `INTERVAL` one that omits it comes back with the
modelled defaults of 60 seconds and `SEQUENTIAL` storage. A `renditionConfiguration` with
`renditionSelection` set to `CUSTOM` must list `renditions`.

Reads and deletes of a missing channel, key pair or recording configuration return
`ResourceNotFoundException`; malformed input returns `ValidationException`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_IVS_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws ivs create-channel --name my-channel

aws ivs get-channel --arn arn:aws:ivs:us-east-1:000000000000:channel/...

aws ivs create-recording-configuration \
  --destination-configuration '{"s3":{"bucketName":"recordings"}}'
```
