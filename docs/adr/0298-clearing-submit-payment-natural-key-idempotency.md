---
date: 2026-09-09
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, payments]
summary: "Clearing submit dedups on the payment natural key: a retry replays the existing item and uq_clearing_items_payment backstops the race, so one payment can never settle twice."
---

# ADR-0298 — Clearing submit is idempotent on the payment natural key

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. `POST /api/v1/clearing/submit` was
baselined as a creation POST with no idempotency: `ClearingService.submit` saved a new
`ClearingItem` row unconditionally, so a retried call stacked a second PENDING item for the
same payment. The clearing cycle sweeps all PENDING items into batches — the same payment
would have been settled twice.

## Decision

- The natural key of a clearing submission is `paymentId`: a payment enters clearing exactly
  once. `submit` now reads `findByPaymentId` first and replays the existing item; only a
  genuinely new payment inserts.
- **DB backstop:** V9 adds `uq_clearing_items_payment` (unique index on
  `clearing_items.payment_id`). A lost true-concurrency race (two parallel first submits) is
  recovered, not reported: the loser detects the 23505 violation on that constraint and
  re-reads the winner, so the caller sees the same item either way.
- No synthetic `Idempotency-Key`: the endpoint's designed callers are payment services that
  already hold a stable `paymentId`, and no REST caller exists today (see the class comment on
  `ClearingResource.submit`). A synthetic key would protect against nothing the natural key
  does not.

## Alternatives considered

- **Optional Idempotency-Key header (sca/lending shape)** — rejected: duplicate protection
  keyed on a caller-minted header is weaker than the domain invariant itself, and the payment
  id is already the caller-stable handle. The header remains addable later without conflict.
- **Replay restricted to non-terminal items (collateral shape)** — rejected: a payment that
  reached SETTLED/RETURNED and is submitted again is not a legitimate new clearing entry in
  this codebase (no re-submit path exists); replaying the existing row keeps that visible
  instead of silently double-settling. If a re-clear flow is ever designed, it gets its own
  explicit endpoint, not an overload of submit.

## Consequences

- A transport retry or a double-clicked operator action can no longer duplicate a clearing
  item; the settlement cycle never sees the same payment twice.
- Existing duplicate rows, if any, must be deduplicated before V9 applies (the migration file
  carries the detection query).
- The contract is documented in openapi.yaml (1.4.0 → 1.4.1, description only).

## Compliance impact

- Settlement integrity (money-path invariant): eliminates a double-settlement path reachable
  from a single retry; the four-eyes and authorization posture is unchanged (no new caller,
  route or role).
