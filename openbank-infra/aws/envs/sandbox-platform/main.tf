# ---------------------------------------------------------------------------
# Platform root (day-2). The substrate (envs/sandbox-substrate) builds the bare
# EKS + IAM; this root installs the in-cluster operators that ADR-0027 says own
# everything stateful/identity. ArgoCD is seeded here and then becomes the owner
# of all further app-of-apps state — this root stays intentionally small.
# ---------------------------------------------------------------------------

locals {
  karpenter_node_role_name      = local.s.karpenter_node_role_name
  karpenter_controller_role_arn = local.s.karpenter_controller_role_arn
  karpenter_queue_name          = local.s.karpenter_interruption_queue_name
  node_security_group_id        = local.s.node_security_group_id
  private_subnet_ids            = local.s.private_subnet_ids
}

# ---------------------------------------------------------------------------
# cert-manager — base dependency for webhook/serving certs used by other
# operators. Installed with its CRDs.
# ---------------------------------------------------------------------------
resource "helm_release" "cert_manager" {
  name             = "cert-manager"
  namespace        = "cert-manager"
  create_namespace = true
  repository       = "https://charts.jetstack.io"
  chart            = "cert-manager"
  version          = var.cert_manager_version

  # helm provider v3: set{} blocks -> a single set=[...] list argument.
  set = [
    {
      name  = "crds.enabled"
      value = "true"
    },
    # Keep controller off Spot churn: it tolerates the bootstrap on-demand pool.
    {
      name  = "extraArgs[0]"
      value = "--enable-certificate-owner-ref=true"
    },
    # DNS-01 self-check via public recursive resolvers, not the authoritative-NS
    # walk. open-bank.tech was freshly un-delegated from serverHold (2026-06); the
    # default authoritative self-check (determineAuthoritativeNameservers) stalls
    # on the just-changed delegation chain and reports "not yet propagated" forever
    # even though the _acme-challenge TXT is verifiably live on Route53 and every
    # public resolver. Pinning recursive-only + Google/Cloudflare makes cert-manager
    # confirm propagation the same way Let's Encrypt will, and issuance proceeds.
    {
      name  = "extraArgs[1]"
      value = "--dns01-recursive-nameservers-only=true"
    },
    {
      name = "extraArgs[2]"
      # commas escaped so Helm --set keeps this one list element, not three.
      value = "--dns01-recursive-nameservers=8.8.8.8:53\\,1.1.1.1:53"
    },
  ]
}

# ---------------------------------------------------------------------------
# Karpenter — node autoscaler. Controller auth is EKS Pod Identity (association
# created in the substrate root), so the ServiceAccount needs no IRSA
# annotation; only the name must match ("karpenter").
# ---------------------------------------------------------------------------
resource "helm_release" "karpenter" {
  name       = "karpenter"
  namespace  = "kube-system"
  repository = "oci://public.ecr.aws/karpenter"
  chart      = "karpenter"
  version    = var.karpenter_version

  set = [
    # Single replica for sandbox FinOps; prod should run 2 for HA.
    {
      name  = "replicas"
      value = "1"
    },
    {
      name  = "serviceAccount.name"
      value = "karpenter"
    },
    {
      name  = "settings.clusterName"
      value = local.cluster_name
    },
    {
      name  = "settings.interruptionQueue"
      value = local.karpenter_queue_name
    },
    # Auto-replace nodes stuck NotReady (guest-level hang passes EC2 status checks;
    # EKS node auto repair covers only the bootstrap managed node group). Issue #809.
    {
      name  = "settings.featureGates.nodeRepair"
      value = "true"
    },
    # Controller must run on the bootstrap managed nodes, never on nodes it owns.
    {
      name  = "controller.resources.requests.cpu"
      value = "500m"
    },
    {
      name  = "controller.resources.requests.memory"
      value = "512Mi"
    },
    {
      name  = "controller.resources.limits.cpu"
      value = "1"
    },
    {
      name  = "controller.resources.limits.memory"
      value = "1Gi"
    },
  ]
}

