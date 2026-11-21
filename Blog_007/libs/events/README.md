# libs/events — shared event contracts

Records and constants shared by every Pulse producer and consumer of Kafka events. **Contracts only**:
no Spring, no business logic, so services never become coupled through shared behaviour.

| Class | Purpose |
|---|---|
| `EventEnvelope<T>` | CloudEvents-style wrapper: `id`, `type`, `source`, `time`, `schemaVersion`, `data` |
| `EventTypes` | topic names (`content.post.events`, `ingestion.article.events`, `.DLT`) and event types |
| `PostEventData` | payload of `content.post.published / updated / unpublished` |
| `ArticleIngestedData` | payload of `ingestion.article.ingested` (metadata only, excerpt ≤ 300 chars) |

Rules: events are immutable past-tense facts; only add optional fields; breaking changes bump `schemaVersion`.

---
Designed and developed by Ankur Kumar · © 2026 Ravenclaw_007 LLC
