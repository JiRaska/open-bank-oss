---
date: 2026-09-07
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [resilience, payments]
summary: "Interest-service creation POSTs stay free of a synthetic key: accruals and remittances dedup on their DB natural keys, capitalization on its claim, rate configs on the admin natural key."
---

# ADR-0291 — Interest creation POSTs are idempotent on natural keys, not synthetic keys

## Context

The #8351 idempotency inventory requires every money-path POST to either enforce a declared
idempotency handle or carry an ADR-linked exception. Five interest-service POSTs were baselined
as uncovered:

- `POST /api/v1/interest/accrue` — accrue one day for one account
- `POST /api/v1/interest/accrue/all` — the fleet-wide daily accrual run
- `POST /api/v1/interest/capitalize/{accountId}` — capitalize a pending accrual set
- `POST /api/v1/interest/rates` — create a rate config (admin)
- `POST /api/v1/interest/withholding/remittances` — assemble the monthly tax remittance

## Decision

- **accrue** — the gap was real: a retried accrue died on the V12 UNIQUE key
  (`account_id, accrual_date, product_id, currency`) as a 500. Fixed in the same change:
  `InterestService.accrue` reads the natural key FIRST and replays the original accrual (so a
  replay after the rate config was deactivated still answers), and the constraint is the race
  backstop, recovered by re-reading the winner's row.
- **accrue/all** — already idempotent: per-account failures (including the duplicate) collapse
  to a skip, never aborting the batch. Documented.
- **capitalize** — already exactly-once by construction (ADR-0033): the accrual set is claimed
  `ACCRUING → CAPITALIZING` before the ledger is told anything, and the ledger idempotency key
  is derived from `(account, product, periodTo)`, so a retry collapses onto the journal the
  interrupted attempt booked. Documented.
- **rates** — the gap was real for product-wide defaults: a retried admin create stacked a
  duplicate active config that silently shadowed the first in `findEffectiveRate`'s
  `effectiveFrom DESC` ordering (account overrides already died on `ux_rate_active_account`,
  V11). Fixed: `createConfig` replays the active twin on the natural key
  `(productId, accountId-or-null, currency, effectiveFrom)`; a rate CHANGE carries a different
  `effectiveFrom` and persists normally. The V11 index is the race backstop for overrides.
  **No new unique index was added for product-wide defaults**: one would have to deduplicate
  existing production rows before it could build, and the replay path — the case the endpoint
  actually sees — is fully covered by the check-first read. A truly concurrent first insert of
  two product defaults remains possible and is accepted: admin rate management is a
  low-frequency, human-driven operation, and the shadowing ordering is deterministic per
  effectiveFrom.
- **withholding/remittances** — already idempotent: one batch per `(year, month)`, a re-run
  returns the assembled batch; `uq_withholding_remittance_period` (V4) is the race backstop.
  Documented.

## Alternatives considered

- **Synthetic `Idempotency-Key` header on all five** — rejected: every endpoint already has a
  complete natural key (a day, a period, a claim, an admin-typed config identity); a synthetic
  key would identify nothing better and would force scheduler and admin callers to mint and
  store keys for operations that are intrinsically re-runnable.
- **A unique index on product-wide active defaults** — rejected for now (see above): it needs a
  production data cleanup first; if rate-management concurrency ever becomes real, that cleanup
  plus the index is the follow-up, not a code change.

## Consequences

- All five endpoints have a stated, enforced idempotency contract; the five baseline entries
  re-point here.
- `accrue` no longer 500s on a same-day retry — it replays. Callers that treated the 500 as
  "already done" keep working; callers that treated it as failure now get the original row.
- Two active rate configs for one `(product, currency)` can no longer be stacked by a retry;
  a rate change is a new `effectiveFrom` (or `PUT` + deactivate), never a duplicate `POST`.

## Compliance impact

Strengthens the money-path interest pipeline (accrual → capitalization → withholding →
remittance) against double-counting under retries — directly relevant to the accuracy of
customer interest credits and of the §38d withholding remittance reported to the finanční
úřad. No new obligation introduced.
