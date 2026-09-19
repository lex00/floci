# AWS Elemental MediaPackage

**Protocol:** REST JSON (camelCase wire fields)
**Signing name:** `mediapackage`
**Endpoint:** `http://localhost:4566/channels`

Floci emulates the MediaPackage v1 channel control plane. Origin endpoints, harvest
jobs and the packaging data plane are not emulated, and the HLS ingest endpoints
returned on create are plausible but non-functional.

## Supported operations

| Operation | Notes |
|---|---|
| `CreateChannel` | Creates a channel and returns two HLS ingest endpoints |
| `DescribeChannel` | Returns one channel by id |
| `UpdateChannel` | Updates a channel description |
| `DeleteChannel` | Deletes a channel |
| `ListTagsForResource` | Returns tags for a MediaPackage ARN |
| `TagResource` | Adds or replaces tags on a MediaPackage ARN |
| `UntagResource` | Removes tag keys from a MediaPackage ARN |

## Behaviour taken from the service model

- `id` is the only required member of `CreateChannel`, and the model states it must be
  unique and cannot change after the channel exists, so a repeated id is refused with
  `UnprocessableEntityException` rather than overwriting the first channel.
- A description that was never sent is omitted from the response rather than echoed as
  an empty string, which Terraform reports as an inconsistent-result apply error.

## Known gaps

Channel ids are unique per account rather than per account and Region. Floci resolves
the Region from the request credential only on create, and `DescribeChannel` addresses
a channel by id alone.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEDIAPACKAGE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws mediapackage create-channel --id my-channel

aws mediapackage describe-channel --id my-channel
```
