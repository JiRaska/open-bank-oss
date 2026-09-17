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
| A signed Party snapshot continues to look valid after the register rule or KYB attestation changes | The internal `GET /representation/attestations/{id}/current` query reads the stored attestation by ID, bypasses KYB's 24-hour extract cache to re-fetch the verified ACTIVE register extract, and compares the resulting current decision with that exact attestation. It returns only status, hash, count and office constraints, never the rule text. Unknown IDs are 404; a registry failure is an error, not `current=true`. The backend read has a dedicated OPA reason; a cache-bypass test and a real-HTTP/PostgreSQL test prove the boundary. | Provider-first rollout: deploy the KYB endpoint and OPA bundle before delegation starts calling it; the new delegation resolver refuses unavailable responses. This still does not authorize JOINT issuance: the proposal/decision/grant workflow and execution-time recheck are separate release gates. |

## Rollback

Disable the new evidence producer/consumer and JOINT admission while retaining signed case,
ceremony, attestation, mandate and policy evidence. Never rewrite or delete a signed policy to
make an old application version appear compatible. Existing SOLE/1 behavior remains unchanged.
If the current-status provider is rolled back, the delegation reader must remain fail-closed or
be rolled back with it; never interpret a 404/timeout as a current attestation.
