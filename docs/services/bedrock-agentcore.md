# Bedrock AgentCore

**Protocol:** REST-JSON
**Endpoint:** `http://localhost:4566/runtimes/...`

Emulates the Amazon Bedrock AgentCore **control plane** (`bedrock-agentcore-control`)
as a stateful local registry across runtimes, endpoints, gateways, memories, browser/code-interpreter
resources, credential providers, gateway rules, resource policies, and AgentCore Evaluations
evaluators and online evaluation configurations. Runtimes reach `READY` immediately. By default
no agent runs; the `docker` runtime mode runs a runtime's artifact on `InvokeAgentRuntime`. See
[the design note](../design/bedrock-agentcore.md) for scope and protocol details.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAgentRuntime` | Register an agent runtime; returns an id, versioned ARN, and workload identity |
| `ListAgentRuntimes` | List runtimes (paginated) |
| `GetAgentRuntime` | Get a runtime, optionally a specific `version` |
| `UpdateAgentRuntime` | Update a runtime; appends a new immutable version |
| `ListAgentRuntimeVersions` | List a runtime's versions (paginated) |
| `DeleteAgentRuntime` | Delete a runtime |
| `CreateAgentRuntimeEndpoint` | Creates a named runtime endpoint targeting an agent runtime version |
| `GetAgentRuntimeEndpoint` | Returns a runtime endpoint |
| `UpdateAgentRuntimeEndpoint` | Updates the target version or description of a runtime endpoint |
| `DeleteAgentRuntimeEndpoint` | Deletes a runtime endpoint |
| `ListAgentRuntimeEndpoints` | Lists runtime endpoints for an agent runtime |
| `CreateWorkloadIdentity` | Creates a workload identity |
| `GetWorkloadIdentity` | Returns a workload identity |
| `UpdateWorkloadIdentity` | Updates a workload identity |
| `DeleteWorkloadIdentity` | Deletes a workload identity |
| `ListWorkloadIdentities` | Lists workload identities |
| `CreateApiKeyCredentialProvider` | Creates an API key credential provider |
| `GetApiKeyCredentialProvider` | Returns an API key credential provider |
| `ListApiKeyCredentialProviders` | Lists API key credential providers |
| `UpdateApiKeyCredentialProvider` | Updates an API key credential provider |
| `DeleteApiKeyCredentialProvider` | Deletes an API key credential provider |
| `CreateOauth2CredentialProvider` | Creates an OAuth2 credential provider |
| `GetOauth2CredentialProvider` | Returns an OAuth2 credential provider |
| `ListOauth2CredentialProviders` | Lists OAuth2 credential providers |
| `UpdateOauth2CredentialProvider` | Updates an OAuth2 credential provider |
| `DeleteOauth2CredentialProvider` | Deletes an OAuth2 credential provider |
| `CreateGateway` | Creates a gateway |
| `GetGateway` | Returns a gateway |
| `UpdateGateway` | Updates a gateway |
| `DeleteGateway` | Deletes a gateway |
| `ListGateways` | Lists gateways |
| `CreateGatewayTarget` | Creates a target on a gateway |
| `GetGatewayTarget` | Returns a gateway target |
| `UpdateGatewayTarget` | Updates a gateway target |
| `DeleteGatewayTarget` | Deletes a gateway target |
| `ListGatewayTargets` | Lists targets on a gateway |
| `CreateMemory` | Creates a memory resource |
| `GetMemory` | Returns a memory resource |
| `UpdateMemory` | Updates a memory resource |
| `DeleteMemory` | Deletes a memory resource |
| `ListMemories` | Lists memory resources |
| `CreateEvent` | Appends an event to a memory. `sessionId` is optional and generated when omitted |
| `ListEvents` | Lists a session's events, newest first. `POST` to the session path, not `GET` |
| `GetEvent` | Returns one event |
| `DeleteEvent` | Deletes one event and echoes its id |
| `CreateBrowser` | Creates a custom browser |
| `GetCodeInterpreter` | Returns a custom or system code interpreter |
| `DeleteCodeInterpreter` | Deletes a custom code interpreter |
| `ListCodeInterpreters` | Lists custom and system code interpreters |
| `CreateCodeInterpreter` | Creates a custom code interpreter |
| `CreateBrowserProfile` | Creates a browser profile |
| `ListBrowserProfiles` | Lists browser profiles |
| `DeleteBrowserProfile` | Deletes a browser profile |
| `GetBrowserProfile` | Returns a browser profile |
| `GetBrowser` | Returns a custom or system browser |
| `DeleteBrowser` | Deletes a custom browser |
| `ListBrowsers` | Lists custom and system browsers |
| `CreateGatewayRule` | Creates a gateway rule |
| `GetGatewayRule` | Returns a gateway rule |
| `ListGatewayRules` | Lists rules on a gateway |
| `UpdateGatewayRule` | Updates a gateway rule |
| `DeleteGatewayRule` | Deletes a gateway rule |
| `GetResourcePolicy` | Returns the policy attached to an AgentCore resource |
| `PutResourcePolicy` | Creates or replaces a policy on an AgentCore resource |
| `DeleteResourcePolicy` | Deletes the policy attached to an AgentCore resource |
| `CreateEvaluator` | Creates a custom evaluator (LLM-as-a-judge, code-based or derived) |
| `GetEvaluator` | Returns a custom or built-in evaluator; `includedData=METADATA_ONLY` drops instructions and rating scale |
| `UpdateEvaluator` | Updates a custom evaluator; refused while an enabled online evaluation config uses it |
| `DeleteEvaluator` | Deletes a custom evaluator; refused while an enabled online evaluation config uses it |
| `ListEvaluators` | Lists the AWS `Builtin.*` evaluators and the account's custom evaluators |
| `CreateOnlineEvaluationConfig` | Creates an online evaluation configuration (stored only, no traffic is sampled) |
| `GetOnlineEvaluationConfig` | Returns an online evaluation configuration |
| `UpdateOnlineEvaluationConfig` | Updates an online evaluation configuration, including its `executionStatus` |
| `DeleteOnlineEvaluationConfig` | Deletes an online evaluation configuration |
| `ListOnlineEvaluationConfigs` | Lists online evaluation configurations (paginated) |
| `Evaluate` | Data plane. Scores OpenTelemetry spans with a canned score, one result per session, trace or tool span |
| `StartBatchEvaluation` | Data plane. Starts a batch evaluation, returned as `IN_PROGRESS` |
| `GetBatchEvaluation` | Data plane. Returns a batch evaluation, completed with canned scores |
| `ListBatchEvaluations` | Data plane. Lists batch evaluations (paginated) |
| `StopBatchEvaluation` | Data plane. Stops a batch evaluation that has not completed |
| `DeleteBatchEvaluation` | Data plane. Deletes a batch evaluation in a terminal status |
<!-- floci:actions:end -->

A `DEFAULT` endpoint is created automatically with each runtime, and each runtime
is associated with an auto-created, resolvable workload identity.

## Memory events

The event data plane is backed by real storage, so what a read returns is what was written rather
than a fixed answer. Behaviour measured against real AgentCore:

- events come back **newest first**, and the event id embeds a zero-padded timestamp so ids sort
  chronologically as plain strings
- `includePayloads: false` omits the `payload` key entirely rather than emptying it
- `maxResults` accepts 1 to 100
- a **malformed** memory id is a `ValidationException`, while a **well-formed but unknown** one is a
  `ResourceNotFoundException`. A memory id is a name followed by exactly ten alphanumerics
- an unknown actor or session is an empty list, not an error
- a new event lands on the `main` branch, and an empty payload is accepted, though `payload` is a
  required member: omitting it is a `ValidationException` while `[]` is valid
- `CreateEvent` answers `201`, not `200`
- `ListEvents` pages with `nextToken` and defaults to 20 events when a caller names no `maxResults`

Memory *records* (extraction and retrieval) are not emulated: they depend on an extraction engine
rather than on stored events.

## Evaluations

Evaluators and online evaluation configurations are stored with AWS's shapes. No judge model runs
and no live traffic is sampled.

- `ListEvaluators` returns the AWS built-in evaluators first (`Builtin.Helpfulness`,
  `Builtin.GoalSuccessRate`, `Builtin.ToolSelectionAccuracy` and the rest of the 18 the developer
  guide documents), with `evaluatorType` `Builtin`, `provider` `AWS` and a regionless ARN such as
  `arn:aws:bedrock-agentcore:::evaluator/Builtin.Helpfulness`. Their `evaluatorConfig` is empty
  because AWS does not expose a built-in's model or prompt. They cannot be updated or deleted.
- `ThirdParty.<Provider>.<Metric>` ids are accepted wherever an evaluator id is expected, and
  `GetEvaluator` answers for them, but `ListEvaluators` does not list them.
- A custom evaluator's `evaluatorType` follows its configuration: `Custom` for `llmAsAJudge`,
  `CustomCode` for `codeBased`, `CustomDerived` for `derived`. A derived evaluator takes its
  `level` and `provider` from a built-in base.
- An evaluator used by an online evaluation configuration whose `executionStatus` is `ENABLED`
  reports `lockedForModification: true`. Updating or deleting it is a `ConflictException` until the
  configuration is disabled or deleted.
- Evaluator names and online evaluation configuration names are unique per account and region.
  Unknown evaluator ids in an online evaluation configuration are a `ValidationException`.
- Evaluators and online evaluation configurations take tags through `TagResource`,
  `UntagResource` and `ListTagsForResource`.

The data-plane operations are canned, in the same spirit as `InvokeAgentRuntime`:

- `Evaluate` reads `session.id` from the spans' attributes and returns one result per entity at the
  evaluator's level: one for a session-level evaluator, one per trace for a trace-level evaluator
  (or per `evaluationTarget.traceIds`), and one per `execute_tool` span (or per
  `evaluationTarget.spanIds`) for a tool-level evaluator. Every result carries the configured
  score (default `1.0`) and the label `Canned`. Spans with no `session.id` are a
  `ValidationException`, and an unknown evaluator is a `ResourceNotFoundException`.
- `StartBatchEvaluation` answers `IN_PROGRESS`. The job has completed by the first
  `GetBatchEvaluation`, `ListBatchEvaluations` or `DeleteBatchEvaluation` that reads it, with
  `COMPLETED` status and each evaluator's `averageScore` set to the configured score. The session
  count is the number of session ids the request names (`filterConfig.sessionIds` and
  `evaluationMetadata`), or 1 when it names none. Insights produce no clustering results.
- `StopBatchEvaluation` before that first read moves the job to `STOPPED`. Stopping a completed
  job is a `ConflictException`, as is deleting one that has not reached a terminal status.
- No CloudWatch Logs are read and no results or metrics are written to CloudWatch.
- Floci writes no trace spans to the `aws/spans` log group. Logs Insights queries over it run on
  the CloudWatch Logs `StartQuery` subset and return the spans an agent wrote there with
  `PutLogEvents`.

## Data plane — `InvokeAgentRuntime`

`POST /runtimes/{agentRuntimeArn}/invocations` has two modes, chosen with
`FLOCI_SERVICES_BEDROCK_AGENT_CORE_RUNTIME_MODE`.

In the default `stub` mode it returns a fixed, configurable JSON body (default
`{"output":"yes"}`) and echoes the `X-Amzn-Bedrock-AgentCore-Runtime-Session-Id` header. The
request payload (opaque binary, up to 100 MB) is never parsed.

In `docker` mode Floci runs the runtime's artifact in Docker, the way it runs Lambda functions,
and forwards the call to it:

- A `containerConfiguration` artifact runs its `containerUri` image. ECR image URIs resolve to
  Floci's registry.
- A `codeConfiguration` artifact's S3 zip is unpacked into `/var/task` of a Python image
  (`FLOCI_SERVICES_BEDROCK_AGENT_CORE_CODE_RUNTIME_IMAGE`, with `{version}` set from the
  artifact's `runtime`), and its `entryPoint` runs there. A first element ending in `.py` runs
  under `python`.
- The container gets the runtime version's `environmentVariables`, the AWS SDK settings that
  point at Floci, and session credentials for the execution role. Its output goes to the log
  group `/aws/bedrock-agentcore/runtimes/<agentRuntimeId>-<endpoint>`, in streams named
  `YYYY/MM/DD/[runtime-logs]<uuid>`.
- The server follows the AgentCore runtime contract for its `serverProtocol`: `HTTP` (the
  default) and `AGUI` listen on 8080, answer `GET /ping` and take `POST /invocations`; `MCP`
  listens on 8000 at `/mcp`; `A2A` listens on 9000 at `/`.
- The container receives the payload with the `runtimeSessionId` and content type, along with the
  `Accept`, MCP session and trace headers. Its answer streams back with its own content type, so
  a `text/event-stream` reply arrives event by event.
- Each `runtimeSessionId` gets its own warm container, kept until it has been unused for
  `FLOCI_SERVICES_BEDROCK_AGENT_CORE_RUNTIME_IDLE_TIMEOUT_SECONDS`. Deleting the runtime, or
  stopping Floci, stops its containers.
- `qualifier` selects a runtime endpoint by name or a version by number. Without one, the latest
  version runs.
- A container that does not answer its readiness probe in time, or that answers with an error
  status, is a `RuntimeClientError` (424). An unknown runtime ARN is `ResourceNotFoundException`.

## Data plane — `InvokeHarness`

`POST /harnesses/invoke` returns an `application/vnd.amazon.eventstream` response, the same
framing `ConverseStream` uses, so an SDK client's stream iterator works unchanged. The frames
arrive in AWS's order: `messageStart`, `contentBlockStart`, one `contentBlockDelta` per chunk,
`contentBlockStop`, `messageStop`, `metadata`.

`harnessArn`, `runtimeSessionId` and `messages` are all required, and the first two are validated
against their modelled shapes rather than merely checked for presence: a harness ARN ends in the
same `name-<10 alphanumerics>` form AgentCore uses elsewhere, and a runtime session id is 33 to 100
characters. `messages` may be an empty array, but omitting the member is a `ValidationException`.

Note the wire bindings, which are easy to get wrong: `harnessArn` and `qualifier` are **query
parameters**, `runtimeSessionId` and `runtimeUserId` are **headers**
(`X-Amzn-Bedrock-AgentCore-Runtime-Session-Id` / `-User-Id`), and only `messages`, `model`,
`tools` and friends travel in the JSON body. A missing `harnessArn` or `runtimeSessionId` is a
`ValidationException`.

There is no agent loop and no model. The assistant's reply **echoes the caller's last user
message**, so a chat client visibly works end to end and a request that failed to parse is obvious
rather than hidden behind a fixed string. A request whose `messages` array is present but empty, or
carries no user turn, is legitimate and gets a canned reply rather than an error.

The `harnessArn` is not resolved: the emulator models no harness resource, so there is nothing to
look one up in. Tool execution, skills and multi-turn iteration are not emulated, so no tool-use
block is ever produced even when a request supplies `tools`.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_CONTROL_ENABLED` | `true` | Enable/disable the control plane |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_ENABLED` | `true` | Enable/disable the data plane (invoke) |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_INVOKE_RESPONSE` | `{"output":"yes"}` | Canned `InvokeAgentRuntime` response body |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_RUNTIME_MODE` | `stub` | `docker` runs the runtime's artifact and forwards `InvokeAgentRuntime` to it |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_RUNTIME_IDLE_TIMEOUT_SECONDS` | `900` | Seconds a session's container may sit unused before it is stopped |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_RUNTIME_STARTUP_TIMEOUT_SECONDS` | `60` | Seconds a new container has to answer its readiness probe |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_CODE_RUNTIME_IMAGE` | `python:{version}-slim` | Image a `codeConfiguration` artifact runs in |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_VALIDATE_RUNTIME_EXISTS` | `false` | When `true`, `InvokeAgentRuntime` returns `ResourceNotFoundException` for an unknown runtime ARN instead of the canned response |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_HARNESS_ECHO_PREFIX` | `You said: ` | Prefix on the reply `InvokeHarness` streams back |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_HARNESS_EMPTY_REPLY` | `No user message was supplied.` | Reply used when a request carries no user message |
| `FLOCI_SERVICES_BEDROCK_AGENT_CORE_EVALUATION_SCORE` | `1.0` | Score on every `Evaluate` result and every evaluator's average in a completed batch evaluation |

