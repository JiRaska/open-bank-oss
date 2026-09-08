---
date: 2026-09-07
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, payments]
summary: "Lending creation POSTs are idempotent: applications/intake replay on an optional Idempotency-Key, collateral and compliance-pack proposals replay on natural keys, quotes are pure computations."
---

# ADR-0297 — Lending creation POSTs are idempotent on synthetic and natural keys

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. Lending-service was baselined with 22
uncovered rows: 5 creation POSTs and 17 lifecycle POSTs.

The 17 lifecycle POSTs (advance/decision/disburse/settle/withdrawal/mark-delinquent/
mark-defaulted/forbearance/reschedule/repay/accelerate/termination propose+decide/
settlement-quote/writeoff/collateral decision) were re-verified as guarded by the aggregate
state machine: every transition checks the entity's current status before mutating, so a retry
either no-ops against the already-transitioned state or fails loudly with the same 4xx as the
first attempt. They needed no code change — the classification is recorded in the baseline.

The 5 creation POSTs had no aggregate to guard them and were classified individually:

- `POST /api/v1/lending/applications` — submit a loan application (maker)
- `POST /api/v1/lending/intake/applications` — customer self-service intake (ADR-0211)
- `POST /api/v1/lending/intake/quotes` — indicative price
- `POST /api/v1/lending/compliance-packs/proposals` — propose a pack for activation
- `POST /api/v1/lending/loans/{id}/collateral` — register collateral (maker)

## Decision

- **Applications (both endpoints)** — a loan application has no natural key: a customer may
  legitimately apply twice for the same amount, so deduplicating on the body would silently
  drop real business. The two endpoints accept an **optional** `Idempotency-Key` header
  (`X-Request-ID` as fallback), scoped per party and cached for 300 s via the shared
  `IdempotencyStore` (libs-runtime, Redis-backed); a retry inside the window replays the cached
  201 with `X-Idempotency-Replayed: true`. Without a key the endpoint behaves exactly as
  before — the key is opt-in because no caller today can supply one, and forcing it would be a
  breaking change. Same shape as ADR-0296 (sca).
- **Quotes** — inherently idempotent: `quoteCustomerCredit` is a pure computation over
  configuration and the request body; nothing is persisted (deliberately — an indicative price
  with an id would be one lookup away from being treated as a binding offer, ADR-0269 rule 4).
  Documented only.
- **Collateral registration** — the natural key is the full caller-supplied tuple (loan, type,
  description, market value, haircut). While the original registration is still PENDING, a
  retry replays the ORIGINAL PENDING collateral instead of stacking a duplicate that a checker
  could approve twice — two approved identical items would double-count against the loan's LGD.
  Once the original is decided (APPROVED/REJECTED), an identical new registration is legitimate
  and persists, so the twin match is restricted to PENDING rows. No DB backstop: a lost
  true-concurrency race stacks two PENDING rows, but each still needs its own checker decision,
  so nothing affects LGD silently.
- **Compliance-pack proposals** — the natural key is the compiled pack's `contentHash`. While
  an identical pack proposal is still PROPOSED, a retry replays it (same proposal id) instead
  of queuing a duplicate for the checker. Re-proposing after a decision creates a fresh
  proposal — rotating a pack back after a rejection is a legitimate compliance action.

## Alternatives considered

- **Mandatory Idempotency-Key on applications** — rejected: no current caller supplies one and
  the customer can legitimately apply twice, so a required key would either break existing
  clients or be minted mechanically per request, giving no protection. The optional key protects
  automated retries (the actual failure mode: customer-edge / gateway retry on timeout) without
  a breaking change.
- **Natural-key dedup on applications (party, amount, term, product)** — rejected: that tuple
  is not unique in the business domain; a second identical application is a real event.
- **DB unique constraint for collateral/compliance twins** — rejected for now: the PENDING-twin
  check closes the retry window that matters (seconds), and a partial unique index over a
  status column plus a JSON content hash adds migration and rollback complexity out of
  proportion to a race whose worst case is a duplicate row awaiting a human checker.

## Consequences

- Retried submissions inside the 300 s window no longer stack duplicate applications,
  collateral rows or pack proposals; checkers never see the same item twice from one retry
  storm.
- Legitimate duplicates remain possible everywhere the business requires them (second
  application, re-registration after decision, pack re-proposal after rejection).
- The contract test (`IdempotencyCoverageGate`) baseline rows for lending are re-annotated to
  point at this ADR; the lifecycle rows are annotated as verified aggregate-state guards.

## Compliance impact

- EBA/GL/2020/06 four-eyes: the collateral PENDING-twin replay removes the double-approval
  path that could silently halve a loan's effective LGD; the maker-checker separation itself is
  unchanged.
- ADR-0212 compliance packs: the contentHash replay preserves the rule that each activation is
  decided exactly once by a distinct checker; duplicates awaiting decision are no longer
  creatable by transport retries.
