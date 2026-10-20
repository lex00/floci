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
| `CreateImageBuilder` | Create an image builder in RUNNING |
| `DescribeImageBuilders` | Describe image builders by name, or all |
| `StartImageBuilder` | Move an image builder to RUNNING |
| `StopImageBuilder` | Move an image builder to STOPPED |
| `DeleteImageBuilder` | Delete an image builder |
| `CreateImageBuilderStreamingURL` | Return a streaming URL for a running image builder |
| `DescribeImages` | Describe public base images and private images |
| `CreateUpdatedImage` | Create a private image from an existing image |
| `CopyImage` | Copy a private image to a region |
| `DeleteImage` | Delete a private image no fleet uses |
| `DescribeImagePermissions` | List the accounts a private image is shared with |
| `UpdateImagePermissions` | Share a private image with an account |
| `DeleteImagePermissions` | Stop sharing a private image with an account |
| `CreateAppBlock` | Create an app block |
| `DescribeAppBlocks` | Describe app blocks by ARN, or all |
| `DeleteAppBlock` | Delete an app block no application uses |
| `CreateApplication` | Create an application on an existing app block |
| `DescribeApplications` | Describe applications by ARN, or all |
| `UpdateApplication` | Update an application, applying AttributesToDelete |
| `DeleteApplication` | Delete an application with no associated fleet |
| `AssociateApplicationFleet` | Associate an application with a fleet |
| `DisassociateApplicationFleet` | Remove an application and fleet association |
| `DescribeApplicationFleetAssociations` | List application and fleet associations |
| `CreateEntitlement` | Create an entitlement on a stack |
| `DescribeEntitlements` | Describe one entitlement of a stack, or all |
| `UpdateEntitlement` | Update an entitlement |
| `DeleteEntitlement` | Delete an entitlement |
| `AssociateApplicationToEntitlement` | Add an application to an entitlement |
| `DisassociateApplicationFromEntitlement` | Remove an application from an entitlement |
| `ListEntitledApplications` | List the applications of an entitlement |
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
- An image builder is `RUNNING` when created. `StartImageBuilder` and `StopImageBuilder` switch it
  between `RUNNING` and `STOPPED` at once.
- `DescribeImages` lists eight `PUBLIC` base images in every region, such as
  `AppStream-WinServer2022` and `AppStream-AmazonLinux2`, next to the account's `PRIVATE` images.
  `CreateUpdatedImage` and `CopyImage` create private images, which are `AVAILABLE` at once.
- `CreateApplication` needs an existing app block, and `DeleteAppBlock` answers
  `ResourceInUseException` while an application uses it.
- `TagResource`, `UntagResource` and `ListTagsForResource` accept stack, fleet, image builder,
  private image, app block and application ARNs.

## Not emulated

- App block builders, theme, usage reports and image export or import tasks
- Software associations on image builders
- Directory configs
- Sessions and user to stack associations