# ---------------------------------------------------------------------------
# Karpenter provisioning policy. Applied as raw CRs via the kubectl provider so
# the CRDs (installed by the Karpenter chart above) don't need to exist at plan
# time. Graviton-only, Spot-first, aggressive consolidation.
# ---------------------------------------------------------------------------
resource "kubectl_manifest" "ec2nodeclass_default" {
  depends_on = [helm_release.karpenter]

  yaml_body = yamlencode({
    apiVersion = "karpenter.k8s.aws/v1"
    kind       = "EC2NodeClass"
    metadata   = { name = "default" }
    spec = {
      amiFamily = "AL2023"
      amiSelectorTerms = [
        { alias = "al2023@latest" }
      ]
      # Eviction headroom against the no-swap memory-reclaim livelock (issue
      # #809): with the AMI default (evictionHard memory.available<100Mi, 10s
      # cadence) a memory spike outruns kubelet eviction and the kernel
      # livelocks — kubelet + SSM starve while EC2 status checks stay ok, the
      # node lingers NotReady, singleton pods strand. Evict pods well before
      # that point instead; Karpenter also subtracts this from allocatable, so
      # bin-packing gets honest. NOTE: any change here drifts every node of
      # this class → Karpenter rolls them per the NodePool disruption budgets.
      kubelet = {
        evictionHard              = { "memory.available" = "300Mi" }
        evictionSoft              = { "memory.available" = "500Mi" }
        evictionSoftGracePeriod   = { "memory.available" = "60s" }
        evictionMaxPodGracePeriod = 60
      }
      role = local.karpenter_node_role_name
      subnetSelectorTerms = [
        { tags = { "karpenter.sh/discovery" = local.cluster_name } }
      ]
      securityGroupSelectorTerms = [
        { id = local.node_security_group_id }
      ]
      tags = {
        "karpenter.sh/discovery" = local.cluster_name
        Project                  = "openbank"
        ManagedBy                = "karpenter"
      }
      # Containerd mirror config — redirects public registry pulls to in-VPC
      # endpoints, eliminating NAT gateway charges on every new node.
      #
      # docker.io  → in-cluster registry-cache (ClusterIP 172.20.188.54:5000).
      #              ClusterIP is reachable from the node host once kube-proxy
      #              sets up iptables rules; image pulls happen after node joins,
      #              so the timing is safe. No Docker Hub credentials needed.
      #
      # quay.io / ghcr.io / registry.k8s.io / public.ecr.aws → ECR pull-through
      #              cache (ecr-pull-through-cache.tf). ECR fetches upstream
      #              server-side; subsequent pulls are served from private ECR
      #              via the ecr.dkr VPC Interface endpoint — zero NAT.
      # userData intentionally omitted: AL2023 nodeadm bootstrap is sensitive to
      # cloud-config merges and nodes fail to register when userData is set.
      # docker.io mirror (registry-cache) wiring is deferred — a separate
      # MIME-multipart approach or DaemonSet-based config is needed.
    }
  })
}

resource "kubectl_manifest" "nodepool_default" {
  depends_on = [kubectl_manifest.ec2nodeclass_default]

  yaml_body = yamlencode({
    apiVersion = "karpenter.sh/v1"
    kind       = "NodePool"
    metadata   = { name = "default" }
    spec = {
      template = {
        spec = {
          requirements = [
            { key = "kubernetes.io/arch", operator = "In", values = ["arm64"] },
            { key = "kubernetes.io/os", operator = "In", values = ["linux"] },
            { key = "karpenter.sh/capacity-type", operator = "In", values = ["spot", "on-demand"] },
            { key = "karpenter.k8s.aws/instance-category", operator = "In", values = ["c", "m", "r"] },
            { key = "karpenter.k8s.aws/instance-generation", operator = "Gt", values = ["5"] },
            # Cap at 4xlarge: DaemonSet overhead (Alloy + Falco + node-exporter +
            # kube-proxy + aws-node ≈ 350m CPU / 400Mi RAM) justifies large minimum.
            # Without an upper bound Karpenter picked c6g.12xlarge (48 vCPU spot) for
            # ~20 pods — grossly over-provisioned and expensive even at spot pricing.
            # large–4xlarge gives good bin-packing while keeping per-node cost sane.
            { key = "karpenter.k8s.aws/instance-size", operator = "In", values = ["large", "xlarge", "2xlarge", "4xlarge"] }
          ]
          nodeClassRef = {
            group = "karpenter.k8s.aws"
            kind  = "EC2NodeClass"
            name  = "default"
          }
          expireAfter = "720h"
        }
      }
      disruption = {
        consolidationPolicy = "WhenEmptyOrUnderutilized"
        consolidateAfter    = "1m"
        # FinOps: freeze node replacement 20:00–07:00 UTC (overnight).
        # Karpenter consolidation on idle nodes churn image pulls from ghcr.io /
        # quay.io over NAT (~100 GB/night, $4-5) because containerd on a fresh
        # node pulls DaemonSet images before Kyverno ECR-rewrite is active.
        # During the freeze window 0 nodes may be disrupted; outside it, up to 50%.
        budgets = [
          # Standard 5-field cron: no disruption 20:00–07:00 UTC daily
          { schedule = "0 20 * * *", duration = "11h", nodes = "0%" },
          { nodes = "50%" },
        ]
      }
      # Hard cap against runaway provisioning: without it Karpenter once
      # provisioned 14× c6g.12xlarge (~$235/day) with no warning.
      # 32 → 48 vCPU (issue #809): the 32 cap was calibrated to the fleet's
      # OLD, understated memory requests. Right-sizing them (#819) plus the
      # kubelet eviction headroom (#820, subtracted from allocatable) raised
      # declared demand, and the pool pinned at exactly 32/32 CPU — Karpenter
      # then could NOT provision a node for the (2560Mi-request) ArgoCD
      # application-controller ("all available instance types exceed limits"),
      # leaving the deploy backbone Pending and stalling the drift roll. 48
      # gives the roll surge + honest requests room while still capping
      # runaway cost.
      limits = {
        cpu    = "48"
        memory = "128Gi"
      }
    }
  })
}

