<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — sepa-instant-service (SCT Inst)

- **Date:** 2026-06-17
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

SEPA Instant Credit Transfer (SCT Inst): initiate, query, query-by-debtor, recall. Payments are
**near-irrevocable and settle in seconds** — the window to catch fraud is minimal, raising stakes
above batch SEPA.

## 2. Data flow (DFD)

```
[Channels/Operators] --> (REST /api/v1/sepa-instant) --> [sepa-instant-service] --> [(Postgres: sct_inst payments)]
                                                                |
                                                                +--> [(sct_inst_outbox)] --> [Kafka events] --> clearing/scheme
                                                                |
                                                                +--> [fraud-service] (shadow, OIDC CC / mTLS, fail-open)
   recall <-- (POST /{paymentId}/recall)
```

- **External entities:** initiating channels/operators, SCT Inst scheme/clearing.
- **Trust boundaries:** caller↔service (mTLS+OIDC+OPA); service↔Postgres/Kafka; scheme edge;
  service↔fraud-service (OIDC client-credentials + mTLS, internal cluster-only, shadow/read-only).
- **Assets:** instant payment instructions, recall requests.

## 3. Authn/Authz

- Initiation/recall must be role-gated (payments) + OPA enforce; SCA for customer-initiated.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged instant initiation | OIDC + role; mTLS |
| **T**ampering | Amount/beneficiary change before send | Server-validated, immutable once accepted; audit |
| **R**epudiation | Deny initiating an instant payment | AuditEvent + SCA evidence + correlation id |
| **I**nfo disclosure | Debtor payment history (`/debtor/{id}`) leak | AuthZ scoping to owner/role |
| **I**nfo disclosure | Domain metrics leak PII / enable per-payment inference via high-cardinality labels | `DomainMetrics` low-cardinality contract (ADR-0077 / ADR-0079): the outbox-backlog gauge `openbank.outbox.backlog` is tagged **only** by `service="sepa-instant"` — never a payment id, end-to-end id, debtor/creditor IBAN, amount, or any PII. The gauge value is a read-only `COUNT(*)` of PENDING+FAILED outbox rows, refreshed off a scheduled tick (not on the Prometheus scrape thread); `/q/metrics` is cluster-internal |
| **D**oS | Flood to exhaust instant-rail capacity | Rate limit; idempotency |
| **E**oP | Unauthorized recall to claw back funds | Recall gated by distinct authority; audit; reason required |

## 5. Residual risks / assumptions

- **Irrevocability** ⇒ pre-send fraud checks + SCA are the key controls; post-hoc recall is best-effort.
- Idempotency-key mandatory (instant retries must not double-send).

## 6. Change log

- **2026-05-30** — Added `sct_inst_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/
  surface/boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-06-11** — Added outbox-backlog gauge (`openbank.outbox.backlog`, tagged `service="sepa-instant"`)
  + `countProcessable()` on the outbox port (ADR-0077 / ADR-0079). Touches the **I — information
  disclosure** row: a new domain metric. **No new data flow, endpoint, or trust boundary** — it is a
  read-only `COUNT(*)` of PENDING+FAILED `sct_inst_outbox` rows, refreshed by a scheduled in-process
  tick (not on the scrape thread), exposed on the cluster-internal `/q/metrics`. The gauge carries no
  payment id, IBAN, amount, or PII (low-cardinality contract). **Risk class = confidentiality / metric
  cardinality** (bounded to a single per-service series). Mitigated by `SctInstOutboxBacklogGaugeTest`
  (supplier tracks the refreshed cache). No DB change; rollback = revert the commit.
- **2026-06-17** — ADR-0084 fraud shadow scoring (observe-only). New outbound trust boundary:
  `sepa-instant → fraud-service (POST /api/v1/fraud/score, OIDC client-credentials)`.
  **Shadow = fail-open and never-enforce**: `SctInstPaymentService.scoreFraudShadow()` wraps the call
  in `.onFailure().recoverWithUni {}` — any fault (timeout, circuit-open, 5xx) is swallowed; the
  payment outcome is unchanged. `FraudScoringAdapter` applies `@CircuitBreaker` (threshold 0.3,
  30% failure ratio) + `@Timeout(3 s)`. No retry (avoid double-scoring on near-real-time rail).
  **Risk class = availability** (fault in fraud-service cannot block a payment) and **confidentiality**
  (payment amount, debtor/creditor IBAN, currency sent to fraud-service; mitigated by mTLS +
  OIDC client-credentials for service-to-service authn; fraud-service is internal, cluster-only).
  **DFD update**: add `sepa-instant → fraud-service` edge with `OIDC client-credentials / mTLS`
  trust-boundary label. No DB schema change; rollback = revert adapter + port commits.
