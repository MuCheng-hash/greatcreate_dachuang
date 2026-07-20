## Context

The repository already separates the platform into `business-service`, `llm-service`, `data`, `scripts`, and `docs`. The business service manages MySQL data, school and resource admin workflows, map aggregation, static frontend pages, and Neo4j graph queries. The LLM service is currently independent but only returns local template responses for school and town explanation/ask endpoints.

The next product milestone requires a school-centered demonstration flow: a teacher selects a rural school, reviews nearby ideological education resources, enters grade/theme/activity inputs, generates a structured teaching plan, and sees citation sources. The existing frontend currently calls `llm-service` directly, which is workable for a prototype but makes retrieval, citation control, permission handling, and audit logging difficult.

## Goals / Non-Goals

**Goals:**

- Provide a teacher-facing teaching plan generation flow centered on one approved school.
- Keep `business-service` responsible for context assembly from MySQL, Neo4j, and source records.
- Keep `llm-service` responsible for prompt execution and structured natural language generation.
- Return stable structured output that the frontend can render without parsing free-form prose.
- Return citations that are traceable to approved resources, content chunks, or source records.
- Support local demonstration even when Neo4j, vector search, or real LLM credentials are unavailable.

**Non-Goals:**

- Full Agent planning or multi-step autonomous tool orchestration.
- Voice interaction.
- Route planning.
- Graph visualization beyond textual graph facts or relationship chains.
- Replacing existing school/resource/admin CRUD.
- Requiring a vector database in the first implementation.

## Decisions

### Decision 1: Frontend calls business-service for generation

Teacher-facing generation requests will go to a new business-service endpoint, such as `POST /api/ai/teaching-plans/generate`. Business-service will load the school, nearby resources, existing activity plans, content chunks, source records, and optional graph facts, then call llm-service with a bounded internal payload.

Rationale: business-service already owns business data, approval status, school-resource relationships, and map aggregation. Centralizing orchestration there avoids exposing raw LLM service details to the browser and gives one place to enforce approved-only data and citation rules.

Alternative considered: keep direct frontend calls to `llm-service`. This is simpler but pushes business context assembly into the browser and makes citation integrity weaker.

### Decision 2: Start RAG with MySQL full-text and structured filters

The first implementation will retrieve context from approved relational data and `content_chunk` records. It can use school/resource filters, theme keyword matching, and MySQL full-text search before adding embeddings.

Rationale: `content_chunk` and source tables already exist, while vector dependencies are not yet present. A full-text-first RAG path is enough for a reliable demonstration and keeps the teacher-facing API stable for later vector upgrades.

Alternative considered: introduce a vector database immediately. This would improve semantic recall but adds setup complexity and may slow down the next milestone.

### Decision 3: llm-service returns structured JSON

llm-service will expose an internal teaching-plan generation endpoint that accepts a prepared context package and returns a typed JSON result with teaching topic, grade, objectives, resource basis, activity flow, preparation, field tasks, safety notes, reflection, evaluation, citations, and follow-up suggestions.

Rationale: the frontend should render predictable sections and citations. Free-form Markdown would be harder to validate, store, and test.

Alternative considered: return only Markdown. This is faster for demos but brittle once the plan needs citation display, saving, or admin review.

### Decision 4: Citations are assembled and validated by business-service

business-service will provide citation candidates to llm-service and validate that returned citation identifiers are from the provided context. If the model returns unsupported citations, business-service will either remove them or replace them with the known supporting sources.

Rationale: citation correctness is a product requirement. The LLM can phrase answers, but business-service must guard source provenance.

Alternative considered: trust LLM-generated citations. This risks hallucinated or untraceable references.

### Decision 5: Neo4j graph facts are optional enrichment

The generation flow will include Neo4j relationship facts when Neo4j is available and synced. If graph queries fail or no graph facts exist, generation will continue using school-resource and content chunk context.

Rationale: the acceptance criteria require at least one graph query demonstration, but teaching plan generation should not become unusable when the local Neo4j service is absent.

Alternative considered: require Neo4j for every generation. This creates a fragile demo path.

## Risks / Trade-offs

- [Risk] The model may return unsupported facts or citations. → Mitigation: constrain prompts to supplied context and validate citation identifiers in business-service before returning to the frontend.
- [Risk] MySQL full-text retrieval may miss semantically related content. → Mitigation: combine keyword retrieval with structured school-resource relationships and leave an embedding retrieval seam behind the same service interface.
- [Risk] Direct model provider calls may fail due to missing credentials or network issues. → Mitigation: provide provider configuration, timeouts, local fallback output, and visible degraded status.
- [Risk] Generated plans may be too generic. → Mitigation: include resource names, distances, educational value, activity suggestions, grade, duration, and practice requirement in the prompt context.
- [Risk] Adding AI APIs may blur service boundaries. → Mitigation: business-service owns retrieval and orchestration; llm-service owns generation only.

## Migration Plan

1. Add business-service AI request/response VOs and orchestration service.
2. Add llm-service structured teaching plan endpoint with local fallback and provider configuration.
3. Add retrieval helpers for school detail, related resources, existing plans, content chunks, sources, and optional graph facts.
4. Update frontend to call business-service generation endpoint and render structured results.
5. Keep existing direct explanation/ask interactions until the new flow is verified, then migrate school teaching plan generation first.
6. Rollback by hiding the teaching plan panel and leaving existing map/detail/QA flows untouched.

## Open Questions

- Which real LLM provider should be configured first for the team environment?
- Should generated teaching plans be saved automatically as drafts, or only after teacher/admin confirmation?
- Should the first RAG implementation use MySQL full-text only, or also include a lightweight local embedding store if setup time allows?
