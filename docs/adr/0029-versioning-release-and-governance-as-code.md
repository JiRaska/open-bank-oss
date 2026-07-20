---
date: 2026-05-30
decision-status: accepted
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [release-versioning, governance, supply-chain, ci]
summary: "Versioning, release notes, provenance and governance metadata become code-derived, CI-enforced and signed, with per-service SemVer, a generated service dependency graph and a tamper-evident audit chain, delivered in layers."
---

# 29. Versioning, release and governance as code: enforced conventions, per-service SemVer, signed provenance, code-derived catalog

**Delivery note (updated 2026-06-30):**
- **D1 (conventions-as-code)** — ✅ Shipped: `rules.yaml` live in `openbank-libs/governance/`; CI gates running; `CLAUDE.md` hierarchy in place; Claude skills in `.claude/skills/`; `service-graph.json` ⬜ not yet generated.
- **D2 (per-service SemVer + release-please)** — ✅ Shipped: all 43 releasable components have `version.txt` + manifest entry; `release-please-config.json` and `.release-please-manifest.json` in lockstep. The four non-enrolled modules (`openbank-libs`, `openbank-libs-domain`, `openbank-libs-runtime`, `openbank-simulation`) have no `version.txt` by design — they are not independently released components.
- **D3 (API catalog + admin-UI)** — ✅ Shipped: catalog tile + coverage dashboard live in admin-ui; contract tests running via Pact.
- **D4 (CI gates)** — ✅ Shipped: duplicate-yaml, check-threat-models, release-registration, check-app-version-override, check-admin-ui-version-sync all running and enforced.
- **D5 (audit chain)** — Partial: DORA metrics + AuditIntegrityTile live in admin-ui; tamper-evident chain (cosign/SLSA) not yet completed (ADR-0121).

> **Accepted (2026-06-11).** Status raised from Proposed to reflect established practice:
> this ADR is partially implemented and load-bearing — `openbank-libs/governance/rules.yaml`
> is live as the authoritative rule source, the CI gates it defines are running,
> release-please owns per-service `version.txt`/changelogs, and 26+ Accepted ADRs already
> depend on it. Items not yet realized (see D7 phasing and the per-decision realization
> notes) remain tracked follow-ups; acceptance covers the decision, not a claim that every
> phase has shipped.

## Context

OpenBank has strong *engineering discipline on paper* — DCO sign-off, signed commits, Conventional
Commits, hexagonal architecture, a 28-strong ADR corpus, CODEOWNERS two-reviewer gates on money paths.
What it lacks is *enforcement, provenance and feedback*: the conventions live as prose in
`CONTRIBUTING.md` and ADRs that advise but never block, are never read by automation, are never surfaced
back in the product, and produce no tamper-evident audit trail. The recurring, predictable failures all
trace to this one gap:

- **Versioning is fiction.** All 30 services are hardcoded `version = "0.1.0-SNAPSHOT"` in their
  `build.gradle.kts`. Each `openapi.yaml` independently declares `info.version: 1.0.0`. The shared
  `ServiceInfoResource` reads `quarkus.application.version` which defaults to `0.0.0`. Three version
  numbers per service, none of them true, none of them moving when a PR ships.

- **No release notes, no changelog.** No `CHANGELOG.md` anywhere, no `release-please`, no
  `semantic-release`. The Conventional Commits we *already write* are never parsed into anything.
  Shipping a PR bumps no version and produces no human-readable record of what changed.

- **The catalog rots because it is hand-maintained.** `openbank-admin-ui/src/lib/governance/manifest.ts`
  is ~600 lines of hand-curated JSON. It already carries `flywayDrift: true` on five services and
  `flywayDrift: 'unknown'` on others — proof that any artifact maintained by hand diverges from the code
  it claims to describe. The service list in `services/page.tsx` is 27 hardcoded entries. Nothing
  deep-links to a service's OpenAPI contract; the catalog is not clickable through to the API.

- **Test coverage is computed but invisible.** `_service-ci.yml` already uploads Kover/JaCoCo reports to
  CI artifacts (7-day retention), but only `openbank-libs` enforces a floor (39%, ADR-0020) and nothing
  surfaces coverage in the admin UI.

