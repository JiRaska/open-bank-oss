# OpenBank Treasury Service API

The bank's own money-market deals (ADR-0315).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

Validation failures (HTTP 400) from the shared runtime use `ProblemDetail` with `status`, `code`,
`message`, `traceId`, and `timestamp`. Treasury business denials keep their separate `Error` body
with an `error` code. Branch on `code` for validation and `error` for business denials.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
