---
date: 2026-05-30
decision-status: accepted
delivery-status: shipped
authors: [OpenBank core-banking]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, accounts]
summary: "A dedicated openbank-sdd-service owns the debtor-side SEPA Direct Debit mandate vault as system of record, with an explicit pure lifecycle state machine and fail-closed collection authorisation; money movement is delegated."
---

# SEPA Direct Debit (SDD) mandate lifecycle

## Context

The multi-currency current account (ADR-0024) can receive SEPA credit transfers
(`openbank-sepa-payment`, `openbank-sepa-instant`) but has no concept of a **SEPA Direct
Debit (SDD) mandate** — the standing authorisation by which a customer (the *debtor*) lets a
*creditor* pull funds from the EUR pocket. Without it the account cannot host the single most
common recurring-payment instrument in the euro area (utilities, insurance, subscriptions),
and we cannot honour the debtor-protection rights that come attached to it.

SDD is governed by the **EPC SEPA Direct Debit Rulebooks** — `EPC016-06` (SDD **Core**,
consumer scheme) and `EPC222-07` (SDD **B2B**, business scheme) — sitting on top of the
**SEPA Regulation (EU) 260/2012** and **PSD2 (EU) 2015/2366**. The forces that make this a
distinct bounded context rather than an extension of `sepa-payment`:

1. **A mandate is a long-lived stateful agreement, not a payment.** It has its own identity
   (Unique Mandate Reference, *UMR* + Creditor Identifier, *CID*), its own lifecycle
   (active → amended → suspended → cancelled → expired-after-36-months-idle), and outlives any
   single collection. `sepa-payment` models one-shot credit transfers.
2. **The debtor bank carries hard legal duties keyed to the mandate**, not to the payment:
   - **Unconditional refund right, 8 weeks** from debit for any authorised Core collection
     (PSD2 Art. 76 / SEPA Reg. Art. 5(3); CZ §177 zákona 370/2017 Sb.).
   - **13-month refund** for *unauthorised* collections (no/invalid mandate) (PSD2 Art. 73/77).
   - **B2B: mandatory mandate verification** — the debtor bank must check each collection
     against stored, customer-confirmed mandate data and **reject** if it does not match;
     B2B has **no** post-settlement refund right, so the pre-debit check is the only control.
   - **Debtor controls** the customer can set: block all SDD, allow-list / block-list specific
     creditors (CID/UMR), per-collection and per-period amount caps (PSD2 Art. 79 wording on
     blocking/limiting direct debits; an EPC-recommended debtor service).
3. **R-transactions are a state machine.** Reject (pre-settlement, technical), Refusal
   (pre-settlement, debtor says "not this one"), Return (post-settlement, debtor-bank-initiated
   e.g. insufficient funds / account closed, within 5 TARGET2 days), Refund (post-settlement,
   debtor-initiated, the 8-week / 13-month windows above), Reversal (creditor-initiated
   correction). Each has its own actor, deadline and effect on the mandate. This decision logic
   is pure and must be unit-testable offline.

We are building the **debtor-side** capability first: the account-holder *gives* mandates and
is *collected from*. The creditor side (our business customers *issuing* collections, which
needs CSM/clearing connectivity) is explicitly out of v1.

## Decision

We will add a dedicated bounded context **`openbank-sdd-service`** (hexagonal per ADR-0002,
port 8129), owning the debtor-side SDD mandate vault and the R-transaction decision logic.

**A) Mandate vault as the system of record for the debtor mandate.** An immutable-history
aggregate `SddMandate` keyed by `(creditorIdentifier, umr)`, bound to one account + EUR pocket,
carrying `scheme ∈ {CORE, B2B}`, `sequenceType ∈ {OOFF, FRST, RCUR, FNAL}`, debtor/creditor
identification, `signatureDate`, and lifecycle `status`. B2B mandates **must** be
customer-confirmed before they can authorise a collection (rulebook requirement); Core mandates
may be registered lazily from the first inbound collection.

**B) Lifecycle as an explicit, pure state machine.**
`PENDING_CONFIRMATION → ACTIVE → (AMENDED, stays ACTIVE) → SUSPENDED ⇄ ACTIVE → CANCELLED (terminal)`,
plus `EXPIRED (terminal)` reached automatically when a mandate has had **no collection for 36
months** (rulebook auto-expiry). Amendments (UMR, CID, debtor account, creditor name, scheme)
are recorded and surfaced as the `AMDT` markers the next collection must carry. All transitions
are a pure domain function returning the new state or a typed rejection — no framework, no clock
injection beyond an explicit `asOf`.

