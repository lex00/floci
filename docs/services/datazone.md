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
| `CreateProject` | Create a project in a domain |
| `ListProjects` | List a domain's projects |
| `GetProject` | Get a project |
| `DeleteProject` | Delete a project |
| `CreateGlossary` | Create a glossary owned by a project |
| `GetGlossary` | Get a glossary |
| `UpdateGlossary` | Change a glossary's name, description or status |
| `DeleteGlossary` | Delete a disabled, empty glossary |
| `CreateGlossaryTerm` | Create a term in a glossary |
| `GetGlossaryTerm` | Get a glossary term |
| `UpdateGlossaryTerm` | Change a term, or move it to another glossary |
| `DeleteGlossaryTerm` | Delete a disabled term |
<!-- floci:actions:end -->

Tagging rides the shared `/tags/{resourceArn}` routes, so `ListTagsForResource`,
`TagResource` and `UntagResource` work against a domain ARN.

A domain is `AVAILABLE` as soon as `CreateDomain` returns, so SDK and Terraform waiters finish
on their first poll. `DeleteDomain` answers `DELETING` and removes the domain together with its
projects, glossaries and terms; a later `GetDomain` returns `ResourceNotFoundException`.

## AWS-compatible failures

`CreateGlossary` needs an existing project in the domain as `owningProjectIdentifier`, and
`CreateGlossaryTerm` needs an existing glossary. Any missing parent or target returns
`ResourceNotFoundException`. Names are unique per domain for projects and glossaries,
and per glossary for terms; a duplicate returns `ConflictException`.

`DeleteGlossary` requires the glossary to be `DISABLED` and to hold no terms, and
`DeleteGlossaryTerm` requires the term to be `DISABLED`; otherwise they return
`ConflictException`. Set the status with `UpdateGlossary` or `UpdateGlossaryTerm` first.
`DeleteProject` refuses a project that still owns glossaries unless `skipDeletionCheck` is set,
in which case the glossaries and their terms go with it.

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
