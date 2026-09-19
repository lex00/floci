# AWS App Runner

**Protocol:** JSON 1.0 (`X-Amz-Target: AppRunner.*`)
**Endpoint:** `POST http://localhost:4566/` (SigV4 signing name `apprunner`)

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAutoScalingConfiguration` | Create a revision, `ACTIVE` immediately, revisions increment per name |
| `DescribeAutoScalingConfiguration` | Describe by full ARN, `name/revision` ARN, or bare-name ARN, which resolve the latest active revision |
| `DeleteAutoScalingConfiguration` | Delete a revision, or every revision of a name with `DeleteAllRevisions`, rejected while a service still references it |
| `ListAutoScalingConfigurations` | List active revisions, always including the account default configuration |
| `CreateObservabilityConfiguration` | Create a revision, `ACTIVE` immediately, revisions increment per name |
| `DescribeObservabilityConfiguration` | Describe by full ARN, `name/revision` ARN, or bare-name ARN |
| `DeleteObservabilityConfiguration` | Delete a revision, which moves to `INACTIVE` and drops from listings |
| `ListObservabilityConfigurations` | List active revisions, with optional name and `LatestOnly` filtering |
| `CreateVpcIngressConnection` | Create a VPC ingress connection, `AVAILABLE` immediately, requires `IngressVpcConfiguration` |
| `DescribeVpcIngressConnection` | Describe a VPC ingress connection by ARN |
| `DeleteVpcIngressConnection` | Delete a VPC ingress connection, only from `AVAILABLE` or a failure state |
| `ListVpcIngressConnections` | List VPC ingress connections, with optional `ServiceArn` filtering |
| `CreateVpcConnector` | Create a VPC connector, `ACTIVE` immediately, requires at least one subnet |
| `DescribeVpcConnector` | Describe a VPC connector by ARN |
| `DeleteVpcConnector` | Delete a VPC connector, rejected while a service still routes egress through it |
| `ListVpcConnectors` | List active VPC connectors |
| `CreateConnection` | Create a GitHub or Bitbucket connection, `AVAILABLE` immediately |
| `DeleteConnection` | Delete a connection, rejected while a service still authenticates with it |
| `ListConnections` | List connections, with optional name filtering |
| `CreateService` | Create a service, `RUNNING` immediately with a plausible `awsapprunner.com` URL |
| `DescribeService` | Describe a service by ARN |
| `UpdateService` | Update source, instance, health check, network or observability configuration |
| `DeleteService` | Delete a service, which moves to `DELETED` and drops from listings |
| `ListServices` | List non-deleted services |
| `PauseService` | Move a running service to `PAUSED` |
| `ResumeService` | Move a paused service back to `RUNNING` |
| `StartDeployment` | Record a `START_DEPLOYMENT` operation against the service |
| `ListOperations` | List recorded operations for a service, most recent first |
| `TagResource` | Add tags to any App Runner resource by ARN |
| `UntagResource` | Remove tags from any App Runner resource by ARN |
| `ListTagsForResource` | List tags on any App Runner resource by ARN |
<!-- floci:actions:end -->

## Emulation Behavior

No container is built, pushed or run. `CreateService` accepts any `SourceConfiguration` and the
resulting `ServiceUrl` is a plausible App Runner subdomain that resolves nowhere.

Every resource reports its terminal state as soon as its create call returns, so the waiters in
`terraform-provider-aws` and the AWS SDKs complete on the first poll. Services come back
`RUNNING`, auto scaling configurations, observability configurations and VPC connectors come back
`ACTIVE`, and connections and VPC ingress connections come back `AVAILABLE`.

Deleting a resource moves it to the deleted state its own status enum defines, `INACTIVE` for auto
scaling configurations, observability configurations and VPC connectors, `DELETED` for
connections, services and VPC ingress connections, and drops it from the list operations. That
matches what the App Runner API documents, and it keeps a delete waiter from polling for a state
that never arrives.

### Enforced API rules

- Reusing an `AutoScalingConfigurationName` or `ObservabilityConfigurationName` creates the next
  revision and clears `Latest` on the previous one.
- An auto scaling configuration ARN resolves in three forms: the full `name/revision/id` ARN a
  create returns, a `name/revision` ARN, and a bare `name` ARN. The last two select the highest
  active revision.
- The account's `DefaultConfiguration` is materialized on first use and cannot be deleted.
- An auto scaling configuration, VPC connector or connection still referenced by a live service
  cannot be deleted.
- A `SourceConfiguration` must carry exactly one of `CodeRepository` or `ImageRepository`, and
  `UpdateService` cannot switch a service from one to the other.
- `DeleteVpcIngressConnection` only accepts `AVAILABLE`, `FAILED_CREATION`, `FAILED_UPDATE` and
  `FAILED_DELETION`, and answers anything else with `InvalidStateException`.

Every other App Runner action, custom domains and deployment listing beyond what
`StartDeployment` records among them, returns a clean `UnknownOperationException` rather than a
stub success, so a caller fails fast instead of stranding a waiter.

## Example

```bash
aws --endpoint-url http://localhost:4566 apprunner create-service \
    --service-name storefront \
    --source-configuration '{
      "ImageRepository": {
        "ImageIdentifier": "public.ecr.aws/aws-containers/hello-app-runner:latest",
        "ImageRepositoryType": "ECR_PUBLIC",
        "ImageConfiguration": { "Port": "8000" }
      }
    }'

aws --endpoint-url http://localhost:4566 apprunner list-services
```
