# Resilience bounds — openbank-campaign-service

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
| `consent-service` | 2000 | 3000 |
| `incentive-service` | 2000 | 3000 |

## Reactive datasource pool self-heal

Quarkus leaves `idle-timeout` / `max-lifetime` / `max-size` off by default, so a
transient DB blip that poisons pooled connections wedges the service until a pod
restart — with every health probe green (billing #1682: ~8h unreachable against a
healthy DB). These bounds make the pool discard suspect connections on its own.

- `idle-timeout` evicts a dead idle connection within the cleaner period.
- `max-lifetime` is the hard ceiling on how long any wedge can survive.
- `max-size` bounds a reconnect storm.

Configured values:

- `idle-timeout: 5M`
- `max-lifetime: 30M`
- `max-size: 20`



## Test JVM heap budget

`CampaignTraceContractIT` boots a second Quarkus application (the trace contract's own
instance, with its own span recorder), so the module's test fork carries two augmentations. The
Gradle default 512 MiB test heap measured as `OutOfMemoryError` for that shape in other modules
(ADR-0388), so `build.gradle.kts` declares `tasks.test { maxHeapSize = "2g" }`. This is a build-time
bound on the test JVM only; it does not change the service's runtime memory.

To re-measure: run the full `:openbank-campaign-service:test` suite, not a single class, because
single-class runs stay green and only the full suite reproduces the exhaustion. The
`trace-contract-heap` gate keeps the declaration in place.
