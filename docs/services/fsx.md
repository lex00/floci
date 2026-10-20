# Amazon FSx

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSSimbaAPIService_v20180301.<Action>`)
**Endpoint:** `http://localhost:4566/` (SigV4 service `fsx`)

The SDK and the CLI send FSx's Smithy service shape, `AWSSimbaAPIService_v20180301`, as the
target prefix. Floci emulates file systems of all four types (Lustre, Windows File Server,
NetApp ONTAP and OpenZFS), ONTAP storage virtual machines, ONTAP and OpenZFS volumes, backups,
Lustre data repository associations and their tags. Each resource reaches its terminal state as
soon as it is created and carries no file server, so nothing mounts it.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateFileSystem` | Create a Lustre, Windows, ONTAP or OpenZFS file system, `AVAILABLE` at once |
| `DescribeFileSystems` | Describe file systems by id, or page through all of them |
| `UpdateFileSystem` | Grow storage and update the type's configuration |
| `DeleteFileSystem` | Delete a file system |
| `CreateStorageVirtualMachine` | Create an ONTAP storage virtual machine and its root volume, `CREATED` at once |
| `DescribeStorageVirtualMachines` | Describe storage virtual machines by id or `file-system-id` filter |
| `UpdateStorageVirtualMachine` | Merge an Active Directory configuration update |
| `DeleteStorageVirtualMachine` | Delete a storage virtual machine that has no volumes left besides its root |
| `CreateVolume` | Create an ONTAP volume on a storage virtual machine, or an OpenZFS volume under a parent volume |
| `DescribeVolumes` | Describe volumes by id, `file-system-id` or `storage-virtual-machine-id` |
| `UpdateVolume` | Rename a volume or merge its configuration update |
| `DeleteVolume` | Delete a volume, taking an ONTAP final backup unless `SkipFinalBackup` is set |
| `CreateBackup` | Back up a file system, or an ONTAP volume |
| `DescribeBackups` | Describe backups by id, `file-system-id`, `backup-type`, `file-system-type` or `volume-id` |
| `CopyBackup` | Copy a backup, from another region when `SourceRegion` names one |
| `DeleteBackup` | Delete a backup |
| `CreateDataRepositoryAssociation` | Link a Lustre file system path to an `s3://` data repository |
| `DescribeDataRepositoryAssociations` | Describe data repository associations by id or `file-system-id` |
| `UpdateDataRepositoryAssociation` | Update an association's chunk size or S3 import and export policies |
| `DeleteDataRepositoryAssociation` | Delete a data repository association |
| `TagResource` | Tag a file system, volume, storage virtual machine, backup or association |
| `UntagResource` | Remove tags from any of those resources |
| `ListTagsForResource` | List a resource's tags |
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
  fails with `FileSystemNotFound`. Its volumes and data repository associations go with it. An
  ONTAP file system with storage virtual machines left is refused with `BadRequest`.
- An OpenZFS file system's root volume, named `fsx` at `/fsx`, is listed by `DescribeVolumes`
  under its `RootVolumeId`. A child volume's `VolumePath` is its parent's path plus its name.
  Deleting a volume that has children needs the `DELETE_CHILD_VOLUMES_AND_SNAPSHOTS` option, and
  a root volume cannot be deleted.
- A storage virtual machine comes with a root volume, `<name>_root`, and endpoints whose DNS names
  sit under the file system's. `DeleteStorageVirtualMachine` is refused while other volumes remain.
- `CreateBackup` takes a file system that is not ONTAP and not a Lustre scratch deployment, or an
  ONTAP volume. A backup is `AVAILABLE` at once and records the file system, and the volume when it
  backs one up. Backups outlive their file system.
- A data repository association needs a Lustre file system and an `s3://` `DataRepositoryPath`,
  and one file system path takes one association.
