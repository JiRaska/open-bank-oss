# Migrate transaction-service payment orchestration to Temporal

Date: 2026-06-28
Status: Accepted
Delivery-Status: Shipped
Author(s): jiri.raska

## Context

ADR-0101 adopted Temporal as the durable execution engine for money-path orchestration and named a
migration scope of `sepa-payment`, `domestic-payment`, `settlement-service`, `fx-service`, and
`statements-service`. As of this ADR, the migration has landed unevenly:

- **`settlement-service`, `fx-service`, `statements-service` run Temporal live** — each carries its
  own `TemporalClientProducer` + `WorkerRegistrar` + `@WorkflowInterface`/`@ActivityInterface`
  classes, with `openbank.temporal.enabled=true`.
- **`sepa-payment` and `domestic-payment` have Temporal workflows scaffolded but dormant** —
  `SepaPaymentWorkflowImpl` / `DomesticPaymentWorkflowImpl` + `WorkerRegistrar` + activities exist on
  `main`, but `application.yaml` ships `openbank.temporal.enabled=false`. The migration is landed, not
  yet activated.
- **`transaction-service` has no Temporal presence at all.** It still runs the hand-rolled saga from
  ADR-0039: `PaymentSagaOrchestrator.executeSteps`, persisted in `PaymentSagaEntity` /
  `PanachePaymentSagaRepository` (Flyway `V5`, `V7`), driven by `SchemeAcceptedConsumer`, with
  transitions validated by `openbank-libs` `domain/saga/SagaStateMachine`. The actual orchestration is:

  ```
  PAYMENT_INITIATED → FUNDS_RESERVED (balanceCoverPort.placeHold)
                    → LEDGER_POSTING (ledger postJournal)
                    → COMPLETED
  ```

  with compensation on any failure. Per ADR-0039 Phase D-2 there is **no success-path balance debit
  and no success-path hold release** — the balance-service ledger projection is the sole booked-mover
  and releases the cover hold as the booked delta lands.

So the **only money-path orchestrator with zero Temporal scaffolding is `transaction-service`** — even
the scheme-edge services already carry (dormant) Temporal workflows, while the service that posts to
the ledger and reserves customer funds is the one still on a hand-rolled coordinator. ADR-0101 did not
name `transaction-service` in its scope; this is the gap. It reproduces every fragility ADR-0101 cited:
implicit timeouts on intermediate saga states, observability opacity (state spread across
`payment_sagas`, `outbox_events`, and Kafka offsets), and DORA Art. 17 forensic-log-stitching to
reconstruct a failed payment.

Two dependencies make this a coordinated change, not an isolated one:

- **`openbank-libs` `domain/saga/SagaStateMachine`** has exactly one remaining consumer:
  `transaction-service` (verified by grep). Migrating transaction is the precondition for removing that
  primitive from the shared library (ADR-0122).
- **The deterministic-simulation harness `openbank-simulation`** (ADR-0115 / ADR-0100) models the
  transaction saga directly — `PaymentSagaModel`, `MoneyPathInvariants`, `PaymentScenario` import
  `com.openbank.transaction.domain.saga`. The migration must update or retire those models in lockstep,
  or the DST harness breaks.

## Decision

**We will migrate `transaction-service` payment orchestration to a Temporal workflow, completing the
money-path scope of ADR-0101. `PaymentSagaOrchestrator` and the `PaymentSagaEntity` state table are
replaced by a `PaymentWorkflow` whose activities wrap the existing ports
(`BalanceCoverPort.placeHold`, ledger `postJournal`, and the compensation reversal). The transactional
outbox and domain-event publication stay inside the activity implementations (per ADR-0101): Temporal
orchestrates *when* an activity runs; the outbox guarantees the event is published *exactly once*.**

- The current saga states (`PAYMENT_INITIATED → FUNDS_RESERVED → LEDGER_POSTING → COMPLETED`) and the
  compensation branch become ordinary workflow code; the explicit state machine, its enum, and the
  `payment_sagas` polling are retired — workflow history is the source of truth for in-flight state.
  The ADR-0039 Phase D-2 invariant is preserved: the workflow places the cover hold and posts the
  journal, and does **not** release the hold or debit on the success path (the balance projection does).
- `transaction-service` joins the `openbank-payments` Temporal namespace and gains its own
  `TemporalClientProducer` + `WorkerRegistrar` (mirroring the existing services until the shared
  producer of ADR-0122 lands).
- **This is a money-path change.** It requires 2 approvals + an updated threat model
  (`docs/threat-models/transaction-service.md`, ADR-0030), and rolls out **flag-gated behind a canary**
  (`openbank.transaction.orchestration.temporal`, default `false`), not as a big-bang cutover.

### Migration phases

1. Introduce `PaymentWorkflow` + activities behind `openbank.transaction.orchestration.temporal`
   (default `false`); `PaymentSagaOrchestrator` stays the default path.