# ---------------------------------------------------------------------------
# ArgoCD seed. Once up, ArgoCD owns all further platform/app state via
# app-of-apps; this Terraform release is just the bootstrap install.
# ---------------------------------------------------------------------------
resource "helm_release" "argocd" {
  name             = "argocd"
  namespace        = "argocd"
  create_namespace = true
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-cd"
  version          = var.argocd_version

  set = [
    # Sandbox: keep it lean. No HA, no dex (SSO wired later via ADR-0031).
    {
      name  = "dex.enabled"
      value = "false"
    },
    {
      name  = "notifications.enabled"
      value = "false"
    },
    # Server-Side Diff, cluster-wide. ServerSideApply=true (our default sync
    # option) otherwise triggers ArgoCD's *Structured-Merge* diff, which builds a
    # typed value from the live object using ArgoCD's BUNDLED OpenAPI schema. On
    # k8s >=1.33 that schema lacks fields the API server adds (e.g. Deployment
    # `.status.terminatingReplicas`) → "field not declared in schema" →
    # ComparisonError → auto-sync/selfHeal silently stop for every SSA app.
    # Server-Side Diff instead computes the diff from an SSA dry-run against the
    # LIVE API server, so it uses the cluster's own (current) schema and the field
    # is known. Successor strategy to structured-merge; same trick `vault` already
    # uses per-app via the ServerSideDiff=true compare-option. Avoids a major
    # ArgoCD upgrade and keeps ServerSideApply for the sync step.
    {
      name  = "configs.params.controller\\.diff\\.server\\.side"
      value = "true"
    },
    # Keep Karpenter from consolidating the node out from under the singleton
    # application-controller. Root cause of a ~7h ArgoCD outage (2026-07-11): the
    # controller's node was Evicted "Underutilized" by Karpenter, went NotReady,
    # and the StatefulSet pod (ordinal 0, at-most-one) got stuck Terminating — so
    # no app synced for hours (deploys silently stopped updating). `do-not-disrupt`
    # tells Karpenter not to voluntarily drain a node running this pod; the
    # priority class keeps it from being preempted. Same footgun class as the
    # DaemonSet/critical-pod priority note in CLAUDE.md.
    {
      name  = "controller.podAnnotations.karpenter\\.sh/do-not-disrupt"
      value = "true"
    },
    {
      name  = "controller.priorityClassName"
      value = "system-cluster-critical"
    },
    # The chart ships the controller with no resources at all (BestEffort). On the
    # bin-packed 4Gi spot nodes the controller repeatedly exhausted node memory
    # into a full guest hang: kubelet + SSM died while EC2 status checks stayed
    # ok, stranding the singleton pod (issue #809, 2026-07-11 — several such
    # nodes in one day, each dying minutes after this pod landed on it). Measured
    # live: the startup reconciliation of this fleet's apps blows through 2.5Gi
    # within a minute (an earlier 2560Mi limit OOM-killed it at 61s of age). The
    # request keeps it off the 4Gi shapes entirely; the limit turns a runaway
    # into a container OOM-kill (self-healing) instead of a node-killing kernel
    # reclaim livelock.
    {
      name  = "controller.resources.requests.cpu"
      value = "250m"
    },
    {
      name  = "controller.resources.requests.memory"
      value = "2560Mi"
    },
    {
      name  = "controller.resources.limits.memory"
      value = "3584Mi"
    },
  ]
}

