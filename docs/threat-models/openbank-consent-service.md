<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — consent-service

- **Date:** 2026-05-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path / PSD2 trust boundary.**
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034. PSD2/RTS relevant.

## 1. Scope & purpose

Data-sharing consent lifecycle (PSD2 AIS/PIS): create, activate, reject, revoke, validate.
Consent is the **authorization root** that lets TPPs access account/payment data — forging or
escalating a consent is a direct path to unauthorized data access or payment initiation.

## 2. Data flow (DFD)

```
[PSD2/TPP layer, Party] --> (REST /api/v1/consents*) --> [consent-service] --> [(Postgres: consents)]
                                                              |
                                                              +--> [(consent_outbox)] --> [Kafka consent events]
   validate() <-- [psd2-service / resource servers checking a consent before serving data]
   hasActiveConsent() <-- [engagement-service: ContactPolicyGate's live per-call consent check]
```

- **External entities:** PSD2/TPP-facing layer, party-service, resource servers calling `validate`.
- **engagement-service** (new caller, 2026-08-07): reads `hasActiveConsent` for the
  `MARKETING_COMMS_INAPP` scope before resolving a surface (ADR-0220, ADR-0219 D1). Read-only,
  same shape as campaign-service's own consent check — no new mutation path.
- **Trust boundaries:** TPP edge (highest scrutiny); service↔Postgres; service↔Kafka.
- **Assets:** consent grants, scopes, party↔grantee linkage, consent state.

## 3. Authn/Authz

- Mutations must be bound to an authenticated party + SCA (sca-service) per PSD2 RTS.
- OPA enforce; consent issuance/activation should not be `@PermitAll`.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | TPP impersonates party to create consent | OIDC + SCA binding; party identity verified upstream |
| **T**ampering | Scope/grantee escalation after creation | Immutable scope post-activation; state machine; audit |
| **R**epudiation | Party denies granting consent | AuditEvent per transition; SCA evidence retained |
| **I**nfo disclosure | `validate` leaks consent details to wrong caller | Caller authz (`@Authorize consent.validate` + `@RolesAllowed`); response is a consent-scoped projection (scopes / covered IBANs / frequencyPerDay) to an already-authenticated resource server — no party PII |
| **D**oS | Consent spam / validate flooding | Rate limit; cache validate decisions briefly |
| **E**oP | Revoked consent still validates | Revocation is synchronous + event; validate reads live state, deny-by-default |

## 5. Residual risks / assumptions

- **SCA linkage** (sca-service) must gate activation — see `sca.md`.
- Revocation propagation latency to resource servers must be bounded; the event is now durably
  enqueued in the transactional outbox within the revocation's own transaction (at-least-once via
  `ConsentOutboxDispatcher`), closing the prior dual-write that could drop it entirely.

## 6. Change log

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. Four parameters on the consent lifecycle: `partyId` on revoke, `scaSessionId` on activate, `reason` on reject, `scope` on hasActiveConsent. `scaSessionId` is the evidence that SCA completed, so a null reaching activate is a state transition with no SCA reference attached — the request must be rejected, not half-processed. hasActiveConsent already answered 400 by accident (`runCatching` swallowed the NPE); it now says which parameter is missing. No new caller or boundary. Rollback: revert.
- **2026-07-17** — Completed ADR-0126 §D2: `/validate` response now carries `scopes`, `grantedAccounts`
  (covered IBANs; null = all of the party's accounts) and `frequencyPerDay` (PSD2 RTS Art. 10 AISP cap
  = 4) so a resource server can cache within that window. Additive optional fields (openapi
  `1.1.0`→`1.2.0`, non-breaking). **Info-disclosure surface widens** from `{valid, reason, code}` to
  the consent-scoped projection above — but only to an authenticated, `@Authorize consent.validate` +
  `@RolesAllowed`-gated resource server that already serves those accounts; no party PII
  (name / birth number / contact) is exposed and the trust boundary is unchanged. Risk class =
  **confidentiality** (bounded). No DB/event change. Verified by `ConsentTest` (frequencyPerDay) +
  `ConsentValidationResponseTest` (projection). Rollback: revert the commit.
- **2026-07-17** — Consent lifecycle events (grant/revoke/reject) and the expiration sweep now write
  their outbox row in the SAME transaction as the status change (ADR-0126 §D3/§D4). The prior
  direct-Kafka emit was a dual-write that silently dropped the event on a crash between the DB commit
  and the send, breaking the GDPR Art. 17 / PSD2 "cease processing" propagation guarantee for the
  notification / analytics / psd2 consumers (a **repudiation/integrity** risk). The same change fixed
  a latent persistence bug where lifecycle transitions used `persist` (INSERT) instead of `merge` on
  the assigned-`@Id` aggregate, so every revoke/reject/activate returned HTTP 500 against a real DB —
  masked because unit tests mocked the repository. No new external surface; the direct-emit
  `ConsentEventPublisher` / `KafkaConsentEventPublisher` are retired. Verified by
  `ConsentRevocationOutboxIT` (real Postgres: status change + outbox row commit together) plus the
  updated `ConsentServiceTest` / `ConsentExpirationJobTest`.
- **2026-05-30** — Added `consent_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/
  surface/boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-08-07** — New caller: `engagement-service` reads `hasActiveConsent` for
  `MARKETING_COMMS_INAPP` (ADR-0220, ADR-0219 D1) before resolving an in-app surface. Read-only,
  network-policy-allowed ingress edge only (`openbank-infra/gitops/components/consent/network-policies.yaml`,
  via `gen-network-policies.py`) — no new mutation, no new scope, no schema change. Risk class =
  **information disclosure**, unchanged: the endpoint already returns only a boolean, and the
  caller authenticates as the shared `openbank-services` M2M client like every other consumer of
  this endpoint. Rollback: revert the network-policy edge; the code-level call fails closed
  (`ContactPolicyGate`'s D5 fail-closed-on-port-failure) if it cannot reach consent-service.