- **The supply chain has detection but no integrity.** `security.yml` runs CodeQL (SAST), Trivy (SCA +
  IaC), Syft + Gradle CycloneDX (SBOM), gitleaks (secrets), Dependabot. But the **SBOM is an orphaned
  7-day artifact, never signed, never attested, never tied to a release.** There is no artifact signing
  (cosign), no SLSA provenance, no in-toto attestation. We can detect a vulnerable dependency but cannot
  prove that the image running in production is the reviewed, scanned code. For a bank under DORA this
  is the single largest SSDLC gap.

- **The audit chain is broken in the middle.** We have a signed commit at one end and a running service
  at the other, but **no continuous, tamper-evident link between them**: commit → PR (reviewed) →
  release (versioned, changelogged) → artifact (signed, SBOM, provenance) → deploy (GitOps) → running
  (`/info` reports version+commit). Today the middle three links do not exist, so "which reviewed commit
  is this running binary?" is unanswerable.

- **AI agents and new contributors repeat the same mistakes.** No `CLAUDE.md` files, no machine-readable
  rule set. Every session and every newcomer re-derives "bump the version, update the OpenAPI spec, add
  a changelog entry, sign the artifact, register in the catalog" from scratch — and forgets a step.

The unifying root cause: **conventions are human-readable prose, not machine-enforced gates; the
artifacts that should describe the system are maintained by hand instead of derived from it; and the
release path produces no signed provenance.** The governing principle of this ADR is therefore: **derive
from code → enforce in CI → sign the evidence → surface in UI.** Nothing that can be computed should be
typed twice, and nothing shipped should be unprovenanced.

This is timely because the platform has crossed ~30 services, aspires to a governance console (admin UI)
fed by stale static data, and is moving toward a **multi-agent development model** where several agents
work concurrently across services — which only works if the rules are machine-readable, the gates are a
deterministic oracle, blast radius is derivable, and every change is provenanced.

Status legend: 🟢 GREEN = built + tested; 🟡 YELLOW = scaffolded, not yet live; ⬜ PLANNED = scoped here.

## Decision

We will treat versioning, release notes, supply-chain provenance and governance metadata as
**code-derived, CI-enforced, cryptographically signed and UI-surfaced**, delivered in layers, with
per-service independent versioning, a closed and tamper-evident audit chain, and explicit multi-agent
readiness. CI gates are tightened only after each producing layer exists.

### D1 — Layer A: conventions as code (the "codebook", rules, agent skills) + derived dependency graph

We will move conventions out of advisory prose into machine- and agent-readable artifacts versioned with
the code:

| Artifact | Location | Single source of truth for | Consumed by |
|---|---|---|---|
| **`rules.yaml`** ⬜ | `openbank-libs/governance/rules.yaml` | commit types; type→SemVer-bump map; "change X ⇒ require Y" (API ⇒ openapi.yaml + minor; DB ⇒ Flyway migration; event ⇒ Avro version); coverage floors; deprecation policy | CI gates **and** the `CLAUDE.md` files (no duplicated prose) |
| **`CLAUDE.md` hierarchy** ⬜ | repo root + per-service | root: invariant workflow (SemVer, PR flow, ship-checklist) referencing `rules.yaml`; per-service: ports, schema, local quirks | Claude Code every session; new contributors |
| **Claude skills** ⬜ | `.claude/skills/` (committed) | the recurring workflows, made executable | invoked as `/command` |
| **`service-graph.json`** ⬜ | generated → `docs/generated/` | the **derived** inter-service dependency graph (API consumers from OpenAPI `$ref`/client configs, Kafka producer/consumer from topic config) | catalog (D3), blast-radius reasoning (D6), CI impact selection |

The **`service-graph.json` is the critical replacement** for the lineage data buried in the hand-curated
`manifest.ts`. It is regenerated in CI from real artifacts (OpenAPI specs, Kafka topic configuration,
Flyway schemas), never hand-edited, and is what lets both humans and agents answer "if I change producer
X, who breaks?".

The skills turn the implicit checklist into runnable commands — what actually stops the repetition:

- `/open-pr` — create branch, open PR from template, verify a version bump and changelog fragment exist
  before the PR opens.
