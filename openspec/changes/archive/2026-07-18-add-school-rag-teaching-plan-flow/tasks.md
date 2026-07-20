## 1. Business API and Contracts

- [x] 1.1 Add business-service request VO for teaching plan generation with school id, grade, theme, activity type, duration, and practice requirement.
- [x] 1.2 Add business-service response VOs for generated teaching plan sections, citations, generation status, and follow-up suggestions.
- [x] 1.3 Add internal context VOs for school summary, nearby resource context, existing plan context, content chunk context, source context, and optional graph facts.
- [x] 1.4 Add `AiTeachingPlanController` with a teacher-facing `POST /api/ai/teaching-plans/generate` endpoint.
- [x] 1.5 Reject missing, inactive, or unapproved school ids before any LLM call.

## 2. Context Assembly and Retrieval

- [x] 2.1 Implement service logic to load approved school detail using existing school and school map services.
- [x] 2.2 Implement service logic to load approved school-resource relationships and related approved local education resources.
- [x] 2.3 Implement retrieval of approved existing teaching activity plans for the target school and relevant resources.
- [x] 2.4 Implement content chunk retrieval for selected school/resource/activity entities using structured filters and keyword/full-text search where available.
- [x] 2.5 Implement source/citation candidate assembly from `entity_source_rel`, `data_source`, and `content_chunk.source_id`.
- [x] 2.6 Add optional Neo4j graph fact retrieval for school-resource relationship chains, with graceful fallback when graph data is unavailable.

## 3. LLM Service Integration

- [x] 3.1 Add llm-service endpoint for structured teaching plan generation from a prepared context package.
- [x] 3.2 Add prompt template logic that instructs the model to use only supplied context and return structured JSON.
- [x] 3.3 Add provider configuration for a real LLM API while preserving local fallback behavior.
- [x] 3.4 Add timeout, error handling, and degraded structured response behavior in llm-service.
- [x] 3.5 Add business-service HTTP client logic to call llm-service with the assembled context package.

## 4. Citation Validation and Persistence

- [x] 4.1 Validate model-returned citation identifiers against the citation candidates supplied by business-service.
- [x] 4.2 Remove or replace unsupported citations before returning the result to the frontend.
- [x] 4.3 Add optional save-confirm endpoint or service method to create a draft `teaching_activity_plan` from a generated result.
- [x] 4.4 Ensure generated plans are not persisted until the user confirms saving.

## 5. Frontend Experience

- [x] 5.1 Add a teaching plan generation panel to the school detail view with grade, theme, activity type, duration, and practice requirement controls.
- [x] 5.2 Call the new business-service generation endpoint from the school detail state.
- [x] 5.3 Render structured teaching plan sections, related resources, generation status, and citations.
- [x] 5.4 Preserve user inputs and show an understandable degraded-state message when generation fails.
- [x] 5.5 Add a save-as-draft interaction if the persistence endpoint is implemented in this change.

## 6. Demo Data and Verification

- [x] 6.1 Ensure at least one sample rural school has enough approved nearby resources for the demo flow.
- [x] 6.2 Ensure citation source records or content chunks exist for demo resources used by the generated plan.
- [x] 6.3 Verify the flow works when Neo4j is available and when Neo4j is unavailable.
- [x] 6.4 Verify the flow works when real LLM credentials are configured and when only local fallback is available.
- [x] 6.5 Add focused backend tests for request validation, context assembly, citation validation, and degraded LLM behavior.
- [x] 6.6 Manually verify the frontend flow from school detail to generated plan and visible citations.
