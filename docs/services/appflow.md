# Amazon AppFlow

**Protocol:** REST JSON
**Endpoint:** `http://localhost:4566/create-connector-profile` and the other single-segment AppFlow paths (SigV4 service `appflow`)

Floci emulates the AppFlow control plane for connector profiles and flows. Both are
persisted and isolated by account and region.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateConnectorProfile` | Creates a connector profile; credentials are not stored. |
| `DescribeConnectorProfiles` | Lists connector profiles by name, connector type or label. |
| `UpdateConnectorProfile` | Replaces a profile's connection mode and properties. |
| `DeleteConnectorProfile` | Deletes a connector profile that no flow uses, or any profile with `forceDelete`. |
| `CreateFlow` | Creates a flow after checking the connector profiles it names. |
| `DescribeFlow` | Returns a flow's configuration, status and last run. |
| `ListFlows` | Lists flow summaries with pagination. |
| `UpdateFlow` | Replaces a flow's trigger, source, destinations and tasks. |
| `DeleteFlow` | Deletes a flow. |
| `StartFlow` | Activates a scheduled or event flow, or records a run of an on-demand flow. |
| `StopFlow` | Suspends an active scheduled or event flow. |
| `ListTagsForResource` | Lists the tags on a flow. |
| `TagResource` | Adds or replaces tags on a flow. |
| `UntagResource` | Removes tags from a flow. |
<!-- floci:actions:end -->

AppFlow never returns connector profile credentials, so Floci discards them. Each profile
reports a `credentialsArn` in the AWS format. Deleting a profile
that a flow names fails with `ConflictException` unless `forceDelete` is set, and a flow
can only name profiles that exist.

An on-demand flow is `Active` from creation. A scheduled or event flow starts as `Draft`
and becomes `Active` on `StartFlow`; `StopFlow` suspends it. `StartFlow` on an on-demand
flow returns an `executionId` and records a successful run in `lastRunExecutionDetails`.
`schemaVersion` increases when an update changes the flow's tasks.

## Not emulated

Flows move no data: Floci never calls the source or destination connector. Schedules do
not run, and credentials are not checked against the SaaS application.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_APPFLOW_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws appflow create-connector-profile \
  --connector-profile-name sales \
  --connector-type Salesforce \
  --connection-mode Public \
  --connector-profile-config '{"connectorProfileProperties":{"Salesforce":{"instanceUrl":"https://example.my.salesforce.com"}},"connectorProfileCredentials":{"Salesforce":{"accessToken":"token"}}}'

aws appflow describe-connector-profiles --connector-profile-names sales
aws appflow delete-connector-profile --connector-profile-name sales
```
