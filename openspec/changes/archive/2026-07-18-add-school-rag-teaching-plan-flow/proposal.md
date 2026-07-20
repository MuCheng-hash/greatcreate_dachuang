## Why

The project needs to evolve from a red culture map portal into a school-centered local ideological education platform. The current codebase already has school, resource, map, admin, Neo4j sync, and LLM service foundations, but it does not yet provide a reliable end-to-end flow for teachers to generate structured teaching activity plans from nearby school resources with citations.

This change focuses the next development stage on a demonstrable, trustworthy loop: choose a school, inspect nearby resources, provide grade/theme/activity inputs, generate a RAG-backed teaching plan, and show the sources used.

## What Changes

- Add a school-centered RAG teaching plan generation capability.
- Add business-service AI orchestration APIs so the frontend calls business-service instead of calling llm-service directly for teaching plan generation.
- Assemble generation context from approved school data, nearby resources, existing teaching activity plans, content chunks, source records, and graph facts where available.
- Extend llm-service from a local answer skeleton toward structured generation with provider configuration, prompt templates, JSON output, and graceful fallback.
- Add frontend controls for teachers to enter grade, teaching theme, activity type, duration, and whether practical activity is required.
- Return and display structured teaching plan sections and citation sources.
- Preserve the existing service boundary: business-service owns business data and context assembly; llm-service owns natural language generation.
- Leave multi-step Agent orchestration, voice interaction, route planning, and graph visualization out of scope for this change.

## Capabilities

### New Capabilities

- `school-rag-teaching-plan`: Covers teacher-facing generation of structured teaching activity plans using school-centered resource context, RAG retrieval, graph facts, LLM generation, and citations.

### Modified Capabilities

- None.

## Impact

- Affected code:
  - `business-service/src/main/java/com/redculture/platform/controller`
  - `business-service/src/main/java/com/redculture/platform/service`
  - `business-service/src/main/java/com/redculture/platform/vo`
  - `business-service/src/main/resources/static`
  - `llm-service/app.py`
  - `llm-service/requirements.txt`
  - `data/sql`
  - `scripts/sync_mysql_to_neo4j.py`
- Affected APIs:
  - New teacher-facing AI generation endpoint under business-service.
  - New or revised llm-service internal generation endpoint for structured teaching plans.
- Affected systems:
  - MySQL remains the source of approved schools, resources, content chunks, and citations.
  - Neo4j provides optional graph facts for school-resource-entity relationships.
  - llm-service calls a configured model provider when credentials are available and falls back cleanly for local demonstration.
- Dependencies:
  - A real LLM provider configuration is required for production-quality generation.
  - Vector search may start with MySQL full-text retrieval and can later be upgraded to embeddings/vector database without changing the teacher-facing API.
