# OpenBank Pension Service API

Participant-side pension contracts and jurisdiction-pack evaluation (ADR-0334).

## Interface

The committed `src/main/resources/openapi.yaml` defines the API contract. Read `/q/openapi` on the running service for the exact paths, request bodies, responses, and contract version of its image.

## Contract access

`X-Customer-Party-Id` is a party assertion supplied by a trusted customer relay after that relay verifies the customer's identity. The service accepts the header only with a verified JWT whose non-empty subject, `azp` client and `preferred_username` identify that client's own service account. The client must be listed in `openbank.pension.trusted-relay-clients` (default `openbank-edge`), and the username must equal `service-account-<azp>`. Matching the principal name alone does not establish trust. A header from another caller receives HTTP 403, even if that caller holds `ROLE_API`.

Contract changes require the vouched-for participant identity. Without the party header, operators, administrators and compliance staff may resolve a staff reader, but cannot act as a participant. A participant asking for another party's contract receives not found. These application ownership checks complement the route's role and OPA authorization checks.

## Build identity

The generated `00-build` page in this same documentation index reports the release version, full source commit, and API contract version packaged in the running image. The index is available at `/q/openbank/docs`.

## Provider configuration and rollout

Development and tests use the synthetic provider UUID `00000000-0000-4000-8000-000000000001`.
The sandbox deployment explicitly sets the same development-only identity. No real provider
identity or production ownership approval is needed for local development or sandbox testing.

Before a real-money production launch, set `OPENBANK_PENSION_PROVIDER_ENTITY_ID` to the assigned
provider legal-entity UUID and reconcile it with existing contracts and onboarding applications.
Production has no default; missing or malformed configuration prevents startup. Synthetic
fixtures do not establish ownership. No existing data is relabelled by this change.

Draft and onboarding creation reject a different provider ID before their application-layer
work, including draft idempotency lookup. This is only a creation invariant. Provider-bound
staff and service identities, database ownership validation, replay authorization, fund ownership
and isolated workflow processing remain prerequisites before real-money multi-provider launch.
These launch requirements do not gate development or synthetic sandbox tests. Do not change
this setting to switch an existing database between providers.
