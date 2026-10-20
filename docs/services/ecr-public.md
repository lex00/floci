# ECR Public

**Protocol:** AWS JSON 1.1, `POST /` with `X-Amz-Target: SpencerFrontendService.<Action>`
**Signing name:** `ecr-public`

Public repositories keep their catalog data and policy for `aws_ecrpublic_repository` and
`aws_ecrpublic_repository_policy`, and their images in the `registry:2` container
[ECR](ecr.md) runs. AWS serves ECR Public from `us-east-1` only, while any region works here,
with one registry per account.

The repository ARN is `arn:aws:ecr-public::<account>:repository/<name>`. The account id is the
registry alias. The `repositoryUri` is `public.ecr.localhost:<port>/<account>/<name>`, the
loopback stand-in for `public.ecr.aws/<alias>/<name>`, or `localhost:<port>/<account>/public/<name>`
with `FLOCI_SERVICES_ECR_URI_STYLE=path`. `FLOCI_SERVICES_ECR_TLS_URI` switches the domain to
`localhost.floci.io` as it does for ECR.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateRepository` | Creates a public repository with optional catalog data and tags. |
| `GetAuthorizationToken` | Returns a `docker login` token for the registry. |
| `DescribeRegistries` | Describes the account's registry, with the account id as its one alias. |
| `GetRegistryCatalogData` | Returns the registry display name. |
| `PutRegistryCatalogData` | Sets the registry display name. |
| `DescribeImages` | Describes the repository's images, one per digest with its tags. |
| `DescribeImageTags` | Describes each tag and the image it names. |
| `BatchDeleteImage` | Deletes images by tag or digest. |
| `PutImage` | Stores an image manifest under a tag or digest. |
| `BatchCheckLayerAvailability` | Reports whether the repository holds each layer. |
| `InitiateLayerUpload` | Starts a layer upload. |
| `UploadLayerPart` | Uploads the next part of a layer. |
| `CompleteLayerUpload` | Completes a layer upload under its digest. |
| `DescribeRepositories` | Describes the named repositories, or every repository in the registry. |
| `DeleteRepository` | Deletes the repository and returns it; one with images needs `force`. |
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

- `docker push` and `docker pull` on the `repositoryUri` go through Floci's ECR data plane, and
  `aws ecr-public get-login-password | docker login --username AWS --password-stdin
  public.ecr.localhost:4566` logs in. The image operations read and write the same repositories.
- Images need Docker for the backing registry. Without a reachable daemon the repository
  operations still work, the image operations answer `ServerException`, and `DeleteRepository`
  treats a repository as empty.
- `DeleteRepository` of a repository with images is a `RepositoryNotEmptyException` unless `force`
  is `true`, which deletes the images with it.
- Catalog data drops `logoImageBlob` and reports `marketplaceCertified` as `false`.
