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

### Requirement: Unified task protocol
The service SHALL expose `/agent/messages` and `/agent/messages/stream` as the only model-facing task endpoints. Both endpoints SHALL accept `CHAT`, `TEACHING_PLAN`, and `RESOURCE_DISCOVERY` requests and return the shared task metadata plus the task-specific structured result.

#### Scenario: Old LLM route is removed
- **WHEN** a client calls any removed `/llm/*` model-facing route
- **THEN** FastAPI returns `404` and does not invoke a model or persist a new Agent turn

#### Scenario: Teaching-plan task
- **WHEN** an authenticated internal caller sends a valid `TEACHING_PLAN` request to `/agent/messages`
- **THEN** the response includes the persisted `threadId`, `taskType=TEACHING_PLAN`, and a validated `teachingPlan` object

#### Scenario: Resource-discovery task stream
- **WHEN** an authenticated internal caller sends a valid `RESOURCE_DISCOVERY` request to `/agent/messages/stream`
- **THEN** the SSE stream uses the unified run/model/token/final/done event family and the final response includes `resourceDiscovery`

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
