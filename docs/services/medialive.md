# AWS Elemental MediaLive

**Protocol:** REST JSON (camelCase wire fields, `/prod` stage prefix on every path)
**Signing name:** `medialive`
**Endpoint:** `http://localhost:4566/prod/multiplexes`

Floci emulates the MediaLive multiplex control plane. Channels, inputs and the video
transport data plane are not emulated.

## Supported operations

| Operation | Notes |
|---|---|
| `CreateMultiplex` | Creates a multiplex that is IDLE as soon as the call returns |
| `DescribeMultiplex` | Returns one multiplex by id |
| `DeleteMultiplex` | Moves an idle multiplex to DELETED, where it stays readable |
| `CreateMultiplexProgram` | Creates a program inside a multiplex |
| `DescribeMultiplexProgram` | Returns one program by multiplex id and program name |
| `DeleteMultiplexProgram` | Deletes a program |
| `ListTagsForResource` | Returns tags for a MediaLive ARN at `/prod/tags/{arn}` |
| `CreateTags` | Adds or replaces tags on a MediaLive ARN |
| `DeleteTags` | Removes tag keys from a MediaLive ARN |

## Behaviour taken from the service model

- `CreateMultiplex` requires `requestId`, `name`, `availabilityZones` and
  `multiplexSettings`. `availabilityZones` must hold exactly two entries, and
  `multiplexSettings` must carry `transportStreamBitrate` (1000000 to 100000000) and
  `transportStreamId` (0 to 65535).
- `requestId` is the idempotency token the model describes as preventing a retry from
  creating a second resource, so repeating a `requestId` returns the multiplex or
  program that token already created.
- `DeleteMultiplex` is documented as requiring an idle multiplex, so a multiplex in any
  other state is refused with `ConflictException`. A deleted multiplex stays readable in
  state `DELETED` because the SDK's `MultiplexDeleted` waiter polls `DescribeMultiplex`
  for that state rather than treating a 404 as success.
- `CreateMultiplexProgram` requires `requestId`, `programName` and
  `multiplexProgramSettings.programNumber` (0 to 65535). A `serviceDescriptor` needs
  both `providerName` and `serviceName`, and `videoSettings` may set `constantBitrate`
  or `statmuxSettings` but not both.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEDIALIVE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws medialive create-multiplex \
  --name my-multiplex \
  --availability-zones us-east-1a us-east-1b \
  --multiplex-settings transportStreamBitrate=1000000,transportStreamId=1 \
  --request-id demo-1

aws medialive describe-multiplex --multiplex-id <id>
```
