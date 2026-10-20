# Amazon Kendra

**Protocol:** JSON 1.1 (`X-Amz-Target: AWSKendraFrontendService.*`)
**Endpoint:** `POST http://localhost:4566/`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateIndex` | Create an index; `ACTIVE` immediately |
| `DescribeIndex` | Read an index by id |
| `UpdateIndex` | Change the name, role, description, capacity, metadata and user context settings |
| `DeleteIndex` | Delete an index |
| `ListIndices` | List index summaries |
| `CreateDataSource` | Create a data source on an index; `ACTIVE` immediately |
| `DescribeDataSource` | Read a data source |
| `UpdateDataSource` | Change a data source's name, configuration, schedule, role and description |
| `DeleteDataSource` | Delete a data source |
| `ListDataSources` | List an index's data source summaries |
| `StartDataSourceSyncJob` | Record a sync job that succeeds at once |
| `StopDataSourceSyncJob` | Accept a stop request for a data source |
| `ListDataSourceSyncJobs` | List a data source's sync job history |
| `BatchPutDocument` | Add or replace documents from inline blobs |
| `BatchDeleteDocument` | Remove documents from an index |
| `Query` | Rank documents by query term frequency |
| `Retrieve` | Return passages from the best-matching documents |
| `TagResource` | Tag an index |
| `UntagResource` | Remove tags from an index |
| `ListTagsForResource` | List an index's tags |
<!-- floci:actions:end -->

## Emulation Behavior

- An index is `ACTIVE` as soon as `CreateIndex` returns, so a status poll completes on
  its first read. Its id is a UUID and its ARN is `index/<id>`.
- `Edition` defaults to `ENTERPRISE_EDITION`. `CapacityUnits` reads back as 0 storage
  and 0 query units until `UpdateIndex` sets them.
- A data source is `ACTIVE` as soon as `CreateDataSource` returns, and every type except
  `CUSTOM` needs a `RoleArn`. A sync job reads nothing from the source: it succeeds at once with
  zero document counts.
- `BatchPutDocument` indexes the text of each document's `Blob`, with HTML tags stripped.
  A document given only an `S3Path` is stored with its title and attributes and no text.
  `IndexStatistics` counts the stored documents and their text bytes.
- `Query` and `Retrieve` match the query's words against each document's title and text and rank
  documents by how often the words occur. `AttributeFilter` supports `EqualsTo`, `ContainsAny`,
  `AndAllFilters`, `OrAllFilters` and `NotFilter`. Results are always of type `DOCUMENT`.
- Tagging a missing index or data source fails with `ResourceUnavailableException`, as in AWS.

## Not emulated

- FAQs, thesauri, experiences, featured results and query suggestions
- Access control configurations and user context filtering
- Reading documents from data source repositories

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_KENDRA_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws kendra create-index --name docs \
  --role-arn arn:aws:iam::000000000000:role/kendra-index

aws kendra describe-index --id <index-id>
aws kendra list-indices
aws kendra delete-index --id <index-id>
```