> **Note on YAML config keys.** The status endpoint reports these services as
> `bedrock-agentcore-control` and `bedrock-agentcore`, but the YAML property paths
> use hyphenated words: `floci.services.bedrock-agent-core-control.enabled` and
> `floci.services.bedrock-agent-core.enabled` (and `…bedrock-agent-core.invoke-response`,
> `…bedrock-agent-core.validate-runtime-exists`, `…bedrock-agent-core.runtime-mode`,
> `…bedrock-agent-core.runtime-idle-timeout-seconds`,
> `…bedrock-agent-core.runtime-startup-timeout-seconds`, `…bedrock-agent-core.code-runtime-image`, `…bedrock-agent-core.harness-echo-prefix`,
> `…bedrock-agent-core.harness-empty-reply`, `…bedrock-agent-core.evaluation-score`). The
> `FLOCI_*` environment variables above map to these paths directly and are the recommended way
> to configure the service.

## Behavior notes

- `agentRuntimeId` is `<name>-<10 alphanumerics>`; the ARN embeds a UUID and the
  version: `arn:aws:bedrock-agentcore:<region>:<account>:agent/<uuid>:<version>`.
- `agentRuntimeName` must match `[a-zA-Z][a-zA-Z0-9_]{0,47}` (no hyphens); invalid
  names return `ValidationException`.
