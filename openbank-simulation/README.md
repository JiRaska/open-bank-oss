<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# openbank-simulation — Deterministic Simulation Testing harness (ADR-0100)

A pure-JVM Deterministic Simulation Testing (DST) harness for the OpenBank money path. It runs
the ledger / balance / payment-saga semantics through a **single seeded, deterministic
scheduler under adversarial fault injection** and asserts global money invariants after every
step. Any failure is reproducible from its seed.

This is **tooling, not a released service**: no `version.txt`, not in `release-please-config.json`,
never containerised. It is the lowest-cost first rung of ADR-0100 Layer 2 — the "Pure JVM
simulation" engine option. The implementation roadmap lives in issue **#1612**.

## Why

The standard test ratchet (unit → integration → property → mutation) still runs on *one*
non-deterministic execution: OS thread scheduling, wall-clock timers, networks that never
partition unless a test remembers to make them. DST instead replaces every source of
non-determinism with a seeded simulator, drives the system through many fault permutations, and
checks invariants after each — the model used by FoundationDB and TigerBeetle. A DST harness
would have caught the manual-settlement compensation gap (ADR-0100 §Context) by exhaustion, not
luck.

## What it runs

```
src/main/kotlin/com/openbank/simulation/
  engine/      VirtualClock, SimulationRandom, FaultInjector, DeterministicScheduler, SimulationContext
  model/       Ledger (double-entry), Balance (overdraft floor), PaymentSaga (libs SagaStateMachine)
  adapters/    SimEventBus (at-least-once: duplicate/drop-redeliver/reorder), BalanceProjection, AuditLog
  invariants/  the ADR-0100 Layer-3 global assertions
  scenario/    PaymentScenario — one internal transfer through the full saga
  runner/      SimulationRunner — seed-driven exhaustion loop + reproducible SeedResult
```

### Layer-3 invariants asserted after every step

| Invariant | Meaning |
|-----------|---------|
| `ledger-conservation-of-money` | `Σ debit == Σ credit` per currency ⇒ no money created/destroyed |
| `no-negative-balance` | available never below the overdraft floor |
| `ledger-balance-projection-consistency` | each balance's booked movement equals the ledger's net delta (idempotent projection) |
| `compensation-completeness` | no saga left stuck; every one reaches COMPLETED / COMPENSATED / FAILED |
| `audit-completeness` | the hash-chained audit log verifies and covers every saga |

## Run it

```bash
./gradlew :openbank-simulation:test          # the full DST suite (compile + run)
./gradlew :openbank-simulation:build         # + detekt + ktlint + coverage report
```

`DstSimulationTest` is the end-to-end proof and is structured to be credible — a harness that
only ever passes proves nothing:

1. **Happy path** — all invariants hold across 200 seeds with no faults.
2. **Adversarial + correct code** — all invariants hold across 300 seeds under the hostile
   fault profile (dropped/duplicated/reordered events, write failures, lock conflicts), because
   the projection is correctly dedup-guarded.
3. **Bug detection** — flip the projection's dedup guard off (a realistic idempotency gap) and
   the harness *finds* the resulting `projection-consistency` violation.
4. **Reproducibility** — replaying the failing seed reproduces the exact same violation.

## Fidelity and limits (honest scope)

- **Ledger postings run the REAL aggregate.** The harness depends on `openbank-ledger-service`
  and drives the production `JournalEntry` — its `validateBalance()` invariant fires on
  construction, `bookedDeltas()` (ADR-0039 credit-positive deposit-control projection) feeds the
  balance, and `reverse()` performs compensation. The ledger domain is framework-free (ADR-0002),
  so only its POJOs are on the classpath, not a Quarkus runtime. Built on the real `openbank-libs`
  primitives too (`Money` arithmetic + scale rules, the shared `SagaStateMachine`).
  - *DST finding:* `JournalEntry.reverse()` calls `Instant.now()`/`UUID.randomUUID()` directly —
    a clock-injection gap (ADR-0100 Layer 1, the `clock_injection` rule). It only affects the
    reversal's id/timestamp, not the booked money math, so the verdict stays seed-reproducible.
- **Still re-modelled** (not yet bound to the exact service classes): `Balance` (overdraft floor)
  and the `PaymentSaga` orchestration — the real ones sit behind heavier application/infra
  collaborators (period locks, fiscal-year repos, outbox). Binding those, plus driving the real
  `LedgerService`/`PaymentSagaOrchestrator` use-cases, is tracked in #1612.
- **Pure-JVM fidelity ceiling** (ADR-0100): this engine virtualises the domain + application
  layers single-threaded; it cannot catch JVM-threading or OS-level non-determinism. The
  higher-fidelity Antithesis hypervisor option is deferred (evaluate after this proves value).
