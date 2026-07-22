## ADDED Requirements

### Requirement: FastAPI model service
The LLM service SHALL run as a FastAPI ASGI application with validated request and response models and an application health endpoint.

#### Scenario: Invalid conversation request
- **WHEN** a caller submits a conversation message with missing required owner, scope, or message fields
- **THEN** FastAPI returns a structured validation error without invoking the model

### Requirement: Stateful message endpoint
The service SHALL expose a message endpoint that accepts trusted owner/scope context and returns `threadId`, answer, status, citations, related resources, follow-up questions, and tool metadata.

#### Scenario: Successful Agent turn
- **WHEN** a valid message is processed by the Agent runtime
- **THEN** the endpoint returns the persisted conversation identifier and a structured Agent response

### Requirement: Legacy endpoint compatibility
The service SHALL preserve the existing town, school, teaching-plan, and resource-discovery route paths and their top-level response shapes during migration.

#### Scenario: Existing teaching-plan caller
- **WHEN** an existing client calls `/llm/teaching-plan/generate` with a valid legacy payload
- **THEN** the FastAPI service returns the compatible structured teaching-plan response

### Requirement: Asynchronous model integration
The service SHALL use a configured LangChain chat-model adapter for OpenAI-compatible providers and SHALL not perform blocking raw `urllib` model requests inside async request handlers.

#### Scenario: Configured OpenAI-compatible provider
- **WHEN** model URL, key, and model name are configured
- **THEN** the runtime invokes the provider through the LangChain chat-model abstraction with configured timeout and bounded retries

### Requirement: Configurable cross-origin policy
The service MUST NOT enable unrestricted cross-origin access by default.

#### Scenario: No CORS origins configured
- **WHEN** the service starts without an allowed-origin configuration
- **THEN** it does not install a wildcard browser CORS policy
