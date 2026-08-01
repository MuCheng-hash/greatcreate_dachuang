## 1. Python memory foundation

- [x] 1.1 Add failing tests for memory settings, scoped CRUD, state transitions, sensitive-content rejection, lifecycle cleanup, conflict replacement, and audit redaction.
- [x] 1.2 Add memory schemas, content policy, settings, and a replaceable SQLite memory repository with startup/lazy cleanup.
- [x] 1.3 Add failing FastAPI tests for all memory setting and memory management routes, owner/school isolation, validation errors, and aggregate metrics.
- [x] 1.4 Implement token-protected FastAPI memory routes and non-content aggregate metrics without changing existing Agent routes.

## 2. Agent runtime integration

- [x] 2.1 Add failing runtime tests for explicit remember instructions, pending inferred candidates, degraded-model behavior, bounded recall, conflict priority, and resource-discovery exclusion.
- [x] 2.2 Extend model and HTTP response schemas with optional memory candidates and applied-memory metadata while retaining SSE compatibility.
- [x] 2.3 Inject confirmed profile and at most five relevant task memories into chat and teaching-plan prompts within the character budget.
- [x] 2.4 Persist explicit and inferred memories without a second model call, enforce candidate limits and content policy, and suppress inference during degraded/resource-discovery runs.
- [x] 2.5 Add startup and daily cleanup wiring plus prompt instructions that keep memory below current input, trusted facts, citations, and authorization.

## 3. Spring authenticated proxy

- [x] 3.1 Add failing Java tests for login-derived owner/school scope, memory proxy operations, forged-scope rejection, admin content denial, and optional Agent response fields.
- [x] 3.2 Add Java memory DTOs and Agent runtime client methods for settings, CRUD, confirmation, recycle-bin operations, and aggregate metrics.
- [x] 3.3 Expose authenticated `/api/ai/memory-settings` and `/api/ai/memories...` routes that never accept browser-controlled owner or school scope.
- [x] 3.4 Expose administrator aggregate metrics only and verify no memory content is present in management responses or logs.

## 4. Vue memory experience

- [x] 4.1 Add failing Vitest coverage for settings states, core profile fields, custom profile/task memories, pending/active/recycle-bin actions, and assistant candidate behavior.
- [x] 4.2 Add typed frontend memory API helpers and optional `memoryCandidates`/`memoryApplied` Agent response fields.
- [x] 4.3 Build the personal-center memory UI with first-use explanation, feature availability, five core fields, custom memories, lifecycle tabs, and destructive-action confirmations.
- [x] 4.4 Add assistant candidate confirmation/ignore cards and the “本次参考 N 条记忆” indicator without regressing history, voice, image, or SSE behavior.

## 5. Verification and delivery

- [x] 5.1 Run the complete Python test suite and verify the real SQLite schema, cleanup, isolation, injection budget, degraded behavior, and compatibility paths.
- [x] 5.2 Run JDK 21 Maven tests for the business service and inspect the resulting authenticated proxy contract.
- [x] 5.3 Run frontend Vitest, type checking, and production build; fix all regressions.
- [x] 5.4 Start the deploy-order-compatible services with memory initially disabled, then perform real-page acceptance for enable, cross-session use, teaching-plan override, confirmation, disable/re-enable, recycle-bin restore, and isolation where local dependencies permit.
- [x] 5.5 Run strict OpenSpec validation, mark completed tasks accurately, and document configuration, rollout, rollback, and any externally blocked acceptance evidence.
- [x] 5.6 Add a streaming regression test for memory candidates with datetime fields, serialize every SSE payload as JSON-safe data, and verify the repaired live stream without model fallback.