# ---------------------------------------------------------------------------
# ArgoCD root Application (app-of-apps seed). Applied immediately after the
# ArgoCD Helm chart so that ArgoCD takes ownership of every Application under
# gitops/apps/ — from this point, all further infra changes flow through git,
# not direct kubectl (ADR-0027). The manifest is sourced from the repo rather
# than inlined here so a single `git push` drives both the bootstrap YAML and
# the Terraform that applies it.
# ---------------------------------------------------------------------------
resource "kubectl_manifest" "argocd_root_app" {
  depends_on = [helm_release.argocd]

  # server_side_apply prevents field-manager conflicts on re-apply: ArgoCD uses
  # SSA internally, so letting Terraform also drive the field with SSA avoids
  # spurious drift detection on subsequent plan runs.
  server_side_apply = true

  yaml_body = file("${path.module}/../../../gitops/bootstrap/root-app.yaml")
}

# ---------------------------------------------------------------------------
# Default StorageClass — gp3 via the EBS CSI driver (addon installed in the
# substrate). EKS ships only a legacy `gp2` class on the removed in-tree
# provisioner, and not marked default, so a PVC with no storageClassName (e.g.
# CNPG's) has nothing to bind to. This makes gp3/CSI the cluster default;
# WaitForFirstConsumer so the volume lands in the consuming pod's AZ.
# ---------------------------------------------------------------------------
resource "kubectl_manifest" "storageclass_gp3_default" {
  yaml_body = yamlencode({
    apiVersion = "storage.k8s.io/v1"
    kind       = "StorageClass"
    metadata = {
      name = "gp3"
      annotations = {
        "storageclass.kubernetes.io/is-default-class" = "true"
      }
    }
    provisioner          = "ebs.csi.aws.com"
    volumeBindingMode    = "WaitForFirstConsumer"
    allowVolumeExpansion = true
    parameters = {
      type      = "gp3"
      encrypted = "true"
    }
  })
}

# ---------------------------------------------------------------------------
# CloudNativePG operator. First stateful-platform operator per ADR-0027: it
# reconciles Postgres `Cluster` CRs (single-owner DBs co-located in their
# domain namespace, ADR-0037 R4). The operator is cluster-wide bootstrap (same
# tier as cert-manager/Karpenter); the Cluster CRs themselves are app state
# delivered later via ArgoCD. Unblocks the Apicurio SQL-storage go-live
# condition (ADR-0027) — Apicurio's Postgres is the operator's first consumer.
# ---------------------------------------------------------------------------
resource "helm_release" "cnpg" {
  name             = "cnpg"
  namespace        = "cnpg-system"
  create_namespace = true
  repository       = "https://cloudnative-pg.github.io/charts"
  chart            = "cloudnative-pg"
  version          = var.cnpg_version
}

# ---------------------------------------------------------------------------
# KEDA — scale-to-zero controller for the FinOps workload tiers (ADR-0057).
# Cluster-wide bootstrap operator (same tier as cert-manager/Karpenter/CNPG):
# it reconciles per-service `ScaledObject` CRs that scale a Deployment from/to
# zero on a measured trigger (Kafka consumer-group lag for T2 event consumers,
# HTTP for T1). The ScaledObjects themselves are app state delivered later via
# ArgoCD next to each service's manifests — this release is just the operator.
#
# Idle cost is the operator's own footprint only: the controller + the metrics
# adapter, both single-replica for sandbox FinOps (prod runs 2 for HA). They sit
# on the bootstrap on-demand pool with Karpenter's controllers; the whole point
# is that the *workloads* they manage consolidate to zero, so the net is a large
# negative — KEDA pays for itself the moment one always-on replica goes to zero.
#
# CRDs ship with the chart (crds.install defaults true). Cloud-agnostic: KEDA is
# OSS and runs on any conformant K8s, so ADR-0027 is preserved (no AWS FaaS).
# ---------------------------------------------------------------------------
resource "helm_release" "keda" {
  name             = "keda"
  namespace        = "keda"
  create_namespace = true
  repository       = "https://kedacore.github.io/charts"
  chart            = "keda"
  version          = var.keda_version

  set = [
    # Single replica each for sandbox FinOps; prod should raise both to 2 for HA.
    {
      name  = "operator.replicaCount"
      value = "1"
    },
    {
      name  = "metricsServer.replicaCount"
      value = "1"
    },
    # Keep the controller modest: it watches CRs and pokes HPAs, it is not a
    # data-plane component. Requests sized to co-tenant the bootstrap pool.
    {
      name  = "resources.operator.requests.cpu"
      value = "100m"
    },
    {
      name  = "resources.operator.requests.memory"
      value = "128Mi"
    },
    {
      name  = "resources.operator.limits.memory"
      value = "512Mi"
    },
  ]
}

