## Purpose

让智能问答的稳定最终结果能够随会话持久化，并在用户切换或重新打开历史会话时一致恢复，同时避免永久保存临时流事件和原始业务上下文。

## ADDED Requirements

### Requirement: Persist a versioned assistant response snapshot
The system SHALL persist an optional versioned response snapshot with every completed Agent assistant message. The snapshot SHALL include final retrieval and generation status, retrieval methods, validated citations, related resources, follow-up questions, effective model metadata, sanitized tool execution summaries, context compaction state, and applied-memory metadata when present.

#### Scenario: Completed streamed answer is stored
- **WHEN** a streamed Agent answer reaches its final event and is appended to a conversation
- **THEN** the stored assistant message contains a versioned response snapshot matching the stable final result shown to the user

#### Scenario: Completed non-stream answer is stored
- **WHEN** a non-stream Agent answer is appended to a conversation
- **THEN** the same response snapshot contract is stored without requiring a second model request

### Requirement: Preserve validated citation fallbacks
When a grounded answer contains trusted evidence but the model does not select a citation, the system SHALL attach up to five validated citations in a stable evidence order before persisting the final snapshot.

#### Scenario: Model omits citations
- **WHEN** trusted retrieval evidence exists and the model returns no citation identifiers
- **THEN** the live answer and stored snapshot contain the same first five validated evidence citations

### Requirement: Restore stable historical presentation
The history interface SHALL restore answer citations, retrieval and generation status, retrieval methods, effective model, applied-memory count, follow-up questions, and a collapsed execution summary from the stored snapshot.

#### Scenario: Switch away and return to a conversation
- **WHEN** a user opens conversation A, opens conversation B, and then reopens conversation A
- **THEN** conversation A displays the same stable sources, statuses, model, memory use, follow-ups, and sanitized tool summary as its completed result

#### Scenario: Archived conversation is opened
- **WHEN** an authorized user opens an archived conversation containing response snapshots
- **THEN** the stable presentation is restored in read-only mode

### Requirement: Keep historical snapshots sanitized
The system MUST NOT persist token events, running-state labels, raw tool outputs, prompt text, secrets, or raw school and business context inside the response snapshot.

#### Scenario: Tool event contains raw output
- **WHEN** a live tool event includes a raw output summary or business-context JSON
- **THEN** the stored snapshot contains only the tool name, final status, and duration

### Requirement: Remain compatible with legacy messages
The history interface SHALL continue to open messages that do not contain a response snapshot and SHALL use only trustworthy legacy metadata without fabricating missing citation details.

#### Scenario: Legacy message has citation IDs only
- **WHEN** an old assistant message contains only citation identifiers and no response snapshot
- **THEN** its answer, follow-ups, applied-memory metadata, and available tool summary remain readable while unavailable source details are omitted

### Requirement: Preserve existing access isolation
Response snapshots SHALL be returned only through the existing account-owned and school-scoped conversation access checks.

#### Scenario: Foreign account requests rich history
- **WHEN** another account or school requests a thread containing response snapshots
- **THEN** access is rejected under the same rules as the thread messages themselves
