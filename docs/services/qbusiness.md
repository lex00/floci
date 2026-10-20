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
- Only applications are implemented. The resources inside an application and chat
  are not.

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
