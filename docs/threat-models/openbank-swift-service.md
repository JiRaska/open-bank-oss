<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — swift-service

- **Date:** 2026-05-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context (high-value wires).
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

SWIFT message handling: create, query, query-by-status, acknowledge, reject. Cross-border
high-value wire instructions — historically the highest-impact fraud target (cf. SWIFT-network
heists). Message authenticity is paramount.

## 2. Data flow (DFD)

```
[Operator/Payments] --> (REST /api/v1/swift) --> [swift-service] --> [(Postgres: swift messages)]
[Counterparty/gateway] --> (ack / reject) ----------^                    |
                                                                         +--> [(swift_outbox)] --> [Kafka swift events]
                                                                         |
                                                                         +--> [clearing-simulator] (pacs.008 out / pacs.002 in; OIDC CC; ADR-0104 D4; flag-gated; MT103→ISO20022)
```

- **External entities:** operators, SWIFT gateway/counterparty (ack/reject inbound),
  clearing-simulator (SWIFT SWIFTNet proxy; swap-point for real SWIFTNet connector).
- **Trust boundaries:** SWIFT network edge (highest scrutiny); caller↔service; service↔Postgres/Kafka;
  service↔clearing-simulator (OIDC client-credentials; cluster-internal; pilot flag off by default).
- **Assets:** SWIFT MT/MX messages, BIC routing, amounts, ack/reject state.

## 3. Authn/Authz

- Send/ack/reject must be role-gated (operator/payments) + OPA enforce; inbound ack/reject must be
  authenticated to the gateway identity (mTLS allow-list).
- `ack`/`reject`/`get`/`listAll`/`listByStatus` carry `@RolesAllowed` (2026-07-08 fix, see §6) in
  addition to the OPA `@Authorize` check, matching `send`'s fix from the same day. No `ROLE_SERVICE`
  is granted on any endpoint: a repo-wide sweep (issue #266, `security/swift-opa-enforce`) found no
  in-repo REST client or Kafka consumer calling `openbank-swift-service` — send/list/read/ack/reject
  are human-only today. Adding `ROLE_SERVICE` without a caller or a matching OPA rule would be an
  untested, misleading grant; revisit if/when a real M2M caller (e.g. a SWIFT gateway adapter) is
  introduced, gating the corresponding OPA rule on `principal.id`, not `principal.type == "SERVICE"`
  (`AuthorizeInterceptor.principalType()` never emits `SERVICE`).

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged outbound wire / spoofed inbound ack | mTLS gateway identity; message authentication; operator role |
| **S**poofing | Forged `pacs.002` ACSC from clearing-simulator (ADR-0104 D4) | clearing-simulator is cluster-internal only; OIDC CC verifies identity; `Pacs002Reader` validates XML schema before parsing; scheme accept moves message to SENT (no funds move until correspondent settlement) |
| **T**ampering | Alter amount/BIC of a message | Message integrity (signing/HMAC); immutable once submitted; audit |
| **R**epudiation | Deny sending/acking a message | AuditEvent per create/ack/reject with actor + message id |
| **I**nfo disclosure | Message content / counterparty leakage | AuthZ scoping; encryption at rest; least-privilege read |
| **D**oS | Message flooding | Rate limit; bounded queue |
| **E**oP | Unauthorized send or false ack/reject | Distinct roles; four-eyes (MakerChecker) for high-value send recommended |

## 5. Residual risks / assumptions

- **Four-eyes for high-value sends** strongly recommended (ADR-0034 MakerChecker).
- Message-level authenticity (signing) is the dominant control against wire fraud.
- Sanctions screening expected upstream before release.

## 6. Change log

- **2026-05-30** — Added `swift_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/surface/
  boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-06-23** — ADR-0104 D4: real ISO 20022 `pacs.008` submission to SWIFT SWIFTNet via
  `clearing-simulator`. New outbound trust boundary: `swift-service → clearing-simulator`
  (POST `/api/v1/clearing/credit-transfers`, pacs.008 XML; pacs.002 XML response; OIDC CC).
  MT103 fields (sender/receiver BIC, value date, amount, beneficiary account, charge code) are
  converted to a `CreditTransferInstruction` and the resulting pacs.008 XML is persisted as
  `rawMt` on the `SwiftMessage` for audit tracing. **Flag-gated**
  (`openbank.swift.scheme-submission.enabled`, off by default; only MT103 messages eligible). Fails
  **closed**: gateway unreachable → message stays VALIDATED. `ACSC` → SENT (rawMt populated),
  `RJCT` → REJECTED. **New STRIDE row**: forged `pacs.002` ACSC → mitigated by cluster-internal
  isolation, OIDC CC, schema validation. **Risk class = integrity** (scheme verdict gates
  high-value wire state) and **confidentiality** (BIC, amount, account sent to simulator; mitigated
  by OIDC CC + cluster-only ingress). **DFD update**: added `clearing-simulator` edge (see §2).
  Added `quarkus-oidc-client-reactive-filter` + `quarkus-rest-client-reactive` deps to
  `build.gradle.kts`. No DB schema change; rollback = flag OFF.
- **2026-07-08** — Follow-up to the `send` **E**oP fix (PR #568, same day): `ack`, `reject`, `get`,
  `listAll`, `listByStatus` on `SwiftResource` also had no `@RolesAllowed` and relied on OPA
  `@Authorize` alone. Added `@RolesAllowed(Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)` to the
  mutating `ack`/`reject` endpoints (matching `send`'s role set and the OPA `operator-swift-write`
  rule's operator/admin gate) and `@RolesAllowed(Roles.VIEWER, Roles.OPERATOR, Roles.PAYMENTS,
  Roles.ADMIN)` to the read/list endpoints (`get`, `listAll`, `listByStatus`), matching the
  `getPayment`/`listPayments` convention on `DomesticPaymentResource`/`SepaPaymentResource` minus
  `ROLE_SERVICE` — investigated and deliberately omitted (see §3): no in-repo caller of any
  SWIFT endpoint exists yet, human-only per the OPA policy landed the day before
  (`security/swift-opa-enforce`, issue #266). No DFD/schema change; rollback = revert the
  annotations.
