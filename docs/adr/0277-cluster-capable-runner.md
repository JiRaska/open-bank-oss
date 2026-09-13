---
date: 2026-09-03
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ci, gitops, finops, resilience]
summary: "Provision the declared-but-unbuilt openbank-batch ARC scale set, and add a least-privilege openbank-dr lane so DR-restore-verify and the chaos drill can finally run."
---

# ADR-0277 — Cluster-capable runner: provision the declared batch scale set and a DR lane

## Context

`rules.yaml: ci_runners.pools` declares three ARC scale sets — build, batch, deploy — and the
OpenTofu creates two. `openbank-batch` is declared with an isolation rationale and provisioned
nowhere, yet `infra_apply.runner_routing` and several weekly workflows (`api-fuzz.yml`,
`perf-gate.yml`) still target it. A job sent there sits `queued` forever, because "no runner has
taken this yet" and "no runner will ever exist" are the same state to every observer (#6458;
the `ci-runner-pools` gate now catches NEW occurrences, but the missing pool itself is still
missing).

The same capacity gap, one trust level up, blocks the resilience evidence program. Measured
2026-09-03, eleven open issues are `blocked` on a cluster-capable runner, not on engineering:

- #8347 / #4757 — `dr-restore-verify` cannot run its quarterly schedule: the recovery manifests
  landed (#4996) but no runner may touch the cluster (DORA evidence).
- #4755 — money-path chaos drill Scenario A has never run; RTO is unmeasured.
- #2365 — 69 TTL'd attestations (3/69 present) keep money-path services NO-GO; restore_drill
  and pentest attestations cannot be earned without a runner that reaches the cluster.
- #6458 — the batch pool itself; #6432's standing digest fails for a different reason
  (missing Alertmanager secrets) and is out of scope here.

Why now: the issue remediation program (docs/issues-2026-09-03-remediation-program.md, §3)
identified a single cluster-capable runner as the highest-leverage unblock in the entire
119-issue backlog, and the current state — declared capacity that does not exist — reads
exactly like capacity that does, to humans and to auditors.

## Decision

We will provision runner capacity in two lanes, preserving the ADR-0053 trust split and the
scale-to-zero FinOps model:

1. **Build the declared `openbank-batch` scale set** exactly as `rules.yaml: ci_runners.pools.batch`
   already specifies it (no cloud write credentials, no secrets, ephemeral per job, minRunners=0).
   This is not a new decision — it is making the infrastructure match a declaration CI already
   enforces. Weekly scan/fuzz/perf lanes stop queuing forever.
2. **Add an `openbank-dr` scale set** for the resilience lanes (`dr-restore-verify`, chaos drill,
   attestation evidence jobs): cluster-reachable, scheduled-workflow-only (never PR code — it
   joins `pr_jobs_allowed_pools`' exclusion list by construction), with a dedicated Kubernetes
   ServiceAccount scoped to the restore/verify namespaces (least privilege; Kyverno policy pins
   the runner pods to that SA). minRunners=0; it exists to be run quarterly, so idle cost is $0.
3. **Wire the unblock**: point `dr-restore-verify.yml` at `openbank-dr` on its quarterly cron
   (#8347), run chaos Scenario A against sandbox and record the measured RTO (#4755), and let
   the attestation lanes start earning the missing 66 TTL'd attestations (#2365).
4. `check-ci-runner-pools.py` already fails when a declared pool is unprovisioned — the gate
   stays red until this ADR is delivered, which is the intended forcing function.

## Alternatives considered

- **Route batch/DR jobs to the existing `openbank-deploy` pool.** Rejected: the build/deploy
  trust split is the load-bearing control (ADR-0053) — deploy carries ECR push and ArgoCD
  credentials, and neither batch scans nor DR jobs should share a pod template with those.
  #6458's gate (`pr_jobs_allowed_pools`) exists precisely to keep PR code off credentialled
  pools; the same logic applies to giving DR jobs deploy credentials they do not need.
- **GitHub-hosted runners + Tailscale-style cluster access.** Rejected: a hosted runner with
  cluster reachability punches the VPC boundary the ARC-on-EKS model exists to avoid, and the
  access credential would live outside the cluster's own identity story (ADR-0275/0276 cover
  human access; this is workload identity).
- **Delete the `openbank-batch` declaration instead and re-route its jobs to hosted runners.**
  Considered and kept as a fallback only for the batch lane: the fuzz/perf jobs do not need the
  cluster network. Rejected as the primary decision because the weekly fuzz lane authenticates
  against deployed services and the Perf baseline (issue #7311) is moving in-cluster (#6901);
  and it does nothing for the DR lane, which is the actual blocker for 11 issues.

## Consequences

**Positive**
- Eleven blocked issues get an unblock path; DORA restore evidence (#8347, #4757) and a measured
  RTO (#4755) become schedulable; money-path NO-GO attestation debt (#2365) starts burning down.
- Declared capacity and provisioned capacity become the same set — the #6458 class of
  "declared reads like provisioned" closes.
- Batch bursts (security scans, finops) stop competing with the merge-required build lane.

**Negative**
- Two more scale sets to operate (both minRunners=0; spot-only; expected idle cost $0, burst
  cost bounded by the pool caps).
- `openbank-dr` is a new credentialled lane: it needs a Kyverno policy, a scoped ServiceAccount,
  and a threat-model paragraph in the DR runbook before first use.

**Neutral**
- The standing critical-alert digest (#6432) is unaffected — its failure is missing Alertmanager
  secrets, a separate fix.

## Compliance impact

- PCI DSS: not applicable — CI runner topology touches no cardholder data environment.
- DORA:    positive — quarterly restore verification and chaos-drill RTO are the ICT resilience
           testing evidence this platform currently cannot produce (#4757, #4755).
- GDPR:    not applicable — runners process no personal data beyond what CI already handles.
- PSD2:    not applicable.
- CNB:     not applicable directly; the attestation backlog (#2365) feeds regulator-facing
           evidence, so delivery improves the audit posture.

## References

- rules.yaml: `ci_runners` (pools, trust split, routing)
- ADR-0053 (ARC scale-to-zero FinOps model), ADR-0144 (gate graduation forcing function)
- Issues: #6458, #8347, #4757, #4755, #2365, #6901, #7311
- docs/issues-2026-09-03-remediation-program.md (cluster E, wave 1 lever)
- Gate: `.github/scripts/check-ci-runner-pools.py`
