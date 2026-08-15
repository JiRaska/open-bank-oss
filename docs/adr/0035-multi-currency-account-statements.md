---
date: 2026-05-30
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [statements, accounts, fx]
summary: "A dedicated openbank-statement-service assembles statements without posting authority; the per-pocket statement is the unit for camt.053 and MT940, and only the human-facing consolidated PDF adds an informational CZK total."
---

# 35. Multi-currency account statements (camt.053 / MT940 / PDF)

## Context

The multi-currency rollout plan (`docs/strategy/multicurrency-implementation-plan.md`, Phase 4
item #12, finding **G4**) records a gap: **no account statements**. A regulated bank must issue
periodic and on-demand statements; corporate customers and PSD2/account-information flows need
machine-readable formats, retail customers need a human-readable PDF.

This is non-trivial for the multi-currency current account. ADR-0024 / ADR-0025 established **one
IBAN with N per-currency pockets** and per-currency ledger balancing. A statement is, by regulation
and by ISO 20022, **per account *and* currency**: you cannot mix EUR and CZK movements with a single
running balance. So the single-IBAN account produces **one statement per pocket**, plus a
human-facing **consolidated** view across pockets.

The booked-entry source of truth is `openbank-transaction-service` (`Transaction`: `amount: Money`
with currency, `bookingDate`, `valueDate`, `type`, `referenceNumber`, `status`); per-pocket
opening/closing balances come from `openbank-balance-service`. No service today assembles, numbers,
formats, retains, or serves statements. This ADR settles **where statements are produced, what the
unit of statement is, how the three formats relate, how statements are numbered and made immutable,
and how period boundaries reconcile** — so that the formats are projections of one audited model
rather than three divergent pipelines.

## Decision

### A. A dedicated `openbank-statement-service`

Statement assembly is its own bounded context, not an extension of account- or transaction-service.
It **reads** booked entries from transaction-service and opening/closing balances from
balance-service; it **never moves money** and holds no posting authority. Isolating it keeps the
heavy formatting/PDF dependencies and the legal-retention concern out of the money-path services and
gives statements an independent release cadence. Hexagonal per ADR-0002: domain owns the canonical
statement model and the format projections; infrastructure owns the cross-service reads and storage.

### B. The per-pocket statement is the unit; consolidated is a derived envelope

Each pocket (IBAN + currency) yields its own camt.053 `<Stmt>` and its own MT940 `:25:` block with an
independent legal sequence. The **consolidated** statement is a human-facing envelope (PDF) that
stacks the per-pocket statements and adds an **informational** reference-currency (CZK) grand total
computed at the ČNB rate on the statement date — clearly labelled non-accounting, since pockets are
not netted (ADR-0024). Machine formats (camt.053, MT940) are emitted **per pocket only**; there is no
multi-currency machine statement, because no standard one exists and netting currencies would be
wrong.

### C. Three renderers over one canonical `StatementModel`

The domain builds a single canonical statement aggregate once — opening balance, ordered booked
entries, closing balance, the two sequence numbers (D), period, account/pocket identity — and renders
it to **camt.053.001.08** (ISO 20022 XML), **MT940** (SWIFT MT text), and **PDF**. The formats are
pure projections of the same audited model; a number shown on the PDF is the same field serialized
into camt.053. No format re-derives balances or re-queries source data.

### D. Statements are immutable, sequence-numbered legal records

camt.053 / MT940 require a **legal sequence number** (statement page, monotonic per pocket) and an
**electronic sequence number**. The unit that carries those numbers is the **period close** (F): once
a period is closed it is **immutable** and retained (E). Re-rendering the same period reproduces
**byte-identical** output (deterministic rendering off the stored model); a correction is a **new**
period close with the next sequence and an explicit reference to the superseded one — never an
in-place edit. What is persisted and retained is the **canonical model plus its sequence/balance
anchors**, never the rendered camt/MT/PDF bytes — those are replayed on demand (F). Storing rendered
files would mean retaining (for 10 years) artefacts a customer views perhaps once; the model is the
durable record, the file is a view of it.

### E. Booked-only, value-dated, reconciled period boundaries — fail closed

A statement contains only **booked** entries (transaction `status = COMPLETED`) selected by
`bookingDate` within the period; `valueDate` is carried per entry (camt `<ValDt>` / MT940 value
date). `openingBalance` = the prior statement's `closingBalance`; `closingBalance` = opening ± the
period's booked movements, and it **must equal** balance-service's closing balance for that pocket. A
mismatch **fails the statement** (no partial issue, alert raised) rather than emitting a
self-inconsistent legal document — the statement analogue of the fail-closed stances in ADR-0032 /
ADR-0033.

### F. Rendering is on-demand; the only scheduled job is a cheap period-close

Rendered files are **never pre-generated or stored**. PSD2 Art. 58(2) requires the transaction
information to be *"provided **or made available** … at least once a month, in a manner which allows
the user to store and reproduce it unchanged"* — **"made available" (pull/on-demand) satisfies the
standard**; nothing obliges the bank to push or warehouse a monthly file. So we split two concerns the
naive design conflates:

1. **Period-close — scheduled, monthly, cheap, mandatory cadence.** Per account/pocket a job closes
   the calendar-month period: it assigns the next **legal + electronic sequence**, snapshots
   opening/closing balances, runs the **fail-closed reconciliation** (E), and persists a small
   `StatementPeriod` metadata record (period bounds, sequences, balance anchors, model source range).
   It emits **no camt/MT/PDF bytes**. This is what guarantees monthly availability and a monotonic
   legal sequence. Idempotent on `(accountId, pocketCurrency, period)` — a re-run returns the existing
   close, never a duplicate sequence.
2. **Render — on-demand only.** camt.053 / MT940 / PDF are produced **deterministically when
   requested** (customer, AIS, or ad-hoc API), replayed byte-identically from the closed period + its
   booked entries. Nothing is stored.
3. **Ad-hoc range export — on-demand, non-sequenced.** An arbitrary date range yields an
   *informational* export that carries **no** legal sequence (it is not a statement page), clearly
   distinct from the numbered period statement.

Period-close emits a versioned `account.statement.period.closed` event (outbox) for notification
(e.g. "your statement is ready") and downstream archival. eIDAS sealing (future) is downstream of an
on-demand render and gates nothing. **Out of scope:** the PAD Art. 5 annual *statement of fees* is a
genuine **push** obligation (provided ≥ yearly on a durable medium) but is a fee-domain artefact, not
a transaction statement — owned elsewhere, noted here only to bound this service.

## Alternatives considered

- **Generate statements inside account-service.** Pros: one fewer service. Cons: pulls XML/MT/PDF
  libraries and a 10-year retention store into a money-path-adjacent service; couples statement
  cadence to account releases. **Rejected** — separate bounded context (A).
- **Generate from the ledger GL (double-entry journal).** Pros: GL is the deepest truth. Cons: the GL
  is internal double-entry across many GL accounts; a *customer* statement is the customer-facing
  account view (one running balance per pocket), which is exactly transaction-service's booked
  entries. **Rejected** — read transaction-service, reconcile against balance-service.
- **Store only the rendered PDF.** Pros: simplest archive. Cons: corporate customers and PSD2 AIS need
  machine-readable camt.053 / MT940; re-rendering other formats later is impossible without the model.
  **Rejected** — persist the canonical model, render on demand (C/D).
- **One consolidated multi-currency machine statement.** No ISO 20022 / MT940 form exists for it and a
  single running balance across currencies is meaningless. **Rejected** — per-pocket machine
  statements + consolidated PDF only (B).

### Implementation status (2026-06-06)

Accepted and deployed (statement-service v0.2.0, sandbox). Two notes on how the decision landed:

- **Account enumeration for the scheduled close** is sourced from a local read-only **account registry**
  — a Kafka projection of the account-service `AccountCreated` stream (topic
  `openbank.accounts.account.created`), not a direct "all accounts" endpoint (account-service owns its
  own DB, ADR-0002). The scheduler enumerates `accountRegistry.allAccountIds()`.
- **§D/§F's byte-identical promise was not true until 2026-08-09 (issue #3986).** The close persisted
  only the metadata record, so `render()` rebuilt the canonical model at request time from two LIVE
  projections — transaction-service's booked entries for the (already closed) window, and the
  account's current IBAN/holder name. A late entry booked into the closed window, or a holder rename,
  therefore changed an already-issued legal statement page, with the stored closing balance staying
  put so the document also stopped reconciling. The close now freezes those inputs (`StatementSnapshot`
  → `statement_period.model_snapshot`, Flyway V7) and a closed period renders purely from stored
  state. This does **not** weaken §F: no camt/MT/PDF bytes are stored, only the canonical *model* —
  which is exactly what this ADR's own "Alternatives considered" already chose ("persist the canonical
  model, render on demand"). §F.1's "a small `StatementPeriod` metadata record" should be read as
  including that snapshot. **Periods closed before V7 have no snapshot and still replay live data**;
  they are deliberately not backfilled, because the live projections may already have drifted from
  what was issued and freezing today's answer would make the drift canonical. §D/§F are therefore
  true for every period closed from V7 onward, and best-effort for older ones.
- The **scheduled monthly close stays disabled** (`openbank.statement.scheduled-close.enabled=false`);
  the per-customer close model here is deliberately *completeness-over-atomicity*, and the operational
  hardening required before enabling the cron (self-healing catch-up, run-outcome persistence,
  transactional-outbox atomicity fix, monitoring/alerting) is owned by **ADR-0069**.

## Consequences

**Positive**
- Closes the G4 gap with standards-conformant machine formats plus a human PDF, per pocket and
  consolidated.
- One audited canonical model; the three formats cannot drift, and balances are reconciled at
  period-close before anything is rendered.
- Period closes are immutable, sequence-numbered, reproducible legal anchors with a clear correction
  path.
- **No file warehouse**: only lightweight period-close metadata is retained; renders are produced
  on demand and discarded. Storage and the retention footprint stay small, matching the reality that
  most statements are never viewed.

**Negative**
- A new service with cross-service reads (transaction + balance), a (small, metadata-only) retention
  store, and format libraries to own and operate.
- Reconciliation can block period-close (by design) — requires an operational runbook for mismatches.
- On-demand rendering must be **provably deterministic** (same period ⇒ byte-identical bytes); this is
  a hard test target, since reproducibility now rests on the renderer, not a stored file.

**Neutral**
- Consolidated reference-currency totals are informational only; pockets remain un-netted.
- eIDAS sealing of the PDF is noted as a future enhancement, not in v1.
- The PAD Art. 5 annual statement of fees (a push obligation) is deliberately **not** this service's
  concern.

## Compliance impact

- **ISO 20022** `camt.053.001.08` (bank-to-customer statement) and **SWIFT MT940** (customer
  statement) for machine formats; legal + electronic sequence numbers per the standards.
- **CZ accounting / AML retention**: the canonical model + period-close anchors are retained 10 years
  (zákon č. 563/1991 Sb. o účetnictví; AML record-keeping) so any past period reproduces unchanged;
  rendered files are **not** stored — retention is on the reproducible record, not the artefact (D/F).
- **PSD2 Art. 58(2)**: transaction information must be *provided **or made available** at least monthly
  in a form the user can store and reproduce unchanged*. We satisfy this by **making it available**
  (on-demand render off a monthly period-close), not by pushing/warehousing files; camt.053 feeds AIS.
  Customers retain the contractual right to request a monthly statement free of charge.
- **PAD Art. 5 (out of scope, noted):** the annual *statement of fees* is a separate **push** duty on
  a durable medium, owned by the fee/billing domain — not a transaction statement produced here.
- **GDPR**: statements contain personal + financial data — access-controlled issuance, retained on a
  defined legal basis, minimised to the statement record.
- **DORA**: statement-service depends on transaction- and balance-service — resilience (retry/timeout)
  on the reads and the fail-closed reconciliation (E).
- **eIDAS**: qualified electronic seal on the PDF is a documented future enhancement.

## References

- `docs/strategy/multicurrency-implementation-plan.md` — Phase 4 item #12, finding **G4**.
- ADR-0024 / ADR-0025 — single-IBAN multi-currency pockets and per-currency ledger balancing (the
  per-pocket-statement rationale).
- ADR-0032 / ADR-0033 — the fail-closed, pure-model + projection shape reused here.
- **PSD2 (EU) 2015/2366 Art. 57–58** — transaction information "provided or made available … at least
  once a month"; the "made available" standard underpinning on-demand rendering (F).
- **PAD 2014/92/EU Art. 5** — annual statement of fees (push duty, fee domain — scope boundary).
- CJEU **C-375/15 (BAWAG)** — "durable medium" / "provided" vs "made available" distinction.
- `openbank-transaction-service` `Transaction` (booked-entry source); `openbank-balance-service`
  (per-pocket opening/closing balances).
