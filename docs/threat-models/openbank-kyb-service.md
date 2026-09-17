# Threat model — KYB statutory representation evidence (ADR-0284, #10247)

Status: pre-rollout. This document covers the signed-rule evidence path, not a claim that JOINT
delegation is available. Money-path authority remains denied until the operation ledger, current
authority checks and client co-signing are independently proven.

## Assets and boundaries

- The verified register extract, human-confirmed representation attestation, identified signers,
  signed document ceremony and resulting entity mandates are separate facts. None alone authorizes
  a customer delegation.
- KYB receives register data and document-service's ceremony result, then writes its case transition
  and signed outbox event together. Party-service projects all mandates and the statutory rule from
  that event in one database transaction. A case may be SIGNED before projection; that state grants
  no authority by itself.
- The event contains party identifiers and rule constraints, not names, dates of birth or the
  register's free text. The exact text is represented by its SHA-256 hash and source reference.

## Threats and controls

| Threat | Current control | Residual / release gate |
|---|---|---|
| A parser guesses a weaker sole-signature rule | Ambiguous rules require human attestation tied to normalized register text; the automatic sole path is restricted to a verified single-member register. | Re-check the live registry and attestation when a new governed operation opens. |
| The rule changes while people sign | Before the final signature grants authority, KYB compares the case-bound attestation with the active one. A changed rule enters review and emits no signed-rule evidence. | The case cannot be manually completed over old signatures: a signed document cannot be replaced. Abandon it and start a new ceremony. |
| A manual override is mistaken for a verified statutory rule | A review resolution binds an attestation only when its count and office list match; otherwise the signed event carries no statutory policy. | An operator-supplied count alone must never admit JOINT. |
| One of several mandate grants fails after earlier grants succeeded | The Party projector commits the case marker, every mandate, every outbox event and the optional rule in one database transaction. A PostgreSQL integration test forces a late policy failure and verifies full rollback; exact event replay adds nothing. | Roll out the Party consumer and migration before disabling KYB's legacy REST-grant producer. Until projection completes, current-mandate checks must deny authority. A selected business profile is not JOINT authorization. |
| An agreement event is duplicated or delivered late | KYB writes through its outbox; Party stores an immutable case/hash marker, rejects conflicting replay and checks existing mandate changes before applying stale evidence. | Do not authorize from the event alone: verify current attestation and live mandates before opening an operation. Concurrent first delivery and subsequent revocation still need dedicated race tests before JOINT admission. |

## Rollback

Disable the new evidence producer/consumer and JOINT admission while retaining signed case,
ceremony, attestation, mandate and policy evidence. Never rewrite or delete a signed policy to
make an old application version appear compatible. Existing SOLE/1 behavior remains unchanged.
