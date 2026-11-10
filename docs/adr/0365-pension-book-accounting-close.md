---
date: 2026-11-08
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
followup: "#13167 — source adapter coverage, opening/cutover evidence, professional approval and a full live month"
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, accounting-close]
summary: "Bind pension accounting to source facts and separate ledger instances; close each book against its own retained reconciliation and completion evidence."
---

# ADR-0365 — Source-bound pension accounting and independent book close

## Context

ADR-0337 separates the company's ledger from the bank. ADR-0338 requires separate
books for each participant fund, but the fund register has no accounting delivery.
A published NAV proves a valuation, not receipt or payment of money. A successful
bank deposit-control tie-out cannot reconcile either pension book.

This is an engineering chart proposal for review, not a declaration that the
chart, fee calculation or statutory statements have accountant or auditor approval.
The fee amount comes from the existing maker/checker-approved NAV; this decision
does not invent a new tariff or silently change its calculation basis.

## Decision

### Book identity and chart ownership

Use one ledger database/deployment per book: BANK, PENSION_COMPANY, and
PENSION_FUND with an immutable fund id. The destination verifies its identity on
every pension request, including an idempotent replay. The bank refuses pension
accounting requests. A fund and its management company never share a destination.
The ledger owns account mappings; product services send accounting purpose and
source identifiers, never arbitrary GL ids. CZK is the initial supported currency;
other currencies fail closed until a reviewed chart and FX policy exist.

The shared fast jar registers a Java Flyway callback which selects the chart from
the runtime book identity. Flyway SQL locations are fixed during Quarkus augmentation;
changing them only at runtime cannot discover a newly mounted chart. Fund accounts
come from the packaged fund chart. The company callback reads the existing mounted
company chart through `openbank.ledger.book.chart-script`, then installs fee mappings.
It binds identity before changing accounts and refuses changed identities, bank reuse,
or an unbound database with journal history. An existing book needs a reviewed cutover.

### Source facts and proposed mappings

Debit and credit below describe a positive amount; a correction reverses the
sides for a negative delta. Codes are repository codes, not an official chart.

| Source fact | Fund book | Company book | Recognition boundary |
| --- | --- | --- | --- |
| Approved NAV management fee | Dr PF5110 management expense / Cr PF2110 fee payable | Dr PS1310 fee receivable / Cr PS6110 fee income | NAV publication; correction posts only new minus superseded fee |
| Participant cash actually received by custodian | Dr PF1110 custody cash / Cr PF2210 unallocated participant money | None | Custodian/rail settlement reference, currency, value date and fund attribution |
| Fund unit subscription priced | Dr PF2210 participant money / Cr PF3010 participant capital | None | Register transaction linked to received and allocated money |
| Fund unit redemption priced | Dr PF3010 participant capital / Cr PF2220 payout payable | None | Register transaction; creates a payable, not a cash payment |
| Benefit/payout actually settled | Dr PF2220 payout payable / Cr PF1110 custody cash | None | Settled payment instruction linked to the fund and register transaction |
| Fee actually paid to company | Dr PF2110 fee payable / Cr PF1110 custody cash | Dr PS1110 company bank / Cr PS1310 fee receivable | Two separately confirmed settlement legs; never inferred from fee accrual |
| Custody position/valuation change | Instrument asset / valuation result according to reviewed instrument classification | None | Custodian holdings plus valuation provenance; statement ingestion itself is not a trade |

The first automatic accounting producer is the approved NAV management fee. The
other rows define purpose and recognition boundaries; absence of their source
bindings remains an explicit close blocker, not a successful empty accounting run.
Opening balances require attributable approved source evidence; no adapter infers
them from the current NAV or seeds plausible balances.

### Atomic delivery and correction

