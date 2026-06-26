# Feature flags and experimentation (flag-as-code, OpenFeature-aligned)

Date: 2026-06-06
Status: Accepted (2026-06-14 — decision implemented: `openbank-libs/.../flags`
ships the OpenFeature-aligned surface (`FeatureClient`, `FlagdProvider`,
`@FeatureFlag` + interceptor, `FlagDefinition`/`FlagExposure`) merged to `main`.
Remaining tail is tracked as a follow-up issue, not a blocker: four-eyes
enforcement on money-path flag flips — issue #419.)
Author(s): Jiří Raška

## Context

Frontend and backend evolve concurrently and at different cadences: a Next.js
admin-ui and KMP customer app (ADR-0064) ship faster than the ~30 Quarkus
services behind them. Today the only way to turn a behaviour on or off, or to
roll it out to a subset of users, is a code change + deploy through GitOps
(ADR-0010). That is correct for *durable* configuration but too coarse for three
things engineers need daily:

1. **Decoupling deploy from release** — merge code dark, turn it on later, per
   environment, without a second deploy.
2. **Progressive rollout + instant kill** — ship to 1 % → 10 % → 100 %, and pull
   a misbehaving feature in seconds, not in a redeploy cycle.
3. **A/B experimentation** — split traffic between variants and measure the
   outcome with statistical rigour, not vibes.

This is **engineer-owned feature rollout**, which ADR-0047 (governed runtime
operational control plane) explicitly places **out of its scope** and leaves "in
GitOps" (ADR-0047 §"Out of scope"). The two systems must not be confused:

| Axis            | ADR-0047 control plane              | Feature flags (this ADR)            |
|-----------------|-------------------------------------|-------------------------------------|
| Owner           | compliance / risk / SRE             | **engineer / product**              |
| Governs         | limits, thresholds, break-glass     | feature rollout, A/B, kill-switch   |
| Source of truth | runtime `PolicyStore` (per-svc DB)  | **flag-as-code in git (GitOps)**    |
| Change cadence  | propose → approve → effective-date  | PR merge + fast kill-switch overlay |

A bank cannot adopt a flag system that turns into an un-audited second control
plane. The design below keeps flag *definitions* in version control (reviewed,
signed, diffable) and treats any risk-bearing flip as a first-class auditable —
and, for money-path flags, four-eyes-gated (ADR-0023/0034, `libs/foureyes`) —
event. That governance posture is the differentiator: an experimentation
platform that is also auditable like a banking transaction.

## Decision

We will introduce a **feature-flag and experimentation capability** built on the
CNCF **OpenFeature** standard, with definitions managed as code and evaluation
performed by a per-service **flagd** sidecar — structurally identical to the OPA
sidecar pattern already established by ADR-0034.

**1. Standard, not vendor.** `openbank-libs/flags` exposes an OpenFeature-aligned
port (`FeatureClient`) with OpenFeature reason codes and evaluation-context
shape. No proprietary flag API; no vendor lock-in. Phase 2 may delegate the port
to the upstream `dev.openfeature:sdk` directly — the port shape is chosen to make
that a drop-in.

**2. Evaluation via sidecar (mirrors OPA).** Production evaluation goes to a
per-service **flagd** sidecar over OFREP (OpenFeature Remote Evaluation
Protocol) at `localhost:8013` — local, sub-millisecond, no network dependency on
the hot path. `FlagdProvider` uses plain `java.net.http` so the libs JAR stays
runtime-agnostic (`compileOnly` everywhere), exactly like
`OpaSidecarPolicyDecisionPoint`. Fail-static: any provider error resolves to the
caller-supplied default, never throws on the eval path (reason `ERROR`).

**3. Flag-as-code is the source of truth (GitOps).** Flag definitions live in the
gitops repo as flagd JSON, schema-validated in CI (`derive → enforce → show`,
ADR-0029). ArgoCD syncs them to a ConfigMap; flagd hot-reloads. Every flag change
is a reviewed, signed commit — the same audit posture as any other change.

**4. Fast-path kill-switch.** A narrow runtime overlay carries `DISABLED` state
only (fail-safe direction): a Kafka `feature-control` event invalidates the flagd
cache for instant kill without waiting for an ArgoCD sync. Re-enabling is only
possible through git — the overlay can never *loosen*, only *halt*. This is the
same asymmetry ADR-0047 uses for break-glass.

**5. Governance classification.** Every flag declares a `classification`:
`COSMETIC` | `FEATURE` | `MONEY_PATH`. This ADR's skeleton carries the
classification and `fourEyes` metadata (`FlagDefinition`); the runtime
**enforcement** is a tracked follow-up (issue #419). When wired, a `MONEY_PATH`
flip **will be** gated through the four-eyes primitive (`libs/foureyes` +
`governance.Proposal`, ADR-0023/0034) and **will** emit an `AuditEvent`
(`operation = "featureflag.flip"`); OPA (`libs/authz`) **will** decide *who* may
flip *which* flag and mark prohibited combinations (e.g. a flag that would bypass
sanctions screening), mirroring ADR-0047. Until #419 lands these controls are
**declared, not enforced**, and no service wires `@FeatureFlag` to a money-path
endpoint in the meantime.

**6. Declarative gating + exposure tracking.** `@FeatureFlag` decorates a method
(mirrors `@Authorize`); its interceptor consults the injected `FeatureClient` and
short-circuits when the flag is off. Risk/experiment flags emit a `FlagExposure`
event through an `ExposurePublisher` port → outbox → Kafka → analytics-service,
which joins exposures to conversion events and computes statistical significance
(built on the SCD2 / point-in-time analytics store, ADR-0023).

**7. Stale-flag governance.** Every flag declares `expiresAt`. CI warns/fails on
expired flags and on flags with no code reference — flag debt is gated, not
accrued.

**8. Frontend end-to-end.** admin-ui evaluates server-side through the BFF
(ADR-0056) so no flag set leaks to the browser; the KMP customer app consumes the
same OpenFeature contract. A new `/flags` admin section lists, classifies, and
(four-eyes for money-path) flips flags, plus an A/B panel reading from
analytics-service.

This ADR + the `libs/flags` skeleton it accompanies cover the **library port,
provider, annotation, classification and exposure contracts** only. CI schema
validation, the flagd sidecar manifests, the admin-ui pages and the
analytics-service experiment math are scoped follow-ups (see Consequences).

## Boundary with ADR-0047 (governed runtime operational control plane)

Both systems change runtime behaviour without a redeploy; the ownership table in
the Context above draws the line. To make it operational:

- **Feature flags (this ADR)** — **engineer/product-owned code-path toggles**:
  dark launch, progressive rollout, A/B. Source of truth is **flag-as-code in git
  (GitOps)**; the runtime overlay is a fail-safe **kill-switch only** (DISABLED,
  never loosen).
- **ADR-0047 control plane** — **risk/compliance/SRE-owned**: Tier-A break-glass
  resilience controls and Tier-B **effective-dated business parameters** (limits,
  fees, AML/sanctions thresholds) in a per-service `PolicyStore`, behind
  propose → four-eyes → effective-date.

**Tie-breaker rule:** anything that **weakens a risk control** — e.g. disabling
sanctions screening, relaxing an AML threshold, bypassing SCA — is **never a
feature flag**. It belongs to ADR-0047 governance (four-eyes, effective-dating,
OPA prohibitions — disabling sanctions screening is prohibited outright). A flag
whose flip would do a compliance officer's job is misclassified by definition; CI
must reject it. Within this ADR's own scope, **money-path flag flips require
four-eyes** (`libs/foureyes`, enforcement tracked in issue #419).

## Alternatives considered

- **Build proprietary in-libs flag API** — full control, but reinvents a solved
  problem, no ecosystem SDKs (web, KMP, CLI), and locks the org into a bespoke
  contract. Rejected: OpenFeature is a CNCF standard with the same vendor-neutral
  benefit we already get from OpenAPI-first and OPA.
- **LaunchDarkly / Unleash SaaS** — mature UIs and targeting, but a managed
  external dependency on the money-path decision surface (data residency, vendor
  trust, cost at fleet scale) and a proprietary SDK. Rejected for the core;
  OpenFeature keeps the option open to back the port with any provider later.
- **Runtime flag store in a DB (extend ADR-0047)** — would unify "change
  behaviour at runtime", but collapses the engineer/compliance ownership split
  ADR-0047 deliberately drew and removes git review from rollout. Rejected:
  flag-as-code keeps rollout auditable and diffable; ADR-0047 stays for
  compliance-owned parameters only.
- **Quarkus `@ConfigProperty` + ConfigMap toggles** — zero new moving parts, but
  no targeting, no percentage rollout, no A/B, `@ConfigProperty` caches at
  injection (same reason ADR-0047 rejected the ConfigSource SPI), and no FE story.
  Rejected as insufficient for A/B and progressive delivery.

## Consequences

**Positive**
- Deploy decoupled from release; sub-second kill-switch; progressive rollout.
- Real A/B with statistical significance, reusing the analytics store.
- Vendor-neutral (OpenFeature) with the OPA-sidecar operational pattern the team
  already runs — low new-concept cost.
- Risk-bearing flips become auditable and four-eyes-gated once #419 lands — a
  banking-grade posture most flag systems lack.

**Negative**
- A new sidecar (flagd) per service that opts in — operational surface + cost
  (mitigated by scale-to-zero tiers, ADR-0057).
- A second "change behaviour" mechanism alongside ADR-0047 — mitigated by the
  hard ownership/source-of-truth split above; CI must prevent a money-path flag
  from doing a compliance officer's job.

**Neutral**
- Flag-as-code adds files to the gitops repo and a CI validation step.
- `libs/flags` adds no new runtime dependency (plain `java.net.http` + jackson,
  already `api`); the OpenFeature SDK is an optional Phase-2 delegation.

## Compliance impact

- PCI DSS: not applicable (no CHD in flag definitions; flag state is not a secret).
- DORA:    Art. 17 — money-path flag flips **will be** recorded as operational
  events via the canonical `AuditEvent` envelope (24 h reconstruction) once #419
  lands. Kill-switch is a resilience control.
- GDPR:    Art. 30 — flag-change and exposure events carry actor/trace; targeting
  keys must be pseudonymous (no PII in `EvalContext.targetingKey`).
- PSD2:    not applicable directly; a flag must never disable SCA (SCA is an
  ADR-0021 money-path control). This will be **enforced** by an OPA
  prohibited-combination rule (follow-up #419); it is **not** yet enforced by the
  skeleton in this ADR.
- CNB:     not applicable.

## References

- ADR-0047 — Governed runtime operational control plane (the ownership boundary).
- ADR-0034 — OPA for fine-grained authz (the sidecar + port pattern this mirrors).
- ADR-0023 — Analytics regulatory hardening (`MakerChecker`/`Proposal`, SCD2 store).
- ADR-0029 — Versioning, release and governance as code (`derive → enforce → show`).
- ADR-0010 — GitOps via ArgoCD (flag-as-code delivery path).
- ADR-0056 — admin-ui BFF as sole browser-to-cluster path (no flag leak to client).
- ADR-0064/0065 — Customer app (KMP) and edge (the other OpenFeature consumer).
- `libs/foureyes` — four-eyes primitive used to gate money-path flips.
- OpenFeature (CNCF) — https://openfeature.dev ; OFREP; flagd provider.
