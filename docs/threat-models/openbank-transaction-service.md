<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — transaction-service

- **Date:** 2026-06-25
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

Transaction lifecycle: initiation, status tracking, and BIAN-aligned history/search (by IBAN/BBAN/
reference/counterparty/amount/date). Holds customer financial movement data.

## 2. Data flow (DFD)

```
[Payment / saga callers] --> (REST POST /api/v1/transactions) --> [transaction-service] --> [(Postgres: transactions)]
[Operator] --------------> (initiate) ------------------------^                                  |
[Viewer / service] ------> (GET list/search/{id}) -----------^                                  +--> [(tx_outbox)] --> [Kafka]
[Kafka: payment.scheme-accepted] --> (SchemeAcceptedConsumer) --> [transaction-service]
                                                                        +--> [Kafka: payment.scheme-accepted.dlq]  (on failure)
```

- **External entities:** payment flows / service callers + operators (initiate), viewers/service (read history), Kafka rail services (ACSC events).
- **Trust boundaries:** caller↔service (OIDC; OPA per ADR-0034 is a tracked follow-up); service↔Postgres; service↔Kafka (inbound `payment.scheme-accepted` + outbound `tx_outbox`).
- **Assets:** transaction records, counterparty data, the searchable history index.

### 2a. Kafka inbound trust boundary — `payment.scheme-accepted` (ADR-0108)

`SchemeAcceptedConsumer` opens a new **inbound trust boundary**: any Kafka producer able to publish to `payment.scheme-accepted` can trigger a settlement transaction in the money-path engine.

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing — rogue producer | An attacker or misconfigured service publishes a forged `SchemeAcceptedEvent` to trigger an unauthorised settlement | Strimzi mTLS + ACL: only the `sepa-payment`, `sepa-instant`, `domestic-payment`, and `swift-service` service accounts hold `Write` on `payment.scheme-accepted`; all other producers are denied at the broker. The topic is not auto-created (explicit Strimzi `KafkaTopic` manifest). |
| **T**ampering — message in flight | Alter `amount`, `currency`, or `debtorAccountId` between producer and consumer | Kafka mTLS encrypts and authenticates the channel end-to-end; broker ACLs prevent a third party from producing to this topic. |
| **R**epudiation — deny settlement | Claim the ACSC event was never emitted | Producer-side outbox pattern in sepa/domestic/swift services; `originatingPaymentId` is stored on the `transactions` row for audit trail and reconciliation. |
| **I**nfo disclosure | Consumer logs expose PII from the event payload | Log only `paymentId`, `rail`, `amount`, `currency` — no IBAN, no party data. First 200 chars of raw payload logged only on deserialization failure. |
| **D**oS — message flood | High-volume publish to saturate the consumer group | Consumer is `@Blocking` (one thread per partition); `group.id` isolation means only transaction-service consumes; DLQ prevents partition stall on processing error. Topic partition count is 1 in sandbox (rate-limited); prod should tune. |
| **E**oP — settlement without SCA | Drive a customer-facing debit without SCA (ADR-0021) | `initiatedByPartyId` is **null** on `SchemeAcceptedConsumer`-generated commands — the SCA gate in `TransactionService` only fires for non-null party. Rail settlements are system-initiated, consistent with clearing/interest postings. |

## 3. Authn/Authz

- **K7 closed (this change):** `listTransactions`, `searchTransactions`, `getTransaction` were
  `@PermitAll` — an unauthenticated disclosure of customer financial data (search exposes IBAN/amount/
  counterparty lookups). Now `@RolesAllowed(SERVICE, VIEWER, OPERATOR, ADMIN)`. Initiation stays `OPERATOR`.
- Enforced by Quarkus OIDC; locked declaratively by `TransactionSecurityContractTest`.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged initiate from unknown caller | OIDC; `OPERATOR`/service role required |
| **T**ampering | Alter amount/counterparty post-initiation | Status state-machine; immutable financial fields; audit |
| **T**ampering | Settlement posts the customer the **wrong direction** (an outbound payment credits the payer, or an internal transfer fails to debit the source) — silent money creation/loss in the booked balance | `PaymentJournalFactory` branches the same-currency journal on direction: outbound = DEBIT payer deposit-control / CREDIT cash-clearing; incoming = the mirror; internal transfer = DEBIT source / CREDIT target deposit-control (two sub-ledger legs). Ledger `validateBalance` enforces per-currency debits==credits; the credit-positive booked delta the projection derives (`bookedDeltas`) therefore moves each customer the right way. Pinned by `PaymentJournalFactoryTest` + `PaymentSagaLedgerIT` (outbound + internal-transfer journal shape) |
| **R**epudiation | Deny initiating a transaction | AuditEvent with `initiatedBy`; idempotency key persisted |
| **I**nfo disclosure | Unauthenticated search by IBAN/amount | **Fixed**: reads role-gated (§3) |
| **I**nfo disclosure | Domain metrics leak PII / enable per-transaction inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077 / ADR-0079): the `openbank.outbox.backlog` gauge is tagged **only** by `service="transaction"` — never a transaction id, amount, IBAN, counterparty, party id, or reference. It exposes a single read-only count (PENDING + FAILED outbox rows), sampled off the Prometheus scrape thread from a cached `AtomicLong` refreshed by a scheduled `suspend` tick, so the scrape never runs a per-request DB query. `/q/metrics` is cluster-internal |
| **D**oS | Search flooding (expensive multi-criteria query) | `limit` coerced to ≤200; `offset` ≥0; pagination |
| **E**oP | Viewer initiates a transaction | Reads exclude write role; initiate = `OPERATOR` only, deny-by-default |

