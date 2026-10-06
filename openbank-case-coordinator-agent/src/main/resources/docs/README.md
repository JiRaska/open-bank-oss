# Case Coordinator Agent API

Agent-swarm case coordination (ADR-0244): opens durable Temporal case workflows, accepts mid-flight agent signals (join / contribute / supersede / request-synthesis), and projects case history into the ADR-0246 thread view.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

Validation failures (HTTP 400) use the shared `ProblemDetail` schema with `code`, `message`, `status`, `traceId`, and `timestamp`. Responses built by the case coordinator for denial, conflict, rate limit, missing case, or unavailable dependencies use `ErrorBody` with an `error` message. Clients should use the schema documented for each response status.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