- `/bump <service>` — raise the service SemVer and its `openapi.yaml` `info.version` per `rules.yaml`.
- `/release <service>` — assemble release notes from changelog fragments (delegates to release-please).
- `/ship-check` — **authoritative** preflight (the same checks the CI gates run, so an agent self-verifies
  "done" identically to CI): bump? changelog? OpenAPI updated if API touched? test added? coverage floor?
  contract test green? catalog auto-derivable? artifact will be signed?

`CONTRIBUTING.md` stays as the human narrative but stops being the source of truth; it links to
`rules.yaml`.

### D2 — Layer B: per-service SemVer + signed, provenanced releases

We will give each service its **own independent SemVer**, threaded through the existing-but-unfed runtime
plumbing, and make every release a **signed, attested, self-describing artifact**:

- **One version per service, one source.** Replace hardcoded `version = "0.1.0-SNAPSHOT"` with a
  per-service `version.txt` (or gradle property) read by the build, which sets
  `quarkus.application.version`. This single value then *automatically* fills the already-built
  `ServiceInfoResource` (`/api/v1/info`) and the `X-API-Version` header from `ApiVersionResponseFilter`
  🟢 (both exist today, both currently emit the `0.0.0` default).
- **Collapse three versions into one.** `openapi.yaml info.version` == `quarkus.application.version` ==
  git release tag.
- **release-please, per-service components.** Monorepo/components mode: each `openbank-*-service` is its
  own release unit. From the Conventional Commits we already write it computes the next SemVer, generates
  a per-service `CHANGELOG.md`, opens a "Release PR", and on merge cuts a tagged GitHub Release.
- **Sign and provenance every released artifact (SLSA L3 target).** On release: build the container, then
  `cosign sign` the image and produce an **in-toto / SLSA provenance attestation** and a **per-service
  CycloneDX SBOM as a first-class release artifact** (not the current orphaned 7-day blob). The Gradle
  CycloneDX SBOM already exists 🟡 — it gets signed and attached, not discarded.
- **Signed release evidence bundle (audit-grade).** Each release publishes one signed bundle:
  `{version, git commit, SBOM, SLSA provenance, changelog, coverage summary, scan results (CodeQL/Trivy
  SARIF), test results}`. This is the DORA evidence object; the `evidenceExported` flag already present
  in the governance manifest becomes a real, produced artifact.
- **Wire the deprecation plumbing that already exists.** `ApiVersionResponseFilter` already emits
  `Deprecation`/`Sunset`/`Link: rel="successor-version"` headers but `isDeprecatedPath()` is hardcoded
  `false`. Drive it from `rules.yaml`.

Per-service (not global) versioning is the chosen strategy (rationale in Alternatives).

### D3 — Layer C: code-derived API catalog + coverage + contract tests in the admin UI

We will delete the hand-maintained governance data and rebuild the catalog from data the system emits,
optimized for both performance and audit:

- **Retire `manifest.ts` as a data source.** The catalog reads two tiers:
  - **Build-time snapshot (authoritative, fast, auditable):** CI generates `catalog.json` per release
    from `service-graph.json` + each service's `/openapi` + `/info` contract + coverage summary, and
    publishes it as a point-in-time, signed evidence artifact. The admin UI loads this static snapshot —
    no fan-out of 30 live HTTP calls on page load (performance), and it doubles as a per-release manifest
    (audit).
  - **Runtime drift check (optional, live):** the UI may additionally hit live `/api/v1/info` to flag
    "running version ≠ released version" — turning the catalog into a deployment-drift detector.
  - **API contract** ← deep-link each service's `/q/openapi`, render Swagger UI, "open contract". This is
    the missing click-through from catalog to API.
- **Consumer-driven contract tests.** Introduce Pact (or spring-cloud-contract): each consumer publishes
  its expectations; each producer verifies them in CI. This is what the `api-contract` *file* check
  cannot do — catch a producer change that silently breaks a consumer. Essential at 30 services and
  **mandatory under the multi-agent model** (D6).
- **Coverage dashboard from existing reports.** CI publishes a per-service `coverage-summary.json`
  (Kover XML→JSON, a small payload — not raw reports) that the admin UI loads into a "coverage per
  service + trend" view. Extend the Kover regression floor (ADR-0020) from `openbank-libs` to every
  service.

### D4 — CI gates (the enforcement), tightened last

Conventions become "always followed" only when CI blocks omission. Gates are driven by `rules.yaml` (D1)
and enabled **only after** the producing layers exist:

