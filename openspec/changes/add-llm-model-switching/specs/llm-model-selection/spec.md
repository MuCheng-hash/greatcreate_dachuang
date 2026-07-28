## ADDED Requirements

### Requirement: Discover selectable models
The system SHALL expose an authenticated, sanitized catalog of configured chat models containing a stable model identifier, display name, provider, model name, and default indicator, without exposing API keys or connection URLs.

#### Scenario: Load configured models
- **WHEN** an authenticated teacher opens the teaching plan or intelligent assistant page
- **THEN** the client can load the configured selectable models and identify the default model

#### Scenario: Model catalog is unavailable
- **WHEN** the model catalog request fails
- **THEN** the client retains a system-default option and the core feature remains usable without an explicit model selection

### Requirement: Select a model per request
The system SHALL allow teaching plan generation and intelligent question answering requests to carry an optional configured model identifier, and SHALL use the default model chain when the identifier is absent.

#### Scenario: Generate teaching plan with selected model
- **WHEN** a teacher selects an available model and generates a teaching plan
- **THEN** the selected model is attempted first for that generation request

#### Scenario: Ask question with selected model
- **WHEN** a teacher selects an available model and sends an intelligent assistant message
- **THEN** the selected model is attempted first for that message without changing earlier conversation messages

#### Scenario: Use default model
- **WHEN** a supported request does not contain a model identifier
- **THEN** the configured default model is attempted first as before this change

### Requirement: Validate model selection securely
The LLM service MUST resolve model identifiers only against its configured model catalog and MUST reject unknown or disabled identifiers without accepting client-supplied credentials or connection settings.

#### Scenario: Reject unknown model
- **WHEN** a request contains a model identifier that is not in the configured catalog
- **THEN** the request is rejected as a client validation error before invoking any model

### Requirement: Preserve fallback behavior
The system SHALL retain configured fallback behavior after a model is selected by trying the selected model first and then each remaining configured model at most once.

#### Scenario: Selected model fails
- **WHEN** the selected model fails or returns an invalid structured response
- **THEN** the system attempts the remaining configured models in order and emits fallback progress for streaming requests

### Requirement: Report the effective model
The system SHALL report the provider and model that actually produced the response in streaming completion events and final teaching plan or intelligent assistant responses.

#### Scenario: Fallback model produces result
- **WHEN** a selected model fails and a fallback model completes the request
- **THEN** the result identifies the fallback model rather than the originally selected model
