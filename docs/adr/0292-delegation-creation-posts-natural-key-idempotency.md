---
date: 2026-09-07
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, authz]
summary: "Delegation-service creation POSTs stay free of a synthetic key: grant offers dedup on the Berlin Group X-Request-ID, role presets on the admin-supplied natural key (name, resourceType)."
---

# ADR-0292 — Delegation creation POSTs are idempotent on natural/PSD2 keys, not synthetic keys

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. Two delegation-service creation POSTs
were baselined as uncovered:

- `POST /api/v1/delegations` — offer a delegation grant (OFFERED)
- `POST /api/v1/delegation-role-presets` — admin catalog of reusable role presets

## Decision

- **Offers** — already enforced in code: the resource dedups on the Berlin Group
  `X-Request-ID` header via the idempotency store and replays the cached response. The key
  stays OPTIONAL (the pre-existing edge contract), so the baseline entry remains, re-pointed
  here. The OpenAPI already declared the header; the summary now names the ADR.
- **Role presets** — the gap was real: a retried admin create stacked a duplicate catalog
  row. Fixed in the same change: `DelegationRolePresetService.create` checks the natural key
  (name, resourceType) first and replays the original preset; the unique index
  `uq_delegation_role_presets_name_type` (V15) is the race backstop, recovered by re-reading
  the winner's row. An admin catalog entry is identified by exactly the name the admin typed
  — no synthetic key would identify it better.

## Alternatives considered

- **Synthetic `Idempotency-Key` on both** — rejected: offers already carry the PSD2 key every
  edge caller supplies; presets are an admin catalog whose natural key is complete.
- **Making X-Request-ID required** — rejected: breaks existing callers that send no key; the
  baseline-with-ADR-reason mechanism exists for exactly this class.

## Consequences

- Both endpoints have a stated, enforced idempotency contract; the two baseline entries
  re-point here.
- Preset names are now unique per resource type — a rename of an existing preset is `PUT`,
  not a second `POST`.
- The pre-V15 duplicate-preset window is closed for sequential and concurrent retries.

## Compliance impact

Not applicable to any new obligation — the change hardens an admin-only configuration
surface (ROLE_ADMIN) and the PSD2-adjacent offer flow's existing replay protection.
