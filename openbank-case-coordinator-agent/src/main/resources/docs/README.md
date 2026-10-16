# Case Coordinator Agent API

Agent-swarm case coordination (ADR-0244): opens durable Temporal case workflows, accepts mid-flight agent signals (join / contribute / supersede / request-synthesis), and projects case history into the ADR-0246 thread view.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Proposal evidence

The case thread returned by `GET /cases/{caseId}` reads proposal events from `case_outbox`, including rows already marked `SENT`. Those rows remain part of the live read model for cases of any age. The shared seven-day SENT outbox purge therefore skips this service. Enable that purge only after proposal evidence has an independent durable source, or after a separately reviewed and tested change to the case-thread retention contract. The API currently reports retention coverage as unknown; it does not promise a seven-day evidence window.

## Case-open operational logs

Case-open success, duplicate and Temporal-start failure logs correlate the workflow through a stable SHA-256 digest of its identifier. These messages omit the subject-bearing workflow identifier and Temporal exception messages or cause chains; failures retain the exception class. Success logs retain sanitized caller attribution and the case class. The digest is pseudonymous, not anonymous, and requires the same access and retention controls as other operational identifiers. API workflow identifiers, quota accounting and server-side deduplication are unchanged. This scope does not claim that every service log is free of personal data.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
