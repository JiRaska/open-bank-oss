# ADR-0082 — CI runner governance — trust-tiered persistent pools, no human in the merge path

Date: 2026-06-01
Status: Superseded by ADR-0053
Author(s): Jiri Raska

> **Renumbered 0051 → 0082 (2026-06-11).** This ADR was originally filed as ADR-0051, a
> number it accidentally shared with the (more widely referenced) generic-service-discovery
> ADR, which keeps 0051. Historical references to "ADR-0051" in a CI-runner context mean
> this document. ADR-0053 superseding a *higher* number is an artifact of the renumbering,
> not of the decision order (both decisions date 2026-06-01).

> **Superseded (2026-06-01) by [ADR-0053](0053-ephemeral-scale-to-zero-arc-runners.md).**
> Measured CI data and a FinOps/spot-capacity review reversed this ADR's default:
> the runner pools become per-job *ephemeral* ARC scale sets on EKS, scaled to zero
> on Karpenter's diversified Graviton spot pool. ADR-0053 explains why ephemeral-on-EKS
> meets this ADR's own availability and trust-tiering drivers *better* than the
> persistent always-on EC2 model, at ~4× lower cost — accepting a mitigated
> cold-start tax. The build/deploy trust split below is retained (as two scale sets);
> the "persistent + warm + always-on EC2" mechanism is what changed.

## Context

ADR-0040 chose **persistent, cache-warm self-hosted runners** (two Macs, one EC2
cold standby) as the durable, $0-GitHub-minutes execution model, and ADR-0043
refined the *recipe* to keep the Gradle daemon and test-infra warm and reset only
per-job state. Both are still **Proposed**. Operating that model surfaced two gaps
those ADRs did not close — gaps that are governance/risk concerns, not tuning:

1. **Availability single point of failure.** The required merge check
   (`Services CI / all-green`) runs on a Mac that doubles as a developer
   workstation. When the laptop sleeps, in-flight jobs die ("self-hosted runner
   lost communication with the server") and **all merges stall**. A human closing a
   lid is in the critical path of the ICT change pipeline — a DORA availability
   finding.

2. **No trust-tiering of the runner pool.** Today a single label pool
   (`[self-hosted, openbank-sandbox]`) runs *both* untrusted PR build code *and*
   would carry any deploy credentials (ECR push, ArgoCD sync). Persistent runners
   also retain filesystem/cache/state between jobs — the stale `origin/<base>`
   remote-tracking ref already worked around in `services-ci.yml` is a direct
   symptom. A persistent runner that both executes arbitrary build code and holds
   privilege is a supply-chain hazard (ADR-0030).

A third, smaller concern proved a non-issue on inspection but is worth encoding as a
control: **clock trust.** A runner restart looked like a clock fault; measurement
showed the host was within 15 ms of NTP and the "wrong time" was UTC-vs-local
confusion. In a bank, build/audit timestamps must be trustworthy, so clock sync
should be an enforced, monitored control rather than an assumption.

Forces:

- **Cost (ADR-0040).** We will not pay GitHub for hosted minutes. Self-hosted stays.
- **Speed (ADR-0043).** Warm Gradle daemon + warm test-infra + shared cache are the
  main levers; full per-job ephemerality would throw them away.
- **Supply-chain integrity (ADR-0030).** Runners execute build code on hardware on
  our networks; isolation and credential separation are security controls.
- **Availability (DORA ICT change risk).** A single-runner / single-laptop outage
  must not stall the merge pipeline.
- **As-code + enforce + show (ADR-0029).** Runner topology and the rules that govern
  it belong in version control and in `rules.yaml`, not in click-ops.

This is a **refinement of ADR-0040/0043**, not a reversal: persistent and
cache-warm stays; we add trust-tiering, remove the human from the critical path, and
encode the runner controls as governance.

## Decision

**We will run two trust-separated, persistent runner pools, keep at least one
merge-eligible runner off any developer workstation, and govern runner topology and
controls as code.**

Concretely:

1. **Two pools, separated by trust:**
   - **`openbank-build`** — executes PR code (compile, unit/IT tests). **No
     cloud-write credentials, no production secrets**, egress-filtered. Gradle
     daemon and Docker/test-infra caches stay warm (ADR-0043); only the per-job
     *workspace* is reset (`git clean -ffdx` + force-fetch — the documented
     stale-ref fix, promoted to a rule).
   - **`openbank-deploy`** — pushes images to ECR and triggers ArgoCD sync.
     Credentials are obtained at job time via **OIDC→IRSA scoped to exactly ECR
     push + the specific ArgoCD action**, sourced from Vault, never written to disk.
     Jobs target this pool **only on `main`/tags, after merge**. A pull-request job
     must never be able to target `openbank-deploy` or assume the deploy role.

2. **No human in the merge path.** The EC2 runner is promoted from cold standby to
   an **always-on warm primary**. At least **two runners carrying the
   merge-required label exist across independent failure domains** (EC2 + a Mac), so
   one sleeping laptop never blocks `all-green`. Developer Macs remain *accelerators*
   (they are faster, ADR-0040), not the only path.

3. **Persistent-runner hygiene replaces per-job ephemerality.** Acceptable for a
   **private** monorepo (no fork PRs ⇒ no external untrusted code; the residual
   insider-commit risk is handled by branch protection + review, not ephemerality)
   **provided**: clean per-job workspace; secrets only in the deploy pool, fetched
   at runtime; SSM-only shell, egress-only SG, IMDSv2-required, KMS-scoped
   registration token (already in the tofu `runner` module — retained).
   **Re-evaluation trigger:** if this repository ever goes public or accepts
   external contributors (fork PRs), this justification no longer holds — the
   `openbank-build` pool MUST move to per-job ephemeral runners (the ARC escape
   hatch below) before any untrusted PR can execute on it.

4. **Clock is a governed control.** NTP sync is enforced and monitored on every
   runner; a runner whose offset exceeds **1 s** de-registers itself rather than
   stamping builds/audit events from a skewed clock.

5. **Governed as code.** Runner topology lives entirely in OpenTofu (including a
   reproducible bootstrap + label for the Mac runners — no pets). A new
   `ci_runners` section in `openbank-libs/governance/rules.yaml` defines the pool
   labels, the credential separation, the min-availability rule, and which workflows
   may target `openbank-deploy`; CI enforces it. Provenance hardening (cosign-signed
   images from the deploy pool, ArgoCD deploying only signed digests) lands with the
   existing `provenance` gate.

## Alternatives considered

- **Stay single-pool persistent (status quo / ADR-0040 as-is).** Cheapest, simplest.
  Rejected: leaves the laptop SPOF and mixes untrusted build execution with deploy
  privilege — both are governance defects for a regulated pipeline.
- **Full ephemeral runners via ARC on EKS (scale-to-zero, fresh runner per job).**
  Best isolation. Rejected as the *default*: it discards the warm Gradle daemon and
  warm KRaft/Postgres test-infra that ADR-0040/0043 were bought to keep, re-incurring
  the cold-start cost on a ~30-module fan-out. Kept as a documented escape hatch for
  the deploy pool if credential isolation ever needs hardware-fresh guarantees.
- **GitHub-hosted runners.** Zero maintenance. Rejected: the recurring billing block
  (ADR-0040) and per-minute cost on a private repo are exactly what ADR-0040 ruled
  out.
- **Two pools, but both on developer Macs.** Cheaper than always-on EC2. Rejected:
  does not remove the human-laptop SPOF, which is the primary driver here.

## Consequences

**Positive**
- Merges no longer stall when a developer closes a laptop (availability / DORA).
- Deploy credentials are unreachable from PR-code execution (supply-chain / ADR-0030).
- Runner topology and controls are reviewable, reproducible, and CI-enforced (ADR-0029).
- Keeps the ADR-0040 cost win ($0 hosted minutes) and ADR-0043 warm-cache speed win.

**Negative**
- An always-on EC2 runner costs more than a cold standby (mitigated: Graviton/spot
  persistent, already in the module; right-sized).
- Two pools add config surface (two label sets, two IAM scopes, workflow routing).

**Neutral**
- Mac runners stay as accelerators; the model degrades gracefully to EC2-only.
- Full ephemeral ARC remains available as a deploy-pool escape hatch without
  changing the default.
- **Host kernel tuning is per-pool.** `*ApiIT` / Pact provider tests run Redpanda/Kafka
  Testcontainers, which need a raised `fs.aio-max-nr` (a non-namespaced kernel param) or
  they die at startup ("Could not setup Async I/O … Resource temporarily unavailable"),
  flaking the build. The ARC pool sets it via the `init-aio-sysctl` init container
  (`arc-runners.tf`); the persistent EC2 pool sets it in `user-data`; the manually-provisioned
  Linux hosts (Hetzner) run `openbank-infra/scripts/tune-runner-host.sh` (also invoked
  best-effort by `reregister-runner.sh`). Any new Linux runner pool MUST raise it too.

## Compliance impact

- PCI DSS: not applicable (no CDE in the build pipeline) — but credential separation
  aligns with least-privilege expectations.
- DORA:    ICT change-management & operational resilience — removes a single point of
  failure in the change pipeline; runner availability becomes an SLO.
- GDPR:    not applicable (no personal data on runners).
- PSD2:    not applicable directly.
- CNB:     trustworthy build/audit timestamps (clock control) support the
  reproducible-evidence expectation behind the release evidence bundle.

## References

- ADR-0027 — cloud-agnostic substrate, isolated VPC, egress-only.
- ADR-0029 — governance as code (derive / enforce / show).
- ADR-0030 — supply-chain & threat-model gates.
- ADR-0040 — CI execution model and cost (persistent, $0 hosted minutes).
- ADR-0043 — CI performance model (warm-reuse the persistent runner).
- `openbank-infra/aws/modules/runner` — the existing tofu runner module.
- `openbank-libs/governance/rules.yaml` — `ci_runners` section (this ADR).