| Gate | Asserts | On failure |
|---|---|---|
| `conventional-commit` | commit format matches `rules.yaml` | block |
| `version-bump` | change under a service's `src/main` ⇒ that service's version bumped | block |
| `api-contract` | changed `@Path`/DTO ⇒ `openapi.yaml` changed + minor bump | block |
| `contract-test` | consumer Pact expectations verified against producer | block |
| `changelog` | feat/fix ⇒ changelog fragment exists (release-please) | block |
| `coverage-floor` | per-service Kover ≥ floor, ratchet-only | block |
| `provenance` | released image is cosign-signed + has SLSA attestation + SBOM | block release |

### D5 — The closed, tamper-evident audit chain

The point of D1–D4 together is one continuous, verifiable link from intent to runtime — the auditor's
golden thread:

```
signed commit (DCO + GPG/SSH)
  → PR (CODEOWNERS review, 2-eyes on money paths)
    → release-please (SemVer tag, changelog)
      → signed artifact (cosign + SLSA provenance + SBOM)   ← evidence bundle
        → GitOps deploy (ArgoCD, ADR-0027; admission verifies signature — ADR-0030)
          → running service (/api/v1/info reports version + git commit)
            → catalog.json snapshot (point-in-time evidence, per release)
```

Every link is machine-verifiable and produces an immutable record. `rules.yaml` being versioned means
"what were the rules at time T" is itself auditable (policy-as-code with history). This is the chain a
DORA / CNB audit asks for, and today only its two endpoints exist.

### D6 — Multi-agent readiness

The same machinery is the precondition for several agents working concurrently across services:

- **Machine-readable contract** (`rules.yaml`) — agents read one rule set instead of guessing. ✓ D1
- **Deterministic oracle** — CI gates (D4) and `/ship-check` give each agent an objective, identical
  pass/fail signal; `/ship-check` runs the *same* checks as CI so an agent's "done" == CI's "done". ✓
- **Isolation without collision** — per-service `version.txt` (D2) instead of a global version file means
  two agents releasing different services never conflict on a shared file; per-service path-scoped CI
  (already exists) + git worktrees give parallel, non-interfering workspaces. ✓
- **Blast-radius awareness** — `service-graph.json` (D1) lets an agent compute, before editing producer
  X, exactly which consumers it may break — replacing the human intuition that the retired `manifest.ts`
  lineage encoded. ✓
- **Cross-agent safety net** — consumer-driven contract tests (D3) catch one agent silently breaking
  another agent's service; under multi-agent this moves from "nice to have" to **mandatory**. ✓
- **AI-change attribution (audit)** — releases record which agent/model authored a change and that a
  human reviewed it (extends DCO with model provenance, anticipating EU AI Act expectations for
  money-path code), folded into the signed evidence bundle (D2). ✓

### D7 — Phasing

Incremental, additive-first, so nothing freezes:

1. **Layer A** (codebook + `CLAUDE.md` + skills + `service-graph.json` generator) — fastest impact on
   repeated mistakes and the foundation for multi-agent; purely additive.
2. **Layer B** (per-service versions + release-please + cosign/SLSA/SBOM + evidence bundle) — wires the
   existing `/info` plumbing to reality and makes releases provenanced.
3. **Layer C** (catalog snapshot from derived data + coverage dashboard + contract tests) — removes the
   stale `manifest.ts`, makes the API clickable, closes the cross-service safety net.
4. **Layer D gates** — tighten once the above can be satisfied.

The deploy-side enforcement (admission verifies the D2 signatures) and the broader security lifecycle are
**ADR-0030**'s scope; this ADR *produces* the signed provenance, ADR-0030 *enforces and operationalizes*
it.

## Alternatives considered

- **One global monorepo version.** Pros: simplest release-please config, one changelog. Cons: a one-line
  fix bumps all 30; release notes become noise; cannot express maturity divergence; **and a global
  version file is a guaranteed merge-conflict point for concurrent agents.** Rejected — per-service
  independent SemVer (D2).

- **Keep conventions in `CONTRIBUTING.md`, add only CI gates.** Cons: gates and prose encode the same
  rules twice and drift; agents have nothing structured to read. Rejected — single `rules.yaml` consumed
  by both.

