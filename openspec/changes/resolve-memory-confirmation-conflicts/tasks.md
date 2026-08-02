## 1. Contract and specification

- [x] 1.1 Add the conflict-resolution capability specification and revise the cross-session memory activation requirement.
- [x] 1.2 Add failing Python, Java, and Vue regression tests for explicit conflict decisions and stale-list prevention.

## 2. Python memory service

- [x] 2.1 Normalize field aliases and centralize scoped conflict/duplicate discovery in `MemoryRepository`.
- [x] 2.2 Add confirmation preview and atomic confirm-or-conflict behavior, including explicit replacement, keep-old recycle, audit metadata, and concurrent recheck.
- [x] 2.3 Apply the same no-silent-overwrite guard to restore, manual create, and update-field activation paths.
- [x] 2.4 Expose protected FastAPI preview and extended mutation contracts with stable 409 payloads.

## 3. Spring authenticated proxy

- [x] 3.1 Add DTOs and `AgentRuntimeClient` methods for preview, conflict response, and `replaceConflicts` forwarding.
- [x] 3.2 Expose authenticated proxy endpoints derived only from the logged-in owner and school scope.
- [x] 3.3 Add Java tests for forwarding, 409 no-side-effect behavior, and forged-scope isolation.

## 4. Vue conflict experience

- [x] 4.1 Add a reusable project-native memory conflict dialog with replace, keep-old, cancel, keyboard and request-in-flight behavior.
- [x] 4.2 Wire `AssistantView` candidate confirmation to preview, resolution choices, feedback, and candidate removal.
- [x] 4.3 Wire the memory center activation routes to the same resolution flow and refresh all three state lists after mutation.
- [x] 4.4 Add Vitest coverage for 28-year-old to 26-year-old replacement, keep-old recycle, cancellation, duplicate, and immediate list consistency.

## 5. Verification and delivery

- [x] 5.1 Run focused and complete Python tests, JDK 21 Maven tests, Vitest, type checking, and production frontend build.
- [ ] 5.2 Start the local services and validate with a real teacher account that no refresh is needed for consistent pending, active, and recycle-bin lists.
- [x] 5.3 Validate the OpenSpec artifacts and mark completed tasks accurately (the OpenSpec CLI is unavailable in this environment; artifacts were manually checked in full).