# ---------------------------------------------------------------------------
# KEDA HTTP add-on — ADR-0083 T1 (HTTP → 0) pilot on product-catalog.
#
# The plain KEDA ScaledObject (above) handles T2 (event → 0): it reacts to a
# queue metric and is invisible to callers because Kafka reads are async. T1
# needs a different mechanism: when a deployment is at zero replicas, an inbound
# HTTP request arrives *before* any pod exists to serve it. The HTTP add-on
# installs an interceptor proxy that parks the first request and scales 0 → 1;
# once a pod is ready the request is forwarded (caller sees latency, not a 5xx).
#
# Interceptor HA: two replicas to avoid a single-pod SPOF on the first request.
# The interceptor is only fronting non-money-path, non-critical read services
# (ADR-0083 guardrail: money-path stays T0, never behind the interceptor).
#
# KEDA must be installed before the add-on (depends_on enforces ordering).
# ---------------------------------------------------------------------------
resource "helm_release" "keda_http_add_on" {
  name             = "keda-add-ons-http"
  namespace        = "keda"
  create_namespace = false # already created by helm_release.keda above
  repository       = "https://kedacore.github.io/charts"
  chart            = "keda-add-ons-http"
  version          = var.keda_http_add_on_version

  depends_on = [helm_release.keda]

  set = [
    # Two interceptor replicas: if the single interceptor pod is evicted the first
    # request to a scaled-to-zero workload fails; HA here is cheap (tiny pod).
    {
      name  = "interceptor.replicaCount"
      value = "2"
    },
    {
      name  = "scaler.replicaCount"
      value = "1"
    },
    # Resource sizing follows the same lean sandbox pattern as KEDA core.
    {
      name  = "interceptor.resources.requests.cpu"
      value = "50m"
    },
    {
      name  = "interceptor.resources.requests.memory"
      value = "64Mi"
    },
    {
      name  = "interceptor.resources.limits.memory"
      value = "128Mi"
    },
    {
      name  = "scaler.resources.requests.cpu"
      value = "25m"
    },
    {
      name  = "scaler.resources.requests.memory"
      value = "32Mi"
    },
    {
      name  = "scaler.resources.limits.memory"
      value = "64Mi"
    },
  ]
}

# ---------------------------------------------------------------------------
# ARC (Actions Runner Controller) — ADR-0053: per-job EPHEMERAL scale-to-zero
# runners on Karpenter spot nodes (supersedes the persistent EC2 model of
# ADR-0082). The controller is ALWAYS installed; the two runner *scale sets*
# (build/deploy) live in arc-runners.tf, gated behind var.arc_runner_enabled
# because they need a GitHub App credential that only the repo owner can mint.
#
# MANUAL STEP before flipping arc_runner_enabled=true (key never enters state):
#   1. Create a GitHub App on the repo, install it, generate a private key.
#   2. kubectl create secret generic arc-github-app -n arc-runners \
#        --from-literal=github_app_id=<id> \
#        --from-literal=github_app_installation_id=<inst_id> \
#        --from-file=github_app_private_key=<path-to-pem>
# ---------------------------------------------------------------------------
resource "helm_release" "arc_controller" {
  name             = "arc"
  namespace        = "arc-systems"
  create_namespace = true
  repository       = "oci://ghcr.io/actions/actions-runner-controller-charts"
  chart            = "gha-runner-scale-set-controller"
  version          = var.arc_controller_version
}
