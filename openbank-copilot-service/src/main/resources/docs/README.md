# OpenBank Copilot Service API

Customer-facing AI assistant (mobile copilot), per ADR-0089.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

For the buffered chat and action confirmation routes, a missing bearer token is rejected before the resource runs. Its JSON 401 body uses the shared `ProblemDetail` shape (`status`, `code`, `message`, `traceId`, `timestamp`). An authenticated token without a usable subject can instead receive the resource's `ErrorResponse` (`error`). Clients should handle both documented shapes.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
