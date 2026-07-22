## ADDED Requirements

### Requirement: Typed allowlisted tool registry
The Agent SHALL expose only registered tools with typed input schemas and bounded outputs.

#### Scenario: Agent requests an approved resource tool
- **WHEN** the model emits a valid call to an allowlisted resource or retrieval tool
- **THEN** the runtime executes the tool against trusted scope context and returns a bounded observation

#### Scenario: Agent requests an unknown capability
- **WHEN** the model attempts to call an unregistered tool or arbitrary SQL, Cypher, URL, or shell command
- **THEN** the runtime does not execute the requested capability

### Requirement: Bounded planning and execution
The Agent SHALL use a model-driven planning/tool loop with configurable limits on tool rounds, execution time, and tool-output size.

#### Scenario: Tool loop reaches its limit
- **WHEN** the Agent has used the maximum configured tool rounds without completing an answer
- **THEN** execution stops and returns a controlled degraded or incomplete response instead of continuing indefinitely

### Requirement: Authoritative scope injection
The runtime MUST derive owner and school, region, or resource scope from trusted server metadata rather than model-generated tool arguments.

#### Scenario: Prompt attempts scope escalation
- **WHEN** a user asks the model to query another school's data
- **THEN** every tool remains restricted to the scope supplied by the authenticated Spring service

### Requirement: Evidence-bound citations
The runtime SHALL return only citation identifiers present in the trusted citation candidates or retrieved evidence supplied for the invocation.

#### Scenario: Model invents a citation identifier
- **WHEN** the model response contains a citation ID that is absent from available evidence
- **THEN** the system removes the invented citation before returning the response

### Requirement: Auditable tool execution
The system SHALL persist tool name, sanitized arguments, status, duration, and bounded result metadata for each executed tool call.

#### Scenario: Tool completes or fails
- **WHEN** an Agent tool execution finishes
- **THEN** an audit record is associated with the thread without storing credentials or unrestricted sensitive payloads
