<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — fx-service

- **Date:** 2026-06-17
- **Status:** Lightweight STRIDE/DFD (ADR-0030 D2). **Money-path** bounded context.
- **Service ADR:** see `docs/adr/`; platform controls per ADR-0029/0030/0034.

## 1. Scope & purpose

FX rates and currency conversion: publish/query rates, convert, query conversion. Rate integrity
directly determines monetary outcomes — a manipulated rate is a financial-loss vector.

## 2. Data flow (DFD)

```
[Rate source/operator] --> (REST /api/v1/fx/rates) --> [fx-service] --> [(Postgres: fx rates, conversions)]
[Payment/account callers] --> (convert) ----------------^                   |
                                                                            +--> [(fx_outbox)] --> [Kafka fx events]
                                                                            |
                                                                            +--> [fraud-service] (shadow, OIDC CC / mTLS, fail-open)
```

- **External entities:** rate-feed/operator, payment/account services requesting conversion.
- **Trust boundaries:** rate ingestion (integrity-critical); caller↔service; service↔Postgres/Kafka;
  service↔fraud-service (OIDC client-credentials + mTLS, internal cluster-only, shadow/read-only).
- **Assets:** FX rate table, conversion records.

## 3. Authn/Authz

- `@RolesAllowed` enforced: rate publish restricted to `ROLE_OPERATOR/ADMIN`; convert allows
  `ROLE_PAYMENTS`; read includes `ROLE_VIEWER`.

## 4. STRIDE

| Threat | Vector | Mitigation |
|---|---|---|
| **S**poofing | Fake rate-feed pushes a rate | Authenticated rate source; operator-only publish |
| **T**ampering | Off-market / stale rate used for conversion | Rate provenance + timestamp; staleness check; bounds/sanity limits; audit |
| **R**epudiation | Deny publishing a bad rate | AuditEvent per rate publish + conversion |
| **I**nfo disclosure | Rate scraping / conversion history | AuthZ; rate read is low-sensitivity but rate-limited |
| **D**oS | Conversion flooding | Rate limit; cache current rate |
| **E**oP | Viewer publishes a rate | Distinct publish role; deny-by-default |

## 5. Residual risks / assumptions

- **Rate-source integrity** is the dominant risk — compromise yields silent financial loss.
- Conversions should pin the rate id/timestamp used (auditability + dispute defense).

## 6. Change log

- **2026-08-03** — Missing required query/header parameter answered 500, not 400 (#3104). A required `@QueryParam`/`@HeaderParam` declared with a non-nullable Kotlin type was fed `null` by JAX-RS when the caller omitted it, and answered **500** rather than 400 (#3104). Kotlin's null-safety is compile-time only, so the declared type only decided where the failure landed: a non-suspend handler threw `Intrinsics.checkNotNullParameter` at the method boundary, and a **suspend** handler got no intrinsic at all, so the null flowed into the body. `Idempotency-Key` on convert, the sibling of the null-body guard added by #3050 one argument position over. Same defect as domestic-payment: `require(key.isNotBlank())` in a `suspend` handler threw NPE on an absent header, so the replay control answered 500 for a missing key and 400 only for a blank one. Now `require(!key.isNullOrBlank())`. No new caller or boundary. Rollback: revert.
- **2026-05-30** — Added `fx_outbox_seq` (Hibernate fix). Additive DDL only — no new flow/surface/
  boundary. Risk class = **availability**, mitigated by `HibernateSequenceGuardTest`.
  Rollback: `DROP SEQUENCE`.
- **2026-06-17** — ADR-0084 fraud shadow scoring (observe-only). New outbound trust boundary:
  `fx-service → fraud-service (POST /api/v1/fraud/score, OIDC client-credentials)`.
  **Shadow = fail-open and never-enforce**: `FxConversionService.scoreFraudShadow()` wraps the call
  in `.onFailure().recoverWithItem {}` — any fault (timeout, circuit-open, 5xx) is swallowed; the
  conversion outcome is unchanged. `FraudScoringAdapter` applies `@CircuitBreaker` (30% failure ratio)
  + `@Timeout(3 s)`. No retry (avoid double-scoring on the same conversion).
  **Risk class = availability** (fault in fraud-service cannot block a conversion) and **confidentiality**
  (conversion amount, currency pair, account id sent to fraud-service; mitigated by mTLS +
  OIDC client-credentials; fraud-service is internal, cluster-only).
  **DFD update**: added `fx-service → fraud-service` edge (see §2). No DB schema change;
  rollback = revert adapter + port commits.
- **2026-06-15** — V5 seed migration + CNB validTo window extension.
  - **V5 seed** (`V5__seed_fx_history.sql`): inserts synthetic `INTERNAL` rates (90-day history + today)
    and approximate CNB reference rows for sandbox/CI. Pure INSERT — no DDL, no conversion-path change.
    Seed values are synthetic; actual rates still require operator publication via the authenticated
    `/api/v1/fx/rates` endpoint. Risk class = **data integrity (sandbox only)**: stale seed rates cannot
    affect conversion accuracy once a real operator publish or CNB ingest overrides them (findLatest
    returns highest validFrom row within the current window). No prod-settlement risk.
    Rollback: delete rows WHERE source IN ('INTERNAL','CNB') AND created_at >= '2026-06-15'.
  - **CNB validTo 1→3 days** (`CnbRateIngestionService`): extends the reference-rate window to cover
    weekends and public holidays (scheduler runs at 14:40 Prague time on business days). Idempotency
    unaffected (keyed on `(source, pair, validFrom)`). Risk: a slightly stale CNB mid rate used in
    `spreadPct` display for at most 3 days. Rate table integrity and conversion accuracy are not affected
    (INTERNAL bid/ask are the settlement rates; CNB mid is display-only). Residual risk = **negligible**.
