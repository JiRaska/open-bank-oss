# Case Coordinator Agent API

Agent-swarm case coordination (ADR-0244): opens durable Temporal case workflows, accepts mid-flight agent signals (join / contribute / supersede / request-synthesis), and projects case history into the ADR-0246 thread view.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Proposal evidence

The case thread returned by `GET /cases/{caseId}` reads proposal metadata from `case_proposal_evidence`. Migration V11 backfills retained outbox rows, and a database trigger records future inserts and delivery status changes in the same transaction. Payloads remain in the outbox only. The shared seven-day SENT outbox purge still skips this service until the historical backfill, target deployment, and separate evidence retention policy are reviewed. The API reports retention coverage as unknown; it does not promise a seven-day evidence window.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
