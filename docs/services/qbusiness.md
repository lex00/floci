# Amazon Q Business

**Protocol:** REST JSON (SigV4 service `qbusiness`)
**Endpoint:** `http://localhost:4566/applications`, `/v1/tags/{resourceARN}`

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateApplication` | Create an application; `ACTIVE` immediately |
| `GetApplication` | Read an application by id |
| `UpdateApplication` | Change the display name, description, role and feature settings |
| `DeleteApplication` | Delete an application |
| `ListApplications` | List application summaries |
| `CreateDataSource` | Create a data source on an index; `ACTIVE` immediately |
| `ListDataSources` | List an index's data sources |
| `GetDataSource` | Read a data source |
| `UpdateDataSource` | Change a data source |
| `DeleteDataSource` | Delete a data source |
| `StartDataSourceSyncJob` | Record a sync job that succeeds at once |
| `StopDataSourceSyncJob` | Accept a stop request for a data source |
| `ListDataSourceSyncJobs` | List a data source's sync job history |
| `GetChatControlsConfiguration` | Read the chat controls, with defaults until set |
| `UpdateChatControlsConfiguration` | Change the response scope, blocked phrases, topics and modes |
| `DeleteChatControlsConfiguration` | Reset the chat controls to their defaults |
| `AssociatePermission` | Add a statement to the application's resource policy |
| `GetPolicy` | Read the application's resource policy |
| `DisassociatePermission` | Remove a statement from the resource policy |
| `CreateIndex` | Create an index; `ACTIVE` immediately |
| `CreateRetriever` | Create a retriever; `ACTIVE` immediately |
| `CreatePlugin` | Create a plugin; `READY` immediately |
| `CreateWebExperience` | Create a web experience; `ACTIVE` immediately |
| `CreateDataAccessor` | Create a data accessor |
| `ListIndices` | List an application's indices |
| `ListRetrievers` | List an application's retrievers |
| `ListPlugins` | List an application's plugins |
| `ListWebExperiences` | List an application's web experiences |
| `ListDataAccessors` | List an application's data accessors |
| `GetIndex` | Read an index |
| `GetRetriever` | Read a retriever |
| `GetPlugin` | Read a plugin |
| `GetWebExperience` | Read a web experience |
| `GetDataAccessor` | Read a data accessor |
| `UpdateIndex` | Change an index |
| `UpdateRetriever` | Change a retriever |
| `UpdatePlugin` | Change a plugin |
| `UpdateWebExperience` | Change a web experience |
| `UpdateDataAccessor` | Change a data accessor |
| `DeleteIndex` | Delete an index and its data sources |
| `DeleteRetriever` | Delete a retriever |
| `DeletePlugin` | Delete a plugin |
| `DeleteWebExperience` | Delete a web experience |
| `DeleteDataAccessor` | Delete a data accessor |
| `ListTagsForResource` | List an application's tags |
| `TagResource` | Tag an application |
| `UntagResource` | Remove tags from an application |
<!-- floci:actions:end -->

## Emulation Behavior

- An application is `ACTIVE` as soon as `CreateApplication` returns. Its id is a UUID
  and its ARN is `application/<id>`. `identityType` defaults to `AWS_IAM_IDC`.
- AppConfig and EMR Serverless serve `/applications` too. Floci routes a request there
  by the service its SigV4 credential scope names, so Q Business calls must be signed
  for `qbusiness`, as every SDK does.
- Tags use the `/v1/tags/{resourceARN}` routes with a list of lowercase `key` and
  `value` entries.
- Indices, data sources, retrievers and web experiences are `ACTIVE` as soon as they are
  created, and a plugin is `READY` and `ENABLED`. Each has an ARN under its application, such
  as `application/<id>/index/<index-id>/data-source/<data-source-id>`, and takes tags.
- A data source's `type` comes from its `configuration.type`. A sync job reads nothing and
  succeeds at once with zero document counts.
- Deleting an application deletes everything inside it, and deleting an index deletes its data
  sources.

## Not emulated

- Chat, conversations, messages and attachments
- Documents, groups, users and subscriptions
- Chat response configurations and plugin actions

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_QBUSINESS_ENABLED` | `true` | Enable or disable the service |

## Examples

```bash
export AWS_ENDPOINT_URL=http://localhost:4566

aws qbusiness create-application --display-name helpdesk
aws qbusiness get-application --application-id <application-id>
aws qbusiness list-applications
aws qbusiness delete-application --application-id <application-id>
```