- Each `UpdateAgentRuntime` increments the version and preserves prior versions for
  `GetAgentRuntime?version=` and `ListAgentRuntimeVersions`. As on AWS, the new version holds
  exactly the configuration the request carries: an omitted `description`,
  `authorizerConfiguration`, `protocolConfiguration` or `environmentVariables` is cleared on
  it rather than carried over from the previous version.
- `networkConfiguration.networkMode` must be `PUBLIC` or `VPC` and
  `protocolConfiguration.serverProtocol` must be `MCP`, `HTTP`, `A2A` or `AGUI`; anything else returns
  the API's `ValidationException`.
- Timestamps (`createdAt`, `lastUpdatedAt`) are ISO-8601 strings.
- Config blobs (`agentRuntimeArtifact`, `networkConfiguration`, …) are otherwise stored opaquely
  and echoed back; they are not deeply validated.
- `CreateMemory` persists `tags`, `encryptionKeyArn`, and `memoryExecutionRoleArn`;
  `UpdateMemory` applies `description`, `eventExpiryDuration`, and
  `memoryExecutionRoleArn`. As in AWS, memory tags are returned only by
  `ListTagsForResource`, never embedded in the `memory` response shape.

## CloudFormation

Floci provisions these resource types:

- `AWS::BedrockAgentCore::Runtime`
- `AWS::BedrockAgentCore::RuntimeEndpoint`
- `AWS::BedrockAgentCore::Memory`

