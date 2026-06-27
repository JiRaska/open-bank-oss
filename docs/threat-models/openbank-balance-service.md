<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-balance-service

STRIDE/DFD threat model for the balance bounded context, per ADR-0030 D2.
Money-path service. Reviewed in PR; referenced from ADR-0039.

- **Status:** Draft (lightweight, ADR-0039-aware)
- **Last reviewed:** 2026-06-08
- **Owner:** balance CODEOWNERS
- **Related ADRs:** ADR-0002 (hexagonal), ADR-0017 (Vault), ADR-0018 (OPA authz),
  ADR-0024/0025 (single IBAN + currency pockets), ADR-0034 (OPA unified authz),
  ADR-0039 (balance as projection of ledger)

## 1. Scope & assets

Under ADR-0039 the ledger is the golden source and **balance is a projection** — the fast,
queryable view of available/booked funds per currency pocket, plus overdraft (N2) headroom.
It is on the money path because **authorization-to-spend decisions** (`BalanceCoverPort`) are made
here: an over-stated balance permits an unfunded debit; an under-stated one wrongly declines a payment.
Per-account, per-currency operations: credit, debit, holds (place/release), initialize.

Assets protected, in priority order:

1. **Balance correctness per currency pocket** — booked + available, the basis of spend authorization.
2. **Overdraft limits (N2)** — per-pocket headroom that bounds permitted negative balance.
3. **Hold records** — placed/released funds reservations.
4. **Reconciliation truth (Phase A)** — per-currency tie-out of balance projection ⇄ ledger
   deposit-control accounts; the control that detects projection drift.
5. **Idempotency of applied movements** — exactly-once application of booked changes.

## 2. Data-flow diagram (textual)

