# CodeCommit

**Protocol:** AWS JSON 1.1, `POST /` with `X-Amz-Target: CodeCommit_20150413.<Action>`
**Signing name:** `codecommit`

Repository metadata and tags are stored for `aws_codecommit_repository`. The repository ARN is
`arn:aws:codecommit:<region>:<account>:<name>`, and `cloneUrlHttp` and `cloneUrlSsh` follow the
AWS form `https://git-codecommit.<region>.<dns-suffix>/v1/repos/<name>`. No Git server answers
at those URLs.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRepository` | Creates a repository with an optional description, KMS key id and tags. |
| `GetRepository` | Returns the repository metadata, clone URLs included. |
| `BatchGetRepositories` | Returns metadata for each named repository and lists the names not found. |
| `UpdateRepositoryDescription` | Replaces the repository description. |
| `UpdateRepositoryName` | Renames the repository, keeping its id and tags. |
| `DeleteRepository` | Deletes the repository and returns its id. |
| `ListRepositories` | Lists repository names and ids, sorted by name or last modified date. |
| `TagResource` | Merges tags into the repository. |
| `UntagResource` | Removes the named tag keys from the repository. |
| `ListTagsForResource` | Returns the repository tags as a map. |
<!-- floci:actions:end -->

## Behaviour

- `DeleteRepository` on a name that does not exist succeeds with an empty body, as in AWS.
- `UpdateRepositoryName` moves the tags and recomputes the ARN and clone URLs.
- `ListRepositories` sorts by `repositoryName` unless `sortBy` is `lastModifiedDate`.
