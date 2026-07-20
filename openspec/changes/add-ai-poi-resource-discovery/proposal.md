## Why

The teacher map currently shows only resources that were manually entered and approved, so schools cannot inspect nearby map places or discover overlooked ideological-education opportunities. The platform needs a trustworthy discovery loop that combines AMap POI facts with LLM-assisted classification while keeping unverified candidates separate from approved teaching resources.

## What Changes

- Add server-side AMap nearby-place search and on-demand POI detail retrieval around an authenticated school.
- Run cached, asynchronous discovery automatically when the teacher map opens, with selectable 1/3/5/10 km radii.
- Add LLM classification that maps returned POIs to the existing education-resource taxonomy, confidence, evidence, themes, grade suitability, activity ideas, and verification reminders.
- Display approved resources, AI-relevant candidates, and unanalyzed POIs as distinct map layers with click-through details and clear verification status.
- Add administrator review actions that approve a candidate into an approved resource and school-resource relationship, or reject it without later automatic rediscovery overriding the decision.
- Add ownership and role checks so school accounts can only discover and inspect their bound school while platform administrators can review all schools and force refreshes.
- Keep unapproved candidates out of teaching-plan RAG context, citations, and school assistant answers.

## Capabilities

### New Capabilities
- `school-poi-resource-discovery`: Covers cached AMap POI discovery, LLM classification, teacher candidate-map interaction, administrator review, and approved-resource conversion.

### Modified Capabilities

None.

## Impact

- Adds MySQL tables for discovery runs, candidates, and run membership, plus external-provider identity fields on approved resources.
- Adds authenticated school-map and administrator APIs and one internal LLM classification endpoint.
- Extends the Vue teacher map and the existing static administrator workspace.
- Adds a server-only `AMAP_WEB_SERVICE_KEY`; the existing browser AMap key remains unchanged and the service key is never returned to the client.
- Uses the existing Spring `RestClient`, MyBatis-Plus, Flask LLM service, resource taxonomy, review model, and session authentication.
