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
# Karpenter CRDs, applied BEFORE the controller. Helm installs a chart's crds/
# directory on first install only and never upgrades it, so a chart-only bump
# leaves the cluster on the previous version's CRDs while the new controller
# expects the new schema (the upgrade guide's standing advice is to manage CRDs
# separately). These are the files the chart ships (pkg/apis/crds at the pinned
# tag), vendored per version so the plan shows exactly what changes. Server-side
# apply with force_conflicts takes field ownership from Helm's original install
# without deleting the CRD — a CRD delete would cascade to every NodePool,
# NodeClaim and EC2NodeClass. prevent_destroy guards that same cascade.
resource "kubectl_manifest" "karpenter_crd" {
  for_each = fileset("${path.module}/karpenter-crds/${var.karpenter_version}", "*.yaml")

  yaml_body         = file("${path.module}/karpenter-crds/${var.karpenter_version}/${each.value}")
  server_side_apply = true
  force_conflicts   = true

  lifecycle {
    prevent_destroy = true
  }
}

# Authenticated pull of the Karpenter chart from ECR Public. Anonymous pulls are rate
# limited per source IP and shared GitHub-hosted runners hit it: plan and apply both failed
# with "Error locating chart ... 429: toomanyrequests: Data limit exceeded" on 2026-09-28.
# The token is minted in us-east-1 only (ECR Public's control plane), whatever region we run.
data "aws_ecrpublic_authorization_token" "karpenter_chart" {
  provider = aws.us_east_1
}

