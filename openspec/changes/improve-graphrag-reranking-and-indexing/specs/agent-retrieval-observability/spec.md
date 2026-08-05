## Purpose

让开发和管理人员能够确认内部 Dense、Lexical、Graph 和重排是否执行，同时不把内部检索伪装成 LLM 工具调用。

## ADDED Requirements

### Requirement: Produce a bounded retrieval trace
The retriever SHALL report intent, Graph routing and status, channel candidate counts, retrieval methods, and at most eight ranked score summaries without exposing prompts or private fields.

#### Scenario: Graph is intentionally skipped
- **WHEN** the intent is teaching suggestion or resource explanation
- **THEN** the trace reports `needGraph=false` and `graphStatus=skipped`

### Requirement: Expose detailed trace only for debug streaming
The question-answering SSE endpoint SHALL include detailed retrieval trace data in the retrieval completion event only when the optional debug flag is true.

#### Scenario: A normal teacher asks a question
- **WHEN** `debug` is absent or false
- **THEN** the existing SSE event shape remains usable without detailed candidate scores

### Requirement: Persist sanitized retrieval metadata
The Python Agent SHALL copy the bounded retrieval summary into existing LLM observability metadata and SHALL prioritize the jointly ranked evidence list for prompt context.

#### Scenario: Admin reviews a completed session
- **WHEN** the model Trace contains retrieval metadata
- **THEN** the admin timeline displays retrieval and Graph status separately from the count of actual tool executions
