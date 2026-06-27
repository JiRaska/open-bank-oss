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
```

- **External entities:** PSD2/TPP-facing layer, party-service, resource servers calling `validate`.
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
| **I**nfo disclosure | `validate` leaks consent details to wrong caller | Caller authz; minimal validate response (allow/deny + scope) |
| **D**oS | Consent spam / validate flooding | Rate limit; cache validate decisions briefly |
| **E**oP | Revoked consent still validates | Revocation is synchronous + event; validate reads live state, deny-by-default |

## 5. Residual risks / assumptions

- **SCA linkage** (sca-service) must gate activation — see `sca.md`.
- Revocation propagation latency to resource servers must be bounded.

## 6. Change log

- **2026-05-30** — Added `consent_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/
  surface/boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
