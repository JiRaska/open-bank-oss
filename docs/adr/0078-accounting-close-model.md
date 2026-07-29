---
date: 2026-06-06
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [accounting-close, ledger, statements, compliance]
summary: "Period-end close is modelled as completeness rather than atomicity, separating per-customer statement close from entity-level accounting close; daily, monthly and yearly closes get period locks and four-eyes attestation."
---

# Accounting close model: period-end taxonomy, completeness over atomicity

> **Migrated status note.** The pre-schema `Status:` line carried this prose,
> which the enum cannot hold; it is kept here rather than dropped:
> 2026-06-14 — implemented: End-of-Day, End-of-Month and End-of-Year closes are live (period lock + re-verify endpoint #985, four-eyes maker≠checker year-close attestation #1014); statement-close hardening (D3) and trial-balance attestation shipped. D5 entity-level statutory close remains explicitly out of scope per this ADR.

**Delivery note (updated 2026-06-30):**
- **Operational closes** — ✅ Shipped: End-of-Day, End-of-Month, End-of-Year closes live with period lock + re-verify endpoint, four-eyes maker≠checker attestation, statement-close hardening, and trial-balance attestation.
- **Statutory close** — ⬜ Deferred: entity-level statutory close (rozvaha/VZZ/EoY) explicitly deferred to ADR-0096; completeness alerting partial (runs but alert routing off).

**Correction (2026-07-29, closing audit #1302):** the "period lock" claim above is true only at
**fiscal-year** granularity — `LedgerService.postJournal` guards `requireOpenPeriod(entryDate.year)`
against ATTESTED years. **No day- or month-granularity lock exists in code**: `entryDate` is
caller-supplied and freely backdatable into days already tied out and statement-closed, and
reversals inherit the original `entryDate`, so a July reversal rewrites March. The month lock +
late-entry routing is the #1302 backlog (ADR-0096, delivery-status Planned). Read every "period
lock" mention in this ADR as "year lock" until that lands.

## Context

"Závěrka" (close) is an overloaded word in this platform. A single operational question —
*"must the close be closed as a whole?"* — exposed two conflations that this ADR exists to untangle,
because they drive different designs and map to different regulations:

1. **Atomicity vs completeness.** "As a whole" can mean *all-or-nothing within one run* (atomicity)
   or *the whole eligible set is provably closed, or has a recorded exception* (completeness). Not the
   same requirement.
2. **Per-customer statement close vs entity-level accounting close.** Minting a customer's monthly
   statement is a different artefact, owner and legal basis from the bank's statutory accounting close
   (účetní závěrka) at a balance-sheet date.

### The actual close calendar (verified against code, not docs)

| When (Europe/Prague) | Process | Service / ADR | Nature | Posts journals? |
| --- | --- | --- | --- | --- |
| Daily 14:40 | ČNB FX fixing **ingestion** | fx-service, ADR-0046 | Input data | No |
| Daily 15:00 | **FX revaluation** to ČNB rate (unrealised P&L to acct 5900) | ledger-service, ADR-0046/0025 | **Daily posting batch**, idempotent per business day | **Yes** |
| Daily (`24h`) | **Interest accrual** (IAS 1 accrual-basis income recognition) | lending-service, ADR-0028 | Per-arrangement, idempotent, catch-up | Yes |
| Daily 23:30 | Control-account ⇄ sub-ledger **reconciliation** (per-currency tie-out) | balance-service, ADR-0039 Phase A | Read-only, aggregate, **detective** | No |
| Off-peak | OLTP source ⇄ warehouse **reconciliation** (completeness/integrity) | per-service, ADR-0026 (BCBS 239) | Read-only, detective | No |
| Monthly, 1st 02:30 | Per-pocket **statement period-close** (sequence + balance anchors) | statement-service, ADR-0035 | Per-customer-per-pocket; **deployed, cron disabled** | No |
| Monthly | Withholding-tax **remittance assembly** (§38d ZDP) | interest-service, ADR-0033/0038 | Tax close-adjacent | Yes |

Non-close scheduled jobs: transactional-outbox dispatch (every service, ADR-0050), SEPA SDD mandate
expiry, ČNB sanctions-list refresh.

### What does **not** exist

- **No daily entity-level accounting cut-off** ("books closed for business date D" as one act).
- **No entity-level general-ledger period close, no trial-balance freeze/attestation, no fiscal-year
  (EoY) close, no statutory financial statements** (rozvaha / výkaz zisku a ztráty / příloha). The
  ledger is a continuous double-entry journal; a *trial balance exists only as a read API*
  (`LedgerTrialBalanceClient`, used by reconciliation) — a query, **not** a frozen period artefact.
- **No completeness or failure monitoring on the monthly statement close** (logs only; `/q/metrics`
  exists but is not scraped — no ServiceMonitor; no alert rules; no operator surface; alertmanager
  routing off in the sandbox).

### Three findings surfaced and verified while writing this ADR

- The monthly close is **not atomic across an account's currency pockets**: pockets are closed
  sequentially, each in its own transaction (`StatementService.closeMonth` →
  `transformToUniAndConcatenate` over currencies). A partial-account close (CZK committed, EUR failed)
  is possible.
- Within a single pocket the state write and the event are **two separate transactions** —
  `StatementService.persistClose` has **no** `@WithTransaction`, so `periods.save(...)` (one tx) then
  `outbox.append(...)` (another tx) are chained by `flatMap`. A crash between them yields a closed
  period with **no emitted event**: a lost-notification window that violates the transactional-outbox
  invariant (ADR-0003 / 0013) that this platform otherwise holds to regulatory grade (ADR-0050).
- The monthly cron closes **only the prior month** (`priorMonthBounds`), and a failed close persists
  **nothing**, so a failed/missed period is **never retried** by a later run.

## Decision

### D1 — A close taxonomy with explicit integrity semantics per axis

Adopt and document the calendar above. Each axis declares its integrity contract:

- **Reconciliation is where "as a whole" lives, not statement minting.** Two detective gates assert
  wholeness: the EoD per-currency control-account ⇄ sub-ledger tie-out (ADR-0039) and the OLTP
  source ⇄ warehouse completeness/integrity reconciliation (ADR-0026, a BCBS 239 control). Both must
  be **fail-loud** — drift records *and* alerts; a silent reconciliation is a defect.
- **FX revaluation and interest accrual are idempotent daily posting batches** with catch-up; they are
  per-position / per-arrangement and need no cross-unit atomicity.
- **Statement close is per-customer and independent** — no cross-customer accounting invariant.

### D2 — Completeness over atomicity for per-customer artefacts

For the per-customer statement close we choose **completeness + eventual-consistency + provability,
explicitly NOT batch atomicity.** Rationale:

- There is **no accounting invariant linking customers**; a statement is a standalone legal document
  (PSD2 Art. 58 "made available"), with its own per-pocket monotonic legal sequence (ADR-0024, whose
  "pockets are never netted" is verified).
- All-or-nothing across the batch creates a **poison-account failure mode**: one customer's
  reconciliation glitch would withhold the legally-required statement from every healthy customer —
  *worse* compliance, not better.

"Closed as a whole" is therefore a **completeness control over the period**, not a transactional
rollback:

> For period P, every eligible (account, pocket) has a closed `StatementPeriod`, **or** a recorded,
> queryable exception with a reason — provable from data, not from logs.

This completeness control should **reuse the ADR-0026 reconciliation contract** (per-aggregate
`max(version)` / counts; BCBS 239 completeness) rather than invent a parallel mechanism: "expected set
(registry) vs closed set (statement_period) vs exceptions" is the same shape.

### D3 — Statement close hardening (prerequisite to enabling the cron)

The monthly cron stays **disabled** until the close is operationally safe. Required before go-live:

1. **Self-healing catch-up.** Enumerate *unclosed* periods per (account, pocket) up to the last
   complete month and close them — not only "the prior month". A missed or failed period then
   auto-recovers on the next run (mirrors the accrual/FX-revaluation catch-up pattern).
2. **Persist run outcome + per-(account, pocket) exceptions** (run id, status
   `CLOSED` / `FAILED` / `SKIPPED`, reason). A reconciliation mismatch currently persists *nothing*.
3. **Fix the transactional-outbox atomicity** (the verified finding): write the `StatementPeriod` and
   its `period.closed` event in **one** transaction (wrap `persistClose` in `@WithTransaction`), and
   add a `period.close_failed` event for failed closes.
4. **Observability**: close counters (success/fail), a "last successful close" gauge, a ServiceMonitor
   scraping `/q/metrics`, and alert rules — **missed run** (no close within N hours of cron = critical)
   and **failed accounts** (≥1 reconciliation failure in a period = warning). Requires alertmanager
   routing (off in the sandbox).
5. **Operator surface**: extend admin-ui `/day-end` (the EoD view) to show the real EoM close — last
   run, success/fail/skip counts, per-exception detail, manual retry.

### D4 — Intra-account: keep per-pocket independence, surface partial closes

Per-pocket closes stay independent (ADR-0024: pockets are separate legal documents, never netted), so
an account's pockets are **not** atomic. The completeness control (D2) **must** flag a partial-account
close (some pockets closed, others pending/failed) as an exception so it is visible and retried, never
silently half-done.

### D5 — Name the gap: there is no entity-level statutory accounting close

The platform implements **no statutory entity-level accounting close**, and the per-customer statement
close **must not** be presented as one. Concretely absent, with the obligation each maps to:

| Missing capability | Obligation it would satisfy |
| --- | --- |
| **Účetní závěrka** — financial statements (rozvaha, výkaz zisku a ztráty, příloha) at the balance-sheet date | Zákon č. 563/1991 Sb. §18–19; bank form per vyhláška ČNB 501/2002 Sb. |
| **GL period freeze + trial-balance attestation** (signed, immutable per period) | Zákon 563/1991 *průkaznost/úplnost*; auditor attestation |
| **Fiscal-year (EoY) close** — year-end cut-off, result allocation, opening-balance roll | Zákon 563/1991; ČNB |
| **Supervisory / prudential returns derived from an attested close** (FINREP/COREP-shaped, ČNB výkazy) | CRR/CRD; ČNB reporting |

Relationship to what **does** exist (so this is not over-claimed as a total reporting void): the
platform has an **event-fed analytics/reporting layer** (ADR-0022/0023, with BCBS 239 / EBA / DORA /
GDPR controls), **AnaCredit** credit-exposure rendering (ADR-0037, render-only, no ČNB transport),
**withholding-tax** recording + remittance (ADR-0033/0038), and **reconciliation** (ADR-0026/0039).
These are **point-in-time / event-fed**, *not derived from an attested period close* — so they
complement, but do **not** substitute for, the statutory accounting close. For a licensed bank this is
a real, separate capability; it is **out of scope of the transaction platform at the current stage**
and tracked as its own future ADR. This ADR's job is to stop the gap from being silently assumed away.

## Alternatives considered

- **Atomic all-or-nothing batch close.** Rejected: no cross-customer invariant to protect; it would
  withhold legally-required statements from healthy customers on a single bad account (poison-account).
- **One monolithic nightly "close everything" job.** Rejected: couples independent cadences and bounded
  contexts (FX revaluation, accrual, reconciliation, statements, tax); one failure stalls all; defeats
  per-service path-scoped deploys and fault isolation.
- **Build the entity-level GL close / statutory-reporting engine now.** Deferred, not rejected: a large,
  distinct capability not required at the current licensing/scope stage; building it prematurely would
  entangle the OLTP platform with statutory-reporting concerns. Revisit via its own ADR.
- **Status quo (ad-hoc, logs-only, cron flipped on).** Rejected: no completeness proof, no monitoring,
  no retry of failed periods — fails the DORA data-integrity / operational-resilience expectation and
  the *průkaznost / úplnost* requirement of the Act on Accounting.

## Consequences

**Positive**
- One honest, documented close taxonomy; "as a whole" is pinned to where it belongs (reconciliation).
- Completeness without fragility: failed/missed periods self-heal and are provable, no poison-account.
- Reuses the ADR-0026 completeness contract instead of a parallel mechanism.
- The statutory-close gap is on the record and cannot be silently assumed.

**Negative**
- statement-service cannot go live (cron enabled) until D3 lands; deliberate.
- Three defects are now explicit and owned: non-atomic save/outbox (verified), partial-account closes,
  no retry of failed periods.
- Reconciliation's value depends on alerting the sandbox does not yet route (DORA gap).

**Neutral**
- Per-pocket independence (ADR-0024), per-currency balancing (ADR-0025), and the daily FX/accrual
  cadences are unchanged.
- No customer-facing API shape changes.

## Compliance impact

*Met* = operational today; *Partial* = mechanism exists but not operational/monitored; *Gap* = absent.

| Regulation / requirement | Satisfied by | Status |
| --- | --- | --- |
| **PSD2 Art. 58(2)** — transaction info *made available* ≥ monthly | EoM per-pocket statement close (ADR-0035), on-demand render | **Partial** — deployed, cron disabled, no monitoring (D3) |
| **PAD Art. 5** — annual *statement of fees* (push) | Fee/billing domain | **Out of scope** here (ADR-0035 bounds it) |
| **Zákon 563/1991 Sb.** — *průkaznost / úplnost* of records | Completeness control (D2) reusing ADR-0026; outbox integrity (ADR-0050) | **Gap → addressed by D2/D3** |
| **Zákon 563/1991 Sb. §18–19** — entity účetní závěrka / financial statements | — | **Gap** (D5, scoped out) |
| **Vyhláška ČNB 501/2002 Sb.** — control account backed by *analytická evidence*, provable tie-out | EoD reconciliation (ADR-0039 Phase A; per-account from Phase B) | **Partial** — aggregate-only, detective until ADR-0039 Phase D-2; alerting weak |
| **Vyhláška ČNB 501/2002 Sb.** — bank financial statements / prudential returns | — | **Gap** (D5) |
| **ČÚS 108–110** (banks, FX position) — daily revaluation at ČNB rate | FX revaluation 15:00 + per-currency balancing (ADR-0046/0025) | **Met** |
| **CRR/CRD + ČNB** — AnaCredit granular credit exposure | anacredit-service render (ADR-0037) | **Partial** — dataset only, no ČNB transport |
| **§38d ZDP** — withholding tax on credit interest + remittance | interest-service (ADR-0033/0038) | **Partial** — recording done; remittance assembly proposed |
| **BCBS 239** — risk-data aggregation: completeness, accuracy, integrity | Source/warehouse reconciliation (ADR-0026); analytics controls (ADR-0023); regulatory-grade outbox (ADR-0050) | **Partial** — controls exist; not extended to the statement close |
| **DORA** — data integrity, reconciliation, incident detection | Reconciliation + close monitoring/alerting (D1, D3) | **Partial** — reconciliation runs; alert routing off |
| **EBA guidelines** (ICT risk, SoD, monitoring) | ADR-0023/0026/0047/0050 | **Partial** |
| **Zákon o účetnictví / AML** — 10-year retention | Retained `StatementPeriod` model + ledger journal | **Met** |
| **GDPR** — personal + financial data, access-controlled, lawful retention | Access-controlled reads, retained on legal basis | **Met** |
| **PCI DSS** | n/a (no PAN in close) | n/a |

## References

- ADR-0003 / 0013 / 0050 — transactional outbox (pattern, libs primitives, regulatory-grade dispatch).
- ADR-0022 / 0023 — event-fed analytics/reporting layer; BCBS 239 / EBA / DORA / GDPR controls.
- ADR-0024 / 0025 — single IBAN + currency pockets (never netted); per-currency balancing + FX position.
- ADR-0026 — OLTP source-side reconciliation contract (completeness/integrity, BCBS 239) — reused by D2.
- ADR-0028 — lending bounded context; daily interest accrual (and IFRS 9 provisioning, on-demand only).
- ADR-0033 / 0038 — withholding tax recording + monthly remittance.
- ADR-0035 — multi-currency account statements; per-pocket monthly period-close; PSD2 Art. 58(2).
- ADR-0037 — AnaCredit granular credit-exposure reporting (render-only).
- ADR-0039 — ledger as golden source; control-account ⇄ sub-ledger reconciliation (the integrity gate).
- ADR-0046 — ČNB FX fixing ingestion + daily revaluation posting.
- ADR-0030 (threat models); ADR-0029 (governance-as-code).
- Actionable tail: statement-close hardening (D3) — issue JiRaska/open-bank#470; entity-level statutory close (D5) —
  issue JiRaska/open-bank#471 (future ADR when prioritised).
