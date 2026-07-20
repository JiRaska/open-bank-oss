---
date: 2026-06-24
decision-status: accepted
delivery-status: shipped
authors: [Claude (paired with Jiří Raška)]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, transactions, compliance]
summary: "Inbound pacs.004 SEPA return handling is added to sepa-payment-service and books its reversal through the existing transaction-service reversal path, completing the SCT lifecycle required by the EPC rulebook."
---

# 111. SEPA R-Transaction Returns via pacs.004

## Context

ADR-0108 ships the settlement leg (PROCESSING → COMPLETED): once a SEPA SCT is accepted by the
scheme, `transaction-service` books the double-entry journal and the payment reaches COMPLETED.

This completes the *happy path*, but the ISO 20022 SEPA SCT Rulebook also mandates **R-transaction
support**: a creditor bank (or the scheme) can return a payment after settlement via a `pacs.004`
Return message. Without handling inbound `pacs.004`, a bank that can send payments cannot reverse
them — the lifecycle is half-complete. Regulatory requirements (EPC SCT Rulebook DS-04) require
return processing within 5 business days.

The relevant message type is `pacs.004.001.09` (Payment Return). A `pacs.004` carries:
- `OrgnlEndToEndId` — the original pacs.008 end-to-end reference, used to look up the payment.
- `RtrdIntrBkSttlmAmt` — the returned amount.
- `RtrRsnInf/Rsn/Cd` — a standard reason code (AC04 = closed account, AM09 = wrong amount, etc.).

Scope is SEPA SCT only. Domestic returns (CERTIS XML), SEPA Instant recalls (`pacs.028`/`camt.056`),
and SWIFT returns (`MT202`/`pacs.004` SWIFT variant) use different message formats and different
lifecycle semantics; they are deferred to separate ADRs.

## Decision

Inbound `pacs.004` return handling is added to `sepa-payment-service` and backed by the existing
`transaction-service` reversal path (ADR-0039 / ADR-0108).

1. **Inbound endpoint.** `sepa-payment-service` gains `POST /api/v1/sepa-payments/returns`, protected
   by `ROLE_SERVICE` (cluster-internal, called by clearing-simulator). The request body is a raw
   `pacs.004.001.09` XML payload.

2. **Parsing.** `Pacs004Reader` (added to `openbank-libs`) parses the XML with XXE hardening
   (`XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES = false`). It extracts `OrgnlEndToEndId`,
   returned amount, currency, value date, and reason code. `Pacs004Reader` is a public API addition
   to libs — a minor libs release is required.

3. **Payment lookup.** `sepa-payment-service` looks up the payment by `originalEndToEndId`. If not
   found or already RETURNED/CANCELLED, the request is rejected (409 or 404) — no double-return.

4. **Reversal booking.** `sepa-payment-service` calls `transaction-service` at
   `POST /api/v1/transactions/{id}/reverse` to book the reversal credit. This uses the existing
   `REVERSED` status and `REVERSAL` transaction type in transaction-service (ADR-0039 § reversal);
   no new transaction-service domain concepts are needed.

5. **Status transition.** The payment transitions to `RETURNED`. This is the terminal state for a
   returned payment.

6. **Sandbox testing.** `clearing-simulator` gains `POST /api/v1/clearing/returns` so integration
   and end-to-end tests can trigger a return without a real scheme.

7. **Out of scope:** Domestic (CERTIS), SEPA Instant recalls, and SWIFT returns are not handled here.
   Proactive returns (bank-initiated, `camt.056`) are a separate flow.

## Alternatives considered

- **A — Consume `pacs.004` via Kafka outbox event (async).** Symmetric with the pacs.008 flow;
  however, R-transactions are rare, time-sensitive (5-day window), and the creditor bank may retry.
  A synchronous REST path allows the clearing-simulator to surface errors immediately and re-send.
  The async path can be added later if volume warrants it.
- **B — Handle the reversal entirely in sepa-payment-service** (directly call ledger/balance).
  Duplicates ADR-0039 / ADR-0108 settlement logic. Rejected — transaction-service is the sole
  settlement authority.
- **C — Add the return endpoint to transaction-service directly.** Couples ISO 20022 parsing to the
  settlement engine. Rejected — payment protocol (pacs.004) belongs in the rail service
  (sepa-payment-service); settlement belongs in transaction-service.

## Consequences

**Positive**
- Payment lifecycle is complete: RECEIVED → VALIDATED → PROCESSING → COMPLETED → RETURNED.
- Regulatory compliance: SCT Rulebook return handling (DS-04) is implemented.
- `REVERSED` status and `REVERSAL` type in transaction-service, until now theoretically present but
  never exercised, are now actively used and tested.
- `Pacs004Reader` in libs is reusable by any future pacs.004 consumer (SEPA Instant, SWIFT).

**Negative**
- New `Pacs004Reader` in libs triggers a fleet rebuild on libs bump (a minor libs release).
- `POST /api/v1/transactions/{id}/reverse` is a new money-path endpoint in transaction-service
  (requires 2 approvals + threat-model review per ADR-0030).

**Neutral**
- Domestic/instant/SWIFT returns remain deferred; this closes the SCT-only return gap.
- Clearing-simulator gains the `/returns` endpoint solely for sandbox/test parity.

## Implementation

PRs: feat/returns-pacs004-reader (libs `Pacs004Reader`), feat/returns-transaction-reversal
(transaction-service `/reverse` endpoint), feat/returns-sepa-payment (sepa-payment-service
`/returns` endpoint + state machine), feat/returns-clearing-simulator (sandbox `/returns`).

## References

- ADR-0039 — Ledger as golden source; reversal / REVERSAL type
- ADR-0104 — Production-faithful payment rails (the outbound pacs.008 path)
- ADR-0108 — Rail settlement via transaction-service (PROCESSING → COMPLETED)
- EPC SCT Rulebook DS-04 — R-transaction obligations (return within 5 business days)
- ISO 20022 pacs.004.001.09 — Payment Return message definition
