# Amazon AppStream 2.0

**Protocol:** JSON 1.1

**Endpoint:** `POST /` + `X-Amz-Target: PhotonAdminProxyService.*`, SigV4 service `appstream`

Floci emulates the AppStream 2.0 control plane. No streaming instances run.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateStack` | Create a stack |
| `DescribeStacks` | Describe stacks by name, or all |
| `UpdateStack` | Update a stack, applying AttributesToDelete |
| `DeleteStack` | Delete a stack with no associated fleet |
| `CreateFleet` | Create a fleet in STOPPED |
| `DescribeFleets` | Describe fleets by name, or all |
| `UpdateFleet` | Update a fleet, applying AttributesToDelete |
| `StartFleet` | Move a fleet to RUNNING |
| `StopFleet` | Move a fleet to STOPPED |
| `DeleteFleet` | Delete a stopped, unassociated fleet |
| `AssociateFleet` | Associate a fleet with a stack |
| `DisassociateFleet` | Remove a fleet and stack association |
| `ListAssociatedStacks` | List stacks associated with a fleet |
| `ListAssociatedFleets` | List fleets associated with a stack |
| `CreateUser` | Create a user pool user |
| `DescribeUsers` | List users of one authentication type |
| `EnableUser` | Enable a user |
| `DisableUser` | Disable a user |
| `DeleteUser` | Delete a user |
| `TagResource` | Tag a resource |
| `UntagResource` | Remove tags from a resource |
| `ListTagsForResource` | List a resource's tags |
<!-- floci:actions:end -->

## Behavior

- A fleet is `STOPPED` when created. `StartFleet` moves it to `RUNNING` and `StopFleet` back to
  `STOPPED` at once, and `ComputeCapacityStatus` reports the desired capacity as running and
  available while the fleet runs.
- `DeleteFleet` answers `ResourceInUseException` for a running fleet or one still associated with
  a stack, and `DeleteStack` does the same for a stack with a fleet.
- A stack created without `UserSettings` reports the clipboard, file transfer, printing and
  domain sign-in actions as `ENABLED`.
- `UpdateStack` and `UpdateFleet` apply `AttributesToDelete`, `DeleteStorageConnectors` and
  `DeleteVpcConfig`.
- `CreateUser` starts a user in `FORCE_CHANGE_PASSWORD`, enabled. Users are keyed by user name and
  authentication type.
- `TagResource`, `UntagResource` and `ListTagsForResource` accept stack and fleet ARNs.

## Not emulated

- Image builders and images
- Applications and entitlements
- Directory configs
- Sessions and user to stack associations