```
                ┌─────────────────── trust boundary: balance-service ───────────────────┐
 [Payment svc]  │                                                                        │
  BalanceCover  │── 1 ─▶ REST / port (BalanceResource, BalanceCoverPort)                 │
  JWT           │            │                                                           │
                │            ▼                                                           │
 [Reader / UI]  │── 2 ─▶ BalanceService (use case) ── overdraft check ──▶ [Postgres] ─3─ │
  JWT           │            │                                  balances, balance_holds   │
                │            ▼                                  reconciliation             │
 [Scheduler] ─4─┼─▶ BalanceReconciliationService ──▶ Ledger REST client ──▶ [ledger-svc] │
                │      (per-currency tie-out)        balance_outbox ──▶ [Kafka]          5│
                └────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries: (1) caller → REST/port (mTLS + OIDC + OPA); (3) service → Postgres;
(4/5) service → Kafka / ledger-service REST.
Domain layer has zero framework imports (ADR-0002); overdraft + reconciliation math are unit-testable.

## 3. Authn / Authz

- Service-to-service callers authenticated (mTLS + OIDC); OPA policy gates credit / debit / hold (ADR-0034).
- Money-moving endpoints are role-gated: `@RolesAllowed(SERVICE, OPERATOR, ADMIN)` on
  credit/debit/hold/initialize, supervisor/admin on overdraft-limit override; **no endpoint is
  `@PermitAll`** (locked by `BalanceResourceSecurityTest`). On top of role gating, `@Authorize`
  (OPA, ADR-0034) is wired on `balance.credit` / `balance.debit` in **advisory** mode and graduates
  to enforce in Phase 5.

## 4. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST/port in | **Spoofing** — unauthenticated caller posts a credit/debit, or forges identity | mTLS + OIDC; reject anonymous; bearer JWT (Keycloak); role-gated mutations | OPA fine-grained authz (ADR-0018/0034) advisory on balance — *open* (see §3 finding) |
| T1 | Cover check | **Tampering** — manipulated request authorizes an unfunded debit / negative balance | Server-side overdraft evaluation against stored limit; available = booked − holds + overdraft; optimistic locking / row versioning; per-currency rows; pure-domain, unit-tested | Low |
| T2 | Balance rows | **Tampering** — direct DB mutation desynchronizes from ledger | App-only write path; DB creds in Vault (ADR-0017); **Phase A reconciliation** detects drift vs ledger deposit-control per currency | Drift detected, not prevented — by design (projection); Phase D cutover hardens |
| R1 | Movements | **Repudiation** — actor denies a balance change it applied | AuditEvent per credit/debit/hold; movements carry origin/actor + idempotency key; outbox event with correlation id; reconciliation run timestamped/persisted | Strengthen with signed audit (ADR-0029) — *planned* |
| I1 | Reads | **Information disclosure** — balance harvesting across accounts/pockets | AuthZ scoped to account owner/role; per-account server-side scoping; no bulk export/enumeration; JWT required. **A1 (issue #628):** `X-Customer-Party-Id` triggers M2M ownership lookup via `AccountServiceClient`; mismatch → 404 (existence oracle protection) | OPA read-path enforce — *planned* |
| I2 | Domain metrics | **Information disclosure** — domain metrics leak PII / enable per-account inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077): the outbox-backlog gauge (`openbank.outbox.backlog`) is tagged only by `service="balance"` — never an account id, IBAN, currency-pocket value, balance, or party id. The gauge reads a read-only `count(*)` of PENDING/FAILED outbox rows refreshed off the Prometheus scrape thread by a scheduled tick (no per-scrape reactive query); `/q/metrics` is cluster-internal | Low |
| D1 | Reconciliation / writes | **DoS** — hold exhaustion / write storm / expensive tie-out scans | Rate limits; idempotency drops retries; per-currency aggregation; scheduled reconciliation cadence; reactive non-blocking stack | Gateway rate-limit — infra scope |
| E1 | Roles | **Elevation** — read role triggers a debit / raises own overdraft | Deny-by-default; explicit role for money movement; cover check cannot mutate | OPA advisory — *open* |
| T3 | Ledger client | **Tampering / spoofing of source** — projection trusts a forged ledger response | Authenticated ledger-service inside trust mesh; reconciliation compares against ledger as golden source, flags mismatch | mTLS/service-identity hardening — infra scope |

## 5. Key invariants (must never regress)

- A debit is authorized only if `available ≥ amount` where `available` includes the configured
  overdraft headroom for that currency pocket — never beyond it.
- Balance is a **projection**: on divergence, the **ledger wins** (ADR-0039); reconciliation must flag,
  never silently "fix" toward the projection.
- **Idempotency is critical** — a duplicate credit/debit on retry must be impossible. Enforced on the
  direct path by the `balance_movement` dedup ledger (PK `(account_id, currency, reference_id,
  operation)`, V8), whose marker insert and balance mutation share one transaction — so a Kafka
  at-least-once redelivery or a saga retry that replays a referenceId is a no-op. Mirrors
  `ledger_projection_event` for the projection path (ADR-0039 Phase D).
- Reconciliation ties out **per currency** against ledger deposit-control accounts (2100–2103).

## 6. Open items / follow-ups

- Confirm authz annotations vs OPA coverage on money-moving endpoints (see §3 finding); enforce
  OPA authz on balance read + write + cover paths (ADR-0034) — currently advisory.
- Signed audit / evidence bundle (ADR-0029 D2) for movement non-repudiation.
- ~~Phase D: cut the projection over to ledger-emitted `AccountBookedChangedEvent` (ADR-0039),
  retiring any independent balance write path.~~ **Done 2026-06-17** (Phase D-2): projection enabled
  as the sole booked-mover; the transaction saga's direct debit/credit is removed (see change log).
- Mutation testing (pitest) on overdraft + reconciliation math (ADR-0030 D3).

## 7. Change log

- **2026-06-19** — A1 defense-in-depth (issue #628): added per-account ownership check on `getBalances` /
  `getBalance` via new `AccountServiceClient` (M2M REST call to account-service). When the caller
  supplies `X-Customer-Party-Id` the returned balance is scoped to the requesting party — mismatch
  or unknown account → 404 to deny existence oracle. Operator/service callers without the header are
  unaffected. NetworkPolicy updated to permit `balances→accounts` egress. STRIDE row I1 updated.
- **2026-06-17** — ADR-0039 Phase D-2 cutover: flipped `openbank.balance.projection.enabled` ON.
  The balance `bookedAmount` is now derived **solely** from the ledger's `AccountBookedChanged`
  projection (`LedgerProjectionConsumer` → `LedgerProjectionService`); the transaction saga no longer
  debits/credits balance directly (coupled transaction-service PR). The projection apply is idempotent
  (`ledger_projection_event` dedup on `(journalEntry, account, currency)`) and, as it applies the delta,
  releases the originating payment's cover hold (`referenceId == transactionId`) — this is what closes
  the overspend window during the saga-debit removal (invariant §5). Touches **integrity**: `bookedAmount`
  becomes eventually consistent (projection lag), but overspend stays prevented synchronously by holds
  (the cover decision runs at payment time, not on `bookedAmount`). **Deploy in lock-step** with the
  transaction-service change — running the saga debit and this projection together double-counts the
  booked movement. Rollback: set the flag back to `false` (and re-enable the saga debit). Config-only
  change; no DB/schema/flow/boundary change.
- **2026-06-11** — Added the outbox-backlog gauge (`openbank.outbox.backlog`, tagged only by
  `service="balance"`) + `countProcessable()` on the outbox port (ADR-0077 / ADR-0079). New STRIDE
  row **I2**: domain-metric cardinality. The gauge exposes only a read-only count of processable
  (PENDING + FAILED) outbox rows — never an account id, IBAN, currency-pocket value, balance, or
  party id — and the count is refreshed on a scheduled `suspend` tick (off the scrape thread), so a
  Prometheus scrape never triggers a reactive DB query. **This change also wires Micrometer's
  Prometheus registry** (`quarkus-micrometer-registry-prometheus`): balance-service previously had
  **no MeterRegistry**, so the shared `DomainMetrics` was a silent no-op — the gauge now actually
  emits and the service gains a `/q/metrics` surface (cluster-internal). No new external trust
  boundary, endpoint, or data flow; the metrics surface is internal-only. Risk class =
  **confidentiality** (metric-label leakage), mitigated by the low-cardinality `DomainMetrics`
  contract + `BalanceOutboxBacklogGaugeTest`. No DB or schema change; rollback = revert the commit.
- **2026-06-08** — Closed the credit/debit idempotency gap on the direct money-movement path
  (`BalanceUseCase.credit/debit`, used by the transaction saga; surfaced by the welcome-bonus work,
  which newly exercises the credit leg). The path was idempotent only at the saga entry point; a
  Kafka redelivery or a saga retry after a crash between the COMPLETED write and the event ack could
  replay a referenceId and double-apply. Added the `balance_movement` dedup ledger (V8) keyed by
  `(account_id, currency, reference_id, operation)` and a `BalanceMovementPort` whose marker insert +
  balance mutation share one transaction (mirrors R1 / invariant §5, and the `ledger_projection_event`
  pattern); the event is published only on first application. Concurrent duplicates are also blocked
  by the PK constraint + the balance row version. Additive DDL only; no new flow/surface/boundary.
  Risk class = **integrity** (double credit/debit on a money-path balance). Rollback:
  `DROP TABLE balance_movement` (only with the credit/debit path quiesced — see migration note).
  Implemented + verified live in PR #590.
- **2026-05-30** — Reframed around ADR-0039 (balance as projection of ledger): added projection/
  reconciliation assets, `BalanceCoverPort` spend-authorization surface, overdraft (N2) invariants,
  ledger-client trust boundary. No new runtime surface — documentation/governance pass.
- **2026-05-30** — Added `balances_seq`, `balance_holds_seq`, `balance_outbox_seq` (Hibernate fix);
  added `assertj` test dep for the guard. Additive DDL only — no new flow/surface/boundary.
  Risk class = **availability** (missing sequence breaks all balance writes), mitigated by
  `HibernateSequenceGuardTest`. Rollback: `DROP SEQUENCE`.
