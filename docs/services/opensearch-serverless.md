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
- `UpdateSecurityPolicy` requires the current `policyVersion`.
- Tags apply to collections.

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
