<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-ledger-service

STRIDE/DFD threat model for the general-ledger bounded context, per ADR-0030 D2.
Money-path service. Reviewed in PR; referenced from ADR-0039.

- **Status:** Draft (lightweight, first pass for Phase B)
- **Last reviewed:** 2026-05-31
- **Owner:** ledger CODEOWNERS
- **Related ADRs:** ADR-0002 (hexagonal), ADR-0017 (Vault), ADR-0018 (OPA authz),
  ADR-0039 (ledger as golden source, balance as projection),
  ADR-0050 (regulatory-grade outbox dispatch — N1–N5)

## 1. Scope & assets

The ledger is the **golden source of truth for money** (ADR-0039). The double-entry journal is the
system of record from which all balances are projected. Compromise of its integrity is the highest-impact
failure in the platform.

Assets protected, in priority order:

1. **Journal integrity** — every posting is balanced (Σdebit = Σcredit per base currency) and immutable
   once `POSTED`. Corrections happen only by compensating reversal, never mutation.
2. **GL account structure** — chart of accounts, deposit-control accounts (2100–2103), FX-position
   accounts; their `type`/normal-side semantics.
3. **Sub-ledger dimension** (`subAccountId`, Phase B) — analytická evidence that lets GL control
   accounts tie out per customer (CNB zákon 563/1991 Sb., vyhláška 501/2002 Sb.).
4. **Trial balance / sub-ledger balances** — derived reporting that auditors and reconciliation rely on.
5. **Idempotency + outbox** — exactly-once posting and reliable event emission (ADR-0050).

## 2. Data-flow diagram (textual)

```
                     ┌────────────────────── trust boundary: ledger-service ─────────────────────┐
 [Operator / svc]    │                                                                            │
 ROLE_OPERATOR  ──1──┼─▶ REST (LedgerResource)  ──▶  LedgerService (use case)  ──▶  domain model  │
   JWT (Keycloak)    │      @RolesAllowed             validateBalance()             JournalEntry   │
                     │      (reads role-gated)        loadAndValidateGlAccounts()   .reverse()     │
                     │           │                          │                                      │
 [Reader / svc] ──2──┼─▶ GET journals / trial-balance       ▼                                      │
   JWT @RolesAllowed │                              PanacheJournalRepository ──▶ [Postgres]  ──3── │
                     │                                       │                   journal_entries   │
                     │                                       ▼                   journal_lines     │
                     │            ledger_outbox (same tx) ──▶ dispatcher ──▶ [Kafka] ──4──         │
                     │            key=aggregate_id, hdr ce-id/idempotency-key=event.id (ADR-0050)  │
                     └────────────────────────────────────────────────────────────────────────────┘
```

Trust boundaries crossed: (1) external caller → REST; (3) service → Postgres; (4) service → Kafka.
Domain layer has **zero** framework imports (ADR-0002), so business invariants are unit-testable in
isolation from transport/persistence.

## 3. STRIDE analysis

