<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->

# openbank-simulation — Deterministic Simulation Testing harness (ADR-0100)

A pure-JVM Deterministic Simulation Testing (DST) harness for the OpenBank money path. It runs
the ledger / balance / payment-saga / SEPA-settlement / billing / interest / statement-close
semantics through a **single seeded, deterministic scheduler under adversarial fault injection**
and asserts global money invariants after every step. Any failure is reproducible from its seed.

This is **tooling, not a released service**: no `version.txt`, not in `release-please-config.json`,
never containerised. It is the lowest-cost first rung of ADR-0100 Layer 2 — the "Pure JVM
simulation" engine option.

This module is also the **day-in-the-life money-path proof** scoped in issue #667: the five
scenarios below run every seed, interleaved, against the same shared `World` — a single
reproducible run exercises payment → SEPA settlement → billing fee charge/reversal → interest
accrual/capitalization → statement period-close, all through the REAL domain aggregates of their
respective services, not a re-model.

## Why

The standard test ratchet (unit → integration → property → mutation) still runs on *one*
non-deterministic execution: OS thread scheduling, wall-clock timers, networks that never
partition unless a test remembers to make them. DST instead replaces every source of
non-determinism with a seeded simulator, drives the system through many fault permutations, and
checks invariants after each — the model used by FoundationDB and TigerBeetle. A DST harness
would have caught the manual-settlement compensation gap (ADR-0100 §Context) by exhaustion, not
luck — and, in practice, it has: interleaving `FeeBillingScenario` and `InterestAccrualScenario`
into the same per-step loop surfaced a real stale-balance-snapshot race (issue #667, fixed by
draining the scheduler after every scenario, not just once at the end of the step).

## What it runs

```
src/main/kotlin/com/openbank/simulation/
  engine/      VirtualClock, SimulationRandom, FaultInjector, DeterministicScheduler, SimulationContext
  model/       LedgerState, BalanceStore, BillingFeeLedger, InterestAccrualBook, StatementCloseBook
  adapters/    SimEventBus (at-least-once: duplicate/drop-redeliver/reorder), BalanceProjection, AuditLog
  invariants/  the ADR-0100 Layer-3 global assertions (MoneyPathInvariants)
  scenario/    one object per money-path leg (below) — each drives the REAL service domain
  runner/      SimulationRunner — seed-driven exhaustion loop + reproducible SeedResult
```

### Scenarios (each step runs all five, interleaved, against the real domain of its service)

| Scenario | Money-path leg | Real domain driven |
|---|---|---|
| `PaymentScenario` | internal transfer, saga + compensation | `openbank-ledger-service` `JournalEntry`, `openbank-transaction-service` `SagaState` |
| `SepaSettlementScenario` | SEPA payment + settlement | `openbank-sepa-payment` `SepaPayment`, `openbank-settlement-service` `Settlement` |
| `FeeBillingScenario` | billing fee charge + reversal (ADR-0143) | `openbank-billing-service` `AssessedFee`/`FeeJournalCommand`/`FeeReversalCommand` |
| `InterestAccrualScenario` | interest accrual + capitalization + withholding (ADR-0033) | `openbank-interest-service` `InterestAccrual`/`InterestCapitalization`/`WithholdingTaxPolicy` |
| `StatementCloseScenario` | statement period-close (ADR-0035/0078) | `openbank-statement-service` `StatementPeriod`/`ReconciliationPolicy` |

### Layer-3 invariants asserted after every step

| Invariant | Meaning |
|-----------|---------|
| `ledger-conservation-of-money` | `Σ debit == Σ credit` per currency ⇒ no money created/destroyed |
| `no-negative-balance` | available never below the overdraft floor |
| `ledger-balance-projection-consistency` | each balance's booked movement equals the ledger's net delta (idempotent projection) |
| `compensation-completeness` | no saga left stuck; every one reaches COMPLETED / COMPENSATED / FAILED |
| `audit-completeness` | the hash-chained audit log verifies and covers every saga |
| `sepa-payment-completeness` | every `SepaPayment` reaches a terminal status |
| `settlement-completeness` | every `Settlement` reaches a terminal status |
| `billing-fee-conservation` | `Σ fees assessed == Σ fee journals posted` per cycle/account/fee/currency |
| `interest-capitalization-conservation` | `Σ capitalized ≤ Σ accrued`, `gross == net + tax`, both journal legs actually posted |
| `statement-close-integrity` | a period is persisted **iff** `ReconciliationPolicy` reconciled — never on a mismatch, never skipped on success |

