# Case Coordinator Agent API

Agent-swarm case coordination (ADR-0244): opens durable Temporal case workflows, accepts mid-flight agent signals (join / contribute / supersede / request-synthesis), and projects case history into the ADR-0246 thread view.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

Invalid case-open and signal requests return the shared libs-runtime validation envelope with
`status`, `code`, `message`, `traceId` and `timestamp` (and optional RFC 9457 members). Explicit
case policy, missing-case and Temporal outcomes return the resource's `{"error": "..."}` body.
An HTTP role rejection can have an empty 403 body because it occurs before the resource runs.
Clients should distinguish the two documented bodies by status and declared schema; never
assume an `error` member exists on a validation response.

## Proposal evidence

The case thread returned by `GET /cases/{caseId}` reads proposal events from `case_outbox`, including rows already marked `SENT`. Those rows remain part of the live read model for cases of any age. The shared seven-day SENT outbox purge therefore skips this service. Enable that purge only after proposal evidence has an independent durable source, or after a separately reviewed and tested change to the case-thread retention contract. The API currently reports retention coverage as unknown; it does not promise a seven-day evidence window.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
