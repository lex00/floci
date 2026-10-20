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
- Directory and bundle ids are stored as given and not checked against registered directories.

## Not emulated

- Directory registration
- Bundles and images
- Account links and connection alias associations
