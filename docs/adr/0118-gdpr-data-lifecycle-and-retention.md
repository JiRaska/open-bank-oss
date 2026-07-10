# 118. GDPR data lifecycle — PII classification, retention periods, erasure model

Date: 2026-06-25
Author: Claude (paired with Jiří Raška)
Status: Accepted
Delivery-Status: Shipped

**Delivery note (updated 2026-07-10):**
- **Art. 17 erasure cascade** — ✅ Shipped: `party-service` anonymises in-place + deletes binary documents; `kyc-service` deletes documents and anonymises check results (`PartyEventConsumer.handleErased`); `notification-service` deletes preferences and history (`PartyErasureConsumer`); `card-issuance-service` anonymises `cardholderName`, `embossedName`, `deliveryAddress` (`PartyEventConsumer`). `audit-service`, `ledger-service`, `transaction-service` correctly retain data (AML/accounting override, Art. 17(3)(b)).
- **Art. 15 data export** — ✅ Shipped: `GET /api/v1/parties/{id}/gdpr-export` in `party-service` aggregates direct PII with the cross-service contributions — `GdprAggregationAdapter` calls kyc-service (`GET /api/v1/kyc/cases/party/{id}`, sensitive PII) and card-issuance-service (`GET /api/v1/cards/party/{id}`, card PII), best-effort so one downstream being down degrades the export rather than failing it. Endpoints wired via #2630; cross-service test coverage closed via #356.
- **Automated retention enforcement** — ✅ Shipped: three schedulers now exist. `card-issuance-service` (`CardPiiRetentionScheduler`) and `kyc-service` (`KycRetentionScheduler`) are enabled and live (`RETENTION_*_ENABLED=true`, `dry-run=false`, 5-year retention per AML Act §16). `audit-service`'s `SessionLogRetentionScheduler` (session/access-log 90-day TTL) ships **disabled-by-default** (`openbank.retention.session-log.enabled=false`, `dry-run=true`, #356) — it is new PII-deletion code, so enabling it per environment is a deliberate operational decision, not a delivery gap.

Issue #268 (Art. 15 export gaps + retention enforcement) is **closed** (2026-07-06). Remaining is operational only: a deliberate per-environment decision to enable session-log retention.

## Context

GDPR Art. 17 (Right to Erasure) is partially implemented in `party-service`: `DELETE /api/v1/parties/{id}`
anonymises the party row in-place and deletes binary document files. `PARTY_ERASED` is published
via `KafkaPartyEventPublisher`.

This ADR establishes the data lifecycle policy and the implementation plan, all of which has since shipped:
1. ✅ `PARTY_ERASED` consumers implemented in `kyc-service` (`PartyEventConsumer`) and
   `notification-service` (`PartyErasureConsumer`).
2. ✅ GDPR Art. 15 (Right of Access / data export) implemented and aggregated cross-service:
   `GET /api/v1/parties/{id}/gdpr-export` in party-service pulls kyc + card PII via
   `GdprAggregationAdapter`.
3. ✅ Retention enforcement (automated cleanup) implemented: `CardPiiRetentionScheduler` (live),
   `KycRetentionScheduler` (live), `SessionLogRetentionScheduler` (shipped, disabled-by-default).
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

Retention enforcement is implemented by per-service schedulers: `CardPiiRetentionScheduler`
(card-issuance-service) and `KycRetentionScheduler` (kyc-service) run live on the 5-year AML clock;
`SessionLogRetentionScheduler` (audit-service, 90-day session/access logs) ships disabled-by-default
and is enabled per environment by a deliberate operational decision (it is PII-deleting code).

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

Per-service `@Scheduled` retention jobs enforce these periods (implemented as service-local
schedulers rather than one central Temporal workflow — each owner deletes its own PII, keeping the
boundary clean):
- Session/access logs older than 90 days → delete from audit-service (`SessionLogRetentionScheduler`,
  behavioural PII only; ships disabled-by-default).
- KYC documents older than `relationshipEndDate + 5 years` → delete from kyc-service
  (`KycRetentionScheduler`, live).
- Card PII older than `cardExpiry + 5 years` → anonymise in card-issuance-service
  (`CardPiiRetentionScheduler`, live).

Ledger and transaction records are never deleted by these jobs.

**6. Right of Access — Art. 15.**

✅ `GET /api/v1/parties/{id}/gdpr-export` is implemented in party-service (`PartyGdprExport` aggregate,
`PartyResource`). It aggregates party-service PII directly and pulls kyc-service and
card-issuance-service contributions via `GdprAggregationAdapter` (best-effort cross-service fan-out).

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
- Session-log retention (audit-service) ships disabled-by-default, so behavioural-PII cleanup does
  not run until an operator deliberately enables it per environment. KYC-document and card-PII
  retention run live; ledger/transaction records are intentionally never deleted (AML/accounting
  override).
- The cross-service Art. 15 export is best-effort: if kyc-service or card-issuance-service is
  unavailable, the export degrades (omits that section) rather than failing — an availability, not
  a completeness, trade-off.

**Neutral**
- Tombstone email pattern is an established industry approach for GDPR erasure in systems with
  unique email constraints.

## Compliance impact

- GDPR Art. 5(1)(e): storage limitation — retention periods defined above and enforced by the
  per-service retention schedulers (card/KYC live; session-log disabled-by-default).
- GDPR Art. 17: right to erasure — party-service ✅, kyc-service ✅, notification-service ✅, card-issuance-service ✅.
- GDPR Art. 15: right of access — party-service ✅ implemented; kyc + card export ✅ aggregated via `GdprAggregationAdapter`.
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
