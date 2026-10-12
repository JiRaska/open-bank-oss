# OpenBank Copilot Service API

Customer-facing AI assistant (mobile copilot), per ADR-0089.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

The copilot's explicitly constructed error responses use `{"error": "..."}`. The
confirmation endpoint currently has no production token issuer; when one is added, callers
must treat unknown, malformed and already-removed expired tokens alike as 404. If a store still
returns a token whose `expiresAt` has passed, the endpoint returns 422. The split depends on
store timing; neither status is proof that an action was executed.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
