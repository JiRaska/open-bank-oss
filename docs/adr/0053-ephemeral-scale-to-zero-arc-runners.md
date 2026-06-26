# ADR-0053 — Ephemeral scale-to-zero ARC runners — supersedes ADR-0082

Date: 2026-06-01
Status: Accepted
Author(s): Jiri Raska
Supersedes: ADR-0082 (CI runner governance — renumbered from ADR-0051 on 2026-06-11 to
resolve a duplicate number with the service-discovery ADR-0051)

## Context

ADR-0082 (Proposed, same day) chose **two trust-separated *persistent* runner
pools** with an **always-on warm EC2 primary**, and explicitly **rejected
full-ephemeral ARC-on-EKS as the default** — on the grounds that ephemerality
discards the warm Gradle daemon and warm KRaft/Postgres test-infra that
ADR-0040/0043 were bought to keep, re-incurring cold-start cost on a ~30-module
fan-out. ARC was kept only as a documented *deploy-pool escape hatch*.

Operating the persistent model surfaced that the assumptions behind that rejection
no longer hold, and that the persistent model carries costs ADR-0082 under-weighted:

1. **FinOps.** The cost-dominant box is idle most of the day yet billed 24/7. A
   measured week showed CI is **bursty** (≈200+ runs/wk, jobs 1–31 min), not
   continuous. The dominant cost lever is therefore *running hours*, which only
   scale-to-zero removes. An always-on persistent spot box (~$23–26/mo) versus an
   ephemeral scale-to-zero pool (~$5–8/mo) is a ~4× difference, and an
   on-demand stop/start middle ground is **worse** than always-on spot at this
   utilisation (5× hourly rate beats the idle saving).

2. **Spot is not being exploited.** A pinned single instance type cannot shop the
   spot market. The substrate already runs **Karpenter with a Graviton spot-first,
   instance-diversified `default` NodePool** (`c,m,r,t` gen>3) — i.e. per-build
   *price-capacity-optimized* selection and interruption resilience are already
   available in-cluster, for free, the moment runners become pods.

3. **The persistent model's own drivers are met *better* by ephemeral-on-EKS.**
   ADR-0082's real motivations were (a) availability — no laptop SPOF in the merge
   path — and (b) trust-tiering. Both are *better* served by ARC on EKS than by an
   always-on EC2 box:
   - **Availability:** runners become pods on a multi-AZ EKS cluster with Karpenter
     re-provisioning. This removes *both* the laptop SPOF *and* the single-EC2 SPOF
     ADR-0082 would have introduced. No human and no single host in the critical path.
   - **Trust-tiering:** per-job *ephemeral* runners are the *strongest* isolation
     (fresh kernel-namespaced pod per job, nothing retained between jobs) — exactly
     what ADR-0082 conceded ARC is best at. The build/deploy split is preserved as
     two scale sets (below), not two long-lived hosts.

The one genuine cost of reversing ADR-0082 is the **cold-start tax**: a fresh pod
has no warm Gradle daemon and no warm test-infra. We accept it, with mitigations.

Forces (unchanged from ADR-0040/0082): $0 GitHub-hosted minutes; supply-chain
integrity (ADR-0030); availability (DORA ICT change risk); as-code + enforce + show
(ADR-0029). What changed is the weighting: **cost + capacity resilience + isolation
now outrank warm-cache speed**, and warm-cache loss is mitigable where the other
properties were not.

## Decision

**We reverse ADR-0082's default: the CI runner pools become per-job *ephemeral*
ARC runner scale sets on the existing EKS cluster, scaled to zero when idle, on
Karpenter's diversified Graviton spot pool. Persistent self-hosted runners are
retired as the default.**

Concretely:

1. **Three ephemeral ARC scale sets — trust-separated (build/deploy) and
   capacity-separated (build/batch):**
   - **`openbank-build`** — executes PR code, merge-required (per-service
     compile+test, admin-ui, manifest validation). Bursts to `maxRunners`. **No
     cloud-write credentials.** Image builds use **rootless BuildKit / Kaniko** (no
     privileged docker-in-docker), so the bank's threat model keeps a non-privileged
     build surface.
   - **`openbank-batch`** *(amendment 2026-06-01)* — same trust level as build (no
     credentials), but a **separate, low-capped pool** for the **non-blocking** lane:
     the security/secret scans, the finops version check, and label sync — on both
     their PR runs and the weekly cron burst. `minRunners: 0`, scale-to-zero. ARC has
     **no job preemption**, so the only way to stop a scan/cron burst from starving
     the merge-required build lane is to give build its own dedicated capacity; a
     batch burst tops out at `arc_batch_max_runners` and never touches build. (Driver:
     a multi-workflow push/PR burst queued a per-service build ~45 min behind scans.)
   - **`openbank-deploy`** — ECR push + ArgoCD sync, **post-merge only** (push to
     `main`/tags). Its own pod ServiceAccount carries **IRSA scoped to exactly ECR
     push + the ArgoCD action**; PR jobs can never schedule onto it. Replaces the
     OIDC→IRSA persistent-deploy-pool design of ADR-0082 with the same guarantee on
     an ephemeral pod. (`pr_jobs_allowed_pools` is therefore `[build, batch]` — both
     credential-free — and explicitly excludes deploy.)

