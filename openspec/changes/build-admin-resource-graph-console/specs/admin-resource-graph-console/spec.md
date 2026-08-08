## Purpose

让平台管理员可维护并发布具备来源、媒体、关系和检索投影的思政资源与图谱实体。

## ADDED Requirements

### Requirement: Show a consistent administrative overview
The system SHALL expose authenticated administrators with resource, school, teacher, student, teaching-plan, completed question, and RAG index metrics.

#### Scenario: Agent observability is temporarily unavailable
- **WHEN** the dashboard upstream cannot be reached
- **THEN** database and RAG metrics remain available and the question metric reports an unavailable state

### Requirement: Govern catalog publication
The system SHALL only publish approved active resources, graph entities, and whitelisted relations. Editing a published record SHALL return it to pending review and logical deletion SHALL deactivate it.

#### Scenario: An approved site is edited
- **WHEN** an administrator changes its public content
- **THEN** the site becomes pending and is excluded from graph projection until reapproved

### Requirement: Import catalog data safely
The system SHALL provide a multi-sheet XLSX template and a preview-confirm workflow with row-level validation, duplicate detection, and cross-sheet relation validation. The resource worksheet SHALL expose resource code, resource name, resource type, administrative region, address, longitude, latitude, introduction, education value, data source, and target grade fields, while continuing to accept legacy column aliases and reordered columns.

Resource rows SHALL require code, name, type, administrative region, address, longitude, latitude, introduction, education value, data source, and target grade. Administrative region input SHALL accept an existing region ID, an unambiguous region name, or an adcode. Longitude SHALL be within -180 to 180 and latitude SHALL be within -90 to 90.

The preview SHALL report duplicates found in the workbook or in the existing catalog by entity type and code. Confirmation SHALL repeat the catalog duplicate check before creating an entity. Valid relation rows SHALL resolve endpoints by workbook code or existing catalog code, SHALL use only whitelisted directions and relation types, and SHALL be persisted without public projection until both endpoints are approved and active.

#### Scenario: A relation references an invalid entity code
- **WHEN** a workbook preview is requested
- **THEN** the relation row is reported invalid and confirmation does not persist it

#### Scenario: Resource columns are reordered or use legacy aliases
- **WHEN** a resource worksheet uses supported column names in a different order
- **THEN** preview maps values by header name, preserves the resource fields, and does not depend on fixed column positions

#### Scenario: A resource has an invalid coordinate or region
- **WHEN** a resource row contains a malformed/out-of-range coordinate or an unknown/ambiguous administrative region
- **THEN** the row is reported invalid with a field-specific message and cannot be confirmed

#### Scenario: A resource code already exists in the catalog
- **WHEN** preview or confirmation encounters an existing entity with the same entity type and code
- **THEN** the row is reported duplicate and no second entity is created

#### Scenario: A valid relation references pending entities
- **WHEN** confirmation imports a relation whose endpoints exist but are not yet approved
- **THEN** the relation is stored, no public graph edge is projected, and the edge becomes eligible for projection after both endpoints are approved and active
