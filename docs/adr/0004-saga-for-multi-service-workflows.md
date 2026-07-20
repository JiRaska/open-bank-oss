---
date: 2026-05-26
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [architecture, resilience, payments]
summary: "Every multi-service write workflow is a saga with idempotent steps and explicit compensations, choreography by default and orchestration above four steps, because XA across microservices is not viable."
---

# 4. Saga for multi-service workflows

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

## Alternatives considered

- **XA / 2PC across services** — run the multi-service workflow as one distributed transaction. Rejected: the ADR states XA/2PC is not viable across microservices — heavy, fragile, blocks resources, and not supported across heterogeneous resources.
- **Ad-hoc chains of REST calls (the status quo)** — let each team wire the steps together directly without an explicit pattern. Rejected: this leaves the system in inconsistent states when one step fails partway through.
- **Orchestration for every workflow** — always drive steps from a saga coordinator. Not rejected outright but not made the default: choreography is the default, and orchestration is reserved for workflows with more than four steps or those needing explicit timeout / compensation logic.
- **An off-the-shelf saga framework (Axon, Temporal)** — adopt an existing framework rather than building one. Rejected per ADR-0045, which records the choice of a lightweight custom primitive in `openbank-libs` instead; that ADR, not this one, carries the reasoning.

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

## Compliance impact

- PCI DSS: not applicable — no cardholder data scoped in this workflow pattern.
- DORA:    engaged — crash-resumable workflows, stuck-saga alerting and remediation runbooks are ICT resilience controls; specific articles not mapped in this ADR.
- GDPR:    not applicable — ADR decides workflow coordination, not personal data handling.
- PSD2:    not applicable — internal workflow pattern, no TPP-facing interface decided.
- CNB:     not applicable — no supervisory reporting or regulatory obligation discussed here.

## References

- Hector Garcia-Molina, Kenneth Salem, "Sagas" (1987)
- Chris Richardson, "Microservices Patterns" — Saga pattern