`Ref` follows each registry schema's `primaryIdentifier`: the runtime's `AgentRuntimeId`, the
endpoint's `AgentRuntimeEndpointArn` and the memory's `MemoryArn`. Every read-only attribute the
schemas declare resolves through `Fn::GetAtt`, including the runtime's nested
`WorkloadIdentityDetails.WorkloadIdentityArn`; `FailureReason` is always empty because the
emulated resources never leave `READY` / `ACTIVE`. Nested configuration objects
(`AgentRuntimeArtifact`, `NetworkConfiguration`, `AuthorizerConfiguration`) are stored in the
API's camelCase shape, so `GetAgentRuntime` returns what the template declared; the template's
string `ProtocolConfiguration` becomes the API's `{"serverProtocol": …}`, and an omitted
`NetworkConfiguration` defaults to `PUBLIC`.

An update that keeps the create-only properties (the runtime's `AgentRuntimeName`, the endpoint's
`AgentRuntimeId` and `Name`, the memory's `Name` and `EncryptionKeyArn`) is applied in place with a
tag diff; a runtime whose declared configuration did not change keeps its version. The template is
the whole declared configuration: an optional property it no longer carries (a `Description`, the
runtime's `AuthorizerConfiguration`, `ProtocolConfiguration` or `EnvironmentVariables`, the
memory's `MemoryExecutionRoleArn`) is cleared on the resource. A member whose `Fn::If` selects
`AWS::NoValue` inside a nested configuration object is omitted. When a later resource fails the
stack update, an in-place change is rolled back onto the resource (a runtime as a further
version, as on AWS), and a provision that fails after its own change undoes it before the failure
propagates. Changing a create-only property creates a replacement and deletes the displaced
resource once the stack update commits, as on AWS. Deleting the stack deletes the resources.

Properties the emulated control plane has no model for are accepted and logged once at `WARN`
rather than failing the stack: the runtime's `CapacityProviderConfiguration`,
`FilesystemConfigurations`, `LifecycleConfiguration`, `PlatformVersion` and
`RequestHeaderConfiguration`; the endpoint's `Tags`; and the memory's `MemoryStrategies`,
`IndexedKeys`, `NamespaceKeys` and `StreamDeliveryResources`.

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

# Create an agent runtime
aws bedrock-agentcore-control create-agent-runtime \
  --agent-runtime-name myAgent \
  --agent-runtime-artifact '{"containerConfiguration":{"containerUri":"public.ecr.aws/x/agent:latest"}}' \
  --network-configuration '{"networkMode":"PUBLIC"}' \
  --role-arn "arn:aws:iam::000000000000:role/agent-runtime" \
  --endpoint-url $AWS_ENDPOINT_URL

# Get / list
aws bedrock-agentcore-control get-agent-runtime --agent-runtime-id <id> --endpoint-url $AWS_ENDPOINT_URL
aws bedrock-agentcore-control list-agent-runtimes --endpoint-url $AWS_ENDPOINT_URL

# Delete
aws bedrock-agentcore-control delete-agent-runtime --agent-runtime-id <id> --endpoint-url $AWS_ENDPOINT_URL
```
