---
date: 2026-06-19
decision-status: accepted
delivery-status: shipped
authors: [OpenBank platform]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [payments, architecture, resilience]
summary: "Temporal becomes the durable execution engine for all money-path orchestration, replacing hand-rolled saga coordinators, while the outbox pattern is retained inside activity implementations for exactly-once event publishing."
---

# 101. Temporal durable execution for money-path workflows

## Context

OpenBank currently orchestrates multi-step money-path operations — payment processing, SEPA
settlement, onboarding (ADR-0094), FX revaluation, end-of-day close — with hand-rolled saga
coordinators backed by the transactional outbox pattern (ADR-0049 libs/outbox). Each service
implements its own: state machine enum, compensation dispatch, timeout polling, idempotency
keys, and retry policy. This approach works for simple two-step flows but shows structural
fragility as complexity grows:

- **The settlement orchestrator money-bug (2026-06-19)** — a compensation branch was reachable
  only after a specific timing window between the debit activity and the ledger flush; the
  manual state machine had no explicit timeout on the intermediate state, so the saga could
  hang indefinitely. Discovered by code review, not by automated testing.
- **Observability opacity** — the current saga state lives in a domain table (`outbox_events`,
  `saga_state`). Reconstructing "what did this payment do, step by step, across services"
  requires joining six tables and reading Kafka consumer-group offsets. There is no single pane
  for in-flight workflows.
- **DORA Art. 17 / Art. 11 gap** — reconstructing the exact execution sequence of a failed
  payment for a regulator currently requires forensic log stitching. A durable, replayable
  execution history would satisfy this requirement directly.
- **ADR-0094 already adopts Temporal** for onboarding orchestration. Running two
  orchestration models in parallel (Temporal for onboarding, hand-rolled sagas for payments)
  increases cognitive load and prevents sharing retry, compensation, and observability
  patterns.

Temporal is a durable execution engine: it persists the full execution history of every
workflow, replays it on failure or restart, and guarantees exactly-once activity execution via
idempotency tokens. Workflows are written as ordinary Kotlin code; the framework handles
persistence, retry, compensation scheduling, and timeout enforcement transparently.

**Scope.** This ADR covers money-path workflow migration: `sepa-payment-service`,
`domestic-payment-service`, `settlement-service`, `fx-service`, and
`statements-service` (EoD/EoM/EoY close). Onboarding is already committed in ADR-0094.
`openbank-libs/outbox` remains for event publishing from activity implementations — Temporal
orchestrates *when* to call an activity; the outbox pattern ensures the event is published
*exactly once* even if the activity crashes mid-way.

## Decision

**We will adopt Temporal as the durable workflow engine for all money-path orchestration.
Hand-rolled saga coordinators in the listed services will be replaced by Temporal workflows
over a phased migration. The existing outbox and domain-event patterns are retained inside
activity implementations; Temporal replaces only the orchestration layer.**

### Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Temporal Server (self-hosted, openbank-infra GitOps)        │
│  Namespace: openbank-payments  |  openbank-settlement        │
└────────────────────┬────────────────────────────────────────┘
                     │ gRPC (no transport auth — see correction below)
        ┌────────────┼────────────┐
        ▼            ▼            ▼
  sepa-payment  domestic-pmt  settlement
  Workflow       Workflow      Workflow
  Worker         Worker        Worker
        │
        ▼
  Activity impls (Kotlin)
  → DB write + outbox publish (libs/outbox, ADR-0049)
  → external REST (SEPA CSM, FX feed) via port/adapter
