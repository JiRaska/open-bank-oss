# Resilience bounds — openbank-agent-service

Operational reference for the declarative resilience configuration seeded in
`application.yaml` by the 2026-10 fleet sweep (billing #1682 convention). The
values below are held by the enforced CI gates `rest-client-timeouts` and
`datasource-pool-self-heal`; loosening them requires an explicit owner decision
in this file's service, not a silent edit.

## REST client timeouts

An unbounded wait on an upstream call is a defect in itself: one slow upstream
holds caller threads until the pool exhausts and the failure surfaces several
services away from its cause.

| Client | connect-timeout | read-timeout |
|---|---|---|
| `account-service` | 2000 | 5000 |
| `aml-service` | 2000 | 5000 |
| `balance-service` | 2000 | 5000 |
| `clearing-service` | 2000 | 5000 |
| `consent-service` | 2000 | 5000 |
| `dispute-service` | 2000 | 5000 |
| `fx-service` | 2000 | 5000 |
| `interest-service` | 2000 | 5000 |
| `ledger-service` | 2000 | 5000 |
| `opa` | 2000 | 5000 |
| `product-catalog` | 2000 | 5000 |
| `psd2-service` | 2000 | 5000 |
| `sanctions-service` | 2000 | 5000 |
| `sepa-instant-service` | 2000 | 5000 |
| `transaction-service` | 2000 | 5000 |

## Reactive datasource pool self-heal

Quarkus leaves `idle-timeout` / `max-lifetime` / `max-size` off by default, so a
transient DB blip that poisons pooled connections wedges the service until a pod
restart — with every health probe green (billing #1682: ~8h unreachable against a
healthy DB). These bounds make the pool discard suspect connections on its own.

This service does not use a reactive datasource pool.

## Coverage floor

`build.gradle.kts` pins the Kover coverage floor to the value measured on 2026-10-14 (the previous floor was far below the real coverage, so a real regression could land without tripping it). The floor is ratchet-only: it may be raised, never lowered without justification in the PR.
