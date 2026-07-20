---
date: 2026-06-28
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [libs, architecture, ci]
summary: "openbank-libs splits into openbank-libs-domain (framework-free) and openbank-libs-runtime along the Quarkus boundary, keeping the package namespace stable so only Gradle dependency lines change, cutting fleet-wide rebuild cost."
---

# Split openbank-libs into domain and runtime modules

**Delivery note (updated 2026-07-01):**
- **Phase 0** — ✅ Shipped (PR #2821 predecessor, dead `libs/temporal/` skeleton removed).
- **Phase 1** — ✅ Shipped (PR #2821 `refactor(libs)`: `openbank-libs-domain` and
  `openbank-libs-runtime` modules created; packages moved per-file; composite build updated).
- **Phase 2** — 🔄 In progress (issue #32): non-money-path batch (22 services + simulation, PR #33)
  open; money-path batch (12 services, PR #34) open, awaiting 2 approvals per ADR-0030. The
  monolithic `openbank-libs` acts as a backward-compat umbrella (`api()` re-export) until
  both batches merge.
- **Phase 3** — Not started (evaluate publish-versioned; separate ADR revision of ADR-0014).

## Context

`openbank-libs` is part of the composite build consumed by every `openbank-*-service`, in the
composite-build-safe form described by ADR-0014. It has no `version.txt`, so it is **not** a released,
versioned artifact: it is recompiled into the build of each consumer. The practical consequence is that
**any change to libs triggers a fleet-wide rebuild** (~30 min, the recurring "CI libs merge friction").
As libs has grown — ~20 packages and ~7.8k LOC spanning pure domain primitives, Quarkus runtime
plumbing, ISO 20022, analytics, lending, docs, and governance — this coupling tax compounds, and a
one-line change to a money primitive rebuilds the HTTP/outbox plumbing of 30 services that did not
change.

libs already mixes two concerns with a clean natural fault line — the **framework boundary**, which
today is enforced only by convention (CLAUDE.md: "Domain layer has zero framework imports"). A
package-by-package audit of framework dependence (`import jakarta` / `import io.quarkus`):

| Side | Packages |
|------|----------|
| **domain** (no framework) | `domain/*` (money, calendar, case, event, account, payment), `domain/identifiers` pure id types (`EntityId`, `Ids`, `LendingIds`), `iso20022`, `lending`, `governance`, `identity`, `util/Ids` |
| **runtime** (Quarkus/Jakarta) | `web`, `persistence`, `idempotency`, `authz`, `observability`, `flags`, `foureyes`, `security`, `audit`, `api` (`error/CommonExceptionMappers`), `time` (`DefaultClockProducer`), `docs`, `analytics`, `domain/identifiers` JPA converters (`IdConverters`, `LendingIdConverters`), `util/BuildInfo` |

Note the **mixed packages** — these need a per-file placement pass during the move, not a blanket
package assignment:
- `domain/identifiers` — the id types (`EntityId`, `Ids`, `LendingIds`) are pure domain, but
  `IdConverters`/`LendingIdConverters` are JPA `jakarta.persistence.AttributeConverter` adapters that
  bridge those ids to Hibernate → runtime.
- `util` — pure `Ids` (domain) vs `BuildInfo` (runtime resource loader).
- `api` — mostly DTOs (domain-ish) but `error/CommonExceptionMappers` pulls JAX-RS → runtime.

## Decision

**We will split `openbank-libs` into two Gradle modules along the framework boundary, keeping the
`com.openbank.libs.*` package namespace stable so no consumer source imports change.**

- **`openbank-libs-domain`** — pure Kotlin/JVM, zero Quarkus/Jakarta dependencies (the `domain` rows
  above). A domain import of a framework type becomes a *compile error*, enforcing the zero-framework
  rule structurally.
- **`openbank-libs-runtime`** — Quarkus plumbing (the `runtime` rows above), depends on
  `openbank-libs-domain`.

The split is at the **Gradle-module boundary, not a package rename** — consumer `build.gradle`
dependency declarations change; their `import com.openbank.libs.*` lines do not. This keeps the fleet
blast radius to a mechanical dependency-repoint sweep, not a source migration. Mixed packages
(`domain/identifiers`, `util`, `api`) are split per file during the move.

### Phasing

- **Phase 0 — remove dead code.** Delete the unreferenced `libs/temporal/` skeleton and drop the Temporal
  SDK from libs (separate `refactor(libs)` PR, already in flight). Shrinks the surface before the split
  and removes a heavy transitive dep from the 27 non-Temporal services.
- **Phase 1 — create the two modules**, move packages (per-file for mixed ones), keep the namespace.
  Single libs-internal change; one fleet rebuild.
- **Phase 2 — fleet sweep** (1 PR per service, the proven sweep recipe) to repoint `build.gradle` from
  the monolithic dependency to `domain` + `runtime` as needed.
- **Phase 3 — evaluate publish-versioned (separate ADR revising ADR-0014).** Once the modules are clean,
  assess giving each a `version.txt` and publishing to the in-cluster Nexus, so path-scoped CI rebuilds
  only the consumers of the *changed* module (a domain change stops rebuilding runtime plumbing). This is
  the real fix for the rebuild coupling; it is **staged after** the split proves out, not bundled with it.

`libs/domain/saga` is intentionally **not** placed by this ADR: ADR-0120 removes its last consumer
(`transaction-service`), after which it is deleted rather than moved.

## Alternatives considered

- **Status quo (single libs module)** — Rejected: the fleet-rebuild tax grows with libs and is already
  the dominant CI-friction source.
- **Many fine-grained modules (one per package)** — Rejected: over-fragmentation, heavy Gradle graph,
  marginal benefit over the two-axis split; the framework boundary is the one fault line that matters.
- **Full publish-versioned big bang now** — Rejected as the *first* step: revising ADR-0014 and wiring
  Nexus consumption across 30 services simultaneously is high-risk. Split first (mechanical,
  package-stable), publish second (measured), so each step is independently reversible.

## Consequences

**Positive**
- Domain changes decouple from runtime-plumbing rebuilds along the real change-frequency boundary.
- The zero-framework domain rule is enforced by the compiler, not by review.
- Clearer ownership and a smaller, more legible runtime module.

**Negative**
- One fleet-wide `build.gradle` sweep (Phase 2), with the usual transitional sweep risk.
- Mixed packages (`domain/identifiers`, `util`, `api`) need careful per-file splitting; a wrong
  placement re-introduces a framework dep into the domain module (caught by compile, but churns the
  sweep). The `identifiers` JPA converters are the easiest to get wrong — the id types they convert are
  pure domain, so the package reads as domain until the converter files are inspected.
- The composite-build consumption form (ADR-0014) changes; Phase 3 may revise it outright.

**Neutral**
- Runtime behavior is unchanged; package names are stable, so no service source edits.
- Release axis: libs remains unversioned until/unless Phase 3 introduces `version.txt`.

## Compliance impact

- PCI DSS: not applicable.
- DORA:    not applicable (build-topology change; no runtime resilience impact).
- GDPR:    not applicable.
- PSD2:    not applicable.
- CNB:     not applicable.

## References

- ADR-0014 — composite build consumption of `openbank-libs` (revised in Phase 3).
- ADR-0049 — libs consolidation backlog (the substrate this reorganizes).
- ADR-0120 — transaction → Temporal migration (gates deletion of `libs/domain/saga`).
- CLAUDE.md — "Domain layer has zero framework imports" (now structurally enforced).
- Recurring "CI libs merge friction" (fleet rebuild on every libs change) — the motivating cost.
