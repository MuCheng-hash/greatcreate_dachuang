## ADDED Requirements

### Requirement: List owned assistant conversations
The system SHALL list active intelligent-assistant conversations owned by the authenticated account in descending order of last update, including a title, message preview, message count, and timestamps.

#### Scenario: View conversation history
- **WHEN** an authenticated school user opens the intelligent assistant
- **THEN** the system displays up to the configured limit of that user's active CHAT conversations with the most recently updated first

#### Scenario: No prior conversations
- **WHEN** the authenticated user has no active CHAT conversations
- **THEN** the history area displays an empty state and the user can start a new conversation

### Requirement: Isolate conversation history
The system MUST derive the history owner from the authenticated user and MUST NOT list, load, continue, or archive a thread owned by another account or outside the authenticated school scope.

#### Scenario: Attempt cross-account access
- **WHEN** an authenticated user requests a thread identifier owned by another account
- **THEN** the system returns not found or access denied without exposing thread metadata or messages

### Requirement: Restore conversation messages
The system SHALL allow an authenticated owner to load the ordered user and assistant messages of an active CHAT conversation into the intelligent-assistant interface.

#### Scenario: Open a history item
- **WHEN** the user selects an owned conversation from history
- **THEN** the interface replaces the current transcript with the stored messages in chronological order and marks that thread as active

### Requirement: Continue a historical conversation
The system SHALL send subsequent questions from a restored conversation with its original thread identifier so the Agent uses the stored context.

#### Scenario: Continue restored conversation
- **WHEN** the user opens a history item and sends a new question
- **THEN** the request carries the restored thread identifier and the new exchange is appended to that conversation

### Requirement: Start a new conversation
The system SHALL let the user leave the current conversation and start an empty conversation without deleting existing history.

#### Scenario: Start new conversation
- **WHEN** the user activates the new-conversation command
- **THEN** the current transcript and thread identifier are cleared while previous conversations remain available in history

### Requirement: Archive a conversation
The system SHALL allow the authenticated owner to archive a conversation, retain its stored messages, and exclude it from the active history list.

#### Scenario: Archive current conversation
- **WHEN** the user archives the currently open owned conversation
- **THEN** it disappears from active history and the interface switches to a new empty conversation
