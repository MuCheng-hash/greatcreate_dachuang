## 1. Persistence and configuration

- [x] 1.1 Add the discovery run, candidate, run-item, and approved-resource provider identity schema.
- [x] 1.2 Add Java enums, entities, mappers, configuration properties, and bounded async execution support.

## 2. Discovery and classification services

- [x] 2.1 Implement the server-side AMap search/detail client with validation, deduplication, limits, and private credentials.
- [x] 2.2 Implement cached asynchronous school discovery runs, candidate persistence, polling, and school-scoped authorization.
- [x] 2.3 Add the structured LLM classification endpoint and business-service client validation with unanalyzed fallback.

## 3. Review and resource details

- [x] 3.1 Add teacher-facing discovery run, candidate detail, and approved-resource detail APIs.
- [x] 3.2 Add administrator candidate listing, force refresh, approve, reject, and reopen APIs with transactional conversion.

## 4. User interfaces

- [x] 4.1 Extend the teacher map with radius selection, automatic polling, distinct marker layers, and click-through details.
- [x] 4.2 Extend the administrator workspace with candidate filters, detail review, edit-before-approve, rejection, reopen, and force refresh.

## 5. Verification

- [x] 5.1 Add focused backend and LLM tests for cache, failures, response validation, authorization, and approval deduplication.
- [x] 5.2 Add frontend tests for discovery state and marker/detail behavior, then run backend, frontend, and Python test suites.
