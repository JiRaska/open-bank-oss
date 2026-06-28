# 20. Code coverage: Kover with a per-module regression floor

Date: 2026-05-29
Status: Accepted
Delivery-Status: Partial

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

## References

- [ADR-0011](0011-testing-pyramid.md) — testing strategy this coverage gate enforces
- [_service-ci.yml](../../.github/workflows/_service-ci.yml) — per-service build step the gate hooks into
- Kover — https://github.com/Kotlin/kotlinx-kover
