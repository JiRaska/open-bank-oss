# 118. GDPR data lifecycle — PII classification, retention periods, erasure model

Date: 2026-06-25
Author: Claude (paired with Jiří Raška)
Status: Accepted
Delivery-Status: Partial

## Context

GDPR Art. 17 (Right to Erasure) is partially implemented in `party-service`: `DELETE /api/v1/parties/{id}`
anonymises the party row in-place and deletes binary document files. `PARTY_ERASED` is published
via `KafkaPartyEventPublisher`.

This ADR establishes the data lifecycle policy and the implementation plan for the remaining gaps:
1. ✅ `PARTY_ERASED` consumers implemented in `kyc-service` (`PartyEventConsumer`) and
   `notification-service` (`PartyErasureConsumer`).
2. ✅ GDPR Art. 15 (Right of Access / data export) implemented: `GET /api/v1/parties/{id}/gdpr-export`
   in party-service (`PartyGdprExport`).
3. Retention enforcement (automated cleanup) remains pending — policy intent only.
4. The AML/accounting retention vs. GDPR erasure conflict is documented below.

## Decision

**1. PII classification.**

| Class | Where | Examples |
|-------|-------|---------|
| Direct PII | party-service | Name, email, phone, national ID, address |
| Sensitive PII | kyc-service | Identity documents, PEP/sanctions results (GDPR Art. 9) |
| Financial PII | ledger-service, transaction-service | IBAN, amounts, counterparties |
| Behavioural PII | audit-service | Access logs, actions, timestamps |
| Pseudonymised | party-service (post-erasure) | UUID + tombstone email only |

**2. Retention periods.**

| Data type | Retention | Legal basis |
|-----------|-----------|-------------|
| Transactions / ledger entries | **10 years** | Zákon o účetnictví No. 563/1991 §31 |
| KYC documents and screening results | **5 years** after relationship end | AML Act No. 253/2008 §16 |
| Audit log | **5 years** | AML Act §16 + GDPR accountability (Art. 5(2)) |
| Complaints and disputes | **5 years** | EBA Guidelines + civil statute of limitations |
| Session / access logs | **90 days** | Proportionality; no specific statutory requirement |
| Card data (maskedPan, cardholder name) | Duration of card + 5 years | AML Act §16 |

Retention periods are currently **policy intent only** — no automated cleanup is implemented.

**3. Erasure model (GDPR Art. 17 vs. AML conflict).**

GDPR Art. 17(3)(b) permits refusal of erasure when processing is necessary to comply with a legal
obligation. The AML Act and accounting law override GDPR erasure for financial records:

| Service | Action on erasure request |
|---------|--------------------------|
| party-service | Anonymise in-place (name → `"GDPR_ERASED"`, email → UUID tombstone); delete binary documents | ✅ Implemented |
| kyc-service | Delete KYC documents; anonymise check results | ✅ Implemented (`PartyEventConsumer.handleErased`) |
| notification-service | Delete notification preferences and history | ✅ Implemented (`PartyErasureConsumer`) |
| card-issuance-service | Anonymise `cardholderName`, `embossedName`, `deliveryAddress`; retain card aggregate | ✅ Implemented (`PartyEventConsumer`) |
| audit-service | **Do NOT delete** — AML retention obligation overrides GDPR | ✅ Correct (no subscriber needed) |
| ledger-service | **Do NOT delete** — 10-year accounting retention overrides GDPR | ✅ Correct |
| transaction-service | **Do NOT delete** — 10-year accounting retention overrides GDPR | ✅ Correct |

The tombstone email pattern (`GDPR_ERASED_{uuid}@tombstone.openbank.internal`) preserves the
unique-constraint on the `email` column after PII removal.

**4. Cross-service erasure propagation.**

`PARTY_ERASED` is published by party-service and consumed by:
- ✅ `kyc-service` — `PartyEventConsumer.handleErased`: deletes KYC documents, anonymises check result notes.
- ✅ `notification-service` — `PartyErasureConsumer`: deletes notification preferences, purges
  undelivered queued messages.
- ✅ `card-issuance-service` — `PartyEventConsumer` anonymises `cardholderName`, `embossedName`, and `deliveryAddress`; card aggregate (status, limits, expiry) retained under AML/banking record-retention obligations.

Services that retain data under AML/accounting obligations (`audit-service`, `ledger-service`,
`transaction-service`) must explicitly **not** subscribe to `PARTY_ERASED`.

**5. Retention enforcement (NOT YET IMPLEMENTED).**

A scheduled Temporal workflow will enforce retention periods:
- Session/access logs older than 90 days → delete from audit-service (behavioural PII only).
- KYC documents older than `relationshipEndDate + 5 years` → delete from kyc-service.
- Card PII older than `cardExpiry + 5 years` → anonymise in card-issuance-service.

Ledger and transaction records are never deleted by this workflow.

**6. Right of Access — Art. 15.**

✅ `GET /api/v1/parties/{id}/gdpr-export` is implemented in party-service (`PartyGdprExport` aggregate,
`PartyResource`). It aggregates PII from party-service directly. kyc-service and card-issuance-service
export contributions remain pending.

## Alternatives considered

- **Physical deletion of party row.** Infeasible — FK references in ledger and transaction tables
  prevent deletion. In-place anonymisation is the industry standard for banking.
- **Centralised GDPR service.** Over-engineering for the current number of PII holders; the
  event-driven fan-out is simpler and more resilient.
- **Synchronous erasure call to all services.** Tight coupling; a single failing downstream blocks
  the entire erasure. Event-driven is eventual but resilient.

## Consequences

**Positive**
- The AML / GDPR conflict is explicitly decided: AML Act and accounting law override Art. 17 for
  financial records. This is documented and auditable.
- party-service erasure (anonymise + document delete) is already implemented.

**Negative**
- Retention enforcement is not automated — data is not deleted when its retention period expires
  (session logs, KYC documents, card PII).
- GDPR Art. 15 export covers party-service only; kyc-service and card-issuance-service contributions
  are pending.

**Neutral**
- Tombstone email pattern is an established industry approach for GDPR erasure in systems with
  unique email constraints.

## Compliance impact

- GDPR Art. 5(1)(e): storage limitation — retention periods defined above.
- GDPR Art. 17: right to erasure — party-service ✅, kyc-service ✅, notification-service ✅, card-issuance-service ✅.
- GDPR Art. 15: right of access — party-service ✅ implemented; kyc + card data export pending.
- GDPR Art. 5(2): accountability — audit log retention (5 years) supports this.
- AML Act No. 253/2008 §16: 5-year retention after relationship end — implemented by omission
  (audit/ledger/transaction records are not deleted).
- Zákon o účetnictví No. 563/1991 §31: 10-year accounting record retention — implemented by
  omission.
- ČNB: data lifecycle policy must be documented for the licensing application.
- DORA Art. 6: ICT risk management must include data lifecycle; this ADR is part of that record.

## References

- `openbank-party-service/src/main/kotlin/.../usecase/PartyService.kt` (erasure implementation)
- `openbank-party-service/src/main/kotlin/.../port/out/PartyPorts.kt` (GDPR Art. 17 comments)
- `openbank-party-service/src/main/kotlin/.../kafka/KafkaPartyEventPublisher.kt` (`PARTY_ERASED`)
- ADR-0113 (card issuance — `cardholderName` PII)
- ADR-0116 (KYC engine — sensitive PII)
- ADR-0086 (customer payment non-repudiation — audit chain retention)
- GDPR Art. 17(3)(b) — erasure exemption for legal obligations
- AML Act No. 253/2008 §16 (Czech AML — retention after relationship end)
