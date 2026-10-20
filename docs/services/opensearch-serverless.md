# OpenSearch Serverless

**Protocol:** AWS JSON 1.0
**Header:** `X-Amz-Target: OpenSearchServerless.<Action>`
**Endpoint prefix:** `aoss`

Floci stores OpenSearch Serverless security configurations and policies, and the collections the
policies cover. A collection holds metadata only, and no search engine runs behind its endpoint.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateSecurityConfig` | Create a SAML, IAM Identity Center or IAM federation configuration |
| `GetSecurityConfig` | Get a configuration by id |
| `UpdateSecurityConfig` | Update a configuration at its current `configVersion` |
| `DeleteSecurityConfig` | Delete a configuration |
| `ListSecurityConfigs` | List configurations of one type |
| `CreateSecurityPolicy` | Create an encryption or network policy |
| `GetSecurityPolicy` | Get a policy by type and name |
| `UpdateSecurityPolicy` | Update a policy at its current `policyVersion` |
| `DeleteSecurityPolicy` | Delete a policy |
| `ListSecurityPolicies` | List policies of one type, optionally only those covering given resources |
| `CreateCollection` | Create a collection covered by an encryption policy |
| `BatchGetCollection` | Get collections by id or by name, with endpoints |
| `ListCollections` | List collections, filtered by name, status or collection group |
| `UpdateCollection` | Update a collection's description |
| `DeleteCollection` | Delete a collection |
| `CreateAccessPolicy` | Create a data access policy |
| `GetAccessPolicy` | Get a data access policy |
| `UpdateAccessPolicy` | Update a data access policy at its current version |
| `DeleteAccessPolicy` | Delete a data access policy |
| `ListAccessPolicies` | List data access policies, filtered by the resources their rules cover |
| `CreateLifecyclePolicy` | Create a retention lifecycle policy |
| `UpdateLifecyclePolicy` | Update a lifecycle policy at its current version |
| `DeleteLifecyclePolicy` | Delete a lifecycle policy |
| `ListLifecyclePolicies` | List lifecycle policies, filtered by resource |
| `BatchGetLifecyclePolicy` | Get lifecycle policies by type and name |
| `BatchGetEffectiveLifecyclePolicy` | Report the retention rule that applies to each index |
| `CreateVpcEndpoint` | Create a VPC endpoint; ACTIVE immediately |
| `BatchGetVpcEndpoint` | Get VPC endpoints by id |
| `UpdateVpcEndpoint` | Add or remove a VPC endpoint's subnets and security groups |
| `DeleteVpcEndpoint` | Delete a VPC endpoint |
| `ListVpcEndpoints` | List VPC endpoints, filtered by status |
| `CreateCollectionGroup` | Create a collection group |
| `BatchGetCollectionGroup` | Get collection groups by id or name |
| `UpdateCollectionGroup` | Update a collection group's description or capacity |
| `DeleteCollectionGroup` | Delete a collection group with no collections |
| `ListCollectionGroups` | List collection groups |
| `CreateIndex` | Create an index with its schema on a collection |
| `GetIndex` | Get an index schema |
| `UpdateIndex` | Replace an index schema |
| `DeleteIndex` | Delete an index |
| `GetAccountSettings` | Get the account's OCU capacity limits |
| `UpdateAccountSettings` | Change the account's OCU capacity limits |
| `GetPoliciesStats` | Count the policies and security configurations by type |
| `TagResource` | Tag a collection |
| `UntagResource` | Remove tags from a collection |
| `ListTagsForResource` | List a collection's tags |
<!-- floci:actions:end -->

## Emulation Behavior

- A security configuration id is `<type>/<account>/<name>`, as AWS returns it. `UpdateSecurityConfig`
  requires the current `configVersion` and issues a new one; a stale version returns `ValidationException`.
- `CreateCollection` requires an encryption policy whose rules cover `collection/<name>`, the same check
  AWS makes. The collection's `kmsKeyArn` is the policy's `KmsARN`, or `auto` for an AWS owned key.
- A new collection reports `CREATING` in the create response and `ACTIVE` on every later read.
- `UpdateSecurityPolicy`, `UpdateAccessPolicy` and `UpdateLifecyclePolicy` require the current
  `policyVersion`; a stale version returns `ConflictException`.
- `BatchGetEffectiveLifecyclePolicy` reports the first retention rule, by policy name, whose
  `Resource` pattern matches the index.
- A VPC endpoint is `ACTIVE` as soon as `CreateVpcEndpoint` returns. Account capacity limits
  default to 10 indexing and 10 search OCUs.
- Indexes keep the schema they are given; no documents are stored.
- Tags apply to collections and collection groups.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_OPENSEARCHSERVERLESS_ENABLED` | `true` | Enable or disable the service |

## Example

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws opensearchserverless create-security-config --name my-saml --type saml \
  --saml-options metadata='<EntityDescriptor/>'
aws opensearchserverless get-security-config --id saml/000000000000/my-saml

aws opensearchserverless create-security-policy --name logs-enc --type encryption \
  --policy '{"Rules":[{"ResourceType":"collection","Resource":["collection/logs"]}],"AWSOwnedKey":true}'
aws opensearchserverless create-collection --name logs
```
