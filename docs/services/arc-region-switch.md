# ARC Region switch

**Protocol:** Smithy RPC v2 CBOR (`POST /service/ArcRegionSwitch/operation/<Action>`) and AWS JSON 1.0
(`X-Amz-Target: ArcRegionSwitch.<Action>`)
**Endpoint prefix:** `arc-region-switch`

Floci stores Application Recovery Controller Region switch plans. Plans are configuration only, and
Floci runs no plan executions.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreatePlan` | Create a plan for two Regions |
| `GetPlan` | Get a plan by ARN |
| `GetPlanInRegion` | Get a plan by ARN from a plan Region |
| `UpdatePlan` | Replace a plan's workflows and settings |
| `DeletePlan` | Delete a plan |
| `ListPlans` | List the account's plans |
| `ListPlansInRegion` | List the account's plans from a plan Region |
| `TagResource` | Tag a plan |
| `UntagResource` | Remove tags from a plan |
| `ListTagsForResource` | List a plan's tags |
<!-- floci:actions:end -->

## Emulation Behavior

- A plan ARN has no region, `arn:<partition>:arc-region-switch::<account>:plan/<name>:<suffix>`, with a
  random six-character suffix. Every region reads the same plans.
- `CreatePlan` requires two different Regions, and `primaryRegion`, when given, must be one of them.
- `UpdatePlan` replaces the plan's settings and increments `version`.
- `GetPlanInRegion` and `ListPlansInRegion` return the same plans as `GetPlan` and `ListPlans`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_ARCREGIONSWITCH_ENABLED` | `true` | Enable or disable the service |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws arc-region-switch create-plan --name my-plan --regions us-east-1 us-west-2 \
  --recovery-approach activePassive --primary-region us-east-1 \
  --execution-role arn:aws:iam::000000000000:role/switch \
  --workflows '[{"workflowTargetAction":"activate","workflowTargetRegion":"us-west-2","steps":[]}]'
aws arc-region-switch list-plans
```
