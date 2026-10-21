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
| `ListEnvironmentBlueprints` | List the managed blueprints DefaultDataLake and DefaultDataWarehouse and the custom blueprints |
| `GetEnvironmentBlueprint` | Get a managed or custom blueprint |
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
| `CreateDataSource` | Create a data source in a project, on an environment or a connection |
| `GetDataSource` | Get a data source |
| `UpdateDataSource` | Change a data source's name, settings, schedule, forms or configuration |
| `DeleteDataSource` | Delete a data source with its runs |
| `ListDataSources` | List a project's data sources |
| `StartDataSourceRun` | Run a data source; the run succeeds at once and imports no assets |
| `GetDataSourceRun` | Get a data source run |
| `ListDataSourceRuns` | List a data source's runs, newest first |
| `ListDataSourceRunActivities` | List a run's asset activities, always empty |
| `CreateConnection` | Create a domain or project connection |
| `GetConnection` | Get a connection |
| `UpdateConnection` | Change a connection's description, configurations, location or properties |
| `DeleteConnection` | Delete a connection no data source uses |
| `ListConnections` | List connections by name, environment, project, type or scope |
| `StartMetadataGenerationRun` | Start a metadata generation run on an asset |
| `GetMetadataGenerationRun` | Get a metadata generation run with its per-type status |
| `ListMetadataGenerationRuns` | List metadata generation runs |
| `CancelMetadataGenerationRun` | Cancel a metadata generation run that has not finished |
| `CreateProjectProfile` | Create a project profile with its environment configurations |
| `GetProjectProfile` | Get a project profile |
| `UpdateProjectProfile` | Change a project profile |
| `DeleteProjectProfile` | Delete a project profile |
| `ListProjectProfiles` | List project profiles, by name |
| `CreateRule` | Create a metadata form or glossary term rule on a domain unit |
| `GetRule` | Get a rule, or one of its revisions |
| `UpdateRule` | Change a rule and start its next revision |
| `DeleteRule` | Delete a rule with its revisions |
| `ListRules` | List a domain unit's rules, with the ones it inherits if asked |
| `CreateAccountPool` | Create an account pool from accounts or a Lambda handler |
| `GetAccountPool` | Get an account pool |
| `UpdateAccountPool` | Change an account pool |
| `DeleteAccountPool` | Delete an account pool |
| `ListAccountPools` | List account pools, by name |
| `ListAccountsInAccountPool` | List the accounts of an account pool |
| `CreateEnvironmentBlueprint` | Create a custom blueprint from a CloudFormation template URL |
| `UpdateEnvironmentBlueprint` | Change a custom blueprint's description, template or parameters |
| `DeleteEnvironmentBlueprint` | Delete a custom blueprint no profile or environment uses |
| `CreateEnvironmentAction` | Add a console link action to an environment |
| `GetEnvironmentAction` | Get an environment action |
| `UpdateEnvironmentAction` | Change an environment action |
| `DeleteEnvironmentAction` | Delete an environment action |
| `ListEnvironmentActions` | List an environment's actions |
| `AssociateEnvironmentRole` | Associate an IAM role with an environment |
| `DisassociateEnvironmentRole` | Remove an IAM role from an environment |
| `GetEnvironmentCredentials` | Get placeholder credentials for an environment |
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

A custom blueprint created with `CreateEnvironmentBlueprint` reports the provider `Custom` and
lists beside the managed ones; `managed` set to `false` lists only custom blueprints. Environment
actions and associated roles are stored and change nothing else. `CreateProject` does not read
project profiles. `GetEnvironmentCredentials` returns the same placeholder keys for every environment, valid
for one hour. Project profiles and account pools sit in the root domain unit unless a profile names
another one.

A rule's type follows its `detail`: a metadata form detail makes a `METADATA_FORM_ENFORCEMENT`
rule and a glossary term detail makes a `GLOSSARY_TERM_ENFORCEMENT` rule. Each `UpdateRule` makes
the next revision, and `GetRule` returns any revision. `ListRules` with `includeCascaded` adds the
rules of the unit's ancestors that set `includeChildDomainUnits`. Rules are recorded but enforce
nothing on listings or subscription requests. `ListAccountsInAccountPool` lists a pool's static
accounts, and a pool backed by a Lambda handler lists none.

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

A data source is `READY` as soon as `CreateDataSource` returns. Its configuration reports the
account and region of its environment, or of its connection when it has no environment.
`StartDataSourceRun` imports no assets and returns a run that is already `SUCCESS`. The data source's `lastRunStatus` follows its latest run. Each connection takes its type
from the `props` member given, or from the Glue connection type for `glueProperties`. Created on
an environment, it belongs to that environment's project with `PROJECT` scope; without one it has
`DOMAIN` scope. Redshift connections report a JDBC URL built from the given host and database. A
metadata generation run is `SUCCEEDED` when it returns and generates no predictions.

## Not emulated

- Metadata predictions; `AcceptPredictions` and `RejectPredictions` change nothing
- Lineage
- Connection credentials; `GetConnection` with `withSecret` returns none

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

Data source names are unique per project. Connection names are unique per project, or per domain
for domain connections. A duplicate of either returns `ConflictException`. `ListDataSources`
needs `projectIdentifier`, and deleting a connection that a data source still uses returns
`ConflictException`. An unknown asset in `StartMetadataGenerationRun` gives
`ResourceNotFoundException`, and leaving out both `types` and `type` gives `ValidationException`.
Because every run has finished by the time it is read, `CancelMetadataGenerationRun` always
answers `ConflictException`.

A custom blueprint's name must differ from every blueprint in the domain, and
`DeleteEnvironmentBlueprint` returns `ConflictException` while an environment profile or
environment uses it. Changing or deleting a managed blueprint returns `ValidationException`.
Names of project profiles and account pools are unique per domain, as are rule names. A rule keeps
its type, so `UpdateRule` rejects a `detail` of the other kind with `ValidationException`.
`CreateRule` and `ListRules` return `ResourceNotFoundException` for an unknown domain unit, and
disassociating a role that was never associated returns it too.

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
