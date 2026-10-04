# Campaign Service API

Campaign management first slice (ADR-0200 / ADR-0209 D3): deterministic, versioned segments; per-enrolment Temporal journeys; consent-gated delivery through notification-service.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
