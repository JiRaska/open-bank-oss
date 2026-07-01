# 45. Saga framework: lightweight custom, in openbank-libs

Date: 2026-05-29
Status: Superseded by [ADR-0120](0120-migrate-transaction-payment-orchestration-to-temporal.md)
Delivery-Status: Shipped

**Delivery note (updated 2026-07-01):**
The one saga this ADR covered (`PaymentSagaOrchestrator` in transaction-service) was migrated
to Temporal in ADR-0120 Phase 5+6 (merged 2026-07-01, PR #17). The custom `AbstractSaga` extraction to
`openbank-libs` (pending deliverable 1) and the second-saga implementation (pending deliverable 2)
were never undertaken; ADR-0120's Temporal adoption supersedes the need for a custom primitive.
Future multi-service workflows should adopt Temporal per ADR-0120.
- **Pending deliverables 1–3** — ✅ Closed without implementation (superseded by ADR-0120).

## Context

[ADR-0004](0004-saga-for-multi-service-workflows.md) made the saga *pattern* mandatory
for every multi-service write workflow, but explicitly deferred the **framework choice**
("Axon, Temporal, or custom") to a follow-up ADR. That follow-up was never written — the
slot it pointed to (0011) was taken by the testing-pyramid ADR. As a result only one saga
exists (`PaymentSagaOrchestrator` in transaction-service), hand-rolled, and the remaining
target workflows (account-opening, SEPA SCT) were never started. This ADR closes that gap
by recording the framework decision.

The candidates:

- **Temporal** — durable execution engine. Gives durable timers, automatic retries,
  crash-recovery, and a workflow-history UI for free.
- **Axon Framework** — JVM event-sourcing + saga framework with its own event store.
- **Custom** — a small saga primitive living in `openbank-libs`, persisting saga state in
  each service's own Postgres, dispatched by the same poller pattern already used by the
  transactional outbox ([ADR-0013](0013-shared-outbox-in-openbank-libs.md)).

## Decision

We use a **lightweight custom saga primitive in `openbank-libs`**, not Temporal or Axon.

Rationale:

- **Operability / self-hostability.** OpenBank is a reference implementation an operator
  must be able to run. Temporal adds a stateful cluster (history + matching + frontend +
  its own datastore) that an operator would have to deploy, secure, back up, and bring into
  audit scope. Axon brings an opinionated event store that fights the existing
  hexagonal + Panache-per-service design.
- **Audit / regulatory scope.** Keeping saga state in the *same* Postgres-per-service as the
  business data ([ADR-0009](0009-postgres-per-service.md)) means one source of truth,
  co-located with the data it coordinates, and **no extra stateful system inside the CDE**.
  Saga state transitions are auditable through the same `AuditEvent` envelope as everything
  else. This is a smaller, cleaner audit surface than an external durable-execution cluster.
- **Consistency with existing primitives.** The codebase already has a pure, deterministic
  state-machine primitive (`CaseTransitionEngine` / `CaseTransitionPolicy` in
  `libs/domain/case`) and a poller-based dispatch primitive (the outbox dispatcher). A saga
  primitive is the same two ideas combined; reusing the house pattern keeps the cognitive
  surface small.
- **The durability Temporal gives "for free" has a low incremental cost here.** Saga state is
  persisted before each side effect; resuming stuck sagas is the same shape as the existing
  outbox poller. We pay that cost explicitly (see follow-ups) rather than adopting a cluster.

Orchestration vs choreography follows ADR-0004 unchanged: **choreography is the default**
(services react to outbox/Kafka events), **orchestration** (a coordinator driving the steps)
is used when a workflow has > 4 steps or needs explicit timeout / compensation logic.

## Primitive rollout (extract from concrete need, not up front)

To avoid generalising from a single example, the primitive is built incrementally as real
sagas require each piece:

1. **State machine (this ADR).** `SagaTransitionPolicy<S>` + `SagaStateMachine<S>` in
   `libs/domain/saga` — a generic, deterministic transition validator mirroring
   `CaseTransitionPolicy`/`CaseTransitionEngine`, parameterised over each saga's own state
   enum. `PaymentSaga` is migrated onto it, removing its duplicated transition table. This is
   the correctness-critical core every saga shares.
2. **Step + compensation contract + executor (deferred).** A `SagaStep` (idempotent
   `execute` + idempotent `compensate`) and a sequential executor that persists state between
   steps and compensates completed steps in reverse on failure. This lands together with the
   **first multi-step saga (account-opening: party + kyc + account + notification)**, so the
   abstraction is extracted from *two* concrete sagas rather than one.
3. **Stuck-saga sweeper + reconciliation (ops follow-up).** A scheduled resume/alert poller
   (same shape as the outbox dispatcher) satisfying ADR-0004's requirement that stuck sagas
   raise an alert and have a documented remediation path.

## Consequences

**Positive**
- No new stateful infrastructure; nothing extra in the CDE / audit scope.
- Saga state co-located with business data, auditable through the existing envelope.
- House-consistent: same state-machine and poller patterns already in the codebase.
- The abstraction grows from concrete sagas, avoiding a speculative framework.

**Negative**
- We own crash-recovery, retries, and timeouts rather than getting them from Temporal.
- Long-running, human-in-the-loop, or many-step workflows would eventually strain a custom
  primitive; revisit Temporal if such a workflow appears.

**Mitigation**
- The stuck-saga sweeper reuses the proven outbox-poller pattern (low incremental risk).
- Property-based tests on the saga state machine catch compensation-ordering bugs early
  ([ADR-0011](0011-testing-pyramid.md)).
- This decision is reversible per workflow: a future saga may adopt Temporal without
  rewriting the others, since each saga owns its own state.

## References

- [ADR-0004](0004-saga-for-multi-service-workflows.md) — saga pattern mandate (this ADR supplies its deferred framework choice)
- [ADR-0013](0013-shared-outbox-in-openbank-libs.md) — shared outbox / poller dispatch precedent
- Chris Richardson, "Microservices Patterns" — Saga pattern (orchestration vs choreography)
