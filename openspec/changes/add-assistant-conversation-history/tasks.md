## 1. LLM conversation history APIs

- [x] 1.1 Add owner-scoped CHAT thread listing with title, preview, message count, and recent-first ordering to the SQLite repository
- [x] 1.2 Add typed thread summary responses and internal list/detail/archive endpoints with owner and scope validation
- [x] 1.3 Add Python repository and API tests for listing, ordering, message restoration, task filtering, ownership isolation, and archive behavior

## 2. Business service integration

- [x] 2.1 Add Java history summary/detail/message DTOs and AgentRuntimeClient list/detail/archive operations
- [x] 2.2 Add authenticated intelligent-assistant history endpoints that derive owner and school scope from the current user
- [x] 2.3 Add Java tests for history response forwarding, owner derivation, and model-independent archive behavior

## 3. Intelligent assistant history UI

- [x] 3.1 Add frontend history types and load active history when the assistant page opens
- [x] 3.2 Build conversation history navigation with title, preview, date, selected state, empty/loading/error states, and responsive layout
- [x] 3.3 Restore stored messages, continue the selected thread, create a new conversation, and archive an owned conversation
- [x] 3.4 Add frontend tests for history loading, restoration, continuation, new conversation, archive, and unavailable history service

## 4. Verification

- [x] 4.1 Run focused Python, Java, frontend tests and production builds
- [x] 4.2 Validate the OpenSpec change and document persistence, privacy, and compatibility behavior
