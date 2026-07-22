## ADDED Requirements

### Requirement: Server-managed conversation threads
The system SHALL identify each Agent conversation with an opaque `threadId` and SHALL persist its messages independently of browser storage.

#### Scenario: Continue an existing conversation
- **WHEN** an authenticated owner sends a second message with a previously returned `threadId`
- **THEN** the Agent receives the bounded prior conversation context and returns the same `threadId`

#### Scenario: Start a conversation without an identifier
- **WHEN** an authenticated owner sends a message without a `threadId`
- **THEN** the system creates a new thread, persists the turn, and returns its opaque `threadId`

### Requirement: Owner and scope isolation
The system MUST bind every thread to an authoritative owner and business scope and MUST reject access using a different owner or scope.

#### Scenario: Cross-owner thread replay
- **WHEN** one owner supplies a `threadId` created by another owner
- **THEN** the system returns a not-found or forbidden response without exposing conversation content

### Requirement: Durable conversation recovery
The system SHALL retain threads, raw user and assistant messages, summaries, and execution metadata across service restarts.

#### Scenario: Resume after restart
- **WHEN** the Agent service restarts and the owner sends a message to an existing thread
- **THEN** the service restores the thread from persistent storage and continues the conversation

### Requirement: Bounded context-window management
The system SHALL enforce a configurable model-input budget by retaining recent turns, compacting older turns into a rolling summary, and limiting tool output included in the prompt.

#### Scenario: Conversation exceeds the configured budget
- **WHEN** stored conversation content is larger than the configured input budget
- **THEN** older turns are summarized or omitted from model input while raw messages remain persisted and recent turns remain available

### Requirement: Explicit degraded conversation behavior
The system SHALL identify responses produced without a configured or available Agent model as degraded.

#### Scenario: Model credentials are absent
- **WHEN** a conversation message is submitted and no Agent model is configured
- **THEN** the system returns a bounded answer based only on trusted supplied context with `status` set to `degraded`
