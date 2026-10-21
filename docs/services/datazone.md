# Amazon DataZone

**Protocol:** REST JSON

**Endpoint:** `/v2/domains/...` (e.g. `POST /v2/domains`), SigV4 service `datazone`

Floci emulates the part of DataZone that business glossaries need, together with the domains
and projects that hold them.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateDomain` | Create a domain; AVAILABLE immediately |
| `ListDomains` | List domains, filtered by status |
| `GetDomain` | Get a domain by id |
| `DeleteDomain` | Delete a domain with everything in it |
| `UpdateDomain` | Change a domain's name, description, roles or single sign-on |
| `CreateProject` | Create a project in a domain |
| `ListProjects` | List a domain's projects |
| `GetProject` | Get a project |
| `DeleteProject` | Delete a project |
| `UpdateProject` | Change a project's name, description, domain unit or glossary terms |
| `ListEnvironmentBlueprints` | List the managed blueprints DefaultDataLake and DefaultDataWarehouse |
| `GetEnvironmentBlueprint` | Get a managed blueprint |
| `PutEnvironmentBlueprintConfiguration` | Enable a blueprint in a domain |
| `GetEnvironmentBlueprintConfiguration` | Get a blueprint configuration |
| `ListEnvironmentBlueprintConfigurations` | List a domain's blueprint configurations |
| `DeleteEnvironmentBlueprintConfiguration` | Delete a blueprint configuration |
| `CreateEnvironmentProfile` | Create an environment profile on a blueprint |
| `GetEnvironmentProfile` | Get an environment profile |
| `UpdateEnvironmentProfile` | Change an environment profile |
| `DeleteEnvironmentProfile` | Delete an environment profile no environment uses |
| `ListEnvironmentProfiles` | List environment profiles |
| `CreateEnvironment` | Create an environment; ACTIVE immediately |
| `GetEnvironment` | Get an environment |
| `UpdateEnvironment` | Change an environment's name, description, terms or parameters |
| `DeleteEnvironment` | Delete an environment |
| `ListEnvironments` | List environments |
| `CreateFormType` | Create a form type, or a new revision of one |
| `GetFormType` | Get a form type |
| `DeleteFormType` | Delete a form type no asset type uses |
| `CreateAssetType` | Create an asset type from existing form types |
| `GetAssetType` | Get an asset type |
| `DeleteAssetType` | Delete an asset type |
| `CreateUserProfile` | Create an IAM or SSO user profile |
| `GetUserProfile` | Get a user profile by user identifier or id |
| `UpdateUserProfile` | Change a user profile's status |
| `CreateGlossary` | Create a glossary owned by a project |
| `GetGlossary` | Get a glossary |
| `UpdateGlossary` | Change a glossary's name, description or status |
| `DeleteGlossary` | Delete a disabled, empty glossary |
| `CreateGlossaryTerm` | Create a term in a glossary |
| `GetGlossaryTerm` | Get a glossary term |
| `UpdateGlossaryTerm` | Change a term, or move it to another glossary |
| `DeleteGlossaryTerm` | Delete a disabled term |
| `CreateDomainUnit` | Create a domain unit under a parent unit |
| `GetDomainUnit` | Get a domain unit, including the root unit |
| `UpdateDomainUnit` | Change a domain unit's name or description |
| `DeleteDomainUnit` | Delete a domain unit with no child units or projects |
| `ListDomainUnitsForParent` | List a unit's child domain units |
| `UpdateRootDomainUnitOwner` | Replace an owner of the domain's root unit |
| `AddEntityOwner` | Add a user or group owner to a domain unit |
| `RemoveEntityOwner` | Remove an owner from a domain unit |
| `ListEntityOwners` | List a domain unit's owners |
| `CreateProjectMembership` | Add a user or group to a project, or change its designation |
| `DeleteProjectMembership` | Remove a user or group from a project |
| `ListProjectMemberships` | List a project's members |
| `AddPolicyGrant` | Grant a managed policy on a domain unit, profile, configuration or asset type |
| `RemovePolicyGrant` | Remove a policy grant by principal or grant id |
| `ListPolicyGrants` | List an entity's grants of one policy type |
| `CreateAsset` | Create an asset of a custom or `amazon.datazone.` asset type |
| `GetAsset` | Get an asset, or one of its revisions |
| `DeleteAsset` | Delete an asset with its filters and listing |
| `CreateAssetRevision` | Create the next revision of an asset |
| `ListAssetRevisions` | List an asset's revisions, newest first |
| `AcceptPredictions` | Accept an asset's metadata predictions |
| `RejectPredictions` | Reject an asset's metadata predictions |
| `CreateAssetFilter` | Create a column or row filter on an asset |
| `GetAssetFilter` | Get an asset filter |
| `UpdateAssetFilter` | Change an asset filter's name, description or configuration |
| `DeleteAssetFilter` | Delete an asset filter |
| `ListAssetFilters` | List an asset's filters |
| `CreateDataProduct` | Create a data product from assets |
| `GetDataProduct` | Get a data product, or one of its revisions |
| `DeleteDataProduct` | Delete a data product with its listing |
| `CreateDataProductRevision` | Create the next revision of a data product |
| `ListDataProductRevisions` | List a data product's revisions, newest first |
| `CreateListingChangeSet` | Publish or unpublish an asset or data product |
| `GetListing` | Get a listing, or one of its revisions |
| `DeleteListing` | Delete a listing |
| `SearchListings` | Search active listings |
| `Search` | Search assets, data products, glossaries or glossary terms |
| `SearchTypes` | Search custom asset types and form types |
| `CreateSubscriptionRequest` | Request a project, user, group or IAM principal's subscription to active listings |
| `GetSubscriptionRequestDetails` | Get a subscription request |
| `UpdateSubscriptionRequest` | Change a pending request's reason |
| `DeleteSubscriptionRequest` | Delete a subscription request |
| `AcceptSubscriptionRequest` | Accept a pending request and create its subscriptions |
| `RejectSubscriptionRequest` | Reject a pending request |
| `ListSubscriptionRequests` | List subscription requests by status, listing, approver or principal |
| `GetSubscription` | Get a subscription |
| `CancelSubscription` | Cancel an approved subscription and revoke its grants |
| `RevokeSubscription` | Revoke an approved subscription, keeping its grants if asked |
| `ListSubscriptions` | List subscriptions by status, request, listing, approver or principal |
| `CreateSubscriptionTarget` | Create a subscription target on an environment |
| `GetSubscriptionTarget` | Get a subscription target |
| `UpdateSubscriptionTarget` | Change a subscription target |
| `DeleteSubscriptionTarget` | Delete a subscription target with no grants |
| `ListSubscriptionTargets` | List an environment's subscription targets |
| `CreateSubscriptionGrant` | Grant a listing to an environment's subscription target |
| `GetSubscriptionGrant` | Get a subscription grant |
| `DeleteSubscriptionGrant` | Delete a subscription grant |
| `UpdateSubscriptionGrantStatus` | Set the status of one asset in a grant |
| `ListSubscriptionGrants` | List subscription grants by environment, target, listing, subscription or project |
| `PostLineageEvent` | Record an OpenLineage run event and the lineage nodes it names |
| `GetLineageEvent` | Get a lineage event as it was posted |
| `ListLineageEvents` | List lineage events by event time and processing status |
| `DeleteLineageEvent` | Delete a lineage event |
| `GetLineageNode` | Get a lineage node and its neighbours, now or at a past time |
| `ListLineageNodeHistory` | List a lineage node's snapshots |
| `QueryGraph` | Follow lineage edges from matched lineage nodes |
| `PostTimeSeriesDataPoints` | Add time series data points to an asset or listing |
| `GetTimeSeriesDataPoint` | Get one time series data point |
| `ListTimeSeriesDataPoints` | List an entity's data points of one form |
| `DeleteTimeSeriesDataPoints` | Delete an entity's data points of one form |
| `BatchGetAttributesMetadata` | Get the forms of an asset or listing's attributes |
| `BatchPutAttributesMetadata` | Set the forms of an asset or listing's attributes |
| `AssociateGovernedTerms` | Add governed glossary terms to an asset |
| `DisassociateGovernedTerms` | Remove governed glossary terms from an asset |
<!-- floci:actions:end -->

Tagging rides the shared `/tags/{resourceArn}` routes, so `ListTagsForResource`,
`TagResource` and `UntagResource` work against a domain ARN.

A domain is `AVAILABLE` as soon as `CreateDomain` returns, so SDK and Terraform waiters finish
on their first poll. `DeleteDomain` answers `DELETING` and removes the domain together with its
projects, glossaries, terms and environments; a later `GetDomain` returns
`ResourceNotFoundException`.

Each domain lists the managed blueprints `DefaultDataLake` and `DefaultDataWarehouse`, with an
id that stays the same for the domain. An environment is `ACTIVE` as soon as `CreateEnvironment`
returns and provisions no AWS resources. It takes its account, region and blueprint from its
environment profile. A form type or asset type created again under the same name gets the next
revision.

Each domain has a root domain unit, named after the domain, whose id is the domain's
`rootDomainUnitId`. New domain units and projects start with no owners or members; add them with
`AddEntityOwner` and `CreateProjectMembership`. A user given by an identifier that has a user
profile is reported by the profile id, and otherwise by the identifier itself. Policy grants are
recorded and listed but do not limit what callers can do.

An asset's type is a custom asset type in the domain or any `amazon.datazone.` managed type.
Assets and data products keep every revision. `CreateListingChangeSet` makes a listing `ACTIVE`
at once from the latest revision or the one given, and publishing a data product publishes its
assets too. A listing's `forms` is a JSON document keyed by form name. `Search`, `SearchListings`
and `SearchTypes` match `searchText` against names and descriptions, apply filters to the
result's top-level attributes, and sort on one of them. `SearchTypes` with `managed` set returns
no items.

Accepting a subscription request creates an `APPROVED` subscription for each principal and
listing it names. For a project principal, each `AUTOMATIC` subscription target in that project's
environments whose `applicableAssetTypes` covers a listed asset gets a `COMPLETED` grant at once,
with every asset `GRANTED`. A `MANUAL` target gets one only through `CreateSubscriptionGrant`.
Grants record access and change no AWS permissions. `CancelSubscription`, and `RevokeSubscription`
without `retainPermissions`, mark the subscription's grant assets `REVOKED`. A grant's overall
status follows its assets' statuses as `UpdateSubscriptionGrantStatus` sets them.

`PostLineageEvent` takes an OpenLineage run event and processes it at once, so its status is
`SUCCESS`. The event's job and each input and output dataset become lineage nodes, found again by
a source identifier of `namespace/name`. Each input is upstream of the job and each output is
downstream of it. A node keeps a snapshot for every event time it appears at, and an edge stays in
every later snapshot, so `GetLineageNode` with a `timestamp` shows the graph as it stood then.
`QueryGraph` reads its match clauses in order. An entity pattern picks nodes by id or source
identifier and applies its filters. A relation pattern walks up to `maxPathLength` edges.
`OUT` follows edges downstream and `IN` follows them upstream. The result holds every node on the matched
paths. Lineage nodes carry
no forms, and deleting an event leaves its nodes in place.

Time series data points belong to an asset or listing. An asset's
`latestTimeSeriesDataPointFormsOutput` holds the newest data point of each form, and
`ListTimeSeriesDataPoints` returns a form's points oldest first. Attribute metadata stores forms
for any attribute name on an asset or listing. `BatchGetAttributesMetadata` reports each name it
holds nothing for as a `ResourceNotFoundException` entry in `errors`. Governed terms change the asset's `governedGlossaryTerms` across all its
revisions.

## Not emulated

- Metadata predictions; `AcceptPredictions` and `RejectPredictions` change nothing
- Data sources and their runs, and connections
- Project profiles and rules

## AWS-compatible failures

`CreateGlossary` needs an existing project in the domain as `owningProjectIdentifier`, and
`CreateGlossaryTerm` needs an existing glossary. Any missing parent or target returns
`ResourceNotFoundException`. Names are unique per domain for projects and glossaries,
and per glossary for terms; a duplicate returns `ConflictException`.

`DeleteGlossary` requires the glossary to be `DISABLED` and to hold no terms, and
`DeleteGlossaryTerm` requires the term to be `DISABLED`; otherwise they return
`ConflictException`. Set the status with `UpdateGlossary` or `UpdateGlossaryTerm` first.
`DeleteProject` refuses a project that still owns glossaries or environments unless
`skipDeletionCheck` is set, in which case the glossaries and their terms go with it.

Domain unit names are unique among siblings. `DeleteDomainUnit` returns `ConflictException` for the
root unit and for a unit that still has child units or projects. `AddEntityOwner` returns
`ConflictException` for an existing owner, and `RemoveEntityOwner` and `DeleteProjectMembership`
return `ResourceNotFoundException` for one that is absent. `AddPolicyGrant` needs the `detail`
member that matches `policyType`, such as `createGlossary` for `CREATE_GLOSSARY`.

`CreateAsset` returns `ResourceNotFoundException` for an unknown custom asset type, and
`CreateDataProduct` for an unknown asset item. `DeleteAsset` returns `ConflictException` while a
data product holds the asset. Asset filter names are unique per asset.

`CreateSubscriptionRequest` returns `ConflictException` for a listing that is not `ACTIVE` or
that the principal already holds an approved subscription to. A request that is no longer
`PENDING`, or a subscription that is no longer `APPROVED`, returns `ConflictException` to any
change. Subscription target names are unique per environment, and
`DeleteSubscriptionTarget` returns `ConflictException` while a grant uses the target.

`PostLineageEvent` returns `ValidationException` for a body that is not a JSON object. The event
needs `eventTime` and `run.runId`, and the job and each dataset need a `namespace` and `name`.
`GetLineageNode` returns `ResourceNotFoundException` for a time before the node's first snapshot.
The time series, attribute metadata and governed term operations return
`ResourceNotFoundException` for an entity or glossary term that does not exist, and the time series
operations need `formName` on every call except a post.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_DATAZONE_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws datazone create-domain --name analytics \
  --domain-execution-role arn:aws:iam::000000000000:role/service-role/AmazonDataZoneDomainExecution

aws datazone create-project --domain-identifier dzd_... --name governance

aws datazone create-glossary --domain-identifier dzd_... --name business \
  --owning-project-identifier <project-id>

aws datazone create-glossary-term --domain-identifier dzd_... \
  --glossary-identifier <glossary-id> --name revenue
```
