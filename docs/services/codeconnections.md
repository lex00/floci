# CodeConnections

**Protocol:** AWS JSON 1.0, `POST /` with `X-Amz-Target: com.amazonaws.codeconnections.CodeConnections_20231201.<Action>`
**Signing name:** `codeconnections`

Connections and hosts are stored with their tags for `aws_codeconnections_connection` and
`aws_codeconnections_host`. A connection ARN is
`arn:aws:codeconnections:<region>:<account>:connection/<uuid>`.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateConnection` | Creates a `PENDING` connection for a provider type or a host. |
| `GetConnection` | Returns the connection by ARN. |
| `ListConnections` | Lists connections, filtered by provider type or host ARN. |
| `DeleteConnection` | Deletes the connection. |
| `CreateHost` | Creates a `PENDING` host for a self-managed provider endpoint. |
| `GetHost` | Returns the host by ARN. |
| `ListHosts` | Lists hosts in the region. |
| `DeleteHost` | Deletes the host. |
| `TagResource` | Merges tags into a connection or host. |
| `UntagResource` | Removes the named tag keys from a connection or host. |
| `ListTagsForResource` | Returns the tags of a connection or host. |
<!-- floci:actions:end -->

## Behaviour

- A new connection reports `ConnectionStatus` `PENDING` and keeps it. AWS moves a connection to
  `AVAILABLE` once a person completes the provider handshake in the console, which has no local
  equivalent.
- A connection created with `HostArn` takes its `ProviderType` from the host.
- New hosts report `Status` `PENDING`.
