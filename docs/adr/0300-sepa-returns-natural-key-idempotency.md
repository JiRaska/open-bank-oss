---
date: 2026-09-09
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, payments]
summary: "SEPA pacs.004 returns are idempotent on the OrgnlEndToEndId natural key plus the RETURNED state guard: a re-delivered return replays, never reverses twice. Documented, no code change."
---

# ADR-0300 — SEPA payment returns are idempotent on the pacs.004 natural key

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. `POST /api/v1/sepa-payments/returns`
(inbound pacs.004 from clearing) was baselined as a creation POST with no idempotency key. It
moves money: the handler posts a ledger reversal for the returned payment.

## Decision

Re-verified as idempotent — no code change, documented in openapi.yaml:

- The natural key is the pacs.004 `OrgnlEndToEndId`, resolved server-side to the payment;
  a payment in `RETURNED` short-circuits and returns the current state, so a re-delivered
  return message (clearing redelivery, operator replay) never posts a second reversal.
- The reversal leg itself carries its own idempotency key
  (`sepa-reversal-{paymentId}`) into transaction-service, so even a crash between reversal and
  state transition replays onto the same ledger entry.
- The evidence record commits in the same transaction as the transition (issue #6056), so a
  retried call cannot produce a second evidence row for a first transition.

## Alternatives considered

- **Synthetic Idempotency-Key header** — rejected: the caller is the clearing rail, which
  redelivers the same message; the message's own business key is the stronger handle and
  requires no caller cooperation.

## Consequences

- The baseline row is re-annotated as verified; the endpoint stays keyless by design.

## Compliance impact

- Strengthens the money-movement audit story: one pacs.004 ⇒ at most one reversal and one
  evidence record, regardless of transport redelivery.
