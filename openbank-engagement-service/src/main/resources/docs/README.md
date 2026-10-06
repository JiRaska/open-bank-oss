# OpenBank Engagement Service

In-app engagement surfaces (ADR-0220), first infrastructure slice.

## Authorization

The deployed OPA sidecar supplies decisions to `@Authorize` through the shared `PolicyDecisionPoint` producer. Production authorization remains enforced; the `%test` profile disables the producer and uses advisory authorization for HTTP contract tests without a sidecar.

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.
