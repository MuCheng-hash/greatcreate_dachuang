## ADDED Requirements

### Requirement: Teacher submits school-centered generation request
The system SHALL allow a teacher-facing client to request a teaching activity plan for an approved school by providing school, grade, teaching theme, activity type, duration, and whether practical activity is required.

#### Scenario: Valid generation request
- **WHEN** the client submits a generation request with an approved school id, grade, theme, activity type, duration, and practice requirement
- **THEN** the system returns a structured teaching plan response or a clear degraded response if generation dependencies are unavailable

#### Scenario: Unknown or unavailable school
- **WHEN** the client submits a generation request for a missing, inactive, or unapproved school
- **THEN** the system rejects the request and does not call the LLM service

### Requirement: Business service assembles approved RAG context
The business service SHALL assemble generation context from approved school data, approved school-resource relationships, approved nearby resources, existing approved teaching activity plans, available content chunks, source records, and optional graph facts.

#### Scenario: School has approved resources
- **WHEN** a generation request targets a school with approved resource relationships
- **THEN** the context includes resource names, categories, distances, travel modes, educational values, target grades, activity suggestions, and safety notes where available

#### Scenario: Content chunks and source records exist
- **WHEN** related content chunks or entity source records exist for selected context entities
- **THEN** the context includes citation candidates that identify the supporting entity, source, excerpt, or chunk

#### Scenario: Graph facts are unavailable
- **WHEN** Neo4j is unavailable or no graph facts exist for the school context
- **THEN** the generation flow continues using MySQL school, resource, activity plan, and content chunk context

### Requirement: LLM service generates structured teaching plan
The LLM service SHALL accept a bounded context package from business-service and return a structured teaching plan JSON object rather than only free-form prose.

#### Scenario: Successful structured generation
- **WHEN** the LLM service receives a valid teaching plan context package
- **THEN** it returns sections for teaching theme, applicable grade, teaching objectives, resource basis, activity flow, preparation, field tasks, safety notes, post-class reflection, evaluation method, citations, and follow-up suggestions

#### Scenario: LLM provider unavailable
- **WHEN** configured model credentials or network access are unavailable
- **THEN** the LLM service returns a local degraded structured response and marks the generation status as degraded

### Requirement: Citations are traceable to provided context
The system SHALL return citations that are traceable to context assembled by business-service, and SHALL NOT expose unsupported model-generated citations as trusted sources.

#### Scenario: Model returns supported citation identifiers
- **WHEN** the model response cites identifiers present in the supplied context package
- **THEN** the system returns those citations with display title, source type, excerpt or description, and related entity where available

#### Scenario: Model returns unsupported citation identifiers
- **WHEN** the model response cites identifiers that were not present in the supplied context package
- **THEN** the business service removes or replaces those citations with known citation candidates before returning the response

### Requirement: Frontend renders teaching plan and citations
The frontend SHALL provide a school-detail teaching plan generation panel and render the structured generated result with visible citation sources.

#### Scenario: Teacher generates a plan from school detail
- **WHEN** a teacher opens a school detail view and submits grade, theme, activity type, duration, and practice requirement
- **THEN** the frontend displays the generated plan sections and citation list without requiring the teacher to inspect raw JSON

#### Scenario: Generation fails gracefully
- **WHEN** the generation request fails or returns degraded status
- **THEN** the frontend displays an understandable degraded-state message and preserves the teacher's input values

### Requirement: Generated plan can be reviewed before persistence
The system SHALL allow generated teaching plans to be reviewed by the user before being saved into the teaching activity plan management data.

#### Scenario: User reviews generated result
- **WHEN** a generated teaching plan is returned
- **THEN** the user can inspect the content and citations before any new teaching activity plan record is created

#### Scenario: User confirms saving generated result
- **WHEN** the user confirms that a generated plan should be saved
- **THEN** the system creates a draft teaching activity plan associated with the school and selected resource context
