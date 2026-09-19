# AWS Elemental MediaPackage V2

**Protocol:** REST JSON (PascalCase wire fields)
**Signing name:** `mediapackagev2`
**Endpoint:** `http://localhost:4566/channelGroup`

Floci emulates the MediaPackage V2 channel group control plane. Channels, origin
endpoints and the packaging data plane are not emulated, and the egress domain is
plausible but non-functional.

## Supported operations

| Operation | Notes |
|---|---|
| `CreateChannelGroup` | Creates a channel group and returns an egress domain |
| `GetChannelGroup` | Returns one channel group by name |
| `DeleteChannelGroup` | Deletes a channel group |
| `ListTagsForResource` | Returns tags for a MediaPackage V2 ARN |
| `TagResource` | Adds or replaces tags on a MediaPackage V2 ARN |
| `UntagResource` | Removes tag keys from a MediaPackage V2 ARN |

## Behaviour taken from the service model

- `ChannelGroupName` is required, must match `[a-zA-Z0-9_-]+` over 1 to 256 characters
  (the model spells out that spaces are not allowed), and must be unique, so a repeat is
  refused with `ConflictException`. `Description` is capped at 1024 characters.
- One wire quirk is reproduced faithfully. The service model binds the tag map to `tags`
  on `CreateChannelGroupRequest` and `GetChannelGroupResponse` through a locationName,
  but leaves it as `Tags` on `CreateChannelGroupResponse`, so a create echoes `Tags` and
  a get returns `tags`. The create path accepts either spelling on input.
- A description that was never sent is omitted rather than echoed as an empty string,
  which Terraform reports as an inconsistent-result apply error.

## Known gaps

`x-amzn-client-token` is accepted but not used for idempotency, and channel group names
are unique per account rather than per account and Region.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_MEDIAPACKAGEV2_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws mediapackagev2 create-channel-group --channel-group-name my-group

aws mediapackagev2 get-channel-group --channel-group-name my-group
```
