---
date: 2026-05-29
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [testing, ci, governance]
summary: "Code coverage uses Kover rather than JaCoCo because the codebase is Kotlin-first, gated by a per-module regression floor that ratchets up but never lets coverage silently rot down."
---

# 20. Code coverage: Kover with a per-module regression floor

**Delivery note (updated 2026-07-17):** both phases delivered; the 2026-06-30 "per-service rollout pending" note was stale.
- **Coverage gate (libs)** — ✅ Shipped: Kover wired into `check`. Since the ADR-0122 libs split the floors live per sub-module — `openbank-libs-domain` (30) and `openbank-libs-runtime` (50); the umbrella `openbank-libs` is `minValue = 0` (no source). `SecurityContextExtensions(.kt/Test.kt)` now sits in `openbank-libs-runtime`. (The old "39% on `openbank-libs` / libs-security 50→59%" wording predates that split.)
- **Per-service rollout** — ✅ Shipped: the `openbank.quarkus-service` convention plugin in `build-logic/` applies Kover fleet-wide and wires `koverVerify` into `check`; ~53 modules are gated with per-module ratchet floors (e.g. account 65, ledger 65, transaction 85, agent 70, analytics-sink 51). `rules.yaml` carries default 39 / money_path 40; the in-code floors are already higher. Enforced per-PR via `_service-ci.yml` `:<service>:build`, no new workflow YAML.

## Context

The project had **no code-coverage tooling configured in any module**. "Saga coverage"
could not even be measured, and there was no signal — local or in CI — when a change
removed tests or shipped untested code. [ADR-0011](0011-testing-pyramid.md) defines the
testing strategy (unit / integration / contract) but never pinned a coverage mechanism or
a threshold, so coverage was effectively unenforced.

Two questions had to be answered: **which tool**, and **what threshold policy**.

Tooling candidates:

- **JaCoCo** — the de-facto JVM standard, bundled with Gradle. Bytecode-based: it does not
  understand Kotlin inline functions, `data class` synthetic members, or coroutine state
  machines, so it systematically under-reports Kotlin coverage and needs hand-maintained
  exclusion lists to stop punishing generated code.
- **Kover** — JetBrains' Kotlin-native coverage plugin. Understands Kotlin language
  constructs, needs no separate agent, and is configured in the same `libs.versions.toml`
  catalog the rest of the build already uses.

Threshold-policy candidates:

- **A flat aspirational target** (e.g. "70% everywhere") — would fail the build on day one
  (libs sits at ~40% line) and invite the usual workarounds: assertion-free tests written
  only to move the number.
- **A per-module regression floor** — the gate fails only if coverage drops *below what the
  module already has*. It can ratchet up as tests land but never silently rot down.

## Decision

Use **Kover**, gated by a **per-module regression floor**.

- Kover over JaCoCo because the codebase is Kotlin-first; accurate Kotlin coverage with no
  agent and catalog-managed versioning outweighs JaCoCo's ubiquity.
- The verify rule is a **line-coverage floor set just under the module's current coverage**,
  not an aspiration. For `openbank-libs` that floor is **39%** (current ~40%). Raise the
  floor when a PR adds tests; **never lower it to turn a red build green** — a drop is the
  signal the gate exists to catch.
- The floor is wired into `check` (and therefore `build`), so the existing per-service CI
  step (`./gradlew :<service>:build`, see [_service-ci.yml](../../.github/workflows/_service-ci.yml))
  enforces it with no extra workflow YAML.

### Exclusions

Classes that are pure IO/runtime glue — they read resource files or JVM system properties
and a unit test would assert nothing meaningful — are excluded from measurement rather than
padded with hollow tests. In `openbank-libs` that is `BuildInfo` (build-stamp loader) and
`ServiceInfoResource` (its REST surface); both are exercised end-to-end by the service
`@QuarkusTest` layer. Framework-coupled code that needs the JAX-RS / ArC runtime to mean
anything (e.g. the `@Provider` exception mappers in `libs/api/error`) is likewise covered by
`@QuarkusTest` in the services, not by libs unit tests.

## Rollout

1. **`openbank-libs` first (this ADR).** Plugin applied, floor at 39%, gate in `check`. A
   unit test for the security primitive (`SecurityContextExtensions`) was added alongside,
   raising the `libs/security` package from 50% to ~59% line and demonstrating the ratchet.
2. **Per-service rollout (deferred).** Apply the same plugin + floor to the 28 services via a
   shared convention plugin (`build-logic/`) so the recipe stays DRY, rather than copy-pasting
   the `kover {}` block into every `build.gradle.kts`. This lands with the build-logic
   consolidation work (roadmap Fáze 4).

## Alternatives considered

- **JaCoCo** — the de-facto JVM coverage standard, bundled with Gradle. Rejected because it is bytecode-based and does not understand Kotlin inline functions, `data class` synthetic members or coroutine state machines, so it systematically under-reports coverage on a Kotlin-first codebase and needs hand-maintained exclusion lists.
- **A flat aspirational coverage target** (e.g. "70% everywhere") — one global threshold for all modules. Rejected because it would fail the build on day one (libs sits at ~40% line) and invite assertion-free tests written only to move the number.
- **Keep the status quo: no coverage tooling at all** — the project had no coverage configured in any module, so "saga coverage" could not be measured and nothing signalled when a change removed tests or shipped untested code. Rejected because coverage was effectively unenforced.
- **Copy a `kover {}` block into every service `build.gradle.kts`** — per-service duplication instead of a shared `build-logic/` convention plugin. Rejected to keep the recipe DRY across the service fleet.

## Consequences

**Positive**
- Coverage is measurable and regressions fail the build, locally and in CI, with no new
  workflow steps.
- Accurate Kotlin numbers; no JaCoCo inline/coroutine under-reporting.
- The floor-not-target policy resists the "write empty tests to hit a number" failure mode.

**Negative**
- A per-module floor is bespoke per module — the number must be tuned and bumped by hand
  (mitigated by the convention plugin carrying a sane default once rollout starts).
- A floor can sit stale (true but unraised) if reviewers forget to bump it; it still prevents
  regression, which is the load-bearing guarantee.

## Compliance impact

- PCI DSS: not applicable — build-tooling decision, no cardholder data in scope.
- DORA:    not applicable — code-coverage gate, no ICT resilience control claimed here.
- GDPR:    not applicable — coverage measurement processes no personal data.
- PSD2:    not applicable — no payment-service or authentication behaviour affected.
- CNB:     not applicable — internal engineering quality gate, no supervisory reporting.

## References

- [ADR-0011](0011-testing-pyramid.md) — testing strategy this coverage gate enforces
- [_service-ci.yml](../../.github/workflows/_service-ci.yml) — per-service build step the gate hooks into
- Kover — https://github.com/Kotlin/kotlinx-kover