- **Keep the hand-curated governance `manifest.ts`, add a sync script.** Cons: one more thing that
  breaks; it already drifts. Rejected for volatile fields (versions, Flyway, topics, lineage) which must
  be derived into `service-graph.json`; purely *editorial* fields (data classification, retention) may
  remain curated but move out of the volatile data path.

- **Live-poll 30 `/info` endpoints on every catalog page load.** Pros: always fresh. Cons: 30-way HTTP
  fan-out per page load (slow, fragile); no point-in-time audit record. Rejected as the *primary* source
  — chosen instead a CI-generated `catalog.json` snapshot (fast + auditable) with live `/info` as an
  optional drift overlay (D3).

- **Adopt Backstage for the catalog.** Cons: a heavy second frontend stack; the admin UI already aspires
  to be the governance console. Rejected for now — reuse the admin UI.

- **Defer signing/provenance to "later".** Cons: leaves the central SSDLC gap open while we build
  releases that would have to be retrofitted. Rejected — signing is folded into the release flow from day
  one (D2), since retrofitting provenance onto an established release pipeline is strictly harder.

## Consequences

**Positive**
- Recurring failures (forgotten bumps, missing changelog, stale catalog, invisible coverage, unsigned
  artifacts) become CI-blocked or auto-generated rather than memory-dependent.
- One true version per service, visible at runtime, in headers, in the catalog, as a git tag, and inside
  a signed evidence bundle.
- A continuous, tamper-evident audit chain from signed commit to running binary — the DORA/CNB golden
  thread.
- The platform is structurally ready for concurrent multi-agent development: machine-readable rules,
  deterministic gates, derivable blast radius, cross-service contract safety net, AI attribution.
- The admin UI catalog stops lying because it reads what services emit.

**Negative**
- Per-service versioning needs a working release-please components config and commit-scope discipline
  (scope selects the released service).
- Signing/provenance adds release-pipeline complexity (key management via Vault/KMS, Sigstore/cosign in
  CI) and some build time.
- CI gets stricter; until contributors adjust, more PRs bounce off the new gates.
- Initial migration touches all 30 `build.gradle.kts`, removes a large hand-authored `manifest.ts`, and
  introduces contract tests where there were none.

**Neutral**
- `CONTRIBUTING.md` and ADRs remain as narrative pointing at `rules.yaml`, not as the enforceable source.
- No new runtime dependency in services — the plumbing (`ServiceInfoResource`, `ApiVersionResponseFilter`,
  Kover, CycloneDX SBOM) already ships; this wires and signs it.

## Compliance impact

- PCI DSS: Req. 6.3 (secure SDLC), 12.10 (change records) — supported by enforced versioning + evidence.
- DORA:    Art. 8–10 (ICT change management, configuration/version traceability), Art. 28–30
  (ICT third-party / supply-chain risk via signed SBOM + provenance) — **directly supported**: every
  change becomes a versioned, changelogged, signed, attested, auditable artifact with a closed chain to
  runtime.
- GDPR:    not applicable (no personal-data path change).
- PSD2:    API versioning/deprecation headers improve TPP-facing contract stability.
- CNB:     improved change traceability and supply-chain provenance support auditability expectations.

## References

- ADR-0005 — OpenAPI design-first (API contract source).
- ADR-0014 — `openbank-libs` as service-infrastructure layer (home of `rules.yaml` and web plumbing).
- ADR-0017 — Secrets via Vault (signing-key custody for cosign/KMS).
- ADR-0020 — Code coverage, Kover regression floor (extended per-service here).
- ADR-0027 — Cloud-agnostic substrate / GitOps (the deploy link in the audit chain).
- ADR-0030 — Supply-chain security & SSDLC hardening (consumes this ADR's signed provenance; sibling).
- `CONTRIBUTING.md` — Conventional Commits, Definition of Done (narrative this ADR makes enforceable).
- `openbank-libs/.../web/ServiceInfoResource.kt`, `ApiVersionResponseFilter.kt` — existing, unfed runtime
  version plumbing.
- `openbank-admin-ui/src/lib/governance/manifest.ts` — the hand-maintained data this ADR retires.
- `.github/workflows/security.yml` — existing CodeQL/Trivy/SBOM detection this ADR makes provenanced.
