<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — clearing-service

- **Date:** 2026-05-30
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

Payment clearing and settlement: batch submission, cycle triggering, settlement position
management, item lifecycle. Aggregates many payments into settlement — high blast radius.

## 2. Data flow (DFD)

```
[Payment services] --> (REST /api/v1/clearing/submit) --> [clearing-service] --> [(Postgres: batches, items, positions)]
[Operator] --> (cycle/trigger, settle) ----------------------^                       |
                                                                                     +--> [(clearing_outbox)] --> [Kafka settlement events]
```

- **External entities:** payment services (submit items), operators (trigger cycle / settle).
- **Trust boundaries:** caller↔service (mTLS+OIDC+OPA); service↔Postgres; service↔Kafka.
- **Assets:** clearing batches, settlement positions, cycle state.

## 3. Authn/Authz

- The prior class-level `@PermitAll` was replaced with per-operation least-privilege roles (K7 /
  ADR-0018): submit is service/payment-ops, reads are payment-ops/viewer/operator, and **settle +
  cycle/trigger are restricted to `@RolesAllowed(PAYMENTS, ADMIN)`** (locked by
  `ClearingResourceSecurityTest`). `settle` additionally carries `@Authorize(clearingBatch.settle)`
  (OPA, ADR-0034) in **advisory** mode, graduating to enforce in Phase 5.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Forged submit from non-payment caller | mTLS service identity allow-list |
| **T**ampering | Alter batch items / settlement position | Immutable items once cycle starts; position recomputed, not client-supplied; audit |
| **R**epudiation | Deny triggering a settlement cycle | AuditEvent on submit/trigger/settle with actor |
| **I**nfo disclosure | Cross-institution position leakage | AuthZ scoping; positions keyed by cycle, access-controlled |
| **D**oS | Batch flooding delays a cycle | Rate limit submit; bounded batch size |
| **E**oP | Submitter triggers settlement | Distinct role for `cycle/trigger` + `settle`; deny-by-default |

## 5. Residual risks / assumptions

- **Double-settlement** must be impossible — idempotent cycle/settle keyed by cycle id.
- Consider four-eyes (MakerChecker, ADR-0034) for `settle`.
- Graduate OPA authz from advisory to enforce (Phase 5) so `@Authorize` denies are blocked, not just logged.

## 6. Change log

- **2026-05-30** — Added `clearing_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/
  surface/boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
