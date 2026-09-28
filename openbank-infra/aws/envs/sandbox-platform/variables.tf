variable "cert_manager_version" {
  type = string
  # One minor at a time, latest patch each (cert-manager upgrade guide), toward
  # 1.21 — the first line supporting EKS 1.36 (#10893):
  # v1.16.2 -> v1.17.4 -> v1.18.6 -> v1.19.6 -> v1.20.4 -> v1.21.2, each step
  # merged AND applied (Platform OpenTofu workflow_dispatch) before the next.
  # crds.enabled=true, so the chart upgrades the CRDs with each step.
  #
  # v1.18: default privateKey.rotationPolicy flips Never -> Always. Reviewed
  # every Certificate/ClusterIssuer in this tree (#11146): the root CA
  # (openbank-sandbox-ca, gitops/components/platform/clusterissuer.yaml) is
  # already pinned rotationPolicy: Never explicitly (#11124); the three
  # ingress-shim Certificates with mobile SPKI pinning (customer.open-bank.tech
  # /customer-edge, kc.open-bank.tech/keycloak, rum.open-bank.tech/rum-gateway,
  # the last fixed in #11152) already carry the
  # cert-manager.io/private-key-rotation-policy: "Never" annotation. The other
  # Certificates are internal mTLS leaves trusted via the openbank-ca chain,
  # not pinned to a leaf public key, so picking up the new Always default on
  # renewal is harmless for them.
  #
  # v1.19 (#11157): install v1.19.1+ only (v1.19.0 has a known unexpected-
  # renewal bug, github.com/cert-manager/cert-manager/issues/8158, fixed in
  # 1.19.1) — v1.19.6 is the latest 1.19.x patch. ACME client metrics dropped
  # the high-cardinality `path` label for a bounded `action` label (n/a: this
  # tree has no dashboard/alert referencing `acme_client_request_*{path=...}`,
  # grepped fleet-wide). The `cert-manager-edit` ClusterRole loses create on
  # challenges.acme.cert-manager.io and create/patch/update on
  # orders.acme.cert-manager.io as of 1.19.6 (security hardening) — n/a: no
  # RBAC binding in this tree references cert-manager-edit or creates those
  # resources directly; only the controller itself does, via its own
  # ClusterRole. No Certificate/ClusterIssuer API field removals/renames, no
  # Helm values shape change for our `helm_release.cert_manager` block.
  #
  # v1.20 (#11158): v1.20.4 is the latest 1.20.x patch (checked GitHub
  # releases 2026-09-27: v1.20.0..v1.20.4, no later tag). Supported Kubernetes
  # range is 1.32-1.35 (cert-manager.io/docs/releases) — our sandbox control
  # plane is 1.35, in range. v1.20.3 restates the same cert-manager-edit
  # ClusterRole restriction noted above (GHSA-8rvj-mm4h-c258) — still n/a,
  # same grep as v1.19. Default container UID/GID moves 1000/0 -> 65532/65532
  # (chart default, not pinned by any `set` here) — the cert-manager namespace
  # carries no pod-security-admission label (create_namespace=true, no PSA
  # annotation applied), so nothing enforces the old UID; n/a. The
  # DefaultPrivateKeyRotationPolicyAlways feature gate reaching GA (no longer
  # disable-able) only changes the *default* for a Certificate with no
  # explicit policy — it does not override the three annotation pins above,
  # which set the field directly. No CRD field removals/renames, no Helm
  # values shape change for our `helm_release.cert_manager` block, no new
  # required flags.
  #
  # v1.21 (#10893 step 5/5): v1.21.2 is the latest 1.21.x patch (checked
  # GitHub releases 2026-09-27: v1.21.0-alpha.0/.1, v1.21.0-beta.0, v1.21.0,
  # v1.21.1, v1.21.2, no later tag). Supported Kubernetes range is 1.33-1.36
  # (cert-manager.io/docs/releases) — the first line covering BOTH our
  # current sandbox control plane (1.35) and the #10893 target (1.36), so no
  # further minor bump is required once the cluster moves.
  #
  # Reviewed the v1.21.0 release notes, the v1.21.1/v1.21.2 patch notes and
  # the upgrade guide against this tree:
  # - Helm chart drops the default tokenrequest Role/RoleBinding
  #   ("serviceaccounts/token: create") — n/a, no `serviceAccountRef` in this
  #   tree points at the cert-manager controller ServiceAccount; every
  #   Issuer/ClusterIssuer here authenticates via CA secret, ACME account
  #   secret, or EKS Pod Identity (Route53), never a projected token bound to
  #   this SA.
  # - `cert-manager-edit` ClusterRole Challenge/Order restriction — same
  #   change already reviewed for v1.19.6/v1.20.4; still n/a (no binding
  #   references it, nothing here creates ACME challenges/orders directly).
  # - Helm values removed: `prometheus.servicemonitor.targetPort`,
  #   `prometheus.servicemonitor.path`, `prometheus.podmonitor.path`; metrics
  #   Service port renamed `tcp-prometheus-servicemonitor` -> `http-metrics`
  #   — n/a, `helm_release.cert_manager`'s `set` block here only configures
  #   `crds.enabled` and the three DNS-01 `extraArgs`; no `prometheus.*` key
  #   set, and no ServiceMonitor/PodMonitor manifest in `gitops/` selects the
  #   old port name (grepped fleet-wide).
  # - `enableGatewayAPI`/`enableGatewayAPIListenerSet` deprecated in favor of
  #   `gatewayAPI.enabled`/`gatewayAPI.enableListenerSet` (backward
  #   compatible) — n/a, no Gateway API resources in this tree (all 4
  #   ClusterIssuers are DNS-01/CA only) and neither old nor new key is set.
  # - `ServerSideApply` feature gate deprecated for cainjector (now
  #   unconditional) and `CAInjectorMerging` promoted GA — n/a, neither
  #   feature gate is referenced in `extraArgs` here.
  # - v1.21.2 (security patch): Go 1.26.8 + crypto/TLS/XML dependency bumps,
  #   ACME response bodies capped at 16 MiB and no longer reflected into
  #   Issuer status/Events, restricted ambient AWS credentials for
  #   *namespaced* Vault Issuers — n/a to the last one, this tree has no
  #   Vault issuer; the response-body and DoS fixes are strict hardening, no
  #   manifest change needed. Also fixes a DNS-name de-dup bug for Gateway
  #   listeners sharing a Secret (n/a, no Gateway API here) and an HTTP-01
  #   cleanup failure when the solver pod is already gone (n/a, DNS-01 only).
  # - Known v1.21.0 issues (controller crash-loop with
  #   `renewal.policy: Disabled`, stuck Issuer/ClusterIssuer reconciliation,
  #   ACME event log spam) are all fixed by v1.21.2, which is why this pins
  #   .2 rather than .0/.1 — checked no Certificate in this tree sets
  #   `renewal.policy: Disabled` (grepped fleet-wide; none do).
  # - No Certificate/ClusterIssuer/CertificateRequest CRD field
  #   removals/renames that this tree uses; the three SPKI-pinned mobile
  #   hosts (customer.open-bank.tech/customer-edge,
  #   kc.open-bank.tech/keycloak, rum.open-bank.tech/rum-gateway) keep their
  #   explicit `cert-manager.io/private-key-rotation-policy: "Never"`
  #   annotation, which no default-flip in any version can override (verified
  #   still present on `origin/main`). No Helm values shape change for our
  #   `helm_release.cert_manager` block beyond the removed `prometheus.*`
  #   keys (unused here), no new required flags.
  default = "v1.21.2"
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
  #
  # 1.12.1 -> 1.13.1 (#10893): EKS 1.36 needs Karpenter >= 1.13, and 1.13.1
  # still supports 1.35, so this lands BEFORE the control-plane bump — the order
  # that avoids a repeat of the crashloop above (controller never behind the
  # control plane). Upgrade guide: no breaking changes in 1.13. The CRDs DID
  # change (additive EC2NodeClass fields, list/map-type markers), and Helm never
  # upgrades crds/, so they are applied from karpenter-crds/<version>/ ahead of
  # the chart — a new version needs that directory vendored alongside this bump,
  # or the plan fails (a precondition on helm_release.karpenter) rather than
  # silently skipping the CRDs.
  default = "1.13.1"
}

