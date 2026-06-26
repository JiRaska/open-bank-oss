# 4. Saga for multi-service workflows

Date: 2026-05-26
Status: Accepted

## Context

Many banking workflows span multiple services: opening an account requires the party service, KYC service, account service, and notification service. A payment requires party verification, balance check, reserve, clearing dispatch, and confirmation.

XA / 2PC is not viable across microservices: heavy, fragile, blocks resources, not supported across heterogeneous resources.

Without an explicit pattern, teams write ad-hoc chains of REST calls, leaving the system in inconsistent states when one step fails partway through.

## Decision

Every multi-service write workflow MUST be implemented as a **saga**.

We support both choreography (event-driven, each service reacts to upstream events) and orchestration (a saga coordinator drives the steps). Choreography is the default; orchestration is used when the workflow has > 4 steps or requires explicit timeout / compensation logic.

Each saga step:
- Is idempotent (replay safe).
- Has an explicit compensation action (also idempotent).
- Persists its state so the saga can resume after a crash.

Stuck sagas (no progress for > tier-specific threshold) raise an alert and have a documented manual remediation runbook.

[ADR-0045](0045-saga-framework-lightweight-custom-in-libs.md) records the specific saga framework choice (lightweight custom primitive in `openbank-libs`, not Axon or Temporal).

## Consequences

**Positive**
- Explicit failure handling; no half-committed multi-service transactions.
- Saga state is observable and recoverable.
- Workflows survive partial failures and process crashes.
- Compensation paths are tested.

**Negative**
- More complex than synchronous call chains.
- Compensations require careful design (idempotent, no side effects when called on already-compensated state).
- Eventual consistency between services; callers must handle "in-progress" state.

**Mitigation**
- Saga framework provides primitives so developers focus on business logic, not coordination plumbing.
- Property-based tests on saga state machines catch compensation bugs early.
- Reconciliation jobs run continuously to detect saga inconsistencies.

## References

- Hector Garcia-Molina, Kenneth Salem, "Sagas" (1987)
- Chris Richardson, "Microservices Patterns" — Saga pattern
