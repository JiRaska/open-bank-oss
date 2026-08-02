---
date: 2026-06-16
decision-status: accepted
delivery-status: partial
authors: [@JiRaska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [accounting-close, ledger, compliance, regulatory-reporting]
summary: "A net-new openbank-statutory-close-service builds the entity-level statutory close in-house with the ledger as sole golden source, freezing GL periods into an immutable attested trial balance."
---

# 96. Entity-level statutory accounting close (GL period freeze, attested trial balance, financial statements, EoY)

Relates to: ADR-0039 (ledger golden source, balance projection), ADR-0078 (per-customer
statement close), ADR-0035 (statement periods), ADR-0026 (EoD reconciliation),
ADR-0037 (AnaCredit render-only reporting). Closes the gap tracked in issue JiRaska/open-bank#471.
Prerequisite for: ADR-0097 (supervisory / prudential returns — FINREP/COREP).

## Context

The platform has a per-**customer** statement close (ADR-0078/0035) and a daily reconciliation
(ADR-0026/0039), but **no entity-level statutory accounting close**. Issue #471 records the gap and
the obligations each missing piece maps to:

- **Účetní závěrka** — financial statements (rozvaha, výkaz zisku a ztráty, příloha) at the
  balance-sheet date — *zákon č. 563/1991 Sb. §18–19; bank form per vyhláška ČNB 501/2002 Sb.*
- **GL period freeze + trial-balance attestation** — signed, immutable per period. Today the ledger
  trial balance is a **read API only** (`/api/v1/journals/trial-balance` — point-in-time query, not a
  frozen artefact) — *zákon 563/1991 průkaznost/úplnost; auditor attestation.*
- **Fiscal-year (EoY) close** — year-end cut-off, result allocation, opening-balance roll.

The per-customer statement close MUST NOT be presented as a statutory close: it closes *customer
sub-ledgers*, not the *entity's general ledger*, and emits no attested, immutable, entity-wide
artefact. All existing reporting (AnaCredit render-only ADR-0037, withholding tax, reconciliation,
analytics) is **point-in-time / event-fed**, not derived from an attested period close — it
complements but does not substitute one.

**Why now:** a statutory close is **license-gated** — it becomes mandatory when pursuing a banking
licence, and prudential returns (ADR-0097) cannot be produced without it as their attested source.
Recording the architecture now (even at Proposed) unblocks the dependent FINREP/COREP work and keeps
the ledger's golden-source invariant intact as the foundation.

## Decision

Build an entity-level statutory close as a **net-new bounded context**
(`openbank-statutory-close-service`), with the **ledger as the sole golden source** (ADR-0039). We
**build** the close orchestration in-house (the ledger already owns double-entry truth; outsourcing
the GL-close engine would fork the source of truth) and **render** statements from the frozen artefact.

1. **GL period freeze (the attested artefact).** At a period boundary (month / quarter / year) the
   close service requests an immutable trial-balance snapshot from the ledger for `[from, to]`: the
   ledger marks those journals **closed** (no further postings dated into a frozen period; late entries
   post to the next open period with an audit link), computes the per-account debit/credit/balance
   trial balance, and persists it as a **signed, immutable `ClosedPeriod` artefact** (cosign/KMS or the
   audit-chain ADR-0086 hash-chain). This replaces "trial balance is a read API" with a frozen,
   reproducible, attestable record. The attestation is filed in `attestations.yaml`-style governance
   with the period id, hash, and signer.

2. **Financial statements (rozvaha / výkaz zisku a ztráty / příloha).** A mapping layer projects the
   frozen trial balance onto the ČNB bank statement forms (vyhláška 501/2002 Sb.) via a declarative
   **chart-of-accounts → statement-line** mapping (versioned config, not code), rendered to a
   structured artefact + human-readable PDF. Pure projection over the frozen TB — no recomputation.

3. **Fiscal-year (EoY) close.** Year-end cut-off freezes Q4/December, allocates the result
   (P&L → retained earnings via closing journals posted *by* the ledger, audit-linked), and rolls
   opening balances into the new fiscal year. Builds on the existing EoY trial-balance attestation
   increment (#868) rather than replacing it.

4. **Boundaries.** The close service is **read-mostly on the ledger** (requests freezes + reads frozen
   TBs); it never posts business journals itself (only the ledger does, including the EoY result-
   allocation journals it is asked to post). Statements + attestations surface in the admin-ui
   `regulatory` page. Scope of *this* ADR: statements + attested close. Prudential returns are ADR-0097.

## Delivery

**Increment 1 — the ledger-side period freeze (this ADR's D1). Shipped.**

- `AccountingPeriod` / `PeriodType` — a statutory period is a whole MONTH, QUARTER or YEAR, never an
  arbitrary window; a partial range is rejected by the aggregate, so two closes cannot overlap and
  disagree about the same journal.
- `PeriodTrialBalance` — the trial balance over `[from, to]`, with a deterministic canonical JSON
  and its SHA-256. This is what replaces "the trial balance is a read API":
  `/api/v1/journals/trial-balance` answers a point-in-time question and changes under you, whereas a
  frozen record is the same numbers made reproducible and re-verifiable.
- `ledger_closed_period` (Flyway `V22`) + `ClosedPeriodRecord` — DRAFT → FROZEN, four-eyes at the
  freeze (checker != maker, and a draft with no recorded author can never be frozen), with the hash
  re-verified fail-closed against a fresh computation at freeze time.
- `PeriodFrozen` on the existing outbox, in the same transaction as the flip. Consumers react rather
  than polling, for the reason ADR-0207 D4 gives.
- `PeriodFreezeLock` on the posting path, between the day lock and the fiscal-year lock. The three
  are not redundant: a day can be LOCKED inside an unfrozen month, and a month FROZEN inside an
  unattested year, so neither neighbour can express this state. A reversal out of a sealed period is
  routed forward rather than refused.
- Operator endpoints under `/api/v1/ledger/periods`; OpenAPI `info.version` 1.12.0 → 1.13.0.

**The period lock ships in `shadow`** (`openbank.ledger.period-lock.mode`), like the day lock and for
the same reason: it records what it would have refused and refuses nothing, so the volume of
postings landing in already-frozen periods is measured before any start failing (#1197).

**Prerequisite that had to land first.** The freeze asks "has this period ended", which is an
accounting-date question — so it consumes ADR-0207's `AccountingClock` rather than a wall clock.
Before ADR-0207 there was no single answer to that, and the close cutoff would have been decided by
whichever clock object was asked.

**Not built, and not inferable — this is where the increment deliberately stops.**

Decision point 2 (financial statements: rozvaha / výkaz zisku a ztráty / příloha per vyhláška ČNB
501/2002 Sb.) needs the real chart-of-accounts → statement-line taxonomy. That is regulatory content,
not a design choice: a plausible-looking mapping invented here would render an artefact that passes
every gate in this repo and is wrong, which is the worst failure shape available — a control green
about nothing. It needs the actual form definitions supplied before it can be built.

Decision point 3 (EoY result allocation and opening-balance roll) builds on the existing fiscal-year
attestation (ADR-0078 D5 / #868) and is a separate increment.

## Alternatives considered

- **Outsource the GL-close engine to a vendor** — buy the close orchestration rather than build it. Rejected — the ledger already owns double-entry truth, and outsourcing the close would fork the source of truth; only the statement *forms* mapping is left open to a vendor template later.
- **Keep the status quo: treat the existing per-customer statement close as the statutory close** — rely on ADR-0078/0035 period closes. Rejected — those close *customer sub-ledgers*, not the entity's general ledger, and emit no attested, immutable, entity-wide artefact.
- **Keep the trial balance as a read API only** — continue serving `/api/v1/journals/trial-balance` as a point-in-time query. Rejected — a point-in-time query is not a frozen, reproducible, attestable record and does not satisfy průkaznost/úplnost.
- **Rely on the existing point-in-time / event-fed reporting** (AnaCredit render-only ADR-0037, withholding tax, reconciliation, analytics). Rejected — these complement but do not substitute a close, as none derives from an attested period close.

## Consequences

**Positive:** a real, attestable statutory close satisfying zákon 563/1991 + vyhláška 501/2002;
an immutable per-period artefact (průkaznost) that auditors and ADR-0097 can build on; the ledger's
golden-source invariant is preserved (close reads, ledger posts).

**Negative / open:** net-new service (money-path-adjacent, needs a threat model per ADR-0030); the
ledger gains a "frozen period" concept (posting-date guard + late-entry routing) — a non-trivial
ledger change; the CoA→statement-line mapping is regulatory config that must track vyhláška changes;
license-gated, so this is **Proposed** until a licence track is prioritised.

**Build vs outsource:** build the orchestration + freeze (ledger-native); the statement *forms* mapping
could later adopt a vendor template, but the attested TB and close stay in-house.

## Compliance impact

- PCI DSS: not applicable — entity-level general-ledger close, no cardholder data in scope.
- DORA:    not applicable — accounting-close architecture, not an ICT resilience control.
- GDPR:    not applicable — entity-level GL aggregates, not personal data.
- PSD2:    not applicable — no payment initiation or account-access interface involved.
- CNB:     zákon č. 563/1991 Sb. §18–19 (účetní závěrka, průkaznost/úplnost) and vyhláška ČNB č. 501/2002 Sb. (bank financial-statement forms); auditor attestation of the frozen trial balance.

## References
- Issue #471 (gap placeholder); ADR-0039, ADR-0078, ADR-0035, ADR-0026, ADR-0037, ADR-0086.
- zákon č. 563/1991 Sb. (o účetnictví) §18–19; vyhláška ČNB č. 501/2002 Sb. (bank financial statements).