## 5. Residual risks / assumptions

- **Booked balance is now a ledger projection (ADR-0039 Phase D-2).** The saga no longer debits/credits
  balance-service directly; it posts the ledger journal and `placeHold`s the synchronous cover. The
  booked movement and the cover-hold release both land asynchronously in balance-service as it projects
  the ledger's `AccountBookedChanged` event (release keyed by `referenceId == transactionId`), so there
  is **no overspend window** between hold-release and the booked drop. `bookedAmount` is eventually
  consistent; overspend is still prevented synchronously by the hold. If the journal never posts the
  projection never fires, so the saga releases the hold during compensation (hold TTL is the final
  backstop). The cutover requires balance-service `openbank.balance.projection.enabled=true` to be
  deployed in lock-step — running with the saga debit still live would double-count the booked movement.
- Same-currency non-CZK incoming/outbound legs still route the bank side through the single CZK
  cash-clearing account; only CZK and the cross-currency (FX-routed) and internal-transfer (cash-clearing
  -free) shapes are exercised today. Per-currency cash-clearing is a tracked follow-up before a non-CZK
  rail settles.
- Initiation is idempotent (keyed by `idempotencyKey`) to prevent double-spend on retry.
- Search authorization is role-coarse — per-account/per-party scoping is OPA's job (ADR-0034 follow-up).
- A null security principal on initiate falls back to a zero-UUID actor — acceptable only while OIDC
  is mandatory at the gateway; revisit if the gateway becomes optional.

## 6. Change log

- **2026-06-25** — ADR-0108 rail settlement consumer. Opens new Kafka inbound trust boundary (`payment.scheme-accepted`); threat analysis in §2a. Spoofing mitigated by Strimzi mTLS + per-service-account ACL. DLQ topic (`payment.scheme-accepted.dlq`) provisioned. `originatingPaymentId` stored for reconciliation audit trail.
- **2026-06-25** — #2013 mTLS + write-ACL hardening. §2a gap closed: `KafkaUser` manifests deployed in `openbank-infra/gitops/components/payments/kafka-scheme-accepted-acl.yaml`. Four producer ACLs (sepa-payment, sepa-instant, domestic-payment, swift-service) and one consumer ACL (transaction-service / `transaction-scheme-accepted-cg`) enforce mTLS authentication and broker-level `Write`/`Read` restrictions at the Strimzi User Operator. No other principal can produce to `payment.scheme-accepted`.

- **2026-06-17** — ADR-0039 Phase D-2 settlement cutover. (1) **Direction fix:** `PaymentJournalFactory`
  same-currency journal now branches on payment direction — outbound DEBITs the payer's deposit-control
  (was an unconditional CREDIT that paid the payer), internal transfer posts two deposit-control
  sub-ledger legs (DEBIT source / CREDIT target). This is the latent **T — tampering** money-direction
  defect (§4) that had to be fixed before any payment rail settles through the engine. (2) **Dual-write
  removed:** the saga drops `balanceCoverPort.debit/credit` (and the compensation refund); booked balance
  is the ledger projection's sole mover, the cover hold released by the projection. Risk class =
  **integrity** (money direction + single booked source of truth), mitigated by `PaymentJournalFactoryTest`,
  `PaymentSagaOrchestratorTest`, and `PaymentSagaLedgerIT`. Coupled balance-service change:
  `openbank.balance.projection.enabled=true`.
- **2026-05-30** — K7/ADR-0018: role-gated the previously `@PermitAll` read endpoints; raw-string
  role migrated to `Roles` constant. No DB/flow change. Risk class = **confidentiality**, mitigated
  by `TransactionSecurityContractTest`.
- **2026-06-11** — Outbox-backlog gauge (`TransactionOutboxBacklogGauge` + `countProcessable` on the
  outbox port, ADR-0077 / ADR-0079). Publishes `openbank.outbox.backlog{service="transaction"}` — a
  single low-cardinality count of un-drained (PENDING + FAILED) outbox rows. Touches the **I —
  information disclosure** row: no transaction id / amount / IBAN / counterparty / PII ever becomes a
  label; the count is read off the scrape thread from a cached `AtomicLong` (scheduled `suspend`
  refresh), so no per-scrape DB query. No new endpoint, DB change, data flow, or trust boundary
  (read-only count over the existing `tx_outbox` table). Risk class = **confidentiality** (metric
  cardinality), mitigated by `TransactionOutboxBacklogGaugeTest`.
