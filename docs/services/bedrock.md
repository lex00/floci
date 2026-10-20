# Amazon Bedrock (control plane)

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/guardrails`, `/foundation-models`, `/inference-profiles` (SigV4 service `bedrock`)

This page covers the Bedrock control plane. Model invocation is a separate service
that shares the same signing name: see [Bedrock Runtime](bedrock-runtime.md).

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `ListFoundationModels` | List the foundation model catalog, filtered by provider, output modality, inference type and customization type |
| `GetFoundationModel` | Read one catalog model by model id or foundation-model ARN |
| `ListInferenceProfiles` | List the region's system-defined and application inference profiles, filtered by `type` |
| `CreateInferenceProfile` | Create an application profile that copies a foundation model or a system-defined profile |
| `DeleteInferenceProfile` | Delete an application profile |
| `GetInferenceProfile` | Read an inference profile by id or ARN |
| `CreateGuardrail` | Create a guardrail; returns the `DRAFT` version, `READY` immediately |
| `GetGuardrail` | Read a guardrail by id or ARN, at `DRAFT` or a numbered `guardrailVersion` |
| `UpdateGuardrail` | Replace the `DRAFT` configuration |
| `CreateGuardrailVersion` | Snapshot the `DRAFT` under the next numerical version |
| `DeleteGuardrail` | Delete one numerical version, or the guardrail and all its versions |
| `ListGuardrails` | List every guardrail's `DRAFT`, or every version of one guardrail |
| `TagResource` | Tag a guardrail or application inference profile (`POST /tagResource`) |
| `UntagResource` | Remove tags from a guardrail or application inference profile (`POST /untagResource`) |
| `ListTagsForResource` | List a guardrail's or application inference profile's tags (`POST /listTagsForResource`) |
<!-- floci:actions:end -->

Guardrails are versioned. The working copy is `DRAFT`, and `GetGuardrail` without a
`guardrailVersion` query parameter returns it. `CreateGuardrailVersion` copies the
current `DRAFT` to `1`, `2` and so on. Those snapshots are immutable, because
`UpdateGuardrail` only ever writes `DRAFT`.

`GuardrailStatus` is `READY` from the first read, so `aws_bedrock_guardrail`'s status
poll completes without a transition. Policy blocks are submitted as the `*Config`
shapes (`topicPolicyConfig`, `contentPolicyConfig` and the rest) and read back under
their unsuffixed names (`topicPolicy.topics`, `contentPolicy.filters`), matching the
AWS model.

`ListGuardrails` returns one `DRAFT` summary per guardrail. Passing
`guardrailIdentifier` returns every version of that one guardrail instead. Both forms
honour `maxResults` and `nextToken`.

`DeleteGuardrail` takes a `GuardrailNumericalVersion`, so a version query parameter of
`DRAFT` is rejected with `ValidationException`. Omit the parameter to delete the
guardrail and all of its versions.

Both an id (`abc123def456`) and a full ARN are accepted wherever the API takes a
`guardrailIdentifier` or a `resourceARN`.

`kmsKeyId` on `CreateGuardrail` and `UpdateGuardrail` is a `KmsKeyId`: a key id, a key
ARN, an alias name or an alias ARN. `kmsKeyArn` on the read shapes is a `KmsKeyArn`,
which is only ever the full key ARN, so every accepted form is resolved through KMS to
that one shape. A key that does not exist, is disabled, or is pending deletion is
rejected with `ValidationException`.

The AWS model's length constraints are enforced: `name` is 1 to 50 characters matching
`[0-9a-zA-Z-_]+`, `description` is 1 to 200, and `blockedInputMessaging` and
`blockedOutputsMessaging` are 1 to 500 each. A value outside those bounds is rejected
with `ValidationException`.

The model's tag limits are enforced as well. A `TagList` holds at most 200 items on one
request, and a longer array is rejected with `ValidationException`. A guardrail holds at
most 50 tags, counted over the tags already on it together with the tags in the current
request, so `CreateGuardrail` and `TagResource` both raise `TooManyTagsException` once
the resulting total would pass 50. Replacing the value of a tag key the guardrail
already carries does not add to that total.

## Foundation models and inference profiles

`ListFoundationModels` and `GetFoundationModel` serve a fixed catalog of Anthropic, Amazon,
Meta, Mistral AI and Cohere model ids in AWS's `FoundationModelSummary` shape. The Bedrock
Runtime stub answers for any of them, so a model picked from the list can be invoked. With
the proxy backend, map the ids you use through
`FLOCI_SERVICES_BEDROCK_RUNTIME_PROXY_MODEL_MAPPING`. Model ARNs carry the request region
and no account, as in AWS.

`ListInferenceProfiles` and `GetInferenceProfile` serve the system-defined cross-region
profiles of the request region's geography: `us.` in US regions, `eu.` in EU regions and
`apac.` in Asia Pacific regions, each listing the model's ARN in that geography's regions.
Other regions have none.

`CreateInferenceProfile` makes an `APPLICATION` profile from `modelSource.copyFrom`, which
names a foundation model ARN or a system-defined profile ARN of the region. The profile
takes that source's model ARNs. Its id is random, its ARN is
`application-inference-profile/<id>`, and it accepts tags. A second profile with the same
name fails with `ConflictException`, and a system-defined profile cannot be deleted.

`FLOCI_SERVICES_BEDROCK_FOUNDATION_MODELS` replaces the catalog with a comma-separated list
of model ids, for a proxy backend that serves a different set. An unknown id becomes a text
model from the provider its id prefix names.
`FLOCI_SERVICES_BEDROCK_EXTRA_FOUNDATION_MODELS` appends ids to the catalog instead.

## Not implemented

The rest of the Bedrock control plane is absent rather than stubbed, because
emulating it faithfully would require behaviour Floci has no way to model:

- Custom model, model customization, model import and distillation jobs. These
  depend on real training runs.
- Model evaluation and evaluation jobs. These depend on real inference.
- Provisioned throughput and prompt routers. These describe real
  capacity reservations and routing.
- Model invocation logging, batch inference and async invoke. These are data-plane
  operations backed by real model execution.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_ENABLED` | `true` | Enable or disable the service |
| `FLOCI_SERVICES_BEDROCK_FOUNDATION_MODELS` | _(built-in catalog)_ | Comma-separated model ids that replace the foundation model catalog |
| `FLOCI_SERVICES_BEDROCK_EXTRA_FOUNDATION_MODELS` | _(none)_ | Comma-separated model ids appended to the catalog |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws bedrock create-guardrail \
  --name my-guardrail \
  --blocked-input-messaging "Blocked." \
  --blocked-outputs-messaging "Blocked." \
  --content-policy-config '{"filtersConfig":[{"type":"HATE","inputStrength":"HIGH","outputStrength":"HIGH"}]}'

aws bedrock get-guardrail --guardrail-identifier abc123def456

aws bedrock create-guardrail-version --guardrail-identifier abc123def456

aws bedrock list-guardrails

aws bedrock tag-resource \
  --resource-arn arn:aws:bedrock:us-east-1:000000000000:guardrail/abc123def456 \
  --tags key=team,value=ai

aws bedrock delete-guardrail --guardrail-identifier abc123def456
```