| # | Element | Threat (STRIDE) | Mitigation | Residual |
|---|---------|-----------------|------------|----------|
| S1 | REST in | **Spoofing** — caller forges identity to post journals | Bearer JWT (Keycloak); `@RolesAllowed("ROLE_OPERATOR")` on `postJournal`/`reverseJournal`; reads now `@RolesAllowed(SERVICE, AUDITOR, VIEWER, OPERATOR, ADMIN)` (no longer `@PermitAll` — K7) | OPA fine-grained authz (ADR-0018) not yet enforced on ledger — *open* |
| T1 | Journal posting | **Tampering** — unbalanced or asymmetric entry corrupts the books | `validateBalance()` enforces Σdebit=Σcredit **per base currency** in the domain; rejects on mismatch; covered by unit tests | Low — invariant is in pure domain |
| T2 | Sub-ledger dim | **Tampering** — `subAccountId` stamped on a non-deposit-control leg, polluting GL tie-out | `loadAndValidateGlAccounts` rejects `subAccountId` on any non-deposit-control account (`isDepositControl`, codes 2100–2103); unit-tested (accept 2100 / reject 1100) | Low |
| T3 | Persisted rows | **Tampering** — direct DB mutation of posted lines | App-only write path; posted entries immutable (corrections via reversal `copy()` preserving dimension); DB creds in Vault (ADR-0017); migrations forward-only Flyway | DB-admin insider — covered by infra controls, out of service scope |
| R1 | All mutations | **Repudiation** — operator denies posting/reversal | `postedBy`/`reversedBy`/`createdBy` captured per entry; `entryNumber` monotone; reversal records `reason`; audit via outbox event stream | Strengthen with signed audit (ADR-0029 evidence bundle) — *planned* |
| I1 | Reads | **Information disclosure** — book-of-record / customer sub-ledger balances leak | Reads **now role-gated** to `SERVICE, AUDITOR, VIEWER, OPERATOR, ADMIN` (K7 — previously `@PermitAll`); `subAccountId` filter is server-side; no cross-customer enumeration endpoint | Role-coarse — per-tenant scoping tightened under OPA (ADR-0034) — *open* |
| I2 | Metrics / observability | **Information disclosure** — domain metrics leak PII or enable per-customer inference via high-cardinality labels | `DomainMetrics` enforces a low-cardinality tag contract: ledger postings are tagged only by `currency` + `type` (`posting`/`reversal`), the outbox-backlog gauge only by `service` — **never** an account id, IBAN, amount, party, or entry id (amounts belong in histograms, not labels). Increments happen **after** the posting commits and only for genuinely new entries (idempotent replays return early), so a replay cannot inflate counts. The backlog is a read-only `SELECT count(*) WHERE status IN ('PENDING','FAILED')` refreshed by a 10 s scheduled tick **on the event loop** — no scrape-thread DB access (`HR000068`-safe) and no new write path. `/q/metrics` is cluster-internal (not Ingress-exposed). | Low — labels bounded by ISO-4217 currency × a closed `type` set |
| D1 | Posting path | **DoS** — flood of postings / expensive trial-balance scans | Cursor pagination on journals; partial index on `(sub_account_id, base_currency)`; reactive non-blocking stack; per-service resource limits (k8s) | Rate-limiting at gateway — infra scope |
| D2 | Outbox relay | **DoS** — a poison outbox row retried forever starves the dispatch batch | **Bounded retries → terminal `DEAD` + operator alert** (ADR-0050 N5); `concurrentExecution=SKIP`; sequential per-aggregate dispatch | `FOR UPDATE SKIP LOCKED` claim for multi-writer — *planned* |
| E1 | Roles | **Elevation** — reader triggers a posting | Mutations gated by `ROLE_OPERATOR`; read roles exclude write capability; no posting logic on read endpoints; deny-by-default once OPA enforce mode is on (ADR-0034) | OPA still advisory — *open* |
| T4 | Outbox/Kafka | **Tampering** — downstream consumes a non-emitted, reordered or duplicated event | Transactional outbox (single DB tx with the posting); dispatch runs on the Vert.x event loop so it actually drains (ADR-0050 N1, was `HR000068`); **deterministic Kafka key = `aggregate_id`** preserves per-account order (N2); **`event.id` carried as `ce-id`/`idempotency-key` header** for consumer dedup (N3); idempotency key on posting dedupes retries | Schema-compat on event change (advisory gate); signed event provenance — *planned* |
| S2 | OIDC client secret | **Spoofing (shared-credential blast radius)** — ledger's `OIDC_CLIENT_SECRET` is projected from the **shared** Vault key `account-service` (all services reuse the single `openbank-services` Keycloak confidential client, see `gitops/components/ledger/oidc-externalsecret.yaml`). Compromise of that one Vault key would let an attacker mint bearer tokens accepted by **both** account-service and the ledger money path — a single secret is a single point of forgery across services. | Secret is Vault-projected (never in git/state); ExternalSecret `deletionPolicy: Retain`; the Keycloak client is **confidential** (not public), so the secret alone is required and it is access-controlled in Vault; ledger write endpoints additionally require `ROLE_OPERATOR` (S1/E1), so a forged service token still cannot post without the operator role claim. | **Shared-credential blast radius is accepted for sandbox only.** Tightening = a dedicated Vault path + per-service Keycloak client for ledger (planned, §5). **Production go-live requires the second money-path approver to explicitly sign off this residual** (ADR-0030). — *open* |

## 4. Key invariants (must never regress)

