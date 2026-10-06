# Campaign Service API

Campaign management first slice (ADR-0200 / ADR-0209 D3): deterministic, versioned segments; per-enrolment Temporal journeys; consent-gated delivery through notification-service.

## Authorization

The deployed OPA sidecar is connected to the shared `PolicyDecisionPoint` producer. `@Authorize` evaluates policy in advisory mode while `AUTHZ_ENFORCE` remains false; enabling enforcement requires reviewing live would-deny evidence (issue #9105). The `%test` profile disables the producer because HTTP contract tests run without a sidecar.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