Commit fee intents in the same transaction as NAV publication, unit transactions
and holdings. Keep independent fund/company delivery receipts, a stable source
idempotency key and payload identity. An acknowledgement lost after posting retries
the same key; a partial two-book delivery stays pending. A correction creates new
intents containing the difference, linked to the superseded NAV; it never edits a
posted journal. A zero delta has an explicit NO_POSTING receipt, distinct from a
posted journal. No source fact is marked delivered while accounting is disabled.
The immutable business intent and both receipts outlive the transport outbox;
ordinary SENT retention cannot erase completion evidence or change its source identity.

### Independent close

Each book requires its own pension-fund NAV/fee completion producer, retained source
reconciliation and journal watermark. Fund controls cover cash intake/allocation,
priced units and pending orders, management fees, custody/prices/NAV, and outbound
settlement. Company controls cover its receivables, bank reconciliation, own
portfolio, expenses and tax sources. Initially an independent maker/checker proposal
attests these source categories and compares every enabled own-chart balance, including
zero balances; additional machine producers may be declared as their adapters ship. An empty or missing source must be explained
and attributable; a waiver retains actor and reason and cannot qualify a month for
enforcement. A reconciliation that predates completed work or a later journal is
not valid close evidence. Bank tie-out jobs remain disabled on pension instances.
Each destination is polled independently; failure remains observable after healthy
destinations refresh. Recovery reads the oldest 31 pending own days, including days
older than a fixed wall-clock lookback, without exposing journals or balances.

A fund uses a conservative daily NAV requirement until a reviewed due-calendar adapter
exists. Historical NAVs without accounting intents and pending calculated corrections
remain blockers. Existing funds therefore need a reviewed opening/cutover transition;
this implementation does not fabricate historical fee delivery.

A book may advance only with evidence scoped to that book. The bank's monthly
readiness endpoint does not certify pension books. Promotion remains a separate,
reviewed operational change after one complete representative calendar month.

### Compatibility and rollback

Add schema and optional request fields before enabling producers. Retain existing
NAVs unchanged; historical rows are not claimed as delivered. Delivery defaults off
and leaves durable intents visible. Deploy ledger readers before the producer.
Rollback disables delivery and redeploys the previous application; retain intents,
receipts, journals and migrations. Never reverse or drop financial history merely
to roll back software. Resume using the original keys.

## Alternatives considered

- Adding fund or company identifiers to the bank ledger would cross ADR-0337's
  isolation boundary and expose fund assets to bank consumers. Rejected.
- Posting directly while publishing NAV would split the local commit and two
  remote commits. A crash could lose or duplicate one leg. Rejected.
- Treating NAV publication, unit pricing or a successful scheduler sweep as cash
  settlement would assert a fact those sources do not observe. Rejected.
- Enabling enforcement immediately provides no monthly proof and can freeze an
  operational book. Keep activation separate from implementation.

## Consequences

Independent delivery can temporarily leave one book ahead; that condition must be
observable and prevents close. Additional ledger deployments and source adapters
are required. Unsupported cash attribution, opening balances or reconciliations
remain blocking work, not synthetic evidence of completion.

## Compliance impact

- PCI DSS: not applicable — no card data enters this accounting slice.
- DORA: retained evidence and recoverable delivery support operational control;
  this ADR makes no claim of regulatory compliance.
- GDPR: retain source identifiers and service actors, not participant personal data.
- PSD2: not applicable — fee accrual does not initiate a payment.
- CNB: separate books and source-bound evidence support review of pension accounting;
  the chart and recognition policy still require professional approval.

## References

- [ADR-0337](0337-pension-company-books-as-a-separate-ledger-deployment-per-legal-entity.md)
- ADR-0338 — pension fund domain and accounting boundaries.
- ADR-0342 — close participation and staged enforcement.
- [Zákon č. 427/2011 Sb., official e-Sbírka](https://e-sbirka.gov.cz/sb/2011/427)
  — separate fund property and management remuneration motivate the separation;
  the concrete mappings above are an engineering proposal.
