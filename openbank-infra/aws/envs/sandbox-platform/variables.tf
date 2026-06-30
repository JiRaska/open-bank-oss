variable "cert_manager_version" {
  type    = string
  default = "v1.16.2"
}

variable "karpenter_version" {
  description = "Karpenter Helm chart / app version (OCI public.ecr.aws/karpenter/karpenter)."
  type        = string
  # Must track the EKS control-plane version: Karpenter validates K8s compat at
  # startup and PANICS (CrashLoopBackOff) on a mismatch. The control plane moved
  # to 1.34 (#197) while this stayed 1.1.1 (max K8s 1.31), so Karpenter crash-
  # looped ~2h: no consolidation (idle on-demand nodes lingered) and no
  # provisioning (CI runner pods stuck Pending). Compatibility matrix: K8s 1.34
  # needs Karpenter >= 1.6 (karpenter.sh/docs/upgrading/compatibility). 1.12.1 is
  # the latest stable (min K8s 1.29, supports through >=1.35) so 1.34 is well
  # within range; the v1 CRDs are unchanged so the controller-only upgrade clears
  # the panic without CRD surgery. Bump this in lockstep whenever
  # envs/sandbox-substrate raises the cluster version.
  default = "1.12.1"
}

variable "argocd_version" {
  description = "argo-cd Helm chart version."
  type        = string
  default     = "9.5.21"
}

variable "cnpg_version" {
  description = "CloudNativePG operator Helm chart version (cnpg/cloudnative-pg; chart 0.28.2 = operator 1.29.1)."
  type        = string
  default     = "0.28.2"
}

variable "arc_controller_version" {
  description = "gha-runner-scale-set-controller Helm chart version."
  type        = string
  default     = "0.9.3"
}

variable "keda_version" {
  description = "KEDA Helm chart / app version (kedacore/keda). Chart version == appVersion."
  type        = string
  # Scale-to-zero controller for the FinOps workload tiers (ADR-0057). Unlike
  # Karpenter, KEDA does NOT panic on a K8s version skew: it drives the stable
  # autoscaling/v2 HPA API and its own CRDs, so it tolerates a control plane
  # ahead of its tested matrix. 2.19 is the latest stable and runs cleanly on
  # the cluster's K8s 1.34. Still worth tracking the EKS version on upgrades.
  default = "2.19.0"
}

# ---------------------------------------------------------------------------
# ARC runner scale set. Disabled by default: it needs a GitHub App credential
# that only the repo owner can mint in the GitHub UI. Once the App exists and
# its secret is created (see arc-runners.tf), flip this to true and apply.
# ---------------------------------------------------------------------------
variable "arc_runner_enabled" {
  type = bool
  # Flipped true 2026-06-01: GitHub App + arc-github-app secret created (ADR-0053).
  default = true
}

variable "github_config_url" {
  description = "GitHub repo the ARC runners register against."
  type        = string
  default     = "https://github.com/JiRaska/open-bank-oss"
}

variable "arc_min_runners" {
  description = "Warm runners kept on the openbank-build (PR-gating) scale set."
  type        = number
  # Raised 0 -> 1 (2026-06-01): rules.yaml ci_runners.warm_min_runners_lever permits minRunners>0
  # "only if a measured p95 queue SLO is missed" — a per-service build sat queued ~45 min behind a
  # concurrent push/PR workflow burst, which is a miss. One warm runner removes the Karpenter
  # cold-start (~1-2 min) from the critical merge path. Only the build set reads this var; batch and
  # deploy stay at 0 (true scale-to-zero). Idle cost = one small Graviton spot node.
  # Raised 0 -> 2 (2026-06-13, FinOps): warm node kept dind cached to eliminate NAT spikes.
  # Lowered 2 -> 0 (2026-06-13): ECR pull-through cache (PR #926) now serves docker:dind
  # from private ECR via the ecr.dkr VPC endpoint — zero NAT regardless of node warmth.
  # Warm pool rationale gone; runners-warm NodePool (arc-runners.tf) also set to 0 min.
  # Karpenter cold-start latency (~1-2 min) returns to the merge path — acceptable given
  # the cost saving (~$112/month for 2× c6g.xlarge on-demand).
  default = 0
}