2. **Scale-to-zero, spot-diversified.** Runner pods schedule onto Karpenter's
   spot-first arm64 NodePool (or a dedicated, tainted runner NodePool); Karpenter
   picks the cheapest/most-available instance per scale-up and consolidates to zero
   when idle. `instance-interruption` is handled by Karpenter, not a persistent
   spot request — this also removes the "persistent spot request restarts a
   manually-stopped box" foot-gun observed on the legacy EC2 runner.

3. **Cold-start tax is mitigated, not ignored:**
   - **Remote Gradle build cache (S3)** + **Docker layer cache**, so a fresh pod
     reuses compiled task outputs and image layers instead of rebuilding cold.
   - Karpenter `consolidateAfter` keeps a node warm briefly, so closely-spaced jobs
     in a burst reuse the node (warm OS/page cache, pulled images).
   - `minRunners` is tunable >0 if a measured p95 queue-time SLO is missed — an
     explicit lever. *Engaged 2026-06-01:* build keeps **1 warm runner** after a
     ~45-min queue event (SLO miss) took it off the critical merge path; batch and
     deploy stay at 0 (true scale-to-zero).

4. **Availability as an SLO, no human/host SPOF.** Merge-required jobs run on the
   `openbank-build` scale set; EKS multi-AZ + Karpenter is the failure domain, not a
   laptop or a single EC2. Developer Macs may remain as *opt-in accelerators* but are
   never in the required-check critical path.

5. **Governed as code (ADR-0029).** ARC controller + scale sets are OpenTofu
   (`envs/sandbox-platform`, already scaffolded behind `arc_runner_enabled`). The
   GitHub App private key is created out-of-band and lives only as a k8s secret —
   never in tofu state. `rules.yaml: ci_runners` is updated to this model and CI
   enforces the PR-jobs-may-not-target-deploy rule.

6. **Clock** remains a governed control: pods inherit node NTP (the node is
   NTP-synced); a skewed node is replaced by Karpenter rather than stamping builds
   from a bad clock.

## Alternatives considered

- **Keep ADR-0082 (two persistent pools, always-on warm EC2).** Best raw build
  speed (warm daemon/test-infra). Rejected: pays idle 24/7, cannot exploit spot
  pricing, introduces a single-EC2 SPOF, and the warm-cache edge is partially
  recoverable via remote cache — whereas its cost/availability defects are not.
- **On-demand stop/start scale-to-zero (single box).** Rejected on measured data:
  at this CI frequency the 5× on-demand hourly rate exceeds the idle saving, making
  it *more* expensive than always-on spot — worst of both.
- **philips-labs ephemeral EC2 spot fleet (B2).** Equivalent isolation (fresh VM
  per job, host Docker, no dind). Rejected: would add a net-new VPC + Lambda/APIGW
  webhook surface and a second autoscaler, while EKS + Karpenter (already paid for,
  already spot-shopping) make ARC the lower marginal-cost, lower-net-new-infra path.

## Consequences

**Positive**
- ~4× lower CI compute cost (idle → $0); spot-diversified, interruption-resilient.
- Strongest per-job isolation; deploy credentials unreachable from PR code (ADR-0030).
- Removes both the laptop SPOF and the single-EC2 SPOF (availability / DORA).
- Reuses existing EKS + Karpenter — no net-new control plane, VPC, or autoscaler.

**Negative**
- Cold-start tax on the ~30-module fan-out; mitigated (remote cache, node reuse,
  optional warm `minRunners`) but builds may be slower than warm-daemon persistent.
- Build pod for image builds needs rootless BuildKit/Kaniko wiring (one-time).
- A GitHub App must be created and its key stored as a secret (one-time, owner-only).

**Neutral**
- Macs survive as optional accelerators, not required-check infra.
- The legacy `modules/runner` EC2 + persistent-pool design is retired but kept in
  history; the deploy-pool escape-hatch framing of ADR-0082 is now the default.

## Compliance impact

- DORA: removes single points of failure in the change pipeline; runner
  availability becomes an SLO backed by multi-AZ EKS rather than one host/laptop.
- Supply-chain (ADR-0030): per-job ephemerality + non-privileged builds + a
  PR-jobs-cannot-assume-deploy-IRSA boundary strengthen least-privilege.
- PCI/GDPR/PSD2: unchanged (no CDE/personal data on runners).
- CNB: build/audit timestamps remain trustworthy (NTP-synced nodes; skew → node
  replacement) supporting reproducible release evidence.

## References

- ADR-0027 — cloud-agnostic substrate, EKS + Karpenter, ArgoCD-owned state.
- ADR-0029 — governance as code (derive / enforce / show).
- ADR-0030 — supply-chain & threat-model gates.
- ADR-0040 — CI execution model and cost ($0 hosted minutes) — cost goal retained.
- ADR-0043 — CI performance model (warm reuse) — superseded for the default path;
  warm reuse becomes the optional `minRunners`/node-reuse lever.
- ADR-0082 — persistent trust-tiered pools — **superseded by this ADR**.
- `openbank-infra/aws/envs/sandbox-platform` — ARC controller + scale sets (tofu).
- `openbank-libs/governance/rules.yaml` — `ci_runners` section (this ADR).
</content>
</invoke>
