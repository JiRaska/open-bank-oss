# 63. Consumer-driven contract testing with Pact (git-pact) and pitest mutation testing

Date: 2026-06-04
Status: Accepted
Extends: ADR-0011 (Layer 3 contract tests; pitest mutation section)
Relates to: ADR-0029 (governance-as-code), ADR-0030 (SSDLC D3)

## Context

ADR-0011 defines a six-layer testing pyramid. Layer 3 (Pact consumer-driven contracts) and the
mutation testing section (pitest on money-path domain math) have both remained `enforced: planned`
since the pyramid was adopted. Without contract tests, inter-service API drift is caught late —
in integration environments or production. Without mutation testing, statement coverage can be
satisfied by assertions that never actually validate the financial arithmetic.

The highest-coupling money-path REST path is:

```
openbank-balance-service (consumer)
  └─ GET /api/v1/journals/trial-balance?asOf=…
       → openbank-ledger-service (provider)
```

Balance uses this for the ADR-0039 Phase A control reconciliation. A breaking change to the
ledger response shape (e.g. renaming `balanced`, removing a `lines` field) silently breaks
reconciliation unless a contract test catches it at PR time.

## Decision

### Consumer-driven contracts — Pact JVM, git-pact storage

**Tooling:** Pact JVM (`au.com.dius.pact`, v4.6.x). Pact supports both REST (HTTP) and message
(Kafka) contracts; this ADR covers REST first.

**Storage — git-pact:** Pact JSON files are committed to `pacts/<consumer>-<provider>.json` in the
monorepo root. No external broker is introduced. The consumer test generates the pact file; the
developer commits it with the PR. The provider CI job reads it from the same path.

*Why git-pact over PactFlow/self-hosted:* A Pact Broker adds operational burden and a cross-service
deploy gate before the team has proven the workflow. In a monorepo where consumer and provider
co-evolve in a single PR, git storage is sufficient. Migration to a broker is the natural follow-up
when services need independent deploy cadences and a `can-i-deploy` gate.

**Phasing:**

| Phase | Scope | Trigger |
|---|---|---|
| 1 (this ADR) | Pilot: `balance-service` → `ledger-service` REST | This PR |
| 2 | All inter-service REST on 13 money-path services | Fleet sweep PR |
| 3 | Kafka message contracts (AsyncAPI + Pact message) | Separate ADR |

**CI:** Consumer tests run per-PR (standard `./gradlew test`). Provider verification runs per-PR
in the provider service's CI job (path-scoped). Both must pass. A git diff check on `pacts/`
(Phase 2) will detect uncommitted contract drift.

### Mutation testing — pitest, domain layer only, weekly

**Tooling:** `info.solidsoft.pitest` Gradle plugin (v1.15.0) with the JUnit 5 engine plugin.

**Scope:** `domain.**` package only. Infrastructure, REST resources and CDI producers are excluded —
mutation testing there would produce noise, not signal. The domain layer houses the financial
arithmetic (double-entry balancing, FX rounding, pocket ledger math) where mutants matter.

**Trigger:** Weekly scheduled CI job (`pitest.yml`), never per-PR. Pitest is 10–50× slower than
normal test execution; running it per-PR would exceed acceptable CI budgets on money-path services.

**Threshold:** Mutation score < 70% on `domain/` raises an advisory alert (Slack + CI annotation).
The gate flips to `block` once baseline scores are established across all 13 money-path services.

**Pilot services (Phase 1):** `openbank-ledger-service`, `openbank-balance-service`.
Fleet rollout (all 13 money-path services) follows as a separate PR once the pitest weekly job is
proven stable.

## Admin UI quality dashboard

The `system/tests` page is extended with four tabs:

- **Tests** — existing unit/integration pass-rate table (unchanged)
- **Contract** — provider × consumer matrix; cell = pass / fail / date of last verification
- **Mutation** — per-service mutation score gauges with 70% threshold indicator
- **Quality Score** — composite score per service: unit pass (25%) + coverage (25%) + mutation (25%)
  + contract (25%)

Data flows: CI produces `quality-report.json` (pitest XML + pact verification results, bundled into
the admin-ui image alongside the existing `test-results.json`). The page degrades gracefully when
the file is absent (dev / no-CI environments).

## Consequences

**Positive**
- API drift between balance and ledger caught in the breaking PR, not in integration testing.
- Pitest surfaces "covered but not tested" financial arithmetic.
- Composite quality score gives a single, non-game-able signal per service.
- No new infrastructure dependency (git-pact).

**Negative**
- Pact files must be re-committed when consumer contracts change. A missing re-commit goes undetected
  until Phase 2 adds the drift check.
- Pitest is slow; weekly-only means a mutation regression can persist up to a week.
- Provider verification requires the ledger to boot (Testcontainers), which adds ~30 s to the
  ledger service CI job.

## References

- ADR-0011: Testing pyramid
- ADR-0029: Governance as code
- ADR-0030: SSDLC hardening (D3: pitest)
- ADR-0039: Ledger as golden source, balance as projection
- Pact JVM: https://docs.pact.io/implementation_guides/jvm
- Pitest: https://pitest.org

