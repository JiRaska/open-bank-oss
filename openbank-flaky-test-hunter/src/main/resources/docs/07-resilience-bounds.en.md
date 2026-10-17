# Resilience bounds — openbank-flaky-test-hunter

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

## Policy-gate sidecar timeout

The PR-path policy gate (see the service README) queries the OPA sidecar over plain HTTP with a
single per-call bound, `flaky-test-hunter.policy-gate.timeout` (default `PT0.5S`), covering both
connect and request. The bound is deliberately short because the gate sits on the agent's write
path: exceeding it does not retry or degrade — it fails the disposition closed (`pdp_unreachable`
DENY). Unlike the declarative REST clients above, this bound is held by the policy-gate config
keys, not by the `rest-client-timeouts` gate; loosening it is a policy-gate owner decision, not a
silent edit.

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

## Coverage floor

`build.gradle.kts` pins the Kover coverage floor to the value measured on 2026-10-14 (the previous floor was far below the real coverage, so a real regression could land without tripping it). The floor is ratchet-only: it may be raised, never lowered without justification in the PR.
