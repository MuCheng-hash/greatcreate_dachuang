## 1. LLM model catalog and selection

- [x] 1.1 Add stable model IDs, sanitized catalog output, and request-level attempt ordering to the model gateway
- [x] 1.2 Add optional modelId validation to agent request schemas and expose the LLM model catalog endpoint
- [x] 1.3 Apply selected model ordering to chat and structured teaching-plan runtime paths while preserving fallback metadata
- [x] 1.4 Add Python tests for catalog sanitization, default selection, explicit selection, invalid IDs, and fallback ordering

## 2. Business service integration

- [x] 2.1 Add model catalog response and modelId request fields to Java DTOs and the LLM runtime client
- [x] 2.2 Expose the authenticated business model catalog endpoint and forward modelId for question-answering and teaching-plan generation
- [x] 2.3 Add Java controller and service tests for model listing and request forwarding

## 3. Frontend model controls

- [x] 3.1 Add shared frontend model types and API loading behavior with a system-default fallback
- [x] 3.2 Add an accessible model selector and effective-model feedback to the intelligent assistant page
- [x] 3.3 Add an accessible model selector and effective-model feedback to the teaching plan page
- [x] 3.4 Add frontend tests for loading, selecting, forwarding, unavailable catalogs, and effective-model display

## 4. Verification

- [x] 4.1 Run focused Python, Java, and frontend test suites and resolve regressions
- [x] 4.2 Validate the OpenSpec change and document configuration and compatibility behavior
