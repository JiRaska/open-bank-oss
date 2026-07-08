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
- `send` carries `@RolesAllowed(Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)` (2026-07-08 fix, see
  §6) in addition to the OPA `@Authorize(action = "swift.send")` check. `ack`/`reject`/list/read
  endpoints still rely on OPA alone — tracked as a separate follow-up, not fixed by this change.

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
- **2026-07-08** — Fixed **E**oP gap found during the issue #413 four-eyes audit: `POST
  /api/v1/swift` (`send`, action `swift.send`) had no `@RolesAllowed` at all — this section
  already documented "role-gated (operator/payments)" as a control, but it was never wired on
  the endpoint, so any caller clearing the OPA `@Authorize` check could initiate an outbound
  wire regardless of role. Added `@RolesAllowed(Roles.OPERATOR, Roles.PAYMENTS, Roles.ADMIN)` to
  `send`, matching the `create` convention on sibling money-path payment services
  (domestic-payment, sepa-payment). No DFD/schema change; rollback = revert the annotation.
  **Not addressed here** (separate finding): `ack`/`reject`/`get`/`listAll`/`listByStatus` on the
  same resource are also missing `@RolesAllowed` and rely on OPA alone.
