# Resilience bounds — openbank-loyalty-service

Operational reference for the declarative resilience configuration seeded in
`application.yaml` by the 2026-10 fleet sweep (billing #1682 convention). The
values below are held by the enforced CI gates `rest-client-timeouts` and
`datasource-pool-self-heal`; loosening them requires an explicit owner decision
in this file's service, not a silent edit.

## REST client timeouts

An unbounded wait on an upstream call is a defect in itself: one slow upstream
holds caller threads until the pool exhausts and the failure surfaces several
services away from its cause.

This service declares no url-carrying REST clients.

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

The trace-contract IT boots its own Quarkus application under a distinct test
profile, so the module's test fork hosts two augmentations and boots where the
rest of the suite hosts one. Measured on this branch (CI run 44293166069 and a
local full-suite run): at Gradle's default 512 MiB test heap the fork died with
`OutOfMemoryError` during JPA bootstrap — 41 CI tests completed, the trace
contract failed in boot, and the suite executor aborted.

`tasks.test { maxHeapSize = "2g" }` (the statement-service convention) leaves
measured headroom. Re-measure with `./gradlew :openbank-loyalty-service:test`
after adding any further profile-booting IT; a green single-class run is not
evidence, the OOM only surfaced with the full suite resident.
