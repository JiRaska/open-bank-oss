---
date: 2026-06-22
decision-status: accepted
delivery-status: shipped
authors: [Jiří Raška]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [transactions, payments]
summary: "The payment rail and instruction type become first-class controlled-vocabulary facts captured at origination and propagated through the journal, replacing the app-side heuristic that guessed transaction type."
---

# 103. Transaction rail & instruction type captured at origination

## Context

The customer app now renders a payment-type badge on every transaction — card,
SEPA, SWIFT, domestic one-off, standing order, direct debit (inkaso), subscription,
internal transfer, or incoming credit (openbank-app#121). But the platform does **not
record how money actually moved** as first-class data, so the app reconstructs the
type with a client-side heuristic (counterparty-name match against the customer's
standing orders, merchant-category keywords, credit/debit direction). That heuristic
is fragile, wrong on live data, and — worse — duplicates business knowledge the
*originating* service already had and threw away.

Concretely, today:

- `transaction-service`'s `Transaction.type` is `DEBIT | CREDIT | TRANSFER | FEE |
  INTEREST | REVERSAL | ADJUSTMENT` — the **accounting** classification (direction +
  posting kind), not the payment rail.
- The only rail-shaped field, the `channel` column, is vestigial: it defaults to
  `"API"` and **no creation path ever sets it** to a real rail. It is a search filter,
  not a recorded fact.
- There is **no merchant category** anywhere in `transaction-service`.

Meanwhile the rail is unambiguous *at the moment of origination*: `sepa-payment`
mints a SEPA credit transfer, `domestic-payment` a domestic CZ transfer,
`swift-payment` a SWIFT/international wire, `card-issuance`/card-authorization a card
spend, the standing-order executor a recurring instruction, `transfer` an own-account
move, a direct-debit collection an SDD pull. The `PaymentSagaOrchestrator` /
`PaymentJournalFactory` that writes the journal entry is the single choke point where
all of these converge — and the place where the rail is currently discarded.

The customer app is not the only consumer that needs this dimension. Statements
(payment-type sections), the spend forecast / analytics, dispute routing, AML
transaction-typology monitoring, and the prudential payment-type breakdowns in
FINREP/COREP (ADR-0097) all want an authoritative rail. Reconstructing it
independently in each consumer is the wrong altitude: the same fragile heuristic,
forked N times, none of it able to recover what only the originator knew (e.g. a
SEPA *credit transfer* vs a SEPA *direct debit*, or SCT vs SCT Inst).

The existing `Transaction` model already carries origination metadata stamped by the
initiator — `initiatedByPartyId`, `scaChallengeId`, `scaExemption` (ADR-0021). The
rail belongs in exactly the same place, captured the same way.

## Decision

**We will model the payment rail and the instruction type as first-class,
controlled-vocabulary facts, captured at origination, propagated through the
transaction event and API, and read (never reconstructed) by every consumer.**

### 1. Two orthogonal dimensions + one optional enrichment

These are independent and must not be collapsed into one enum (a SEPA payment can be
a one-off, a standing order, *or* a direct debit):

- **`PaymentRail`** — how the money physically moved / which scheme:
  `CARD`, `SEPA_CT`, `SEPA_INST`, `SWIFT`, `DOMESTIC`, `INTERNAL`, `CASH`, `FEE`,
  `INTEREST`, `UNKNOWN`.
- **`InstructionType`** — how the movement was instructed:
  `ONE_OFF`, `STANDING_ORDER`, `DIRECT_DEBIT`, `FUTURE_DATED`, `SYSTEM`, `UNKNOWN`.
- **`merchantCategory`** (optional enrichment) — MCC-derived category for card spend,
  populated by card-authorization *when the scheme provides it*. Never required.

`SUBSCRIPTION` ("the vampire") is **derived**, not a rail or instruction type: it is a
behavioural property discovered over time (a recurring same-merchant card/DD charge),
computed by a downstream enrichment, not knowable at a single payment's origination.
Modelling it as a rail would corrupt the rail taxonomy. The presentation badge maps
`merchantCategory = SUBSCRIPTION` (or a recurring-merchant detector) to the vampire
mark; the rail underneath stays truthful (`CARD`, `SEPA_CT`, …).

### 2. One source of truth for the vocabulary

The two enums + their AsyncAPI/OpenAPI schema fragments live in **`openbank-libs`**
(per ADR-0014 centralisation), so every service and the generated clients share one
definition.

### 3. Stamp at origination, enforce at the journal

Each payment-initiation path sets `(rail, instructionType)` on its
`CreateTransaction` command. `PaymentJournalFactory` is the enforcement point: it
requires a non-null rail and rejects `UNKNOWN` for any newly originated customer
movement (only legacy/system postings may be `UNKNOWN`). This keeps the rule in one
place rather than trusting each caller.

### 4. Persist, emit, expose

- **Persist:** `transaction-service` gains `rail`, `instruction_type`, and
  `merchant_category` columns (Flyway migration), carried on the domain `Transaction`
  alongside `scaExemption`. The vestigial `channel` column is deprecated and removed
  once nothing reads it.
- **Emit:** the fields are added to the transaction outbox event (ADR-0003 outbox,
  ADR-0006 AsyncAPI schema version bump) so statements / analytics / AML consume them.
- **Expose:** added to `transaction-service`'s `/transactions` `TransactionResponse`.
  The `customer-edge` proxies `/transactions` **verbatim** (ADR-0065 thin edge), so
  **no edge change is needed** — the fields flow straight through.
- **Consume:** the app's `TxDto` (which already parses `channel`/`category`
  defensively) switches to `rail` / `instructionType` / `merchantCategory`; the
  client heuristic is demoted to a fallback that fires *only* for `UNKNOWN`/pre-migration
  rows.

### 5. Phased rollout (D-phases, mirroring ADR-0034)

- **D1** — vocabulary in libs; nullable columns + response/event fields. Purely
  additive, no behaviour change.
- **D2** — every payment-initiation service stamps `(rail, instructionType)` going
  forward; `PaymentJournalFactory` enforces.
- **D3** — app reads the authoritative fields; heuristic fires only on null/`UNKNOWN`.
- **D4** — best-effort backfill of historical rows from available signals; remove the
  `channel` column; merchant-category enrichment + subscription detector come online.

### 6. Honesty over false precision

A transaction whose rail genuinely cannot be determined is `UNKNOWN`, and the app
shows only an incoming/outgoing arrow — never a guessed rail. We would rather show
less than show a confident wrong badge on a banking transaction.

## Alternatives considered

- **A — Keep the client-side heuristic (status quo).** Pros: no backend change. Cons:
  fragile, inaccurate on live data, duplicates origination knowledge in every consumer,
  and structurally *cannot* recover facts only the originator held (SCT vs SDD, SCT vs
  SCT Inst). Rejected — it is the problem this ADR exists to remove.
- **B — Enrich at the customer-edge when proxying `/transactions`.** Pros: one place,
  no per-service change. Cons: the edge has no per-transaction origination context
  either, so it would just relocate the same heuristic; and it violates the edge's
  thin-proxy role (ADR-0065). Rejected.
- **C — Reuse the existing free-text `channel` column.** Pros: column already exists.
  Cons: no controlled vocabulary, already polluted with `"API"`, and conflates the two
  orthogonal dimensions into one string. Rejected in favour of typed enums.
- **D — A single conflated `paymentType` enum (card/sepa/standing/dd/…).** Cons: rail
  and instruction type are orthogonal; one enum explodes combinatorially
  (`SEPA_STANDING`, `SEPA_DIRECT_DEBIT`, `DOMESTIC_STANDING`, …) and loses the ability
  to query each dimension independently. Rejected.
- **E — Stamp subscription / MCC as a rail value at origination.** Cons: a subscription
  is a behavioural pattern discovered across many transactions, not a property of one
  payment at its creation; encoding it as a rail corrupts the taxonomy and is often
  unknowable at origination. Rejected — subscription stays a derived enrichment.

## Consequences

**Positive**
- One authoritative source for "how the money moved", reused by app badges,
  statements, forecast/analytics, dispute routing, AML typologies, and FINREP/COREP
  payment-type breakdowns — no forked heuristics.
- Queryable, orthogonal dimensions (rail × instruction type) instead of a brittle
  string.
- The app badge becomes truthful on live data; `UNKNOWN` is shown honestly rather than
  guessed.
- Captured where it is known and cheap (the saga), following the established
  origination-metadata pattern (ADR-0021).

**Negative**
- Coordinated change across every payment-initiation service plus a
  `transaction-service` schema migration and an event-schema version bump (AsyncAPI).
- Historical backfill (D4) is best-effort; pre-migration rows may stay `UNKNOWN`.
- Merchant-category / subscription accuracy depends on card-scheme data availability
  and a recurring-merchant detector that does not exist yet.

**Neutral**
- The app heuristic is retained, but only as a transitional fallback for `UNKNOWN`.
- `channel` lingers until D4 for backward compatibility, then is removed.

## Compliance impact

- **PCI DSS:** `merchantCategory` is non-sensitive scheme metadata (MCC); the rail
  model must never carry PAN or track data. Card metadata stays out of
  `transaction-service` beyond the MCC code.
- **PSD2:** payment instrument / transaction type underpins SCA-exemption auditing and
  payment reporting; an authoritative rail strengthens the existing SCA metadata
  (ADR-0021).
- **CNB / FINREP-COREP:** authoritative payment-type breakdowns feed the prudential
  returns (ADR-0097) without per-report reconstruction.
- **GDPR:** rail and instruction type are not new personal data; merchant category is
  low-sensitivity, non-identifying scheme data.
- **DORA:** improves end-to-end traceability of payment flows for operational-resilience
  evidence.

## References

- ADR-0003 — Transactional outbox for Kafka event publishing
- ADR-0004 — Saga for multi-service workflows
- ADR-0006 — AsyncAPI for Kafka topics
- ADR-0014 — openbank-libs centralization roadmap
- ADR-0021 — SCA decoupled device approval (origination-metadata pattern on `Transaction`)
- ADR-0065 — Customer edge as a thin authorizing proxy
- ADR-0097 — FINREP/COREP supervisory prudential returns
- openbank-app#121 — payment-type badges (the consumer that surfaced the gap)