variable "arc_max_runners" {
  description = "Max concurrent runner pods in the openbank-build scale set."
  type        = number
  # Raised 4 -> 8 (2026-06-01), 8 -> 12 (2026-06-04): with minRunners 0 /
  # scale-to-zero (ADR-0053) a higher cap is FinOps-neutral at idle (idle runners
  # cost $0) and only widens burst concurrency. At 8 a full-fleet PR backlog still
  # queued behind the cap; 12 drains it faster. Bounded by the runners NodePool
  # cpu limit (64) — 12 build + 4 batch runners ≈ 48 vCPU of pod requests, within
  # limit. Karpenter provisions the extra spot nodes on demand and consolidates
  # them away when the queue drains.
  # Lowered 12 -> 6 (2026-06-05, FinOps): each runner = 1× r8g.xlarge spot +
  # up to ~10 GB NAT traffic (Gradle deps, pre-cache-fix). 12 concurrent runners
  # caused 10-20 GB/h NAT peaks. 6 halves the burst cost while still draining a
  # 30-service fleet build in ~2 batches.
  # Raised 3 -> 6 (2026-06-13, FinOps): CodeArtifact IRSA is now wired (arc-runners.tf
  # openbank-build-runner SA, applied 2026-06-10). Maven Central deps route in-VPC via
  # CodeArtifact's S3 Gateway endpoint — no NAT charge. The original throttle (3 runners
  # to cap NAT at ~6 GB/h) is no longer necessary. 6 runners drains a full-fleet build in
  # ~2 batches vs ~3, shaving ~10 min off merge-to-green. Cap at 6 (not 12): each warm
  # runner keeps the on-demand node from consolidating; more than 6 would spill onto many
  # spot nodes and negate the warm-pool benefit.
  default = 6
}

variable "arc_batch_max_runners" {
  description = "Max concurrent runner pods in the openbank-batch scale set (non-blocking PR checks + weekly cron: security/secret scans, finops audit, label sync). Capped low so a batch burst cannot starve the build lane."
  type        = number
  default     = 4
}

variable "arc_deploy_max_runners" {
  description = "Max concurrent runner pods in the openbank-deploy scale set (post-merge ECR push + ArgoCD; low concurrency)."
  type        = number
  default     = 2
}

# ---------------------------------------------------------------------------
# Stuck-runner reaper (ADR-0053 self-heal guard, see arc-runner-reaper.tf).
# ---------------------------------------------------------------------------
variable "arc_reaper_schedule" {
  description = "Cron schedule for the ARC stuck-runner reaper. Frequent enough that a leaked node is reclaimed within ~one window past the idle threshold; cheap (one tiny pod per run)."
  type        = string
  default     = "*/10 * * * *"
}

variable "governance_gh_pat" {
  description = "Fine-grained GitHub PAT used by the github provider to manage branch protection and environments (issue #282). Pass via TF_VAR_governance_gh_pat in CI; never stored in state. Required scopes: Contents:read + Administration:write + Environments:write on JiRaska/open-bank-oss."
  type        = string
  sensitive   = true
}

variable "arc_reaper_idle_threshold_minutes" {
  description = "A Running+jobless EphemeralRunner beyond its scale set's minRunners is reaped only after it has been idle this long. Must exceed the longest CI job (1-31 min, ADR-0053) so a healthy burst's not-yet-assigned runners are never killed."
  type        = number
  default     = 30
}

variable "keda_http_add_on_version" {
  description = "KEDA HTTP add-on Helm chart version (kedacore/keda-add-ons-http). The add-on installs the interceptor and HTTPScaledObject CRD that enable T1 (HTTP → 0) scaling without a synchronous-caller 5xx — the interceptor parks the first request while the deployment scales 0 → 1. Required by ADR-0083 pilot (product-catalog T1)."
  type        = string
  # 0.15.0: gcr.io/kubebuilder/kube-rbac-proxy sidecar removed (GCR defunct for this image);
  # label selectors changed between 0.10 and 0.15 — requires uninstall+reinstall, not upgrade.
  # 0.15.x is compatible with KEDA 2.19 per the upstream compatibility matrix.
  default = "0.15.0"
}
