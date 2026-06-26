# 100. Deterministic Simulation Testing for the banking core

Date: 2026-06-19
Status: Accepted
Author(s): OpenBank platform

## Context

The test programme (ADR-0029 layer B, expanded by the coverage roadmap from 2026-06) follows
a standard ratchet: unit → integration → property-based → mutation. That progression increases
confidence but each layer still operates on a *single*, *non-deterministic* execution
environment: threads are scheduled by the OS, wall-clock timers fire unpredictably, network
partitions never happen unless the test author remembers to inject them. Flaky CI is the most
visible symptom; silent money-bug recoverability gaps are the dangerous one.

Four converging forces make the next rung — Deterministic Simulation Testing (DST) — worth
designing for now:

1. **The money-bug in the manual settlement orchestrator** (found 2026-06-19) exposed a
   structural fragility: hand-rolled sagas are the *likely* source of compensation gaps, not
   an edge case. A DST harness would have found this class of fault through exhaustive
   fault-injection long before it reached a live system.

2. **Temporal adoption (ADR-0101) is coming.** Temporal's workflow engine is itself designed
   to be deterministic and replayable; coupling it with a DST harness that exercises its
   failure modes closes the gap between "the workflow is correct" and "the workflow survives
   any adversarial execution order."

3. **Property-based tests (Kotest `forAll`) are in production** (PR #1124/#1125). DST is the
   natural next rung: property tests shrink a counterexample found in a random state space;
   DST exhausts a *controlled* state space under fault injection. The mental model is already
   present in the team.

4. **OSS banking credibility.** No open-source core banking system currently publishes a DST
   harness. TigerBeetle's VOPR, FoundationDB's simulation framework, and Antithesis's
   deterministic hypervisor represent the state-of-the-art in financial and distributed
   infrastructure. Shipping an equivalent for a JVM/Quarkus banking core would be a
   genuinely differentiating signal — "correctness under adversarial failure" rather than
   "99% unit coverage."

**Scope for this ADR.** DST is an *architectural commitment* — it constrains how new services
and libraries are written (deterministic-injectable clocks, I/O virtualisation, event-sourced
state). This document decides *whether* to commit and *what the architecture must look like*.
It deliberately does not commit to a specific harness library (the JVM ecosystem here is
immature; see Alternatives) and defers the implementation roadmap to a follow-up issue.

### What "deterministic simulation testing" means in this context

A DST run: (a) replaces all sources of non-determinism (wall-clock, random, I/O, thread
scheduler, network) with a *controlled, seeded simulator*; (b) drives the system through
millions of scenario/fault permutations under that seed; (c) asserts *global invariants* (no
double-credit, no negative balance, ledger always balanced, compensation always reached) after
every permutation; (d) on failure, replays the exact seed to reproduce the trace
deterministically.

## Decision

**We will design the OpenBank core for deterministic simulation testability and ship a DST
harness covering the money-path services (ledger, settlement, sepa-payment,
domestic-payment, balance).**

The commitment is three-layered:

### Layer 1 — Architectural constraints (applied to all new money-path code)

1. **Clock injection.** No `Instant.now()` in domain or application layer. All services
   accept a `Clock` (or Temporal-workflow `currentTimeMillis`) from the container. The CDI
   `@ApplicationScoped Clock` bean is already the pattern in `openbank-libs`; make it
   mandatory for money-path by adding a governance rule to `rules.yaml`.

2. **I/O virtualisation seam.** All outbound I/O (DB, Kafka, HTTP) goes through the
   hexagonal port/adapter boundary (ADR-0002). A DST adapter replaces each port with an
   in-memory, fault-injectable implementation: the `LedgerRepository` returns stale reads,
   throws `OptimisticLockException`, or delays; the `EventPublisher` drops, duplicates, or
   reorders messages on demand.

3. **Deterministic randomness.** Any service that uses `Random` or `UUID.randomUUID()` must
   accept a seeded source from the container. `openbank-libs` will expose a
   `DeterministicRandom` CDI bean (backed by `java.util.Random(seed)`) alongside the
   existing `SecureRandom` production bean.

4. **No thread-scheduler dependence.** Money-path application services must be
   single-threaded-safe: no `synchronized` blocks relying on wall-time, no `Thread.sleep`,
   no `CompletableFuture` chaining that races with business logic. Reactive pipelines
   (Mutiny) are fine — they are virtualised by the DST scheduler.

### Layer 2 — DST harness

A new Gradle sub-project `openbank-simulation` (no `version.txt`; tooling, not a released
service):

```
openbank-simulation/
  src/main/kotlin/
    sim/
      engine/      # deterministic scheduler, virtual clock, fault injector
      adapters/    # in-memory port implementations (DB, Kafka, HTTP)
      scenarios/   # scenario DSL: account lifecycle, payment round-trips, settlement EoD
      invariants/  # global assertions run after every step
      runner/      # seed-driven exhaustion loop + shrinking
```

**Engine choices** (decision deferred to implementation issue; constraints recorded here):

- **Pure JVM simulation** — write a minimal Kotlin scheduler that virtualises Mutiny/coroutine
  execution order. Lower fidelity than process-level simulation but zero tooling dependency.
  Viable for the domain + application layers; cannot catch JVM threading bugs.
- **Antithesis integration** — run the full Quarkus services inside the Antithesis
  deterministic hypervisor. Highest fidelity; requires a commercial agreement and Docker
  images. Evaluate after the pure-JVM harness proves value.
- **TigerBeetle-style VOPR port** — the VOPR is written in Zig; porting its scheduler model
  to Kotlin is possible but costly. Use as design reference, not as a dependency.

The implementation issue will benchmark fidelity vs. cost and pick one.

**Implementation status.** The **Pure JVM simulation** first rung has shipped as the
[`openbank-simulation`](../../openbank-simulation/) module (tooling; no `version.txt`). It
virtualises the money-path semantics — double-entry ledger, balance/overdraft, the payment saga
and its compensation, ledger→balance projection with at-least-once delivery — under a seeded
deterministic scheduler + fault injector, and asserts the Layer-3 invariants below after every
step. It is built on the real `openbank-libs` primitives (`Money`, `SagaStateMachine`); binding
it to each service's exact domain classes, and the Antithesis fidelity step, remain tracked in
#1612.

### Layer 3 — Global invariants (always-on assertions)

Money-path DST asserts after every simulated step:

| Invariant | Expression |
|-----------|-----------|
| Ledger balance | `Σ debit entries == Σ credit entries` across all accounts |
| No negative balance | `∀ account: balance ≥ 0` (unless overdraft facility explicitly granted) |
| Idempotency | replay of any payment with the same `referenceId` produces identical final state |
| Compensation completeness | every saga that enters COMPENSATING eventually reaches COMPENSATED or FAILED (never stuck) |
| Audit completeness | every state transition appears in the hash-chained audit log |

These invariants are encoded as Kotlin `Invariant` objects and can be run against both the DST
harness **and** a production database snapshot (offline invariant checking — a separate use
case).

## Alternatives considered

- **More property-based tests (Kotest `forAll`).** Already in place; DST is complementary,
  not a replacement. Property tests shrink from a random seed; DST exhausts a controlled
  state space under explicit fault schedules. Rejected as the *sole* next step because
  property tests cannot model multi-step distributed failure scenarios.

- **Chaos engineering (Chaos Monkey / Litmus).** Injects faults into a running cluster.
  Non-deterministic: two identical runs produce different outcomes. Cannot reproduce a failure
  without exact log reconstruction. Useful *alongside* DST (production resilience validation)
  but cannot replace it for correctness proofs.

- **Formal verification (TLA+, Alloy).** The strongest correctness signal; used by AWS and
  FoundationDB. Prohibitive learning curve and maintenance cost for a team also shipping
  product. DST is a pragmatic approximation: not a proof, but millions of exhaustive runs
  under adversarial conditions catch almost all practical bugs. Revisit TLA+ for the ledger
  core invariants as a long-term stretch goal.

- **Do nothing / rely on Temporal.** Temporal's deterministic replay gives *workflow*
  correctness; it does not test the domain logic *inside* the workflow activities or the
  distributed interaction between services. The two are complementary.

## Consequences

**Positive**
- Money-path bugs found before production by exhaustive fault injection.
- Clock / I/O injection disciplines the codebase toward cleaner hexagonal boundaries.
- Deterministic reproduction of any failure — no more "works on my machine."
- OSS credibility signal: no other open-source banking core publishes a DST harness.
- The invariant set doubles as a continuous offline audit tool on production snapshots.

**Negative**
- **Non-trivial build investment.** `openbank-simulation` is a multi-month effort. Wrong to
  understate: a credible DST harness is probably 3–6 months of focused work.
- **Architectural discipline tax.** Clock injection, seeded random, and single-threaded-safe
  application services are constraints on every contributor. Governance rule enforcement
  (rules.yaml + CI) is essential to prevent drift.
- **JVM simulation fidelity ceiling.** A pure-JVM scheduler cannot catch JVM threading bugs
  or OS-level non-determinism. The Antithesis option raises fidelity but adds cost.
- **No ecosystem precedent on JVM.** TigerBeetle and FoundationDB are written in Zig and C
  respectively. Porting their models to Kotlin/JVM is design work with uncertain outcome.

**Neutral**
- The Temporal workflow engine (ADR-0101) is designed for deterministic replay and pairs
  naturally with DST; the two ADRs are complementary, not dependent.
- Existing Kotest property tests remain; DST runs are an additional gate, not a replacement.

## Compliance impact

- **DORA Art. 17** — ICT testing programmes must include advanced testing (TLPT). A DST
  harness that produces reproducible fault-injection traces is directly mappable to the
  "threat-led" evidence requirement.
- **DORA Art. 11** — backup and recovery testing. DST can simulate restore-from-backup paths
  deterministically.
- **PCI DSS Req. 6.4** — security testing of payment-processing components. Fault-injected
  DST covers the adversarial-input dimension of that requirement.
- PSD2, GDPR, CNB: not directly affected.

## References

- TigerBeetle VOPR: https://github.com/tigerbeetle/tigerbeetle/blob/main/docs/VOPR.md
- FoundationDB simulation: https://apple.github.io/foundationdb/testing.html
- Antithesis deterministic hypervisor: https://antithesis.com/
- ADR-0029 (governance as code, test ratchet)
- ADR-0101 (Temporal durable execution — companion ADR)
- Test coverage programme (issue #1122 et seq.)
