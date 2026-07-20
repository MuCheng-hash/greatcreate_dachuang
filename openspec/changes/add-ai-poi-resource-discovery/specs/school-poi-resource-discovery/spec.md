## ADDED Requirements

### Requirement: Automatic cached POI discovery
The system SHALL automatically request a school-scoped discovery snapshot when the teacher map opens, using a selectable radius of 1, 3, 5, or 10 kilometers and a default of 5 kilometers.

#### Scenario: Fresh cache is reused
- **WHEN** a teacher opens the map and a successful snapshot for the school and radius is less than 24 hours old
- **THEN** the system returns that snapshot without calling AMap or the LLM again

#### Scenario: Missing cache starts a background run
- **WHEN** no fresh snapshot exists
- **THEN** the system creates or reuses one active background run while approved map resources remain immediately available

### Requirement: Server-side provider integration
The system SHALL call AMap Web Service from the business service using a server-only credential and SHALL retain at most the nearest 50 unique POIs identified primarily by AMap POI ID.

#### Scenario: Provider credential remains private
- **WHEN** the browser requests map configuration or discovery results
- **THEN** the response does not contain the AMap Web Service key

#### Scenario: Provider failure preserves approved data
- **WHEN** AMap times out, rejects the request, or returns invalid data
- **THEN** the discovery run reports failure and the teacher can still use approved resources and the last successful snapshot

### Requirement: Evidence-bound LLM classification
The system SHALL classify only POIs supplied by AMap and SHALL validate returned POI IDs, resource categories, confidence values, and structured fields before persistence.

#### Scenario: Valid relevant candidate
- **WHEN** the LLM marks a supplied POI relevant with confidence at least 0.60
- **THEN** the teacher map exposes the candidate with its AI category, rationale, themes, grade guidance, activity suggestion, and verification notes

#### Scenario: LLM is unavailable
- **WHEN** POI discovery succeeds but LLM classification fails or is not configured
- **THEN** the system exposes raw POIs as unverified and unanalyzed without inventing educational claims

#### Scenario: Model returns an unknown POI
- **WHEN** the LLM response references a POI ID absent from the input batch
- **THEN** the system discards that classification result

### Requirement: Distinct map details and verification state
The teacher map SHALL distinguish the school, approved resources, AI-relevant candidates, and unanalyzed candidates and SHALL provide click-through details for system-owned markers.

#### Scenario: Approved resource is clicked
- **WHEN** a teacher clicks an approved resource marker
- **THEN** the system shows its stored factual and educational details and source status

#### Scenario: Candidate is clicked
- **WHEN** a teacher clicks a candidate marker
- **THEN** the system shows provider details, distance, AI judgment when available, last checked time, and an explicit unverified warning

### Requirement: Administrator review conversion
The system SHALL allow only a platform administrator to approve, reject, reopen, or force-refresh discovery candidates.

#### Scenario: Candidate is approved
- **WHEN** an administrator approves a candidate
- **THEN** one transaction creates or reuses the provider-identified formal resource, creates or reuses an approved school-resource relation, and marks the candidate approved

#### Scenario: Candidate is rejected and rediscovered
- **WHEN** a rejected provider POI appears in a later discovery run
- **THEN** its rejected decision remains unchanged and it is hidden from the teacher map

### Requirement: Candidate isolation
The system MUST exclude every unapproved discovery candidate from teaching-plan generation, RAG citations, and school assistant resource context.

#### Scenario: Teacher generates a plan with visible candidates
- **WHEN** the map displays one or more unapproved candidates and the teacher generates a teaching plan
- **THEN** only approved resources are included in generation context and citations

### Requirement: School-scoped authorization
The system SHALL allow a school account to access discovery data only for its bound school, while a platform administrator MAY access any school.

#### Scenario: Teacher requests another school
- **WHEN** a school account requests another school's discovery run, candidate detail, or approved-resource detail
- **THEN** the system rejects the request without invoking external services
