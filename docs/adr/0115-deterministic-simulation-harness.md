# 115. Deterministic simulation harness — seed-driven money-path invariant checker (ADR-0100 Layer 2/3)

Date: 2026-06-25
Author: Claude (paired with Jiří Raška)
Status: Accepted (2026-06-28 — CI gate wired in `services-ci.yml`: DST harness runs on every
money-path change via a dependency edge to `openbank-simulation`, under `Services CI / all-green`)
Delivery-Status: Shipped

## Context

ADR-0100 introduced deterministic clock injection (Layer 1) across the fleet. Layer 2 (simulation
runner) and Layer 3 (invariant checking) are implemented in `openbank-simulation` but have no ADR
documenting their purpose, architecture, or CI integration status.

`openbank-simulation` is a Gradle module auto-discovered by `settings.gradle.kts`. It builds on
the **real** domain aggregates from `openbank-ledger-service`, `openbank-balance-service`, and
`openbank-transaction-service` (framework-free domain layers, ADR-0002) rather than re-modelling
them — so the simulation exercises production code paths, not a faithful copy.

**As of 2026-06-25, simulation tests are NOT wired into CI.** The module compiles and its JUnit5
tests pass locally (`./gradlew :openbank-simulation:test`), but no CI workflow invokes them. The
5 money-path invariants are only checked on-demand.

## Decision

**What the harness is (implemented):**

`openbank-simulation` is an **offline property-based test harness** for money-path correctness.
It is not chaos engineering (no production fault injection), not a load test, and not deployed to
any cluster — it is a build-time artifact only.

**Architecture (Layer 2 — runner):**

`SimulationRunner` iterates over seeds `0..N`. For each seed it:
1. Builds a fresh `World` (in-memory model: ledger, balances, sagas, audit chain).
2. Runs `PaymentScenario.step(world)` for `stepsPerSeed` iterations (default 50).
3. After each step, drains the deterministic scheduler (`SimulationContext.scheduler.drain()`).
4. Checks all Layer-3 invariants. First violation aborts that seed and reports `(seed, step)` for
   deterministic replay via `runSeed(failingSeed)`.

**Architecture (Layer 3 — invariants):**

Five invariants in `MoneyPathInvariants.ALL`, checked after every step:

| Name | What it checks |
|------|---------------|
| `ledger-conservation-of-money` | `Σ debit == Σ credit` per currency — net is zero |
| `no-negative-balance` | `available ≥ -overdraftLimit` for every account |
| `ledger-balance-projection-consistency` | `balance.booked == opening + ledger.netDelta` (dedup guard) |
| `compensation-completeness` | No saga left in non-terminal state after scheduler drain |
| `audit-completeness` | Audit hash chain verifies + `audit.size ≥ saga.count` |

**Fault injection:** `FaultProfile` can inject timeout, duplicate event, and replay fault modes to
exercise compensating transaction paths.

**CI integration (DONE — `services-ci.yml`):**

The harness runs as part of the existing per-service matrix rather than a hand-rolled standalone
job — `openbank-simulation` is a Gradle module, so `_service-ci.yml` already runs
`:openbank-simulation:build` (which runs `DstSimulationTest`) whenever the simulation itself changes,
on every push to `main`, and in the nightly full-fleet run.

The one gap that needed closing: CI change-detection is **per-directory, not dependency-aware**, so a
change to a money-path service the harness validates (`openbank-ledger-service`,
`openbank-balance-service`, `openbank-transaction-service`) would *not* have triggered the simulation
that depends on it. The `changes` detector in `services-ci.yml` now adds an explicit **dependency
edge**: when any of those three services is in the per-module fan-out, `openbank-simulation` is added
to the build matrix. The harness therefore gates every money-path change, aggregated under the
required `Services CI / all-green` check. A failing seed produces a reproducible `(seed, step)` tuple
in the service's test report; the seed count is tunable via `-Pseed.count=N` for deep manual runs
(default in `DstSimulationTest`).

This realises the intent of the originally-proposed standalone job while reusing the proven
path-scoped pipeline (toolchain, caching, Testcontainers-free) instead of duplicating its setup.

## Alternatives considered

- **Testcontainers integration tests.** Tests real DB and Kafka but are non-deterministic (timing,
  event ordering), slow (minutes per run), and cannot replay a failing scenario with a seed.
- **Kotest property-based testing per service.** Appropriate for unit-level; cannot model
  multi-aggregate interactions (ledger + balance + saga simultaneously).
- **Antithesis / external chaos platform.** Considered in ADR-0100; deferred. The in-house harness
  is the zero-tooling first step.

## Consequences

**Positive**
- Any invariant violation is reproducible and debuggable without a live cluster.
- New money-path features get invariant coverage by adding a scenario step — no separate test setup.
- Uses real production aggregates, not re-modelled copies — drift between simulation and production
  is structurally impossible for the exercised code paths.

**Negative**
- The in-memory `World` model must stay in sync with real domain changes. A domain rename that
  isn't reflected in simulation scenarios silently reduces coverage.
- Simulation does not cover network/DB race conditions (concurrent writes, Kafka redelivery at
  the infrastructure level) — that is the domain of Testcontainers IT tests.
- CI integration is not done yet; until it is, failing seeds go undetected.

**Neutral**
- `openbank-simulation` is a build-time artifact only. It is not deployed to any cluster.
- Seed count of 200 in CI is a starting point; it can be raised as runner capacity allows.

## Compliance impact

- DORA Art. 25 (ICT testing): simulation results can serve as evidence for the ICT testing
  programme alongside Testcontainers IT tests and the pentest programme (ADR-0080).
- Other: not applicable.

## References

- `openbank-simulation/src/main/kotlin/.../runner/SimulationRunner.kt`
- `openbank-simulation/src/main/kotlin/.../invariants/MoneyPathInvariants.kt`
- `openbank-simulation/build.gradle.kts`
- ADR-0100 (deterministic simulation testing — Layer 1 clock injection)
- ADR-0080 (pentest remediation — complementary testing track)