```

*(Corrected 2026-08-27, #6066: this edge previously read `gRPC (mTLS, OPA-gated —
ADR-0034)`. Neither control was built. `TemporalClientProducer` — the single fleet-wide
`WorkflowClient` producer since ADR-0209 D1 — sets a target and an optional metrics scope and
no SSL context; the Temporal HelmRelease configures no frontend TLS; and no API key or
namespace token is configured, so the edge has no transport authentication of any kind, not
just no mTLS. The `OpaActivityInterceptor` named in the P0 row below occurs in no Kotlin source
in this repository (#6055). `openbank-libs-temporal` contains `TemporalClientProducer` and
`TemporalConfig` and nothing else — of P0's three deliverables only the bootstrap exists, so
this ADR's `delivery-status: shipped` overstates P0. Whether this edge should get transport
authentication beyond its NetworkPolicy is the open decision in #6066.)*

**Temporal server deployment**: single-namespace, PostgreSQL backend (CNPG cluster, separate
from application DBs), deployed as a Helm release in `openbank-infra`. Initial sizing: 2
replicas, 4 vCPU / 8 GB — adequate for sandbox; production sizing follows load test.

**Temporal namespace provisioning (namespace-per-domain).** Each bounded context that runs
workflows gets its own Temporal namespace (`openbank-payments`, `openbank-fx`,
`openbank-statements`, …) with a 168h retention. A namespace must exist *before* the owning
service's worker starts — a missing namespace does not crash the pod (readiness is a TCP probe
on the HTTP port, not on Temporal), so the worker poller silently spins on
`NOT_FOUND: Namespace <x> is not found` while the pod reports Healthy. Namespace registration is
**declarative in GitOps**, not a manual break-glass step: the namespace set and the
`describe`-then-`create` script live in the `temporal-namespace-registration` **ConfigMap**
(`openbank-infra/gitops/components/temporal/temporal-namespace-config.yaml`), and two workloads
run it — an idempotent ArgoCD **PostSync hook Job** on every sync, and a daily
`temporal-namespace-reconcile` **CronJob** that registers whatever the sync path missed and then
fails so the gap is alerted rather than merely repaired. The list lives on the ConfigMap and not
on the hook deliberately: ArgoCD does not diff hook resources, so a list carried there could
never be changed in a way that triggered its own registration (issue #3507) —
`check-temporal-namespace-registration.py` now fails any PR that moves it back.
**Adding a Temporal service is a two-line change**: add its namespace to the `NAMESPACES` key in
that ConfigMap *and* add the service namespace to the `temporal-platform-ingress` NetworkPolicy
(`temporal-network-policies.yaml`) so its worker can reach the frontend gRPC port.

**Worker placement**: each service (`sepa-payment-service` etc.) runs its own Temporal Worker
in-process (Quarkus startup lifecycle bean). No separate worker service — keeps the service
boundary clean and avoids cross-service activity invocation.

**Task queues**: one task queue per service (`openbank-payments`, `openbank-settlement`, etc.).
Workflows may fan out to activities in other queues only through explicit, typed stub calls —
no reflective dispatch.

**Compensation pattern**: explicit `saga {}` DSL in the Temporal Java SDK (or equivalent
Kotlin wrapper in `openbank-libs/temporal`) registers compensations that run in reverse order
on failure. This replaces the current `CompensationDispatcher` in the manual saga
implementation.

**Observability**: Temporal Web UI exposed internally (behind Keycloak SSO, `INTERNAL`
audience per ADR-0056). OpenTelemetry traces from Temporal activities flow into the existing
Grafana/Tempo stack (ADR-0077). A single workflow execution ID links all spans from payment
initiation to settlement finality — the forensic audit trail DORA requires.

### Migration phases

| Phase | Scope | PR target | Gate |
|-------|-------|-----------|------|
| P0 | `openbank-libs/temporal` — shared worker bootstrap, saga DSL, OPA activity interceptor | single libs PR | boot smoke-test |
| P1 | `sepa-payment-service` — replace `SepaPaymentSaga` | 1 PR | contract test + DST (ADR-0100) |
| P2 | `domestic-payment-service` | 1 PR | same |
| P3 | `settlement-service` — highest complexity; current money-bug lives here | 1 PR + threat model refresh | 2 approvals (money-path) |
| P4 | `fx-service`, `statements-service` EoD close | 2 PRs | same |
| P5 | Decommission `outbox_saga_state` tables and `SagaCoordinator` in libs | cleanup PR | fleet deploy green |

Each phase ships behind a feature flag (ADR-0067) — Temporal workflow path and legacy saga
path run in parallel; flag flip after canary validation per ADR-0098.

### Idempotency contract

Every Temporal activity that writes to the DB or publishes a Kafka event:
1. Receives the workflow run ID + activity attempt number as the idempotency key.
2. Stores the key in the domain table (`reference_id` or equivalent) before any side effect.
3. Returns the stored result on replay without re-executing the side effect.

This replaces the current `IdempotencyInterceptor` in `openbank-libs` for orchestrated flows.
The interceptor remains for REST endpoints (non-Temporal entry points).

## Alternatives considered

- **Keep hand-rolled sagas; fix the compensation bug.** Addresses the immediate bug but not
  the systemic fragility. Every new money-path flow recreates the same failure modes. Rejected
  as treating a symptom.

- **Apache Camel / Spring Batch for orchestration.** More JVM-native but not designed for
  durable, fault-tolerant long-running processes. No built-in replay; poor DORA traceability.
  Rejected.

- **Zeebe / Camunda 8.** BPMN-native workflow engine; OpenBank already uses YAML BPMN docs
  (ADR-0096 context). Viable alternative. Rejected because: (a) BPMN XML as code is a worse
  developer experience than typed Kotlin workflows; (b) Temporal's deterministic replay model
  aligns directly with ADR-0100 DST; (c) Camunda's licensing model adds compliance risk for
  OSS publication.

- **Conductor (Netflix).** Similar to Temporal, JSON-DSL workflows. Less type-safe than
  Temporal's code-first model. Rejected.

## Consequences

**Positive**
- Compensation gaps and stuck sagas become structurally impossible — Temporal replays until
  the compensation activity succeeds or an explicit terminal state is reached.
- Full execution history of every payment is persisted and queryable — DORA Art. 11/17
  forensic requirement met by the framework, not by log stitching.
- Single orchestration model across onboarding (ADR-0094) and payments — shared patterns,
  shared libs, shared observability.
- Developer experience: write a payment workflow as ordinary Kotlin code with `@WorkflowMethod`
  and `@ActivityMethod`; the framework handles all the rest.
- Pairs with ADR-0100 DST: Temporal's deterministic replay model is exactly what the DST
  harness virtualises.

**Negative**
- **New infrastructure dependency.** Temporal server is a stateful PostgreSQL-backed service.
  It must be operated, backed up, and upgraded. Adds to the infrastructure runbook.
- **Migration risk.** Running Temporal and legacy saga in parallel (feature flag) adds
  temporary complexity. The flag must not live longer than one quarter per phase.
- **Temporal Java SDK maturity.** The SDK is stable but the Kotlin idioms are less established
  than the Java idioms. `openbank-libs/temporal` must provide a thin Kotlin-idiomatic wrapper.
- **Activity timeout tuning.** Temporal's default timeouts need explicit calibration for
  long-running settlement windows (SEPA CSM cutoff cycles can span hours).
- **Not a silver bullet for distributed consistency.** Temporal guarantees workflow execution
  durability; it does not guarantee that an activity's database write and Kafka publish are
  atomic. The outbox pattern inside activities remains necessary.

**Neutral**
- `openbank-libs/outbox` is unchanged; Temporal orchestrates when to call the outbox,
  not how it works.
- Temporal Web UI is an additional internal surface requiring Keycloak integration — handled
  by the standard INTERNAL OIDC pattern already in place.

## Compliance impact

- **DORA Art. 11** — business continuity / recovery. Temporal's persistent execution history
  satisfies the "reconstructable execution trace" requirement for critical services. Workflow
  replays double as recovery tests.
- **DORA Art. 17** — advanced testing. Temporal's deterministic replay is auditable evidence
  of tested failure paths when combined with DST (ADR-0100).
- **PSD2 Art. 5(3)** — SCA / payment initiation integrity. Durable workflows ensure a
  payment's authentication context is preserved across any restart.
- **PCI DSS Req. 10** — audit logging. Temporal's event history provides a tamper-evident
  sequence of all payment state transitions (complementary to the hash-chained audit log in
  `openbank-libs/audit`).
- GDPR: workflow history contains PII. Temporal namespace must be in EU region; retention
  policy set to the shorter of: 90 days or the payment dispute window. Data minimisation:
  activity inputs/outputs store reference IDs, not plaintext account data.

## References

- Temporal documentation: https://docs.temporal.io/
- ADR-0049 (libs consolidation — outbox pattern)
- ADR-0067 (feature flags — migration gate)
- ADR-0094 (EUDI identity hub — onboarding already on Temporal)
- ADR-0098 (progressive delivery — canary flip pattern)
- ADR-0100 (DST — complementary correctness harness)
- Settlement money-bug (found 2026-06-19, root cause: missing intermediate-state timeout)
