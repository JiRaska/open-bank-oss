# Resilience bounds — openbank-interest-service

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
| `transaction-service` | 2000 | 5000 |

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



## Test JVM heap bound

The forked test JVM runs the service's `@QuarkusTest` suite and, since the
trace-contract IT landed, a second full Quarkus boot (OTel SDK +
Testcontainers) in the same process. On the Gradle default ~512m heap CI died
with `java.lang.OutOfMemoryError` and `QuarkusTestExtension` then hung until
the 10-minute hang-detection timeout killed the executor — measured 2026-10-15.

`tasks.withType<Test> { maxHeapSize = "2g" }` in `build.gradle.kts` bounds it.
Deliberately per-module rather than a fleet default (same account-service
reasoning): nothing measures test heap anywhere, so a global bump would be an
unmeasured ratchet across ~50 modules to fix one. A second module OOMing is the
signal to raise the bound in build-logic and count Quarkus boots per module.
