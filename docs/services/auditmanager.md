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
| `CreateAssessment` | Create an assessment from a custom framework; `ACTIVE` immediately |
| `GetAssessment` | Get an assessment with its control sets and controls |
| `UpdateAssessment` | Change an assessment's name, description, scope, roles or report destination |
| `DeleteAssessment` | Delete an assessment with its evidence and reports |
| `ListAssessments` | List assessments, filtered by status |
| `UpdateAssessmentStatus` | Set an assessment `ACTIVE` or `INACTIVE` |
| `UpdateAssessmentControlSetStatus` | Set a control set's review status |
| `UpdateAssessmentControl` | Set a control's status or add a comment |
| `BatchImportEvidenceToAssessmentControl` | Import manual evidence into today's evidence folder |
| `GetEvidenceFoldersByAssessment` | List an assessment's evidence folders |
| `GetEvidenceFoldersByAssessmentControl` | List one control's evidence folders |
| `GetEvidenceFolder` | Get an evidence folder |
| `GetEvidenceByEvidenceFolder` | List the evidence in a folder |
| `GetEvidence` | Get one piece of evidence |
| `AssociateAssessmentReportEvidenceFolder` | Include a folder's evidence in assessment reports |
| `DisassociateAssessmentReportEvidenceFolder` | Leave a folder's evidence out of assessment reports |
| `BatchAssociateAssessmentReportEvidence` | Include evidence in assessment reports |
| `BatchDisassociateAssessmentReportEvidence` | Leave evidence out of assessment reports |
| `BatchCreateDelegationByAssessment` | Delegate control sets to roles |
| `BatchDeleteDelegationByAssessment` | Remove delegations |
| `GetDelegations` | List delegations across assessments |
| `CreateAssessmentReport` | Create an assessment report; `COMPLETE` immediately |
| `DeleteAssessmentReport` | Delete an assessment report |
| `GetAssessmentReportUrl` | Return the report's location in the report destination bucket |
| `ListAssessmentReports` | List assessment reports |
| `RegisterAccount` | Turn Audit Manager on for the account |
| `DeregisterAccount` | Turn Audit Manager off for the account |
| `GetAccountStatus` | Report whether the account is registered |
| `ListTagsForResource` | Lists the tags of a framework or control (`GET /tags/{resourceArn}`) |
| `TagResource` | Adds tags to a framework or control (`POST /tags/{resourceArn}`) |
| `UntagResource` | Removes tag keys from a framework or control (`DELETE /tags/{resourceArn}`) |
<!-- floci:actions:end -->

Floci holds no AWS standard frameworks or controls. Listing the `Standard` or `Core` type
returns an empty list. A framework control set may name a control id that Floci does not hold. The framework
keeps that id and returns the control with only its `id`; a control Floci holds is returned in
full.

An assessment copies its framework's control sets, using each control set's name as its id, and
starts every control `UNDER_REVIEW`. Floci collects no evidence automatically:
`BatchImportEvidenceToAssessmentControl` files manual evidence into one folder per control per
day, and the folder and control counts follow it. Tags work on assessment ARNs as well.

## Not emulated

- Automated evidence collection, insights and change logs
- Framework share requests, notifications and settings
- Organization admin accounts

## Configuration

| Variable | Default | Description |
|---|---|---|
| `FLOCI_SERVICES_AUDITMANAGER_ENABLED` | `true` | Enable or disable AWS Audit Manager |
