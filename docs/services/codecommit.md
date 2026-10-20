# CodeCommit

**Protocol:** AWS JSON 1.1, `POST /` with `X-Amz-Target: CodeCommit_20150413.<Action>`
**Signing name:** `codecommit`

Repository metadata and tags are stored for `aws_codecommit_repository`, and `cloneUrlHttp`
serves the repository over Git's smart HTTP protocol, so Git clients can clone from and push to
it. The repository ARN is `arn:aws:codecommit:<region>:<account>:<name>`.

## Git

- `cloneUrlHttp` is `http://git-codecommit.<region>.localhost:<port>/v1/repos/<name>`, the
  loopback stand-in for AWS's `https://git-codecommit.<region>.<dns-suffix>/v1/repos/<name>`. The
  host names the region. When `FLOCI_BASE_URL` or `FLOCI_HOSTNAME` names another host, the URL is
  `<base-url>/v1/repos/<name>` and serves the default region.
- `cloneUrlSsh` keeps the AWS form. Floci runs no SSH server.
- Any credentials are accepted. A Git credential user name of the form `<name>-at-<account>`, as
  `CreateServiceSpecificCredential` issues for CodeCommit, selects that account; otherwise the
  default account applies.
- The repositories live in a container of `FLOCI_SERVICES_CODECOMMIT_GIT_IMAGE` (default
  `alpine/git:v2.52.0`), which starts with the first Git request and keeps each repository on the
  `floci-codecommit-repos` volume. A new repository is an empty bare repository whose default
  branch is `main`. `DeleteRepository` removes its Git data, and `UpdateRepositoryName` keeps it.
- Git needs Docker. The repository operations work without it.

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

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_CODECOMMIT_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_CODECOMMIT_GIT_IMAGE` | `alpine/git:v2.52.0` | Image that holds the repositories and runs Git for the clone URLs |
- `ListRepositories` sorts by `repositoryName` unless `sortBy` is `lastModifiedDate`.