variable "argocd_version" {
  description = "argo-cd Helm chart version."
  type        = string
  # 10.9.2 = Argo CD v3.5.3 (tested on K8s 1.33-1.36). Chart 10.x defaults
  # global.networkPolicy.create=true; main.tf pins it false (see there).
  default = "10.9.2"
}

variable "cnpg_version" {
  description = "CloudNativePG operator Helm chart version (cnpg/cloudnative-pg; chart 0.29.1 = operator 1.30.1)."
  type        = string
  # 1.30.x supports Kubernetes 1.34-1.36; 1.29.x (1.33-1.35) is EOL 2026-09-29.
  # 1.30 is the LAST minor with in-tree `barmanObjectStore` backups (removed in
  # 1.31): moving past 1.30 requires the Barman Cloud plugin migration first.
  default = "0.29.1"
}

variable "arc_controller_version" {
  description = "gha-runner-scale-set-controller Helm chart version."
  type        = string
  # Raised 0.9.3 -> 0.14.2 (2026-07-15): 0.9.3 (2024-06-25) predates a real upstream bug
  # (actions/actions-runner-controller#4091, "EphemeralRunner stuck in failed state if the
  # job it was allocated to is cancelled") fixed by #4239/#4260, first shipped in 0.13.0
  # (2025-10-16). Live-confirmed on this cluster: when a matrix job's assignment was
  # cancelled/superseded, the controller recreated a NEW pod bound to the SAME stale
  # EphemeralRunner CR/job claim rather than releasing it — surviving `kubectl delete pod`,
  # a listener restart, and a controller restart; only deleting the EphemeralRunner CR
  # itself (with a finalizer strip when it hung) actually cleared it. Took the latest
  # stable (0.14.2, 2026-05-22) rather than stopping at the minimum fix version — no
  # BREAKING/migration/CRD-manual-step notes in 0.13.1-0.14.2's release notes.
  default = "0.14.2"
}