## Run it

```bash
./gradlew :openbank-simulation:test          # the full DST suite (compile + run)
./gradlew :openbank-simulation:build         # + detekt + ktlint + coverage report
./gradlew :openbank-simulation:test -Pseed.count=2000   # deeper sweep (ADR-0115)
```

`DstSimulationTest` is the end-to-end proof and is structured to be credible — a harness that
only ever passes proves nothing:

1. **Happy path** — all invariants hold across 300 seeds × 50 steps with no faults.
2. **Adversarial + correct code** — all invariants hold across 300 seeds under the hostile
   fault profile (dropped/duplicated/reordered events, write failures, lock conflicts), because
   the projection is correctly dedup-guarded.
3. **Bug detection** — flip the projection's dedup guard off (a realistic idempotency gap) and
   the harness *finds* the resulting `projection-consistency` violation.
4. **Reproducibility** — replaying the failing seed reproduces the exact same violation.

Every scenario/invariant pair follows the same two-sided proof at smaller scale in its own test
class (e.g. `StatementCloseIntegrityInvariantTest` proves the invariant both holds correctly *and*
catches a period wrongly persisted despite a reconciliation mismatch).

### Captured output (`./gradlew :openbank-simulation:test`, reproducible from this commit)

```
11 test classes, 51 tests, 0 failures, 0 errors, 0 skipped
  DstSimulationTest                            4 tests  — 300-seed × 50-step sweep, happy/adversarial/bug-detection/reproducibility
  EngineDeterminismTest                        3 tests
  BillingFeeConservationInvariantTest          7 tests
  InterestConservationInvariantTest            7 tests
  StatementCloseIntegrityInvariantTest         5 tests
  LedgerModelTest                              5 tests
  StatementCloseBookTest                       3 tests
  FeeBillingScenarioTest                       6 tests
  InterestAccrualScenarioTest                  4 tests
  SepaSettlementScenarioTest                   4 tests
  StatementCloseScenarioTest                   3 tests
```

## Fidelity and limits (honest scope)

- **Ledger, billing, interest and statement postings run the REAL aggregates and pure domain
  policies.** `JournalEntry.validateBalance()`/`bookedDeltas()`/`reverse()` (ADR-0039),
  `WithholdingTaxPolicy` (ADR-0033 §36/§38d statutory rounding), `ReconciliationPolicy` (ADR-0035
  §E fail-closed period-boundary check) — all framework-free domain code (ADR-0002), so only
  POJOs land on the classpath, not a Quarkus runtime.
  - *DST finding:* `JournalEntry.reverse()` calls `Instant.now()`/`UUID.randomUUID()` directly —
    a clock-injection gap (ADR-0100 Layer 1, the `clock_injection` rule). It only affects the
    reversal's id/timestamp, not the booked money math, so the verdict stays seed-reproducible.
  - *DST finding (issue #667):* a stale per-step balance snapshot let `FeeBillingScenario`'s
    affordability check see an already-decided-but-unapplied `PaymentScenario` debit as if it had
    never happened — two individually-affordable debits could jointly overdraw an account. Fixed
    by draining the scheduler after every scenario, not once at the end of the step.
- **Still re-modelled** (not yet bound to the exact service classes): `Balance` (overdraft floor)
  and the `PaymentSaga` orchestration — the real ones sit behind heavier application/infra
  collaborators (period locks, fiscal-year repos, outbox). Binding those, plus driving the real
  `LedgerService`/`PaymentSagaOrchestrator` use-cases, is tracked in #1612.
- **Not covered, and deliberately out of scope for this harness:** customer onboarding/KYC (a
  fundamentally different, non-money-path flow — party creation, document verification), fraud
  scoring/enforcement (a REST-boundary decision gate, not a domain aggregate this harness can
  bind to the way it binds ledger/billing/interest/statement), statement *rendering*
  (`StatementRenderer` — camt.053/MT940/PDF projection, a read-side concern with no money-path
  invariant of its own), and FinRep/CoRep regulatory extracts (a reporting aggregation, not a
  posting flow). A genuine end-to-end proof of those legs needs a real running-services
  integration script (docker-compose), not this pure-JVM domain simulator — see issue #667 for
  the scope discussion.
- **Pure-JVM fidelity ceiling** (ADR-0100): this engine virtualises the domain + application
  layers single-threaded; it cannot catch JVM-threading or OS-level non-determinism. The
  higher-fidelity Antithesis hypervisor option is deferred (evaluate after this proves value).