**C) Collection authorisation is a fail-closed decision, money movement is delegated.** Given an
inbound collection instruction (UMR, CID, scheme, amount, due date, sequence type), the domain
returns `Accept | Reject(reason) | Refuse(reason)` from, in order: mandate exists & ACTIVE →
scheme matches → **B2B mandate-data match** → sequence-type coherence (FRST before RCUR, single
FNAL) → debtor controls (block-all / block-list / amount caps). v1 **does not post the debit
itself** — an `Accept` emits `sdd.collection.authorised` for the ledger/payment path to execute,
exactly as `statement-service` never moves money. This keeps v1 off the money-path gate while
the irreversible posting stays with the services already hardened for it.

**D) R-transaction windows are computed, never guessed.** A pure `RefundPolicy` derives
eligibility from the debit date and scheme: Core `≤ 56 days` ⇒ unconditional refund; `≤ 13
months` ⇒ refund only if unauthorised; B2B ⇒ no post-settlement refund (must have been rejected
pre-debit). Returns carry the EPC reason code (`AC04`, `MS02`, `MD01`, …). Each R-transaction
emits a versioned event; refunds/returns that move money are likewise delegated.

**E) Pre-notification tracked, not enforced.** The creditor's duty to pre-notify the debtor
(≥14 calendar days before the due date unless otherwise agreed) is recorded against the mandate
so the customer can see it and so a missing pre-notification is a documented refusal ground; we
do not generate the notice (that is the creditor's obligation).

**F) Persistence and events follow house patterns.** One Flyway-migrated table per aggregate +
a transactional outbox (ADR-0045 plumbing), events on `openbank.sdd.event`, versioned
backward-compatibly. OpenAPI `info.version` tracks `version.txt`; contract test enforces it.

## Alternatives considered

- **Fold SDD into `openbank-sepa-payment`.** Pros: one fewer service. Cons: conflates a
  long-lived stateful *mandate* with one-shot *payments*; the refund/return state machine and
  36-month expiry have nothing to do with SCT; debtor-protection duties would be buried in a
  payment executor. Rejected — wrong aggregate boundary (ADR-0002, DDD).
- **Store no mandate register for Core (only B2B).** Permitted by the rulebook (Core does not
  oblige the debtor bank to hold the mandate). Cons: no debtor controls (block-list, caps), no
  basis for the customer-facing mandate list, weaker 13-month unauthorised-refund evidence.
  Rejected — modern debtor protection and PSD2 Art. 79 blocking need the register.
- **Execute the debit inside this service in v1.** Pros: one round-trip. Cons: makes the
  service money-path on day one (threat model + 2 approvals, ADR-0030), and duplicates posting
  logic the ledger already owns. Rejected for v1 — delegate via event, revisit as a fast-follow.

## Consequences

**Positive**
- The account becomes usable for euro-area recurring payments; debtor rights (8-week refund,
  13-month unauthorised, B2B pre-debit rejection) are modelled as first-class, testable logic.
- Clean aggregate boundary; the irreversible money movement stays with already-hardened services.
- Pure domain (lifecycle, authorisation, refund windows) is fully offline-unit-testable.

**Negative**
- A new service to operate. Collection *execution* and refund *posting* are deferred, so v1 is
  not yet end-to-end — it authorises and decides but relies on a fast-follow to move money.

**Neutral**
- Creditor-side issuing (we collect from others) and CSM/clearing connectivity are out of scope.
- CZ domestic *souhlas/povolení k inkasu* (CERTIS) is a separate instrument, not covered here.

## Compliance impact

- PCI DSS: not applicable (no card data).
- DORA:    standard service SLOs/observability; no new third-party ICT dependency in v1.
- GDPR:    debtor/creditor identification stored under existing data-minimisation + retention.
- PSD2:    Art. 64–77 authorisation & refunds (esp. **Art. 76** unconditional 8-week refund,
           Art. 73/77 unauthorised), **Art. 79** blocking/limiting direct debits.
- CNB:     zákon 370/2017 Sb. o platebním styku — §177 (vrácení autorizované inkasní platby),
           unauthorised-transaction provisions; SEPA Reg. (EU) 260/2012 Art. 5.

## References

- EPC016-06 SEPA Core Direct Debit Scheme Rulebook
- EPC222-07 SEPA Business-to-Business Direct Debit Scheme Rulebook
- Regulation (EU) No 260/2012 (SEPA end-date), Art. 5 mandate/refund rights
- Directive (EU) 2015/2366 (PSD2), Art. 64–79
- Zákon č. 370/2017 Sb., o platebním styku, §177 a násl.
- ADR-0002 (hexagonal), ADR-0045 (outbox/saga), ADR-0024 (pockets), ADR-0030 (money-path gate)