2. **Add the `transaction` namespace to the `temporal-platform-ingress` NetworkPolicy** before enabling
   the worker — `WorkerRegistrar` connects to the Temporal frontend `:7233` synchronously at startup;
   a missing allowlist entry is a *boot* failure, not a runtime error (the `payments-temporal-canary-abort`
   footgun, PR #1600 class).
3. **Update `openbank-simulation`** so `PaymentSagaModel` / `MoneyPathInvariants` track the workflow
   rather than the deleted saga types — in the same change set, so the DST harness stays green.
4. Canary cutover (1% → 100%) on the flag, monitored against the existing payment SLOs.
5. Retire `PaymentSagaOrchestrator`. Keep `payment_sagas` tables **read-only for one audit window** (a
   Flyway migration drops the write path), then drop in a later migration with a rollback note.
6. Once cut over, `libs/domain/saga` has no consumers → remove it (ADR-0122).

## Alternatives considered

- **Keep the hand-rolled orchestrator in `transaction-service`** — zero migration risk. Rejected:
  perpetuates the money-path split-brain (the ledger-posting service is the *only* orchestrator with no
  Temporal scaffolding) and every fragility ADR-0101 already decided against, and blocks the
  `libs/domain/saga` cleanup.
- **Activate sepa/domestic Temporal first, defer transaction** — Rejected as a *substitute*: those
  workflows orchestrate the scheme interaction, not the ledger/balance posting that is transaction's
  fragility. Their activation is independent and out of scope here.
- **Migrate, but keep `PaymentSagaEntity` as a permanent dual-write projection** — Rejected as a
  *permanent* design (re-introduces the dual-write seam ADR-0039 fought); adopted only *transitionally*
  if admin-ui needs an in-flight view before Temporal visibility covers it.
- **Build a shared hand-rolled saga framework in libs instead of Temporal** — Rejected: ADR-0101 already
  chose Temporal fleet-wide; a third orchestration model is strictly worse.

## Consequences

**Positive**
- One orchestration model across the entire money path; the split-brain is closed.
- Durable, replayable execution history satisfies DORA Art. 17 reconstruction directly.
- Removes the last consumer of `libs/domain/saga`, unblocking ADR-0122.
- Compensation/timeout logic becomes testable via the DST harness (ADR-0115) natively.

**Negative**
- Money-path migration risk: 2 approvals, threat-model update, canary rollout, money-path SLO watch.
- Coordinated blast radius: the `openbank-simulation` DST models must change in lockstep or the harness
  breaks.
- Operational surface: a `transaction` Temporal worker, namespace, and the NetworkPolicy allowlist entry
  that is a boot-blocker if missed.
- Flyway lifecycle for the retired `payment_sagas` tables (deprecate → drop, with rollback note).

**Neutral**
- No external contract change: REST endpoints and emitted domain events are unchanged.
- Release axis: a `feat` minor bump on `transaction-service`; API-contract axis untouched (ADR-0048).

## Compliance impact

- PCI DSS: not applicable (no cardholder data in the orchestration layer).
- DORA:    Art. 11 (resilience) / Art. 17 (ICT-incident reconstruction) — positive; durable history
           replaces forensic log stitching.
- GDPR:    not applicable (same data, same retention).
- PSD2:    SCA and payment-execution semantics unchanged.
- CNB:     not applicable beyond the DORA mapping.

## References

- ADR-0039 — Ledger as golden source; balance as projection (the Phase D-2 invariant preserved here).
- ADR-0101 — Temporal durable execution for money-path workflows (this completes its scope).
- ADR-0030 — Money-path change controls (2 approvals + threat model).
- ADR-0034 — OPA-gated gRPC for Temporal frontend access.
- ADR-0100 / ADR-0115 — Deterministic simulation harness (the `openbank-simulation` models to update).
- ADR-0122 — Split openbank-libs (removal of `libs/domain/saga` is gated on this migration).
- `payments-temporal-canary-abort` (PR #1600) — the `temporal-platform-ingress` NetworkPolicy boot trap.

## Delivery log

| Phase | Description | Status | PR / commit |
|-------|-------------|--------|-------------|
| 1 | Introduce `PaymentWorkflow` + activities, `openbank.transaction.orchestration.temporal=false` | ✓ Done | on `main` |
| 2 | `transaction` namespace added to `temporal-platform-ingress` NetworkPolicy | ✓ Done | on `main` |
| 3 | `openbank-simulation` `PaymentSagaModel` updated to track workflow | ✓ Done | on `main` |
| 4 | Enable flag in sandbox gitops (`openbank.transaction.orchestration.temporal=true`) | ✓ Done | #2793 |
| 5 | Retire `PaymentSagaOrchestrator`; Flyway tombstone for `payment_sagas` write path | ✓ Done | #TBD |
| 6 | Remove `libs/domain/saga` (ADR-0122 prereq satisfied) | ✓ Done | #TBD |
