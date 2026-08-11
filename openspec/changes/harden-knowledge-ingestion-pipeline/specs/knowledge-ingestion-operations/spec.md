## ADDED Requirements

### Requirement: Administrators can inspect ingestion details
The document detail API and management UI SHALL display lifecycle status, current node, attempt count, node results, degradation reasons, image results, chunk count, index collection and index version.

#### Scenario: Administrator opens a degraded document
- **WHEN** an administrator opens details for a degraded import
- **THEN** the UI identifies completed, degraded and failed processing steps with their sanitized messages

### Requirement: Administrators can recover and inspect imported artifacts
The system SHALL preserve the existing retry endpoint, accept an optional permitted restart node, and provide a protected download endpoint for normalized Markdown after conversion.

#### Scenario: Retry from document details
- **WHEN** an administrator retries a failed document without a restart node
- **THEN** the job is requeued from validation and its previous failure remains available in its task history
