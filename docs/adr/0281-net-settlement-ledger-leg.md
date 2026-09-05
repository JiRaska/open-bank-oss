---
date: 2026-09-04
decision-status: proposed
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ledger, kafka]
summary: "Clearing settlement gains its ledger leg: settleBatch commits one outbox command per batch, and a self-consumer posts the balanced net-settlement journal to ledger-service with a deterministic idempotency key."
---

# ADR-0281 — Net-settlement ledger leg for cleared batches

## Context

`ClearingService.settleBatch` flips a batch to `SETTLED` and emits `batch.settled`, but until now
**no ledger movement happened**: the scheme-settlement obligation accrued while the batch was
`IN_CLEARING` was never booked out, so the general ledger and the clearing lifecycle disagreed by
the full settlement amount of every settled batch. Issue #8361 tracks closing that gap.

The forces at play:

- **Money-path atomicity.** The batch status flip and the instruction to post the journal must
  commit together — a status committed without the posting instruction is silent data loss, a
  posting without the status is a double-book risk.
- **A consumer that rethrows does not dead-letter unless the channel wires a DLQ** — SmallRye's
  default `failure-strategy` is `fail`, which stops the channel (measured fleet-wide, #5745). Any
  new consumer with retry-then-rethrow semantics needs `failure-strategy`, an explicit per-service
  DLQ topic (in nested YAML form — the dotted one-liner is inert), the `KafkaTopic` CR and the
  KafkaUser Write ACL, all four in the same change.
- **Ledger idempotency.** ledger-service's journal API already deduplicates on the caller-supplied
  idempotency key, so a deterministic key derived from the batch id makes retries and DLQ replays
  collapse onto the one booked journal.

## Decision

We will post the net-settlement journal **per settled batch** through the transactional outbox,
owned end-to-end by clearing-service:

1. **Atomic commit.** `settleBatch` persists, in one transaction: batch `SETTLED`, its items, the
   existing `batch.settled` outbox event, and a new outbox command
   `openbank.clearing.net_settlement.post`. The port grows `settleWithEvents(batch, items, events)`
   so the service layer hands both messages to the repository as one unit of work — the pattern
   proven by #8509.
2. **Self-consumer.** `NetSettlementPostingConsumer` consumes the command from
   `openbank.clearing.batch.event` (channel `clearing-net-settlement-in`), filtered by payload
   `eventType`. It retries with backoff and then rethrows; the channel is configured with
   `failure-strategy: dead-letter-queue`, the explicit topic
   `openbank.dlq.clearing-service.clearing-net-settlement-in` (nested YAML form), the matching
   `KafkaTopic` CR, and the clearing-service KafkaUser Write ACL — all four DLQ parts in this
   change.
3. **Ledger posting.** The consumer calls ledger-service `POST /api/v1/journals` over REST with
   idempotency key `clearing-net-settlement-{batchId}`. The journal is balanced per batch:
   **DEBIT** Customer Cash Clearing {CCY} / **CREDIT** Scheme Settlement {CCY}, amount =
   `totalDebit` of the batch, value date = settlement date. GL accounts are the deterministic seed
   ids from ledger migrations V3/V14 (cash-clearing) and the new V26 (scheme-settlement
   1110–1113) — the chart-of-accounts convention `PaymentJournalFactory` already relies on.
4. **Ownership.** clearing-service owns the command topic, the consumer, the journal shape and the
   idempotency key; ledger-service owns journal validation and deduplication. No new synchronous
   coupling on the settle path: `settleBatch` returns after the local commit, the posting is
   eventually consistent and its failure mode is a visible DLQ record, not a wedged batch.

Explicit limits (documented, not hidden):

- **Reversals are out of scope.** A settled batch that must be unwound needs a manual reversing
  journal today; no automated reversal flow exists.
- **The model is bilateral-net, not multilateral.** The journal moves the batch's `totalDebit`
  between the two clearing GLs; per-counterparty net positions (`netPosition = 0` at this stage)
  are not yet distributed. When true multilateral netting lands, the journal factory gains per-
  counterparty legs without changing the transport decided here.

## Alternatives considered

- **Synchronous REST call inside `settleBatch`** — post the journal in the request path, before
  commit. Pros: no consumer, no DLQ. Cons: couples batch settlement to ledger-service availability
  (a ledger outage wedges clearing), breaks the atomicity the outbox exists for, and re-introduces
  exactly the dual-write the outbox pattern removed. Rejected.
- **Ledger-service consumes `batch.settled` directly** — push the leg into ledger-service. Pros:
  one less consumer in clearing. Cons: ledger-service would need clearing's event schema and GL
  mapping knowledge, inverting the dependency direction the fleet uses (domain service owns its
  posting intent, ledger validates), and a poison `batch.settled` would wedged ledger's inbound
  channel for every other publisher on that topic. Rejected.

## Consequences

**Positive**
- The GL and the clearing lifecycle agree after every settled batch; the gap #8361 found is closed.
- Failure visibility: a posting that cannot land surfaces as a DLQ record on an explicit,
  per-service topic with a metric-visible consumer — not as a stopped channel or a silent skip.
- Retries and replays are safe by construction (deterministic idempotency key enforced by
  ledger-service).

**Negative**
- One more Kafka consumer and REST client to operate in clearing-service (oidc-client credentials,
  `LEDGER_SERVICE_URL`).
- The posting is eventually consistent: there is a window where the batch is `SETTLED` and the
  journal is in flight. Reconciliation reads must tolerate it.

**Neutral**
- Reversal handling stays manual and is called out in the threat model changelog.

## Compliance impact

- PCI DSS: not applicable — no cardholder data on this path.
- DORA:    supports ICT risk management — settlement posting gains a durable, monitored failure
           mode (DLQ) instead of an invisible gap.
- GDPR:    not applicable — the journal carries batch references and amounts, no personal data.
- PSD2:    not applicable — clearing-internal settlement, no payment-initiation surface.
- CNB:     supports settlement-record completeness — every settled batch leaves a balanced,
           value-dated ledger entry.

## References

- Issue #8361 (net-settlement ledger leg)
- `ClearingSettleOutboxAtomicityIT` — proves both outbox rows commit in the settle transaction
- `openbank-clearing-service/src/main/resources/application.yaml` — channel + DLQ wiring
- ledger-service migration `V26__scheme_settlement_accounts.sql`
- `docs/threat-models/openbank-clearing-service.md` — 2026-09-04 changelog entry
