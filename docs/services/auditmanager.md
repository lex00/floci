# AWS Audit Manager

**Protocol:** REST JSON

**Endpoint:** `http://localhost:4566`

Floci stores custom assessment frameworks and custom controls for local SDK, CLI, and
Terraform workflows (`aws_auditmanager_framework`, `aws_auditmanager_control`). Resources are
isolated by account and region and use the configured Floci storage mode.

## Supported Actions

<!-- floci:actions:start -->
| Action | Description |
| --- | --- |
| `CreateAssessmentFramework` | Creates a custom framework from named control sets (`POST /assessmentFrameworks`) |
| `GetAssessmentFramework` | Returns a framework with its control sets and controls (`GET /assessmentFrameworks/{frameworkId}`) |
| `UpdateAssessmentFramework` | Replaces the name, description, compliance type and control sets (`PUT /assessmentFrameworks/{frameworkId}`) |
| `DeleteAssessmentFramework` | Deletes a custom framework (`DELETE /assessmentFrameworks/{frameworkId}`) |
| `ListAssessmentFrameworks` | Lists framework metadata by `frameworkType`, with pagination (`GET /assessmentFrameworks`) |
| `CreateControl` | Creates a custom control with its mapping sources (`POST /controls`) |
| `GetControl` | Returns a control (`GET /controls/{controlId}`) |
| `UpdateControl` | Replaces a control's fields and mapping sources (`PUT /controls/{controlId}`) |
| `DeleteControl` | Deletes a custom control (`DELETE /controls/{controlId}`) |
| `ListControls` | Lists control metadata by `controlType`, with pagination (`GET /controls`) |
| `ListTagsForResource` | Lists the tags of a framework or control (`GET /tags/{resourceArn}`) |
| `TagResource` | Adds tags to a framework or control (`POST /tags/{resourceArn}`) |
| `UntagResource` | Removes tag keys from a framework or control (`DELETE /tags/{resourceArn}`) |
<!-- floci:actions:end -->

Floci holds no AWS standard frameworks or controls. Listing the `Standard` or `Core` type
returns an empty list. A framework control set may name a control id that Floci does not hold. The framework
keeps that id and returns the control with only its `id`; a control Floci holds is returned in
full.

Assessments and evidence collection are not implemented.

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AUDITMANAGER_ENABLED` | `true` | Enable or disable AWS Audit Manager |
