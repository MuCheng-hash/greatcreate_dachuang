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
The system SHALL provide a multi-sheet XLSX template and a preview-confirm workflow with row-level validation, duplicate detection, and cross-sheet relation validation.

#### Scenario: A relation references an invalid entity code
- **WHEN** a workbook preview is requested
- **THEN** the relation row is reported invalid and confirmation does not persist it