resource "helm_release" "karpenter" {
  depends_on = [kubectl_manifest.karpenter_crd]

  lifecycle {
    precondition {
      condition     = length(fileset("${path.module}/karpenter-crds/${var.karpenter_version}", "*.yaml")) > 0
      error_message = "No vendored CRDs in karpenter-crds/${var.karpenter_version}/ — vendor pkg/apis/crds from that Karpenter tag with the version bump."
    }
  }

  name      = "karpenter"
  namespace = "kube-system"
  # Registry auth is on the helm PROVIDER (`registries`, providers.tf), not here: a
  # resource-level repository_password is persisted in state, and the ECR Public token
  # is new on every run, so it was a perpetual in-place diff (#11370). Provider config
  # is never stored or diffed, and still logs in on every plan/apply — including the
  # chart fetch for a karpenter_version bump.
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

# ---------------------------------------------------------------------------
# Pool sizing, DERIVED (the arc-runners.tf pattern, #11535): the numbers a human
# measures go in as inputs; node counts and NodePool limits are computed from
# them, never hand-set next to them.
# ---------------------------------------------------------------------------
locals {
  default_instance_categories = ["c", "m", "r"]
  default_instance_sizes      = ["xlarge", "2xlarge", "4xlarge"]
  # arm64 gen>5 types offered in eu-north-1 for the categories/sizes above
  # (`aws ec2 describe-instance-type-offerings --location-type availability-zone`,
  # 2026-09-30): 54 types / 156 AZ offerings, vs 22 / 62 for the previous m|r,
  # xlarge–2xlarge set. Informational — Karpenter reads the requirements, not this.
  default_spot_pools = 156

  # --- `stateful` pool (on-demand, tainted) ---------------------------------
  # SCOPE (owner decision 2026-09-30, #11608): only the CNPG clusters backing
  # `rules.yaml: money_path_services`, plus temporal/temporal-db -- 26 clusters, the
  # set the `stateful-on-demand-cel` Kyverno policy routes (derived, see that file).
  # Other CNPG clusters, Kafka and the Temporal server stay on spot.
  # Measured 2026-09-30 on the live cluster: summed container REQUESTS of those 26
  # clusters' 52 instance pods, grouped by the AZ each pod runs in. Grouped by AZ
  # because it cannot be pooled across AZs: a CNPG instance is bound to its EBS
  # volume, and the volume to its AZ. Total 6.40 vCPU / 16.75 GiB (memory raised by #11621, sanctions-db by #11782;
  # pension-db's two instances added from their DECLARED requests, not measured -- the cluster
  # did not exist yet -- assumed split over 1a and 1c by its zone spread constraint, #12350);
  # check-stateful-not-on-spot.py fails when the requests those 26 Clusters declare
  # in gitops outgrow this table, so the limit below cannot silently fall behind.
  stateful_load_by_zone = {
    "eu-north-1a" = { cpu = 0.75, memory_gib = 1.875, pods = 5 }
    # 1b includes pension-fund-db (ADR-0334, 2 x 100m / 256Mi) as DECLARED, not measured: it is
    # not deployed yet, and its AZ is unknown until its volumes bind. Re-measure after first deploy.
    "eu-north-1b" = { cpu = 4.45, memory_gib = 12.0, pods = 43 }
    "eu-north-1c" = { cpu = 1.20, memory_gib = 2.875, pods = 8 }
  }
  # One xlarge m-family node as the sizing unit (2xlarge is also admitted and is
  # exactly two units, so the limit below bounds both). USABLE = kubelet allocatable
  # (measured on live m7g.xlarge: 3920m / ~14.1 GiB / 58 pods) minus the per-node
  # DaemonSet tax recorded on the default pool (0.26 vCPU / 962Mi / ~7 pods).
  stateful_node = { vcpu = 4, memory_gib = 16, usable_cpu = 3.66, usable_memory_gib = 13.2, usable_pods = 51 }
  # N+1 PER AZ: nodes to carry that AZ's load, plus one spare in the same AZ so a
  # node loss (or a drift replacement surging a new node) always has somewhere to
  # land that the pod's volume can reach. A cross-AZ spare would be useless.
  stateful_nodes_by_zone = {
    for z, l in local.stateful_load_by_zone : z => 1 + max(
      ceil(l.cpu / local.stateful_node.usable_cpu),
      ceil(l.memory_gib / local.stateful_node.usable_memory_gib),
      ceil(l.pods / local.stateful_node.usable_pods),
    )
  }
  stateful_nodes              = sum(values(local.stateful_nodes_by_zone))
  stateful_nodepool_cpu_limit = local.stateful_nodes * local.stateful_node.vcpu
  stateful_nodepool_mem_limit = "${local.stateful_nodes * local.stateful_node.memory_gib}Gi"
}

# `stateful` — ON-DEMAND ONLY, tainted, for the money-path databases and temporal-db
# (#11608). 2026-09-29/30: 41 spot interruptions in 24h on the `default` pool, each
# one costing ~13 CNPG failovers, because 134 of 140 CNPG pods lived on spot.
#
# Pods reach this pool through the Kyverno MutatingPolicy
# gitops/components/kyverno/stateful-on-demand-cel.yaml, which on pod CREATE adds
# REQUIRED node affinity `karpenter.sh/capacity-type In [on-demand]` and a toleration
# for the taint below. NOT this pool's own label, on purpose: if this NodePool is
# missing (policy synced by Argo before this root is applied) the `default` pool can
# still provision on-demand for them, so no database is stranded Pending by the
# rollout order. `weight = 100` is what makes Karpenter choose THIS pool over
# `default` (weight 0) for them once it exists.
#
# Admission-time only: existing pods are untouched, so merging/applying this rolls
# NOTHING. Pods move one at a time as they are recreated — see the PR for the waved
# switchover procedure. Never replace this with Cluster.spec.affinity edits: that
# rolls every CNPG cluster in one Argo sync (the 2026-09-28 outage shape).
resource "kubectl_manifest" "nodepool_stateful" {
  depends_on = [kubectl_manifest.ec2nodeclass_default]

  yaml_body = yamlencode({
    apiVersion = "karpenter.sh/v1"
    kind       = "NodePool"
    metadata   = { name = "stateful" }
    spec = {
      weight = 100
      template = {
        metadata = { labels = { "openbank.io/pool" = "stateful" } }
        spec = {
          taints = [
            { key = "openbank.io/stateful", value = "true", effect = "NoSchedule" }
          ]
          requirements = [
            { key = "kubernetes.io/arch", operator = "In", values = ["arm64"] },
            { key = "kubernetes.io/os", operator = "In", values = ["linux"] },
            # The whole point of the pool. check-stateful-not-on-spot.py fails if
            # `spot` ever appears here.
            { key = "karpenter.sh/capacity-type", operator = "In", values = ["on-demand"] },
            # m only: the routed set requests 2.4 GiB/vCPU, which c (2 GiB/vCPU
            # capacity) cannot hold and r (8 GiB/vCPU) would pay for idle memory.
            { key = "karpenter.k8s.aws/instance-category", operator = "In", values = ["m"] },
            { key = "karpenter.k8s.aws/instance-generation", operator = "Gt", values = ["5"] },
            # `large` admitted 2026-10-02 (#11608): after #11621/#11782 no routed pod's 12h
            # working set exceeds its request (max sanctions-db-1 540/640Mi), and the worst
            # node packed to requests on a large (1.67 vCPU / 5.8 GiB left for DBs after
            # DaemonSets) keeps ~40% memory free. Excluded from `default` for the mixed-
            # workload evictions of 2026-08-02; that evidence does not apply to a tainted,
            # databases-only pool. Adding a size does NOT drift existing xlarge nodes (they
            # still match), so nothing restarts: new nodeclaims after an interruption or an
            # AMI drift simply pick the cheaper fitting shape. The limit below stays sized in
            # xlarge units, a conservative cap for a mix of both.
            { key = "karpenter.k8s.aws/instance-size", operator = "In", values = ["large", "xlarge", "2xlarge"] },
            # All three AZs: every AZ holds target volumes (4/41/7 pods, 2026-09-30).
            { key = "topology.kubernetes.io/zone", operator = "In", values = keys(local.stateful_load_by_zone) },
          ]
          nodeClassRef = {
            group = "karpenter.k8s.aws"
            kind  = "EC2NodeClass"
            name  = "default"
          }
          # No calendar expiry: every node replacement here is a batch of DB
          # failovers, and AMI/kubelet updates already arrive as drift (budgeted
          # below to one node at a time).
          expireAfter = "Never"
          # Same deadline as `default` (#11304): stop waiting on a stuck EBS detach.
          terminationGracePeriod = "1h"
        }
      }
      disruption = {
        # WhenEmpty, not WhenEmptyOrUnderutilized: moving a DB pod to pack nodes
        # tighter is exactly the churn this pool exists to remove. The cost is some
        # fragmentation after pods leave, bounded by the limit below.
        consolidationPolicy = "WhenEmpty"
        consolidateAfter    = "10m"
        budgets = [
          { nodes = "1" },
        ]
      }
      limits = {
        # DERIVED: sum over AZs of (nodes for that AZ's load + 1 spare) x node size.
        cpu    = tostring(local.stateful_nodepool_cpu_limit)
        memory = local.stateful_nodepool_mem_limit
      }
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
            # `c` dropped (2026-08-02): c-family is 2 GiB/vCPU, which after the
            # fixed per-node tax below leaves ~1.2 GiB of usable memory per vCPU
            # — less than this fleet's own request ratio (55.3 GiB of memory
            # requests against 25.0 vCPU ≈ 2.2 GiB/vCPU). A c-family node is
            # therefore memory-exhausted while still half-idle on CPU, which is
            # exactly the state the evictions came from. m (4 GiB/vCPU) and r
            # (8 GiB/vCPU) both clear the ratio; Karpenter still price-sorts
            # within them.
            #
            # `c` RE-ADDED (2026-09-30, spot diversity). The memory-ratio argument
            # above still holds as arithmetic (this pool's requests are 2.2 GiB/vCPU
            # with or without the stateful set, measured 2026-09-30), so a c node
            # the spot allocator picks is memory-bound with idle CPU — an EFFICIENCY
            # cost, not the eviction cause: the #809-era evictions came from the
            # `large` + ~962Mi DaemonSet tax, which the size floor below still
            # excludes. What changed is the other side of the trade: 41 spot
            # interruptions in 24h on ~10 nodes (2026-09-29/30), 12 of them one
            # type (m7g.xlarge) in one AZ (1b). Karpenter asks EC2 for
            # price-capacity-optimized spot across every allowed type, so more
            # pools directly lowers the chance the chosen pool is the one being
            # reclaimed. See local.default_spot_pools for the count.
            { key = "karpenter.k8s.aws/instance-category", operator = "In", values = local.default_instance_categories },
            # minValues: every launch request names >= 5 instance FAMILIES, so the
            # allocator can never be handed a single hot pool (m7g in 1b) because
            # it happened to be cheapest at that moment.
            { key = "karpenter.k8s.aws/instance-family", operator = "Exists", minValues = 5 },
            { key = "karpenter.k8s.aws/instance-generation", operator = "Gt", values = ["5"] },
            # xlarge–2xlarge (2026-08-02). `large` removed; `4xlarge` removed.
            #
            # WHY `large` HAD TO GO. The previous comment justified a `large`
            # MINIMUM with "DaemonSet overhead ≈ 350m CPU / 400Mi RAM". That
            # figure is stale by ~2.4x. Measured on the live `default` pool
            # (21 nodes, Prometheus `container_memory_working_set_bytes`,
            # max_over_time[12h], 2026-08-02):
            #
            #   alloy 636Mi | aws-node 167Mi | falco 77Mi | kube-proxy 32Mi
            #   ebs-csi-node 28Mi | node-exporter 13Mi | pod-identity-agent 9Mi
            #   -> 962Mi and 0.26 vCPU of DaemonSet, on EVERY node.
            #
            # Stack that on a 4 GiB `large`: capacity 4.00 GiB, allocatable
            # 2.87 GiB (kubelet/system reserved eats 1.13 GiB), minus 962Mi of
            # DaemonSet = ~1.93 GiB actually available to workloads. ~52% of the
            # machine is overhead, and the EC2NodeClass evicts at
            # memory.available < 500Mi soft / 300Mi hard — thresholds that sit
            # inside the noise band of what is left. Measured peak headroom on
            # the 17 `large` nodes ran 19Mi–1165Mi; the node at 19Mi is where
            # kyc-service and sdd-service were evicted.
            #
            # The same 962Mi on an xlarge (16 GiB, m-family) is ~7% of the node,
            # and usable memory per node goes 1.93 GiB -> ~12.8 GiB. This is a
            # pure win, not a trade: price per USABLE vCPU is a wash
            # (c8g.large $0.0159/hr vs m7g.xlarge $0.0156/hr, eu-north-1 spot,
            # 2026-08-02) because the tax is per node, not per vCPU.
            #
            # WHY `4xlarge` ALSO WENT. r8g.4xlarge is 128 GiB — a single node
            # would consume the entire `limits.memory` below, so one greedy
            # provisioning decision could wedge the pool at 1 node. 2xlarge caps
            # a single node at 8 vCPU / 64 GiB, which is still 2x the largest
            # bin-packing group this pool has ever needed. The original hazard
            # the upper bound was written for (Karpenter reaching for
            # c6g.12xlarge) is unchanged and still guarded.
            #
            # "Spot diversity is not a casualty: m/r, gen>5, xlarge–2xlarge is
            # 22 instance types x 3 AZs = 66 spot pools." — measured 2026-09-30 it
            # was 62 offerings (not every type is offered in every AZ), and the
            # interruption rate says it WAS a casualty.
            #
            # `4xlarge` RE-ADDED (2026-09-30). Its removal reason was that one
            # r8g.4xlarge (128 GiB) would consume the whole 128Gi memory limit of
            # that day and wedge the pool; the limit has been 288Gi since #3496,
            # so a 4xlarge is <= 16/72 vCPU and 128/288 GiB — no longer a wedge.
            #
            # `large` deliberately NOT re-added: the measured eviction cause above
            # (52% of a `large` is overhead; kyc/sdd evicted at 19Mi headroom) is
            # unchanged, and it would add 52 pools for a known failure.
            { key = "karpenter.k8s.aws/instance-size", operator = "In", values = local.default_instance_sizes }
          ]
          # NO `topology.kubernetes.io/zone` REQUIREMENT HERE, AND ADDING ONE
          # WILL BREAK THE CLUSTER. Recorded 2026-08-03 (#3496) because pinning
          # this pool to one AZ is the obvious answer to the account's largest
          # controllable cost line, it was drafted, and it is wrong.
          #
          # THE COST IT IS MEANT TO FIX IS REAL. Cross-AZ transfer
          # (EUN1-DataTransfer-Regional-Bytes) stepped 11x on 2026-07-27, from
          # ~$3/day (07-20..07-26) to $31-37/day (07-28..08-01) — ~40% of a
          # ~$85/day gross bill against a $50/day target. AWS bills inter-AZ at
          # $0.01/GB in EACH direction, so ~3200 GB/day billed is ~1600 GB/day
          # actually moved. It is DIFFUSE: two independent VPC flow-log captures
          # (the second over 31 ENIs incl. both ECR interface endpoints) put
          # cross-AZ at 16-20% of captured egress with no dominant pair. The
          # named contributors are all structural, not one chatty workload:
          #   - Pods reaching the Kubernetes API server. The EKS control-plane
          #     ENIs exist ONLY in 1a (10.80.28.144) and 1c (10.80.238.102) —
          #     there is NONE in 1b, so every node in 1b pays cross-AZ on 100%
          #     of its API traffic. ArgoCD's application-controller and
          #     repo-server, both in 1b, were the top two talkers measured.
          #   - Prometheus, in 1c, scraping every node in 1a/1b.
          #   - Alloy on all 31 nodes shipping to Loki, which sits in 1a.
          #   - ONE private route table for all three subnets points 0.0.0.0/0
          #     at the fck-nat ENI in 1a, so every internet byte from a 1b/1c
          #     node crosses an AZ in both directions.
          # (Ruled OUT by the same captures: the CI runners. They are the
          # largest byte flow in the account at ~1.5 TB/day, but it is INBOUND
          # from S3 over the gateway VPC endpoint — free, and AZ-less. Node
          # `eni*` interfaces are pod veths and double-count; only `ens*` is the
          # real NIC. Measuring the wrong device makes the runners look like the
          # whole problem when their real egress is ~13 GB/day.)
          #
          # WHY THE PIN STILL CANNOT SHIP: EBS volumes are AZ-BOUND, and this
          # pool's workloads are overwhelmingly stateful. Measured live
          # 2026-08-03: of 88 PVs, 53 are in 1b and 19 in 1c — only 16 in 1a.
          # A zone requirement drifts every node in the pool, and a drained
          # stateful pod whose PV is in 1b/1c can NEVER be scheduled onto a 1a
          # node. For the ~40 `instances: 1` CNPG clusters (aml, audit, kyc,
          # interest, campaign, delegation, dispute, ...) there is no replica to
          # fail over to, so each becomes permanently Pending — an outage with
          # no automatic recovery, affecting most of the fleet at once.
          # `observability` is no safer: Prometheus and Tempo both have their
          # PVs in 1c, glitchtip-pg and goalert-db in 1b.
          # Note that the usual CNPG safety check does NOT catch this — their
          # anti-affinity is `kubernetes.io/hostname`, not zone, so nothing in
          # any pod spec objects. Only the PV topology does.
          #
          # THE SUPPORTED PATH, if this is picked up: add a SECOND NodePool
          # pinned to 1a with a higher `weight`, and leave this one as the
          # unpinned fallback. Karpenter prefers the weighted pool for anything
          # that can run there, while its volume-topology awareness keeps
          # PVC-bound pods on a node in their volume's AZ — so stateless
          # workloads migrate to 1a with no drift roll of this pool and nothing
          # is ever stranded. Moving the existing volumes is a separate,
          # per-database backup/restore exercise, not a NodePool edit.
          nodeClassRef = {
            group = "karpenter.k8s.aws"
            kind  = "EC2NodeClass"
            name  = "default"
          }
          expireAfter = "720h"
          # #11304: a DEADLINE on node termination. 2026-09-28 a drifted node's pods
          # drained but 22 EBS volumes never detached; with no grace period Karpenter's
          # termination controller waits on VolumesDetached (AwaitingVolumeDetachment)
          # forever, and ~22 CNPG clusters stayed down until the instance was terminated
          # by hand. Past this deadline Karpenter stops waiting on PDBs, do-not-disrupt and
          # volume detachment and terminates the instance -- which is what releases the
          # volumes. 1h, not less: CNPG instance pods carry a 30-minute
          # terminationGracePeriodSeconds (stopDelay) and Karpenter deletes pods early
          # enough to honour it, so a shorter deadline would cut clean Postgres shutdowns.
          # This field is part of the NodeClaim hash: applying it drifts every node of
          # this pool ONCE, which the one-node Drifted budget below serialises.
          terminationGracePeriod = "1h"
        }
      }
      disruption = {
        consolidationPolicy = "WhenEmptyOrUnderutilized"
        consolidateAfter    = "1m"
        # FinOps: freeze node replacement 20:00–07:00 UTC (overnight).
        # During the freeze window 0 nodes may be disrupted; outside it, up to 50%.
        #
        # ORIGINAL RATIONALE, NOW OBSOLETE — kept because the window is still here and
        # someone will ask why. It read: "Karpenter consolidation on idle nodes churn
        # image pulls from ghcr.io / quay.io over NAT (~100 GB/night, $4-5) because
        # containerd on a fresh node pulls DaemonSet images before Kyverno ECR-rewrite
        # is active." That was true when written (2026-06-26): a NAT *Gateway* charged
        # $0.045/GB processed, in both directions.
        #
        # It stopped being true four days later. 2026-06-30 replaced the gateway with
        # `openbank-sandbox-fck-nat`, a t4g.small NAT *instance* — see fck-nat in this
        # stack. There is now no NAT Gateway in the account at all, so:
        #   - the $0.045/GB processing charge does not exist;
        #   - image pulls are INBOUND from the internet, which AWS does not charge.
        # Measured 2026-07-16 over 14 days (Cost Explorer): NatGateway-Bytes absent
        # entirely; EUN1-DataTransfer-Out-Bytes $0.63; the only material transfer line is
        # EUN1-DataTransfer-Regional-Bytes at $32.64 (~$70/mo) — CROSS-AZ, because
        # fck-nat sits in eu-north-1a while ~2/3 of nodes run in 1b/1c. That is a
        # different cost with a different fix, and it is mostly pod-to-pod traffic, not
        # image pulls.
        #
        # So this freeze now buys little and costs something: 11h/night of no
        # consolidation means idle nodes are held until 07:00 UTC, and it is why the
        # `default` NodePool limit above cannot be raised without a night-surge bill.
        # DO NOT drop it on the strength of this comment alone — the remaining question
        # is whether fck-nat (a single t4g.small, cross-AZ for most nodes) can absorb an
        # unfrozen night's pull burst, which is a throughput question nobody has
        # measured. Tracked in #1290 along with the last workloads that still need the
        # NAT path at all (CNPG). Re-evaluate once those are pinned at ECR.
        budgets = [
          # Standard 5-field cron: no disruption 20:00–07:00 UTC daily
          { schedule = "0 20 * * *", duration = "11h", nodes = "0%" },
          { nodes = "50%" },
          # #11304: drift (AMI release, kubelet/NodeClass change) replaces ONE node at a
          # time. Karpenter applies the most restrictive matching budget, so consolidation
          # keeps its 50% while a fleet-wide drift can no longer take out half the pool at
          # once -- the 2026-09-28 wedge landed on top of a fleet-wide CNPG bump.
          { nodes = "1", reasons = ["Drifted"] },
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
      #
      # 128Gi → 192Gi memory (2026-08-02), cpu unchanged at 48. The memory cap
      # was calibrated to `large` nodes at ~2.7 GiB of capacity per vCPU. With
      # the xlarge/2xlarge m|r floor above, 48 vCPU of m-family is 192 GiB of
      # CAPACITY — so leaving the cap at 128Gi would make memory bind at 32
      # vCPU and re-create the #809 stall (pool pinned, "all available instance
      # types exceed limits for nodepool", pods Pending) with no CPU pressure
      # anywhere. Setting memory to 4x the CPU cap makes CPU the single binding
      # guardrail, which is what this block was always meant to be.
      #
      # This raises the CEILING, not the spend. Ceiling: 48 vCPU as m7g.xlarge
      # spot = 12 x $0.0568/hr = $497/mo, against $455/mo for 48 vCPU of
      # c8g.large — +$42/mo on a ceiling that is not approached. ACTUAL
      # `default`-pool instance spend is ~$61/mo (Cost Explorer, 7d annualised,
      # 2026-08-02) out of an $853/mo account total, and the shape change is
      # expected to move it by less than $10/mo in either direction because
      # price per usable vCPU is unchanged. For scale: cross-AZ data transfer
      # (EUN1-DataTransfer-Regional-Bytes) is ~$809/mo on the same bill.
      #
      # 48 -> 72 vCPU / 192Gi -> 288Gi (2026-08-03, #3496). THE 48 CAP IS
      # BINDING RIGHT NOW, and it is the third recurrence of the #809 stall the
      # block above describes. Measured on the live cluster 2026-08-03 04:20Z:
      # the pool held 22 nodes summing to EXACTLY 48/48 vCPU, and 12 pods had
      # been Pending for ~35 min — audit, campaign, dispute, interest,
      # notifications, card-issuance, standing-order, pid, copilot, psd2,
      # statements — with Karpenter logging "all available instance types exceed
      # limits for nodepool (NodePool=default)". No service was down (every
      # Deployment read 1/1 Available; the Pending pods were blocked scale-ups,
      # so HA was degraded, not lost).
      #
      # The deadlock is simply that the pool is FULL: 22 nodes hold exactly
      # 48/48 vCPU (and 124.4Gi against the live 128Gi), so Karpenter cannot add
      # a node of ANY size — it can neither schedule a new pod nor surge a
      # replacement, which also means it cannot roll its own drift.
      #
      # DO NOT read this as a consequence of the xlarge/2xlarge floor set on
      # 2026-08-02: THAT CHANGE HAS NEVER BEEN APPLIED. Live `kubectl get
      # nodepool default` on 2026-08-03 still reads instance-category [c,m,r],
      # instance-size [large,xlarge,2xlarge,4xlarge] and limits
      # {cpu:48, memory:128Gi}, where this file says m/r, xlarge–2xlarge and
      # 192Gi. `platform-tofu.yml` applies only on manual workflow_dispatch, so
      # this file and the cluster drift silently whenever nobody dispatches it,
      # and the 22 small nodes are the STEADY STATE, not a half-finished roll.
      # An earlier version of this comment blamed the floor and computed
      # "48 + 4 > 48"; on the live pool the smallest allowed node is still a
      # 2 vCPU `large`, so the true statement is 48 + anything > 48. Reverting
      # the floor would therefore not have unwedged anything.
      #
      # Consequence worth planning for: the next apply lands the raised cap AND
      # the 2026-08-02 shape change together, so it will drift and roll all 22
      # nodes. The surge headroom below is what makes that roll possible at all.
      #
      # 72 leaves 24 vCPU (6 xlarge nodes) of surge against a 50% disruption
      # budget. It raises the CEILING, not the spend: actual `default`-pool
      # instance spend is ~$61/mo, and the post-roll steady state should sit
      # BELOW today's 48 because xlarge nodes pay the ~962Mi/0.26-vCPU per-node
      # DaemonSet tax once instead of 22 times. Memory stays at 4x the CPU cap
      # so CPU remains the single binding guardrail, per the note above.
      #
      # 72 KEPT (2026-09-30) although the money-path databases + temporal-db
      # (5.80 vCPU of requests, 26 clusters) move to the `stateful` pool. The move
      # is gradual — a pod leaves only when it is recreated — so lowering this now
      # would take capacity from the pool that still carries them. The combined
      # CEILING therefore grows by local.stateful_nodepool_cpu_limit (28), stated
      # here so it is not silent; SPEND does not, because pods move rather than
      # duplicate. Once `migrate-stateful-to-on-demand.sh` reports every target on
      # on-demand, this could drop by ceil(5.80 / 4) x 4 = 8 — small enough that
      # keeping the surge headroom is the better trade; revisit with #3496's numbers.
      limits = {
        cpu    = "72"
        memory = "288Gi"
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
    # Chart 10.0.0 flipped global.networkPolicy.create false -> true, adding
    # upstream NetworkPolicies to every argocd component. Kept false so the
    # 3.4 -> 3.5 upgrade changes no traffic path; enabling them is a separate,
    # testable change (repo-server/redis ingress, webhook and metrics callers).
    {
      name  = "global.networkPolicy.create"
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
    # Run the singleton application-controller on the on-demand `stateful` pool
    # (#11608). Root cause of a ~7h ArgoCD outage (2026-07-11): the controller's node
    # was Evicted "Underutilized" by Karpenter, went NotReady, and the StatefulSet pod
    # (ordinal 0, at-most-one) got stuck Terminating -- no app synced for hours. That
    # was guarded with `karpenter.sh/do-not-disrupt`, which had a cost of its own:
    # whichever node the pod landed on could never be consolidated or drift-replaced.
    # 2026-10-02 it pinned an on-demand c6g.xlarge in `default` (AMIDrift pending,
    # DisruptionBlocked, six money-path DB pods stuck on it). The `stateful` pool
    # removes the incident class instead of freezing a node: it consolidates only
    # WhenEmpty (never "Underutilized"), is on-demand (no spot interruption), never
    # expires, and drift is budgeted to one node with a 1h terminationGracePeriod,
    # which also bounds the stuck-Terminating case. The node-hang half of 07-11 is
    # covered by the resources below. Priority class still prevents preemption.
    {
      name  = "controller.nodeSelector.karpenter\\.sh/nodepool"
      value = "stateful"
    },
    {
      name  = "controller.tolerations[0].key"
      value = "openbank.io/stateful"
    },
    {
      name  = "controller.tolerations[0].operator"
      value = "Equal"
    },
    {
      name  = "controller.tolerations[0].value"
      value = "true"
      type  = "string"
    },
    {
      name  = "controller.tolerations[0].effect"
      value = "NoSchedule"
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
    # Application-controller metrics. Both default to false in argo-cd 9.5.21, which is
    # why Prometheus holds 4128 metric names and NOT ONE starts with `argocd`: nothing was
    # ever scraped, so no rule could be written and none was. The cost was concrete — two
    # apps sat Degraded for ~2 days (document-service's unseeded signing keystore, and a
    # KEDA scaler broken for 44 days, #1284) and were found by an unrelated investigation
    # rather than by a page. `argocd_app_info{health_status="Degraded"}` comes from this
    # controller; enabling it is what makes that alertable at all.
    #
    # Only the controller: `argocd_app_info` lives here. server/repoServer/applicationSet
    # metrics are separate values and answer different questions (API latency, sync perf) —
    # not this gap, so not in this change.
    {
      name  = "controller.metrics.enabled"
      value = "true"
    },
    # The chart's own ServiceMonitor — no hand-written one needed (contrast
    # servicemonitor-karpenter.yaml, where the chart is terraform-managed but the Service
    # already existed, so gitops could take it without an apply). The cluster Prometheus
    # has empty serviceMonitorSelector AND serviceMonitorNamespaceSelector ({}), verified,
    # so it discovers this with no additionalLabels.
    {
      name  = "controller.metrics.serviceMonitor.enabled"
      value = "true"
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

  # In-place instance-manager upgrades. By default an operator upgrade rolls
  # EVERY Postgres pod to inject the new instance manager, and a single-instance
  # Cluster has no replica to switch over to, so each one restarts (a few
  # minutes of downtime per DB, all clusters at once). With this set, the
  # operator swaps the instance-manager binary inside the running pod and the
  # postmaster keeps running: no restart, no switchover. Trade-off: the pod's
  # init-container image keeps the old version until the pod is next recreated.
  # A change to the instance pod TEMPLATE itself still rolls pods regardless.
  set = [
    {
      name  = "config.data.ENABLE_INSTANCE_MANAGER_INPLACE_UPDATES"
      value = "true"
      type  = "string"
    },
  ]
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
    # Operator Prometheus metrics. Off by default in chart 2.19.0 — and not merely
    # unexposed: the operator is launched with `--enable-prometheus-metrics=false`, so
    # keda_scaler_errors and friends are not produced at all. Consequence: a ScaledObject
    # can sit Ready=False forever with no signal. notification-service's did, for its
    # entire 44-day life, and was found only because an unrelated investigation happened
    # to read `kubectl get scaledobject` (#1400).
    #
    # Do NOT be misled by `keda-operator-metrics-apiserver:8080` already existing — that
    # is the HPA external-metrics API and serves only keda_internal_metricsservice_*
    # gRPC counters. The scaler metrics live on the keda-operator Service, which today
    # exposes only metricsservice:9666; this value adds metrics:8080 to it.
    {
      name  = "prometheus.operator.enabled"
      value = "true"
    },
    # The chart's own ServiceMonitor (selector app.kubernetes.io/name=keda-operator,
    # namespaceSelector keda) — no hand-written one needed, same as argo-cd in #1453.
    {
      name  = "prometheus.operator.serviceMonitor.enabled"
      value = "true"
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