variable "keda_version" {
  description = "KEDA Helm chart / app version (kedacore/keda). Chart version == appVersion."
  type        = string
  # Scale-to-zero controller for the FinOps workload tiers (ADR-0057). Unlike
  # Karpenter, KEDA does NOT panic on a K8s version skew: it drives the stable
  # autoscaling/v2 HPA API and its own CRDs, so it tolerates a control plane
  # ahead of its tested matrix. Still track the EKS version on upgrades:
  # support matrix 2.19 = K8s 1.32-1.34, 2.20 = 1.33-1.35, 2.21 = 1.34-1.36.
  # 2.21 is the only release covering both the current 1.35 and the 1.36 target.
  default = "2.21.0"
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
  # TEMP raised 6 -> 8 (2026-07-15): ~55 concurrent agent branches drove a ~97-run,
  # 21h+-old backlog on `openbank-build` — services-ci.yml intentionally never cancels
  # a push-to-main lane (per-SHA concurrency group, issue #846), so none of that queue
  # is stale/cancellable, it's genuine backlog. NAT is no longer the constraint (see
  # 2026-06-13 note above) and minRunners stays 0, so this doesn't add idle cost — it
  # only shortens how long the existing backlog takes to drain.
  # TEMP raised 8 -> 12 (2026-07-15, same incident): the backlog turned out to be 229
  # individual openbank-build-labeled jobs (96 Services CI runs x their per-service
  # matrix), not ~97 — at 8 runners that's still a ~1-2h drain. 12 is the same value
  # already validated safe on 2026-06-04 (bounded by the runners NodePool cpu limit of
  # 64: 12 build + 0 batch runners, well within limit now that batch is at 0).
  # Reverted 12 -> 6 (2026-07-15, same incident, hours later): a separate GitHub Actions
  # Runner Scale Set bug surfaced mid-drain — the backend keeps redelivering stale
  # "job available" offers for matrix slots that were fail-fast-cancelled before ever
  # being created, so a large fraction of runner slots sit idle holding a phantom claim
  # instead of doing real work. Confirmed this survives pod delete, listener restart,
  # controller restart, and a full EphemeralRunner purge — it is NOT fixable from this
  # side (GitHub-side queue state), and a bigger pool does not mitigate it: more slots
  # just means more concurrent phantom holds, not more real throughput. Root cause
  # #1149 already stops new inert-push waste at the source, so paying for 12 idle-
  # capable spot runners no longer buys a proportional drain-speed benefit. Back to 6.
  #
  # Raised 6 -> 12 (2026-08-09, #4317 / ADR-0250). The 2026-07-15 revert above rested on
  # the phantom-claim bug, and that premise is gone: it was root-caused in #1152 to ARC
  # 0.9.3 and fixed by the arc_controller_version bump to 0.14.2, which is what runs.
  # Verified rather than assumed — all six runner pods were confirmed to be executing
  # real jobs (WORKER frames in the runner container log) while the listener reported
  # `"assigned job"=6 decision=6 min=0 max=6`. So the pool is genuinely saturated, not
  # holding phantoms, and extra slots now buy proportional throughput.
  # Measured backlog at the time of the change: 42 queued Services CI runs, p50 age 7.0h,
  # max 23.4h, per-build queue p50 85 min against 4-11 min of execution.
  # FinOps: this is close to cost-NEUTRAL, because raising concurrency does not raise
  # total runner-hours — the same ~17.4 runner-h/day of build work runs on more nodes for
  # less wall-clock (the only delta is per-node cold-start overhead, and the ECR
  # pull-through cache made that cheap). It is funded several times over by retiring
  # runners-warm in the same PR (~$197/month). Bounded by the runners NodePool cpu limit
  # of 64: 12 build x 4 vCPU = 48, plus 0 warm now, within limit.
  default = 12
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

variable "arc_batch_max_runners" {
  description = "Max concurrent runner pods in the openbank-batch scale set (weekly scans/fuzz/perf; ADR-0277)."
  type        = number
  default     = 4
}

variable "arc_dr_max_runners" {
  description = "Max concurrent runner pods in the openbank-dr scale set (quarterly DR/chaos lanes; ADR-0277)."
  type        = number
  default     = 1
}
