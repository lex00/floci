# Amazon WorkSpaces

**Protocol:** JSON 1.1

**Endpoint:** `POST /` + `X-Amz-Target: WorkspacesService.*`, SigV4 service `workspaces`

Floci emulates the WorkSpaces control plane. No desktops run.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateIpGroup` | Create an IP access control group |
| `DescribeIpGroups` | Describe IP groups by id, or all |
| `DeleteIpGroup` | Delete an IP group |
| `AuthorizeIpRules` | Add rules to an IP group |
| `RevokeIpRules` | Remove rules from an IP group |
| `UpdateRulesOfIpGroup` | Replace the rules of an IP group |
| `CreateConnectionAlias` | Create a connection alias in CREATED |
| `DescribeConnectionAliases` | Describe connection aliases by id, or all |
| `DeleteConnectionAlias` | Delete a connection alias |
| `CreateWorkspaces` | Create WorkSpaces, reported AVAILABLE on describe |
| `DescribeWorkspaces` | Describe WorkSpaces by id or filter |
| `ModifyWorkspaceProperties` | Change WorkSpace properties |
| `TerminateWorkspaces` | Terminate WorkSpaces |
| `CreateWorkspacesPool` | Create a pool in STOPPED |
| `DescribeWorkspacesPools` | Describe pools by id or PoolName filter |
| `UpdateWorkspacesPool` | Update a pool |
| `StartWorkspacesPool` | Move a pool to RUNNING |
| `StopWorkspacesPool` | Move a pool to STOPPED |
| `TerminateWorkspacesPool` | Delete a pool |
| `RegisterWorkspaceDirectory` | Register a directory, REGISTERED at once |
| `DescribeWorkspaceDirectories` | Describe directories by id, name or filter |
| `DeregisterWorkspaceDirectory` | Deregister a directory with no WorkSpaces |
| `ModifySelfservicePermissions` | Update a directory's self-service permissions |
| `ModifyWorkspaceAccessProperties` | Update a directory's device access properties |
| `ModifyWorkspaceCreationProperties` | Update a directory's default creation properties |
| `ModifySamlProperties` | Update or clear a directory's SAML properties |
| `ModifyCertificateBasedAuthProperties` | Update or clear certificate-based authentication |
| `ModifyStreamingProperties` | Update a directory's streaming properties |
| `ModifyClientProperties` | Update a directory's client properties |
| `DescribeClientProperties` | Describe client properties of directories |
| `ModifyEndpointEncryptionMode` | Set a directory's endpoint encryption mode |
| `AssociateIpGroups` | Associate IP groups with a directory |
| `DisassociateIpGroups` | Remove IP groups from a directory |
| `CreateWorkspaceBundle` | Create a custom bundle |
| `DescribeWorkspaceBundles` | Describe bundles by id, the account's own, or Amazon's |
| `UpdateWorkspaceBundle` | Change a custom bundle's image |
| `DeleteWorkspaceBundle` | Delete a custom bundle no WorkSpace or pool uses |
| `DescribeBundleAssociations` | List a bundle's application associations, always empty |
| `CreateTags` | Tag a resource by id |
| `DeleteTags` | Remove tags from a resource |
| `DescribeTags` | List a resource's tags |
<!-- floci:actions:end -->

## Behavior

- `CreateWorkspaces` returns each new WorkSpace as `PENDING`, and `DescribeWorkspaces` reports it
  `AVAILABLE`, so waiters finish on their first poll. A second request for the same user and
  directory comes back in `FailedRequests`.
- A connection alias is `CREATED` as soon as `CreateConnectionAlias` returns.
- A pool is `STOPPED` when created. `StartWorkspacesPool` and `StopWorkspacesPool` change its
  state at once, and `DescribeWorkspacesPools` filters on `PoolName` with every operator AWS
  defines.
- `CreateTags`, `DeleteTags` and `DescribeTags` accept the id of any emulated resource, and a pool
  ARN.
- `RegisterWorkspaceDirectory` accepts any Directory Service id, and mints a `wsd-` id for a
  `CUSTOMER_MANAGED` or IAM Identity Center directory registered without one. A new directory
  allows every device type except zero clients.
- `DescribeWorkspaceBundles` with `Owner` `AMAZON` lists six Amazon bundles. Without `Owner` or
  `BundleIds` it lists the account's own bundles.
- `CreateWorkspaces` stores directory and bundle ids as given.

## Not emulated

- WorkSpace images and bundle application associations
- Account links and connection alias associations
