---
date: 2026-05-26
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [testing, ci]
summary: "Testing follows a pyramid of unit, Testcontainers integration, Pact contract, Playwright/REST E2E, k6 load and chaos layers, with coverage and latency regression gates, built up from a zero-test baseline."
---

# 11. Testing pyramid: unit + integration (Testcontainers) + E2E + load

## Context

Today the OpenBank repository has **zero tests**. This is not sustainable for a banking platform. A formal testing strategy is required before any production deployment.

Common anti-patterns to avoid:
- Inverted pyramid (many slow E2E tests, few unit tests).
- Mock-heavy unit tests that test the mocks, not the code.
- Integration tests that share state across runs.
- Load tests run only before releases, never catching regressions.

## Decision

OpenBank adopts a standard testing pyramid with four explicit layers:

### Layer 1 — Unit tests (most numerous)
- **Tool:** JUnit 5 + Kotest assertions (JVM); Vitest (TypeScript).
- **Scope:** Pure domain logic; no Spring, no Quarkus, no JPA, no Kafka.
- **Speed:** < 50 ms per test.
- **Coverage gate:** ≥ 80% on `domain/`, ≥ 70% on `application/` by M2.
- **Property-based tests** on financial arithmetic (Kotest property tests).

### Layer 2 — Integration tests
- **Tool:** Testcontainers (real Postgres, Kafka, Redis), Quarkus Test framework.
- **Scope:** One service end-to-end including its DB and event publication.
- **Speed:** < 30 s per test class.
- **Coverage gate:** Every REST endpoint and every Kafka consumer has at least one integration test.

### Layer 3 — Contract tests
- **Tool:** Pact (consumer-driven contracts) on critical inter-service paths.
- **Scope:** Verify producer-consumer contract for each Kafka topic and inter-service REST call.
- **Trigger:** Run on every PR; broker hosted in CI.

### Layer 4 — End-to-end tests
- **Tool:** Playwright for UI; REST flows in JUnit.
- **Scope:** Golden user journeys (login, account opening, payment, balance check).
- **Speed:** < 5 min per suite.
- **Trigger:** Nightly + before release.

### Layer 5 — Load tests
- **Tool:** k6.
- **Scope:** Per-service throughput + latency tests against `docs/strategy/06-scalability-targets.md` budgets.
- **Trigger:** Nightly; regression gate fails if latency p99 degrades > 5% vs baseline.

### Layer 6 — Chaos tests
- **Tool:** Chaos Mesh / LitmusChaos.
- **Scope:** Failure injection in staging (M4 onwards) and production-grade chaos (M7 onwards) on Tier 2-3 services.

### Mutation testing
- **Tool:** Pitest.
- **Scope:** Critical services (ledger, payment, saga coordinators) only.
- **Trigger:** Weekly; alert on mutation score < 70%.

## Alternatives considered

- **Status quo: no tests at all** — the repository has zero tests today. Rejected as not sustainable for a banking platform; a formal testing strategy is required before any production deployment.
- **Inverted pyramid** — many slow E2E tests over few unit tests. Named as an anti-pattern to avoid, hence the pyramid shape with unit tests as the most numerous layer.
- **Mock-heavy unit tests** — unit suites that end up testing the mocks rather than the code. Named as an anti-pattern to avoid.
- **Integration tests sharing state across runs** — rejected as an anti-pattern; the decision instead runs integration tests on Testcontainers-provided real Postgres, Kafka and Redis.
- **Load tests only before releases** — rejected because they never catch regressions; load tests are therefore run nightly with a p99 regression gate.

## Consequences

**Positive**
- Confidence to refactor.
- Regressions caught early and cheaply.
- New contributors trust the test suite.
- Pen-testers + auditors see verifiable controls.

**Negative**
- Substantial effort to build from zero (M1 + M2 work).
- CI compute cost grows; tests must remain fast.

**Mitigation**
- Parallelise test execution in CI.
- Cache Testcontainers images.
- Run mutation + chaos out-of-band (weekly), not per PR.

## Compliance impact

- PCI DSS: not applicable — no cardholder data in scope of this ADR.
- DORA:    engaged — this is an ICT testing and resilience-verification control, including chaos testing; specific articles not mapped in this ADR.
- GDPR:    not applicable — test strategy, no personal data processing described.
- PSD2:    not applicable — internal quality practice, no payment interface changed.
- CNB:     not applicable — no CNB requirement is referenced in this ADR.

## References

- Mike Cohn, "Succeeding with Agile" (test pyramid)
- Testcontainers documentation
- Pact contract testing
- k6.io
