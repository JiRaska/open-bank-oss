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
            # `c` dropped (2026-08-02): c-family is 2 GiB/vCPU, which after the
            # fixed per-node tax below leaves ~1.2 GiB of usable memory per vCPU
            # — less than this fleet's own request ratio (55.3 GiB of memory
            # requests against 25.0 vCPU ≈ 2.2 GiB/vCPU). A c-family node is
            # therefore memory-exhausted while still half-idle on CPU, which is
            # exactly the state the evictions came from. m (4 GiB/vCPU) and r
            # (8 GiB/vCPU) both clear the ratio; Karpenter still price-sorts
            # within them.
            { key = "karpenter.k8s.aws/instance-category", operator = "In", values = ["m", "r"] },
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
            # Spot diversity is not a casualty: m/r, gen>5, xlarge–2xlarge is
            # 22 instance types x 3 AZs = 66 spot pools.
            { key = "karpenter.k8s.aws/instance-size", operator = "In", values = ["xlarge", "2xlarge"] }
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
