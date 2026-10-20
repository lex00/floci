# ECR Public

**Protocol:** AWS JSON 1.1, `POST /` with `X-Amz-Target: SpencerFrontendService.<Action>`
**Signing name:** `ecr-public`

Public repositories keep their catalog data and policy for `aws_ecrpublic_repository` and
`aws_ecrpublic_repository_policy`. AWS serves ECR Public from `us-east-1` only, while any region
works here, with one registry per account.

The repository ARN is `arn:aws:ecr-public::<account>:repository/<name>`. The `repositoryUri` is
`public.ecr.aws/<account>/<name>`, with the account id as the registry alias.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRepository` | Creates a public repository with optional catalog data and tags. |
| `DescribeRepositories` | Describes the named repositories, or every repository in the registry. |
| `DeleteRepository` | Deletes the repository and returns it. |
| `PutRepositoryCatalogData` | Replaces the repository catalog data. |
| `GetRepositoryCatalogData` | Returns the repository catalog data. |
| `SetRepositoryPolicy` | Stores the repository policy text. |
| `GetRepositoryPolicy` | Returns the repository policy text. |
| `DeleteRepositoryPolicy` | Deletes the repository policy and returns the text it had. |
| `TagResource` | Merges tags into the repository. |
| `UntagResource` | Removes the named tag keys from the repository. |
| `ListTagsForResource` | Returns the repository tags. |
<!-- floci:actions:end -->

## Behaviour

- No images are stored, so `DeleteRepository` never reports `RepositoryNotEmptyException`.
- Catalog data drops `logoImageBlob` and reports `marketplaceCertified` as `false`.
