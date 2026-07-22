## Context

The Vue teacher map currently loads an approved school, approved school-resource relations, and approved resources from MySQL, then draws its own AMap markers. AMap is only used as a browser basemap. The Flask LLM service can call an OpenAI-compatible endpoint, but its school endpoints only summarize already-approved resources. The administrator UI already supports resource and relation review but has no discovery queue.

Discovery crosses the browser, Spring service, MySQL, AMap Web Service, Flask LLM service, and administrator workflow. External calls can be slow, unavailable, quota-limited, or incomplete, so approved data must remain usable independently.

## Goals / Non-Goals

**Goals:**
- Discover nearby AMap POIs without exposing the Web Service key.
- Cache and deduplicate discovery while keeping map loading responsive.
- Classify only provider-returned POIs and preserve model uncertainty.
- Give teachers useful candidate details without treating candidates as verified facts.
- Convert an approved candidate atomically into the existing approved resource model.
- Enforce school ownership and administrator-only review/force refresh.

**Non-Goals:**
- Clicking or extracting arbitrary native AMap basemap labels.
- Route planning, navigation, crawling websites, or merging government/encyclopedia sources.
- Using unapproved candidates in RAG, citations, assistant answers, or generated teaching plans.

## Decisions

1. **Use server-side AMap Web Service calls.** Add a server-only key distinct from the browser JS key. This enables trustworthy caching, quota controls, and review provenance. A frontend-only PlaceSearch alternative was rejected because results could be tampered with and cannot be centrally cached safely.

2. **Run discovery asynchronously with a persisted snapshot.** A create-run endpoint returns a fresh cached run immediately or creates one background run per school/radius. The UI renders approved resources first and polls an active run. Runs cache for 24 hours and recover stale `running` rows as failed.

3. **Normalize stable candidates separately from runs.** `resource_discovery_candidate` is unique by school, provider, and provider POI ID. `resource_discovery_run_item` records membership and rank for each radius-specific snapshot. Administrator decisions survive later runs.

4. **Keep provider facts and AI judgments distinct.** Provider name, address, coordinates, type, phone, opening data, distance, and raw JSON are stored separately from AI category, confidence, rationale, themes, grade guidance, activity suggestion, and verification notes. Invalid model IDs and categories are discarded.

5. **Fail open for factual POI display, fail closed for educational use.** If LLM classification fails, raw POIs are visible as neutral “unanalyzed” candidates. They remain excluded from approved-resource consumers.

6. **Approve through one transaction.** Approval reuses an approved resource by `(external_provider, external_place_id)` or creates it, creates/reuses an approved `nearby` school relation, and links the candidate. Rejection remains sticky until an administrator explicitly reopens it.

7. **Reuse current service patterns.** Spring `RestClient`, MyBatis-Plus services, session accounts, and the Flask OpenAI-compatible caller are extended without adding a queue or SDK dependency. A bounded Spring executor handles background runs.

## Risks / Trade-offs

- **AMap coverage and metadata can be incomplete** -> Display provider provenance, last checked time, and an explicit unverified warning.
- **Automatic discovery can consume quota** -> Cache for 24 hours, cap at 50 unique POIs, reuse active runs, and reserve force refresh for administrators.
- **LLM can hallucinate or misclassify** -> Restrict output to supplied POI IDs, validate enums and confidence, preserve raw facts, and require administrator approval.
- **Application restarts can strand runs** -> Mark old running runs failed on the next request and allow a new run.
- **Multiple schools can approve the same POI** -> Keep candidates school-specific but reuse the formal resource by provider identity.

## Migration Plan

1. Apply additive tables, indexes, and nullable external identity columns; existing rows remain valid.
2. Configure `AMAP_WEB_SERVICE_KEY`. Without it, discovery returns a configuration error while approved map resources remain available.
3. Deploy the LLM endpoint and business service, then the teacher/admin UI.
4. Rollback by disabling discovery configuration and UI calls; additive data can remain without affecting existing approved-resource queries.

## Open Questions

None. Defaults are 5 km, selectable 1/3/5/10 km, 24-hour cache, 50 POIs per run, 20 candidates per LLM batch, and 0.60 teacher visibility confidence.
