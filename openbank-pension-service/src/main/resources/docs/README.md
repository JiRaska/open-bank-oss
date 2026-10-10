# OpenBank Pension Service API

Participant-side pension contracts and jurisdiction-pack evaluation (ADR-0334).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Provider configuration and rollout

Set `OPENBANK_PENSION_PROVIDER_ENTITY_ID` to the assigned provider legal-entity UUID before
starting this release. Production and development have no default; missing or malformed
configuration prevents startup. Only the test profile uses a synthetic fixture identity.
Do not choose a new ID to make an existing deployment start: first reconcile it with existing
contracts and onboarding applications. No existing data is relabelled by this change.

Draft and onboarding creation reject a different provider ID before their application-layer
work, including draft idempotency lookup. This is only a creation invariant. Provider-bound
staff and service identities, database ownership validation, replay authorization, fund ownership
and isolated workflow processing remain prerequisites for multi-provider operation. Do not
change this setting to switch an existing database between providers.