- A `JournalEntry` cannot reach `POSTED` unless balanced per base currency.
- `subAccountId` is permitted **only** on deposit-control legs (2100–2103).
- Posted entries are immutable; the only correction is a balanced reversal that preserves all dimensions.
- **Period lock (#869):** once a fiscal year is `ATTESTED`, no posting **or reversal** may land in it
  (`LedgerService.requireOpenPeriod`, checked on `postJournal`/`reverseJournal` by `entryDate.year`,
  derived for a reversal from the original it preserves). A late posting into a sealed year would
  silently invalidate the attested trial-balance content hash; the lock makes that a 409, and the
  read-only re-verify endpoint (`GET /close/{year}/verify`) is the **detective** control that proves a
  sealed period is still hash-identical (or surfaces drift) without flipping state. Corrections to a
  closed year must be booked as an adjustment in the current open period.
- **Four-eyes year-close attestation (#869):** the attestor (checker) MUST differ from the draft
  author (maker, `YearCloseRecord.draftedBy` — recorded as the verified JWT subject on every
  create/refresh of the DRAFT). Enforced **fail-closed** in both `YearCloseService.attest` (409) and
  the domain `YearCloseRecord.attest` (a `check`, defense-in-depth): a self-attest is rejected, and a
  **null** `draftedBy` (a draft predating four-eyes tracking) can **never** be attested — it is a 409
  ("refresh it"), never a silent bypass. A four-eyes bypass would defeat segregation of duties on the
  statutory close, so a null or self-equal author always fails closed.
- Idempotency key ⇒ at-most-once posting per `(idempotencyKey, transactionId)`. Idempotent replay is
  checked **before** the period lock, so replaying an entry booked while the year was open stays
  idempotent even after the year is later attested.
- **No endpoint is `@PermitAll`** — read paths are role-gated and locked by `LedgerSecurityContractTest`.
- Outbox dispatch runs reactively on the event loop and is **single-writer** (`replicas: 1` + in-JVM
  `SKIP`); a posted row's event is published exactly once per successful tick or bounded to `DEAD` (ADR-0050).
- Domain-metric labels are **low-cardinality and PII-free** (currency + a closed `type` set; gauge by `service`);
  never an account id, IBAN, amount, party, or entry id (ADR-0077 cardinality contract).

## 5. Open items / follow-ups

- ~~Read endpoints `@PermitAll`~~ — **closed (K7 / ADR-0018):** reads role-gated; declarative contract
  locked by `LedgerSecurityContractTest`. Remaining: enforce OPA fine-grained authz (ADR-0034) — currently advisory.
- ~~Outbox dispatch fails on every tick (`HR000068`)~~ — **closed (ADR-0050 N1):** dispatch returns
  `Uni<Void>` and runs on the event loop; deterministic key + carried `event.id` (N2/N3); bounded `DEAD` (N5).
  Remaining: `FOR UPDATE SKIP LOCKED` single-writer claim for multi-writer topologies (N4 refinement); full
  `headers` JSONB CloudEvents envelope (ADR-0003).
- **Dedicated OIDC credential for ledger (S2):** ledger currently reuses the shared `account-service`
  Vault key / single `openbank-services` Keycloak client. Provision a per-service Vault path and a
  dedicated confidential client so a single key compromise no longer spans the money path. Sandbox
  risk-accepted; **prod go-live blocked on this or an explicit second-approver sign-off of S2.**
- Wire signed audit / evidence bundle (ADR-0029 D2) for non-repudiation of postings.
- Mutation testing (pitest) on `validateBalance` / reversal math (ADR-0030 D3).
- Phase C: emit `AccountBookedChangedEvent` from ledger as the projection trigger (ADR-0039).

## 6. Tie-out endpoint (`GET /api/v1/control-accounts/{id}/tie-out`)

Added in Phase B to make the scheduler's daily invariant queryable by auditors and ops.

| # | Threat | Control |
|---|--------|---------|
| T5 | **Information disclosure** — tie-out response contains per-currency GL aggregates | Endpoint requires `SERVICE`, `AUDITOR`, `OPERATOR`, or `ADMIN` role (no `VIEWER`, no unauthenticated). Role enforcement locked by `LedgerSecurityContractTest`. |
| T6 | **Enumeration** — caller probes UUIDs for non-existent control accounts | Empty list returned for unknown `controlAccountId`; no 404 distinguishable from zero-activity account — timing-safe. |
| T7 | **Denial of service via large `asOf` range** — `asOf` is a single date; the query is bounded to `entry_date <= :asOf` over a single control account | Query always scans a single account; index on `(account_id, entry_date)` (V7 migration) keeps cost proportional to account volume, not total ledger size. |
| T8 | **TieOutScheduler silent failure** — exception in one currency skips remaining currencies | Per-currency `try/catch` logs `ERROR` and continues; `openbank.subledger.tieout.break` is a counter (non-zero = alert), not suppressed by exceptions. |
