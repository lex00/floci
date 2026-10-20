# Amazon FSx

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSSimbaAPIService_v20180301.<Action>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `fsx`)

The SDK and the CLI send FSx's Smithy service shape, `AWSSimbaAPIService_v20180301`, as the
target prefix. Floci emulates file systems of all four types (Lustre, Windows File Server,
NetApp ONTAP and OpenZFS) and their tags. A file system is `AVAILABLE` as soon as it is created
and carries no file server, so nothing mounts it.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateFileSystem` | Create a Lustre, Windows, ONTAP or OpenZFS file system, `AVAILABLE` at once |
| `DescribeFileSystems` | Describe file systems by id, or page through all of them |
| `UpdateFileSystem` | Grow storage and update the type's configuration |
| `DeleteFileSystem` | Delete a file system |
| `TagResource` | Tag a file system |
| `UntagResource` | Remove tags from a file system |
| `ListTagsForResource` | List a file system's tags |
<!-- floci:actions:end -->

## Behavior

- `CreateFileSystem` requires `SubnetIds` naming existing subnets, and takes `VpcId` from the
  first one. Every type except `LUSTRE` requires its configuration member with the members the
  API marks required. A configuration member for another type fails with
  `IncompatibleParameterError`.
- The response echoes the request's configuration and adds the members AWS fills in, such as a
  Lustre `MountName` or an OpenZFS `RootVolumeId`. A Lustre `ImportPath` or `ExportPath` must be an
  `s3://` path, and `DNSName` takes the form `<id>.fsx.<region>.<dns suffix>` for the region's
  partition.
- `UpdateFileSystem` grows `StorageCapacity` and merges the type's update members into the stored
  configuration.
- `DeleteFileSystem` answers `DELETING` and removes the file system, so a later describe of it
  fails with `FileSystemNotFound`.

The emulation covers file systems and their tags only.
