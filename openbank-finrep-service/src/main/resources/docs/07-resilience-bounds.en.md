# Resilience bounds — openbank-finrep-service

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
| `ledger-service` | 2000 | 5000 |

## Reactive datasource pool self-heal

Quarkus leaves `idle-timeout` / `max-lifetime` / `max-size` off by default, so a
transient DB blip that poisons pooled connections wedges the service until a pod
restart — with every health probe green (billing #1682: ~8h unreachable against a
healthy DB). These bounds make the pool discard suspect connections on its own.

This service does not use a reactive datasource pool.



## Advisory authorization for regulatory reporting

The FINREP template, COREP template, reporting period list and XBRL-CSV preflight
routes send their authorization decisions to the local OPA sidecar. The policy
allows verified human operators and administrators. The existing JWT role gate
continues to decide HTTP access while `authz.enforce` is false. The application
records advisory decisions in `openbank.authz.decisions`. The default OPA request
timeout is 500 ms.

The ledger and risk-engine calls keep their respective outbound identities
`openbank-services` and `openbank-finrep`. Those grants are evaluated by the
upstream services.
