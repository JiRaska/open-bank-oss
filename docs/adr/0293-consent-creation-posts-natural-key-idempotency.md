---
date: 2026-09-07
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, compliance]
summary: "Consent-service creation POSTs stay free of a synthetic key: consents dedup on the Berlin Group tppTransactionId / X-Request-ID, suppressions on the natural key (party, scope, value) over active rows."
---

# ADR-0293 — Consent creation POSTs are idempotent on natural/PSD2 keys, not synthetic keys

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. Two consent-service creation POSTs were
baselined as uncovered:

- `POST /api/v1/consents` — grant a data-sharing consent
- `POST /api/v1/suppressions` — record a do-not-contact suppression (ADR-0219 D3)

## Decision

- **Consents** — already enforced in code, undocumented in the contract: the resource builds
  its dedup key from the Berlin Group `tppTransactionId` body property, falling back to the
  `X-Request-ID` header, and replays the cached response (`X-Idempotency-Replayed: true`).
  These are the PSD2-native keys a TPP already supplies; a synthetic key would duplicate an
  identity every Berlin Group caller already has. The OpenAPI now declares both. They remain
  OPTIONAL (requests without either are accepted, undeduplicated — the pre-existing contract
  with edge callers), which is why the baseline entry stays, re-pointed here, instead of
  being removed as covered.
- **Suppressions** — the gap was real: a retried create stacked a second identical active
  row. Fixed in the same change: `SuppressionService.create` checks the natural key
  (partyId, scope, value) over ACTIVE rows and replays the original row; the partial unique
  index `uq_suppressions_active_natural` (V8, `WHERE revoked_at IS NULL`, value coalesced for
  scope=ALL) is the race backstop, recovered by re-reading the winner's row. A revoked
  suppression deliberately does NOT block a fresh suppression of the same value — revocation
  followed by re-suppression is a new fact, not a retry.

## Alternatives considered

- **Synthetic `Idempotency-Key` on both** — rejected: consents already have the PSD2 key;
  suppressions have a complete natural key. Two identities for one fact buys nothing.
- **Making X-Request-ID required to flip the gate classification** — rejected: it would break
  existing edge callers that send neither key, and the gate's baseline-with-reason mechanism
  exists precisely for ADR-linked exceptions like this one.

## Consequences

- Both endpoints have a stated, enforced idempotency contract; the two baseline entries
  re-point here.
- The pre-V8 duplicate-suppression window is closed for sequential retries (check-first) and
  concurrent first attempts (partial unique index).
- Lifecycle endpoints (`activate`, `reject`, `revoke`, `validate`) remain aggregate-state
  guarded: a replay against a transitioned aggregate fails loud (409), never double-applies.

## Compliance impact

Strengthens GDPR/ADR-0219 D3 handling: a duplicate do-not-contact row was never a privacy
breach (both rows suppressed), but dedup keeps the evidentiary store clean — one suppression
fact, one row, one event.
