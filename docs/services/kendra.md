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
| `TagResource` | Tag an index |
| `UntagResource` | Remove tags from an index |
| `ListTagsForResource` | List an index's tags |
<!-- floci:actions:end -->

## Emulation Behavior

- An index is `ACTIVE` as soon as `CreateIndex` returns, so a status poll completes on
  its first read. Its id is a UUID and its ARN is `index/<id>`.
- `Edition` defaults to `ENTERPRISE_EDITION`. `CapacityUnits` reads back as 0 storage
  and 0 query units until `UpdateIndex` sets them.
- Indexes hold no documents, so `IndexStatistics` counts stay at 0. Only the index
  itself is implemented; document ingestion and queries are not.
- Tagging a missing index fails with `ResourceUnavailableException`, as in AWS.

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
