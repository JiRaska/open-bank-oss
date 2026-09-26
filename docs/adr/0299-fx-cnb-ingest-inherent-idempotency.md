---
date: 2026-09-09
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, payments]
summary: "FX ČNB ingest is inherently idempotent: every rate is keyed (source, validFrom) check-first, so re-ingesting a day skips stored rows; documented, no code change."
---

# ADR-0299 — FX ČNB ingest is inherently idempotent on the fixing natural key

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. `POST /api/v1/fx/cnb/ingest` (ops/backfill
surface for the daily ČNB fixing, ADR-0046) was baselined as a creation POST with no
idempotency key.

## Decision

Re-verified as inherently idempotent — no code change, documented in openapi.yaml:

- Every ingested rate is keyed by the natural tuple `(currency, quote, source=CNB,
  validFrom)`; `CnbRateIngestionService.ingest` checks `findBySourceAndValidFrom` first and
  skips already-stored rows (`skipped++`). Re-ingesting the same day — manually, or because the
  scheduler and the backfill overlap — stores nothing twice.
- The result payload reports `ingested` vs `skipped`, so an operator can distinguish a replay
  from a first ingest.

## Alternatives considered

- **Unique index + replay instead of check-first skip** — unnecessary: a duplicate row here is
  a stale rate, not a double money movement, and the check-first skip already collapses the
  retry window that matters (the scheduler and manual backfill run minutes apart, not
  microseconds). A race between two simultaneous ingests of the same day would need
  serialization the operation does not warrant; worst case is two identical indicative rates,
  visible and deletable.

## Consequences

- The baseline row is re-annotated as an ADR-linked exception; the endpoint stays keyless by
  design.

## Compliance impact

- None — indicative ČNB reference rates, no customer data, no money movement on this path.
