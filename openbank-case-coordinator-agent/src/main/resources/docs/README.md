# Case Coordinator Agent API

Agent-swarm case coordination (ADR-0244): opens durable Temporal case workflows, accepts mid-flight agent signals (join / contribute / supersede / request-synthesis), and projects case history into the ADR-0246 thread view.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

Invalid case-open and signal requests return the shared validation problem body (`code`, `message`, `status`, `traceId`, `timestamp`). Case lookup failures use the endpoint’s compact `error` body.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
