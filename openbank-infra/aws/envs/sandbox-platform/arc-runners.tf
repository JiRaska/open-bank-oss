# ===========================================================================
# ARC runner scale sets (ADR-0053). Per-job EPHEMERAL runners, on a dedicated
# Karpenter Graviton-spot NodePool. Three scale sets — the build/deploy TRUST
# split (ADR-0082) plus a build/batch CAPACITY split so the merge-required lane
# is never starved by non-blocking work (ARC has no job preemption, so isolated
# capacity is the only "priority" lever):
#
#   openbank-build  — runs PR code, merge-required: per-service compile+test,
#                     admin-ui, manifest validation. NO cloud-write creds. dind
#                     for the docker-compose test-infra; rootless BuildKit is the
#                     documented hardening follow-up (ADR-0053). Keeps 1 warm
#                     runner (arc_min_runners) off the critical path; bursts to
#                     arc_max_runners.
#   openbank-deploy — post-merge ECR push + ArgoCD sync. Its pod SA carries
#                     IRSA scoped to ECR push only; a PR job can never schedule
#                     here (workflow routing + the ci-runner-governance lint).
#
# All gated behind var.arc_runner_enabled (needs the arc-github-app secret).
# ===========================================================================

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  arc_namespace = "arc-runners"
  # Runner pods land only on the tainted "runners" NodePool, never on the
  # bootstrap/banking nodes. do-not-disrupt stops Karpenter evicting a node
  # mid-job; the node is reclaimed by consolidation once the job pod exits.
  # Pods may land on EITHER the warm (on-demand) or spot burst NodePool — both
  # carry the openbank.io/pool=runners label and the same taint. The affinity
  # below gives warm nodes a strong preference (weight 80) so ARC's minRunners
  # idle pods park on the on-demand node (keeping it alive and cache-warm) while
  # spot nodes are left free to consolidate. Burst jobs that exceed warm capacity
  # fall through to spot nodes — same scheduling, lower preference weight.
  runner_node_selector = { "openbank.io/pool" = "runners" }
  runner_tolerations = [{
    key      = "openbank.io/runner"
    operator = "Equal"
    value    = "true"
    effect   = "NoSchedule"
  }]
  # Prefer warm on-demand nodes for idle runners; burst onto spot for overflow.
  runner_affinity = {
    nodeAffinity = {
      preferredDuringSchedulingIgnoredDuringExecution = [
        {
          weight = 80
          preference = {
            matchExpressions = [{
              key      = "openbank.io/runner-tier"
              operator = "In"
              values   = ["warm"]
            }]
          }
        }
      ]
    }
  }
  runner_pod_annotations = { "karpenter.sh/do-not-disrupt" = "true" }
  # Overriding template.spec.containers[runner] for resources drops the chart's
  # default image/command (strategic-merge replaces, not merges) — so we must
  # re-state both or the EphemeralRunner pod is rejected (image: Required value).
  #
  # Custom image (runner-image/Dockerfile): the stock ARC runner + the toolchain
  # the workflows assume (docker compose, yamllint, shellcheck, trivy, gitleaks,
  # yq) — the set the retired EC2 AMI baked in. Pinned by digest (immutable +
  # reproducible): bump this when runner-image.yml rebuilds the image. The
  # digest below = the runner-image/Dockerfile in this commit.
  #
  # 2026-07-14: bumped from the 2026-06-16 digest (ce7b1171...), which predates
  # the JDK-preload feature (PR #287, commit 4701ca7d) entirely and left
  # /opt/openbank-jdk-preload/Java_Temurin-Hotspot_jdk/ present-but-empty on
  # that image. openbank-build hit this same class of failure on 2026-07-09
  # (PR #680, live kubectl-patched + this file's `[ ! -d "$preload_root" ]` /
  # `[ -d "$version_dir" ] || continue` guards added) — but that fix was never
  # `tofu apply`'d to the live cluster, so openbank-deploy (this file, unpatched)
  # sat on the same stale digest with the OLD unguarded script and hit
  # `Init:Error` for 24h+ the first time a job landed there (unglobbed `*/` ->
  # literal `cp` target, ENOENT).
  #
  # First bumped to 78949d45... (the 2026-07-06 build), which unblocked the
  # pool, but that image predates the cosign-attestation fix (PR #963,
  # 2026-07-13) and so does NOT carry a valid CycloneDX SBOM attestation — it
  # would still trip verify-openbank-image-sbom-attestation once the
  # arc-runner-image-exception PolicyException (PR #908) is removed. Re-bumped
  # same day to sha256:45c0408d8992a900d7a539463b9210686d988513b39b06beaa35643b4a03e972,
  # the FIRST runner-image.yml run to complete after PR #963 — its "Verify
  # signature + attestation actually landed" step (no continue-on-error)
  # passed for this exact digest, confirmed live before this bump.
  runner_image   = "${data.aws_caller_identity.current.account_id}.dkr.ecr.${data.aws_region.current.name}.amazonaws.com/openbank-ci-runner@sha256:45c0408d8992a900d7a539463b9210686d988513b39b06beaa35643b4a03e972"
  runner_command = ["/home/runner/run.sh"]

  # -------------------------------------------------------------------------
  # Manual dind sidecar (instead of the chart's containerMode.type=dind).
  #
  # WHY NOT containerMode=dind: the gha-runner-scale-set chart (0.9.3) hardcodes
  # the dind container's `dockerd` args in its _helpers.tpl and exposes NO seam
  # to add daemon flags or mount an /etc/docker/daemon.json. We need to pin
  # dockerd's `--default-address-pool` so Docker NEVER auto-allocates a bridge on
  # the cluster Service CIDR (172.20.0.0/16, kube-dns 172.20.0.10): Docker's
  # built-in default pool walks 172.17→172.31/16, so the 4th auto-created network
  # (docker0=172.17, then .18/.19/.20) lands exactly on the Service CIDR and
  # black-holes pod DNS inside the runner pod's netns — the same failure class as
  # the explicit openbank-net collision fixed in docker-compose.yml. Testcontainers
  # / `docker compose` create networks dynamically, so the explicit-network fix
  # alone is not enough; the daemon-level pool is the durable guardrail.
  #
  # HOW: we drop containerMode and define the runner+dind+init containers and
  # volumes ourselves (the chart's "default mode" passes our pod spec through
  # verbatim). This block mirrors the exact spec the chart rendered for dind mode
  # (captured from the live AutoscalingRunnerSet) — identical but for the added
  # --default-address-pool flag. Owning the spec also removes hidden chart drift.
  #
  # Pool 192.168.0.0/17 (size 24 ⇒ 128 networks) is disjoint from: the Service
  # CIDR (172.20/16), the VPC CIDR (10.80/16), and the explicit compose
  # openbank-net (192.168.240.0/20, which sits in the upper half 192.168.128+).
  dind_default_address_pool = "base=192.168.0.0/17,size=24"

  # Docker wiring the chart would otherwise inject into the runner container.
  # GRADLE_REMOTE_CACHE_* wires the in-cluster Gradle Build Cache (ADR-0043).
  # The cache server (gitops/components/gradle-build-cache) runs nginx WebDAV
  # in the gradle-build-cache namespace; plain HTTP is fine inside the cluster.
  runner_docker_env = [
    { name = "DOCKER_HOST", value = "unix:///var/run/docker.sock" },
    { name = "RUNNER_WAIT_FOR_DOCKER_IN_SECONDS", value = "120" },
    { name = "GRADLE_REMOTE_CACHE_URL", value = "http://gradle-build-cache.gradle-build-cache.svc.cluster.local:8080/cache/" },
    { name = "GRADLE_REMOTE_CACHE_PUSH", value = "true" },
    { name = "GRADLE_REMOTE_CACHE_INSECURE", value = "true" },
    # FinOps (2026-06-22): GRADLE_USER_HOME on a node-local hostPath so the
    # Gradle wrapper distribution (~150 MB) and Maven module metadata are
    # downloaded at most ONCE per Karpenter node lifecycle instead of once per
    # job.  Complementary to cache-enabled: false in _service-ci.yml (which
    # eliminated 74 GB/day of GitHub Actions cache NAT upload).
    # Path is per-service under /var/cache/gradle-svc/<service> so concurrent
    # jobs for different services never collide; same-service jobs are
    # serialised by max-parallel. The init-gradle-home init container ensures
    # the directory exists and is world-writable before the runner starts.
    { name = "GRADLE_USER_HOME_NODE_CACHE", value = "/var/cache/gradle-svc" },
    # FinOps (2026-06-30): RUNNER_TOOL_CACHE on the same NVMe hostPath so
    # actions/setup-java downloads JDK 21 + JDK 25 (~380 MB) at most ONCE per
    # Karpenter node lifecycle instead of once per job. Root-cause analysis of
    # the 790 GB/day NAT spike (June 20-29, $250 cumulative) confirmed GitHub
    # CDN (185.199.x.x, 57.150.x.x) as the dominant source: each ephemeral
    # runner pod re-downloaded both JDKs from GitHub on every build. With 30
    # service builds × 380 MB = 11.4 GB NAT per CI run, cached to 380 MB/node.
    { name = "RUNNER_TOOL_CACHE", value = "/mnt/k8s-disks/0/runner-tool-cache" },
  ]
  runner_docker_volume_mounts = [
    { name = "work", mountPath = "/home/runner/_work" },
    { name = "dind-sock", mountPath = "/var/run" },
    { name = "gradle-home-cache", mountPath = "/var/cache/gradle-svc" },
    { name = "runner-tool-cache", mountPath = "/mnt/k8s-disks/0/runner-tool-cache" },
  ]

  # Copies the runner's baked-in externals into the shared volume (chart parity).
  dind_init_container = {
    name         = "init-dind-externals"
    image        = local.runner_image
    command      = ["cp"]
    args         = ["-r", "-v", "/home/runner/externals/.", "/home/runner/tmpDir/"]
    volumeMounts = [{ name = "dind-externals", mountPath = "/home/runner/tmpDir" }]
  }

  # Raises fs.aio-max-nr on the host before the runner pod starts. The default
  # kernel value (65536) is exhausted by the nightly full-fleet build: each
  # Quarkus reactive service (Vert.x epoll) claims ~10-15k AIO slots; with
  # max-parallel=4 (services-ci.yml) running on a shared node the pool hits 0
  # → libc++abi: Could not setup Async I/O. The privileged flag is required
  # because fs.aio-max-nr is a non-namespaced kernel parameter (CAP_SYS_ADMIN).
  aio_sysctl_init_container = {
    name            = "init-aio-sysctl"
    image           = "public.ecr.aws/docker/library/busybox:1.36"
    command         = ["sh", "-c", "sysctl -w fs.aio-max-nr=1048576 && echo 'fs.aio-max-nr raised to 1048576'"]
    securityContext = { privileged = true }
  }

  # Ensures the node-local Gradle home cache directory is writable by the runner
  # user (UID 1001 in the actions-runner image). hostPath creates the dir owned
  # by root; this init container chowns it before the runner starts.
  # FinOps (2026-06-22): Gradle wrapper (~150 MB) and Maven module metadata are
  # fetched once per Karpenter node lifecycle instead of once per job. Combined
  # with cache-enabled: false in _service-ci.yml (eliminates 74 GB/day GitHub
  # Actions cache upload) this drives CI NAT download cost toward the floor.
  gradle_home_init_container = {
    name  = "init-gradle-home"
    image = "public.ecr.aws/docker/library/busybox:1.36"
    # Chowns both node-local caches in a single init container:
    # - gradle-home-cache: Gradle wrapper + Maven metadata (~150 MB per node)
    # - runner-tool-cache: JDK 21 + JDK 25 (~380 MB per node) — fixes the
    #   790 GB/day GitHub CDN NAT spike (2026-06-30 root cause analysis).
    command         = ["sh", "-c", "mkdir -p /mnt/k8s-disks/0/gradle-svc /mnt/k8s-disks/0/runner-tool-cache && chmod 777 /mnt/k8s-disks/0/gradle-svc /mnt/k8s-disks/0/runner-tool-cache && echo 'caches ready'"]
    securityContext = { privileged = true }
    volumeMounts = [
      { name = "gradle-home-cache", mountPath = "/mnt/k8s-disks/0/gradle-svc" },
      { name = "runner-tool-cache", mountPath = "/mnt/k8s-disks/0/runner-tool-cache" },
    ]
  }

  # Populates the shared runner-tool-cache hostPath with the JDK 21 + 25 layout
  # baked into the runner image (runner-image/Dockerfile, /opt/openbank-jdk-preload/),
  # so actions/setup-java finds both already ".complete" and never downloads or
  # extracts a JDK on this node again. This is the actual fix for the
  # ENOENT/EACCES race (see the runner-tool-cache volume comment above): the
  # concurrent-extraction scenario is eliminated at the source rather than
  # patched with a lock, because after this init container runs there is
  # nothing left for two jobs to race on writing.
  #
  # Runs AFTER init-gradle-home (which creates + chmods the parent dir) and
  # must run on every pod start, not just once per node: it is cheap (a `-d`
  # test + a no-op when already populated) and pod restarts on an already-warm
  # node must not depend on ordering against other pods' init containers.
  #
  # Copy is atomic per JDK version: extract-shaped content already exists at
  # its final layout in the image, so this only needs `cp -r` into a SIBLING
  # temp dir on the SAME volume + `mv` (rename, atomic within one filesystem)
  # to the real version dir, then the `.complete` marker is written LAST —
  # identical ordering to what actions/toolkit's own cacheDir does internally
  # for a single writer. Guarding on `[ -e "$dst.complete" ]` first makes two
  # concurrent pods on a cold node redundant-but-harmless: at worst both copy
  # to their own uniquely-named temp dir (mktemp -d) and both rename into
  # place — the second rename simply replaces the first with an identical tree.
  jdk_toolcache_preload_init_container = {
    name    = "init-jdk-toolcache-preload"
    image   = local.runner_image
    command = ["sh", "-c"]
    args = [
      <<-SH
      set -eu
      cache_root="/mnt/k8s-disks/0/runner-tool-cache/Java_Temurin-Hotspot_jdk"
      preload_root="/opt/openbank-jdk-preload/Java_Temurin-Hotspot_jdk"
      mkdir -p "$cache_root"
      if [ ! -d "$preload_root" ]; then
        echo "jdk toolcache: preload_root $preload_root not present in this runner image (stale/rebuilt image?) - skipping preload, actions/setup-java will download on demand"
        exit 0
      fi
      for version_dir in "$preload_root"/*/; do
        [ -d "$version_dir" ] || continue
        version="$(basename "$version_dir")"
        dst="$cache_root/$version/arm64"
        if [ -e "$dst.complete" ]; then
          echo "jdk toolcache: $version already present, skipping"
          continue
        fi
        echo "jdk toolcache: populating $version"
        tmp="$(mktemp -d "$cache_root/.preload-XXXXXX")"
        cp -r "$version_dir/arm64/." "$tmp/"
        mkdir -p "$cache_root/$version"
        rm -rf "$dst"
        mv "$tmp" "$dst"
        touch "$dst.complete"
      done
      echo "jdk toolcache: ready"
      SH
    ]
    volumeMounts = [
      { name = "runner-tool-cache", mountPath = "/mnt/k8s-disks/0/runner-tool-cache" },
    ]
  }

  # The dind daemon — chart-parity args PLUS the pinned default address pool.
  # Image comes from AWS's public ECR mirror of the Docker Official Images, NOT
  # docker.io: with 8 runners churning, anonymous Docker Hub pulls of docker:dind
  # blow the 100-pulls/6h-per-IP limit (HTTP 429 toomanyrequests) and every runner
  # pod wedges in ImagePullBackOff. public.ecr.aws is unauthenticated AND
  # un-throttled, and the runner nodes can already reach it.
  #
  # FinOps: three in-cluster pull-through caches eliminate NAT egress for the
  # three main public registries CI pulls from:
  #   docker.io  → registry-cache:5000  (--registry-mirror, Docker native)
  #   quay.io    → quay-cache:5001      (containerd certs.d, --containerd-snapshotter)
  #   ghcr.io    → ghcr-cache:5002      (containerd certs.d, --containerd-snapshotter)
  # The hosts.toml files are mounted from a ConfigMap so no init script is needed.
  dind_container = {
    name  = "dind"
    image = "public.ecr.aws/docker/library/docker:dind"
    args = [
      "dockerd",
      "--host=unix:///var/run/docker.sock",
      "--group=$(DOCKER_GROUP_GID)",
      "--default-address-pool=${local.dind_default_address_pool}",
      # docker.io: native registry-mirror (Docker has built-in support for docker.io).
      "--registry-mirror=http://registry-cache.registry-cache.svc.cluster.local:5000",
      "--insecure-registry=registry-cache.registry-cache.svc.cluster.local:5000",
      # quay.io / ghcr.io: containerd snapshotter mode is enabled via daemon.json
      # (mounted as /etc/docker/daemon.json from the dind-mirror-certs ConfigMap)
      # which sets features.containerd-snapshotter=true so dockerd reads
      # /etc/containerd/certs.d/ for per-registry mirror selection.
      "--insecure-registry=quay-cache.registry-cache.svc.cluster.local:5001",
      "--insecure-registry=ghcr-cache.registry-cache.svc.cluster.local:5002",
    ]
    env             = [{ name = "DOCKER_GROUP_GID", value = "123" }]
    securityContext = { privileged = true }
    # Memory request (the root-cause fix, and like the runner's ephemeral-storage
    # request below it is mostly FREE — scheduling only). Without it dind ran at
    # request=0, which broke twice over:
    #
    #  1. SCHEDULING: the scheduler saw a 6Gi pod (runner only), so it packed onto
    #     c7gd.xlarge (4 vCPU / 8Gi = 6.6Gi allocatable — the `c` family is 2Gi/vCPU
    #     and this NodePool allows instance-category c/m/r). 6Gi "fits" 6.6Gi, but
    #     real usage is runner ~5Gi + dind ~0.2Gi + DaemonSets ~0.8Gi ≈ 6.2Gi, which
    #     crosses the 400Mi eviction threshold. Verified live: evictions happened on
    #     c7gd.xlarge at `available: 392404Ki`, never on m6gd/r7gd.
    #  2. EVICTION RANKING: kubelet ranks victims by usage-over-request. A container
    #     with request=0 is ALWAYS over its request, so the runner pod was picked
    #     first on any node pressure — the eviction message named dind while it was
    #     using a mere 99Mi.
    #
    # The pod then died mid-build as "The self-hosted runner lost communication with
    # the server" — the exact symptom the ephemeral-storage request below fixed for
    # DiskPressure. Same bug, one resource over: ~24% of build jobs died this way and
    # ~40% of pool job-minutes were wasted (measured 2026-07-16 across 4 main-push
    # runs; dead jobs averaged 13.9 min vs 6.5 min for successes — they die late, in
    # the Testcontainers phase, after the expensive work).
    #
    # 1Gi request is deliberately above dind's measured footprint (25Mi idle, 182Mi
    # peak under load incl. its Testcontainers): it makes total pod requests 7Gi,
    # which is what actually excludes the 6.6Gi c7gd.xlarge and forces Karpenter onto
    # an m/r-family (or c7gd.2xlarge) node. 3Gi limit leaves room for a heavier
    # Postgres+Redpanda pair than any service currently boots.
    resources = {
      requests = { memory = "1Gi" }
      limits   = { memory = "3Gi" }
    }
    volumeMounts = [
      { name = "work", mountPath = "/home/runner/_work" },
      { name = "dind-sock", mountPath = "/var/run" },
      { name = "dind-externals", mountPath = "/home/runner/externals" },
      # docker-lib: Docker image layers stored in an explicit emptyDir so kubelet
      # tracks the usage and evicts the pod at sizeLimit before the node root disk
      # fills (previously layers accumulated in the container writable overlay on
      # the root EBS, invisible to kubelet ephemeral-storage accounting).
      { name = "docker-lib", mountPath = "/var/lib/docker" },
      # daemon.json enables containerd-snapshotter mode; hosts.toml files wire mirrors.
      { name = "dind-mirror-certs", mountPath = "/etc/docker/daemon.json", subPath = "daemon.json" },
      { name = "dind-mirror-certs", mountPath = "/etc/containerd/certs.d/quay.io/hosts.toml", subPath = "quay-hosts.toml" },
      { name = "dind-mirror-certs", mountPath = "/etc/containerd/certs.d/ghcr.io/hosts.toml", subPath = "ghcr-hosts.toml" },
    ]
  }

  dind_volumes = [
    { name = "dind-sock", emptyDir = {} },
    { name = "dind-externals", emptyDir = {} },
    { name = "work", emptyDir = {} },
    # Docker image layer cache: 14 GiB cap per runner pod. Kubelet evicts the pod
    # at sizeLimit before the node root disk fills. 14 GiB ≈ 18 service images ×
    # ~800 MB each. The ec2nodeclass_runners root EBS is 30 GiB: 8 GiB OS +
    # 14 GiB docker-lib = 22 GiB per pod, leaving 8 GiB slack for system.
    { name = "docker-lib", emptyDir = { sizeLimit = "14Gi" } },
    { name = "dind-mirror-certs", configMap = { name = "dind-mirror-certs" } },
    # Node-local Gradle home cache — survives pod restarts on the same Karpenter
    # node. Gradle wrapper (~150 MB) and Maven metadata are fetched once per node
    # instead of once per job. DirectoryOrCreate + chmod 777 via init container.
    #
    # MUST live on the instance-store NVMe (/mnt/k8s-disks/0, the RAID0 array, ~880
    # GiB), NOT the 25 GiB gp3 root. instanceStorePolicy=RAID0 only relocates
    # containerd + kubelet to the NVMe; a hostPath defaults to the ROOT EBS. Pointed
    # at /var/cache/gradle-svc the per-node Gradle cache grew unbounded (~22 GiB) and
    # filled the 25 GiB root, after which the CNI could not write its sandbox results
    # (/var/lib/cni) and every new runner pod wedged in Init with
    # "FailedCreatePodSandBox: no space left on device" (2026-06-28). Backing it with
    # the NVMe gives ~880 GiB of headroom (node is ephemeral, expireAfter 72h, so it
    # is naturally GC'd) and keeps the root for the OS only — so the 25 GiB root stays.
    { name = "gradle-home-cache", hostPath = { path = "/mnt/k8s-disks/0/gradle-svc", type = "DirectoryOrCreate" } },
    # Node-local GitHub Actions tool cache — JDK 21 + 25 (~380 MB) downloaded
    # once per node lifecycle. Root cause of 790 GB/day NAT (June 20-29): each
    # ephemeral runner pod re-downloaded both JDKs from GitHub CDN (185.199.x.x,
    # 57.150.x.x) on every build.
    #
    # CORRECTION (2026-07-06, the previous version of this comment was wrong):
    # actions/setup-java is NOT safe for concurrent first-time writers on a
    # shared cache. Two `build (openbank-<service>)` matrix jobs landing on the
    # same node at the same time, both needing a JDK version not yet cached,
    # both extract to a private temp dir and then copy/rename into the SAME
    # final versioned path — that final copy is not coordinated across
    # processes, so one job's extraction corrupted the other's, surfacing as
    # `ENOENT`/`EACCES` on individual JDK files (Services CI, 3 failures/2 days).
    # Fixed by init_jdk_toolcache_preload below: the runner image now carries
    # JDK 21 + 25 pre-extracted (runner-image/Dockerfile), and this init
    # container copies them into the shared cache atomically (temp dir + mv,
    # `.complete` written last) BEFORE the runner starts accepting jobs — so by
    # the time setup-java runs, both JDKs are already present and it never
    # attempts a download/extract at all. Concurrent pods on a cold node race
    # only on the (idempotent, cheap) copy-if-missing check, never on partial
    # JDK extraction.
    { name = "runner-tool-cache", hostPath = { path = "/mnt/k8s-disks/0/runner-tool-cache", type = "DirectoryOrCreate" } },
  ]
}

# ---------------------------------------------------------------------------
# runner NodePool — TWO TIERS (FinOps 2026-06-13, ADR-0053):
#
# runners-warm  — RETIRED 2026-08-09 (limits.cpu = 0), issue #4317. Kept as a
#   declaration rather than deleted so the history below stays readable and the
#   pool can be revived by raising the limit if spot interruption ever proves
#   to cost more than the rate difference.
#   Original why: every cold Karpenter node pulls docker:dind (~700 MB) from
#   public.ecr.aws over the NAT gateway ($0.045/GB), so two always-alive nodes
#   carried the dind containerd cache and a normal PR build never touched the
#   NAT for the runner image. On-demand also eliminated spot interruption
#   mid-job for the critical build lane.
#   Why that lapsed: PR #926 (2026-06-13) put docker:dind behind the ECR
#   pull-through cache, reached over the ecr.dkr VPC endpoint — zero NAT
#   regardless of node warmth. variables.tf recorded this and set
#   arc_min_runners = 0 the same day; this NodePool was left at limits.cpu = 8
#   with expireAfter: Never, so it kept provisioning on-demand nodes for a
#   rationale that no longer existed.
#   Cost, measured 2026-08-09 (the note this replaces was wrong twice: it
#   quoted c6g.large, which has 2 vCPU and the requirements below make
#   unselectable, at a price ~2.5x under the real bill):
#     today  2x m6gd.xlarge on-demand  $0.1920/h  ~$280/month
#     after  same work on `runners`    $0.0566/h  ~$83/month   (m7gd.xlarge spot)
#   ~$197/month for identical throughput.
#   NOT a reclamation of idle capacity: these nodes are busy. Runner pods carry
#   karpenter.sh/do-not-disrupt: true and the build backlog keeps the pool
#   occupied, which is why WhenEmpty/5m never fires on them. The trade accepted
#   here is that a spot interruption can now kill a main-push build mid-job —
#   the exposure every PR build already carries.
#
# runners       — spot burst pool. Karpenter provisions extra nodes when the
#   queue exceeds 2 concurrent jobs. consolidateAfter extended to 30m so a
#   node stays warm across a typical PR burst instead of being torn down
#   after 2m (triggering another cold start 5m later for the next PR).
#   expireAfter extended to 72h: a surviving spot node already has the dind
#   image in its containerd cache — letting it live longer amortises that NAT
#   pull across many builds.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Dedicated EC2NodeClass for runner nodes — 50 GiB root EBS instead of 20 GiB.
# Non-runner nodes stay on the default EC2NodeClass (20 GiB) because they run
# stateless workloads that don't accumulate Docker layers.
# Runner nodes pull 400-800 MB service images per dind build; with the
# docker-lib emptyDir capped at 14 GiB, one pod needs up to 22 GiB (8 GiB OS +
# 14 GiB docker-lib). Paired with the runner pod's 16Gi ephemeral-storage
# request (see above), 50 GiB (~42 GiB allocatable) holds 2 runner pods with a
# safety buffer above their reservations, so the node never hits DiskPressure.
# This dedicated nodeclass was previously only in code — the live runner
# NodePools were still on the 20 GiB `default` nodeclass; applying this wires
# them to `runners`.
# Cost delta: +$2.40/runner-node/month (30 GiB × $0.08 gp3) — and these are spot
# + ephemeral (expireAfter 72h, scale-to-zero), so the real monthly cost is a
# fraction of that. Net negative cost: it eliminates the wasted node-hours + NAT
# + Trivy-DB re-downloads from every evicted job that currently re-runs.
# ---------------------------------------------------------------------------
resource "kubectl_manifest" "ec2nodeclass_runners" {
  count      = var.arc_runner_enabled ? 1 : 0
  depends_on = [helm_release.karpenter]

  yaml_body = yamlencode({
    apiVersion = "karpenter.k8s.aws/v1"
    kind       = "EC2NodeClass"
    metadata   = { name = "runners" }
    spec = {
      amiFamily                  = "AL2023"
      amiSelectorTerms           = [{ alias = "al2023@latest" }]
      role                       = local.karpenter_node_role_name
      subnetSelectorTerms        = [{ tags = { "karpenter.sh/discovery" = local.cluster_name } }]
      securityGroupSelectorTerms = [{ id = local.node_security_group_id }]
      tags = {
        "karpenter.sh/discovery" = local.cluster_name
        Project                  = "openbank"
        ManagedBy                = "karpenter"
        Role                     = "arc-runner"
      }
      # Eviction headroom against the no-swap memory-reclaim livelock (issue
      # #809) — two of the four hung nodes on 2026-07-11 were runner nodes
      # under Gradle build memory pressure. Evicting the runner pod (the job
      # retries) beats a dead node that strands work for hours. Same block as
      # the default EC2NodeClass; a change here drift-rolls the runner nodes.
      kubelet = {
        evictionHard              = { "memory.available" = "400Mi" }
        evictionSoft              = { "memory.available" = "600Mi" }
        evictionSoftGracePeriod   = { "memory.available" = "60s" }
        evictionMaxPodGracePeriod = 60
      }
      # CI/CD SPEED: put kubelet/containerd ephemeral storage (dind image layers,
      # Gradle build dir, Testcontainers volumes) on the instance's local NVMe via
      # RAID0 instead of the gp3 root EBS. NVMe random-IO is ~an order of magnitude
      # faster than gp3 for exactly the small-file churn a build does, so service
      # builds and image pushes are meaningfully quicker. It is also FREE (local
      # NVMe is included in the spot instance price) and large enough that
      # DiskPressure can never recur. The runner NodePools below require
      # instance-local-nvme>0 so only NVMe-equipped (d-family) instances are picked,
      # which means RAID0 always mounts and ephemeral never lands on EBS. The gp3
      # root therefore only holds the OS, so it is shrunk 50Gi -> 25Gi (AL2023 OS
      # ~4Gi + headroom). This makes the NVMe switch net cost-NEGATIVE: free fast
      # NVMe for ephemeral + a smaller paid gp3 root than before the runner-disk work.
      instanceStorePolicy = "RAID0"
      blockDeviceMappings = [{
        deviceName = "/dev/xvda"
        ebs = {
          volumeSize          = "25Gi"
          volumeType          = "gp3"
          encrypted           = true
          deleteOnTermination = true
        }
      }]
    }
  })
}

# Warm pool: on-demand, expireAfter: Never, WhenEmpty-only disruption so
# Karpenter never consolidates away a node that still has the dind cache hot.
resource "kubectl_manifest" "nodepool_runners_warm" {
  count      = var.arc_runner_enabled ? 1 : 0
  depends_on = [helm_release.karpenter, kubectl_manifest.ec2nodeclass_runners]

  yaml_body = yamlencode({
    apiVersion = "karpenter.sh/v1"
    kind       = "NodePool"
    metadata   = { name = "runners-warm" }
    spec = {
      template = {
        metadata = { labels = {
          "openbank.io/pool"        = "runners"
          "openbank.io/runner-tier" = "warm"
        } }
        spec = {
          taints = [{
            key    = "openbank.io/runner"
            value  = "true"
            effect = "NoSchedule"
          }]
          requirements = [
            { key = "kubernetes.io/arch", operator = "In", values = ["arm64"] },
            { key = "kubernetes.io/os", operator = "In", values = ["linux"] },
            { key = "karpenter.sh/capacity-type", operator = "In", values = ["on-demand"] },
            # Runner requests cpu=3/memory=6Gi → needs ≥4 vCPU node. The *d* variants
            # (c6gd/c7gd/m6gd.xlarge, 4 vCPU / 8 GB + local NVMe) are the cheapest
            # on-demand Graviton that fit one full runner pod AND carry the instance-
            # store NVMe the runners EC2NodeClass RAID0s for fast ephemeral storage.
            # node.kubernetes.io/instance-type is the well-known label Karpenter v1
            # allows in requirements (karpenter.k8s.aws/instance-type is restricted).
            { key = "node.kubernetes.io/instance-type", operator = "In", values = ["c6gd.xlarge", "c7gd.xlarge", "m6gd.xlarge"] },
            { key = "topology.kubernetes.io/zone", operator = "In", values = ["eu-north-1a"] }
          ]
          nodeClassRef = {
            group = "karpenter.k8s.aws"
            kind  = "EC2NodeClass"
            name  = "runners"
          }
          expireAfter = "Never" # warm node lives until explicitly disrupted
        }
      }
      disruption = {
        # WhenEmpty: only reclaim when NO pods are scheduled (i.e. minRunners=0
        # and no active job). Never consolidates underutilised — a partially-used
        # warm node keeps its dind containerd cache intact.
        consolidationPolicy = "WhenEmpty"
        consolidateAfter    = "5m"
      }
      limits = {
        # 0 = retired (#4317). Karpenter provisions nothing here; the two nodes
        # standing today drain as their runner pods finish and are replaced by
        # `runners` spot capacity. Was "8" (2 nodes) — see the tier note above
        # for why that stopped being justified on 2026-06-13.
        cpu = "0"
      }
    }
  })
}

# Spot burst pool: wider instance selection, longer expiry to amortise cold starts.
resource "kubectl_manifest" "nodepool_runners" {
  count      = var.arc_runner_enabled ? 1 : 0
  depends_on = [helm_release.karpenter, kubectl_manifest.ec2nodeclass_runners]

  yaml_body = yamlencode({
    apiVersion = "karpenter.sh/v1"
    kind       = "NodePool"
    metadata   = { name = "runners" }
    spec = {
      template = {
        metadata = { labels = {
          "openbank.io/pool"        = "runners"
          "openbank.io/runner-tier" = "spot"
        } }
        spec = {
          taints = [{
            key    = "openbank.io/runner"
            value  = "true"
            effect = "NoSchedule"
          }]
          requirements = [
            { key = "kubernetes.io/arch", operator = "In", values = ["arm64"] },
            { key = "kubernetes.io/os", operator = "In", values = ["linux"] },
            { key = "karpenter.sh/capacity-type", operator = "In", values = ["spot"] },
            { key = "karpenter.k8s.aws/instance-category", operator = "In", values = ["c", "m", "r"] },
            { key = "karpenter.k8s.aws/instance-generation", operator = "Gt", values = ["3"] },
            { key = "karpenter.k8s.aws/instance-cpu", operator = "In", values = ["4", "8", "16", "32"] },
            # Require local NVMe (d-family: c6gd/c7gd/m6gd/m7gd/r6gd/…) so instance-store
            # RAID0 ephemeral (set on the runners EC2NodeClass) is always present. This
            # narrows the spot pool to d-families — acceptable: arm64 d-family spot in
            # eu-north-1a is deep, and the build-speed win outweighs the marginal
            # interruption-diversity cost. ('t' burstable family dropped: no d variants.)
            { key = "karpenter.k8s.aws/instance-local-nvme", operator = "Gt", values = ["0"] },
            { key = "topology.kubernetes.io/zone", operator = "In", values = ["eu-north-1a"] }
          ]
          nodeClassRef = {
            group = "karpenter.k8s.aws"
            kind  = "EC2NodeClass"
            name  = "runners"
          }
          # Extended: a surviving spot node already has dind in containerd cache.
          # Longer lifetime amortises the ~700 MB NAT pull across more builds.
          expireAfter = "72h"
        }
      }
      disruption = {
        consolidationPolicy = "WhenEmptyOrUnderutilized"
        # Extended from 2m: prevents a node being reclaimed between back-to-back
        # PRs (common pattern: merge → release-please → service PRs) only to be
        # re-provisioned 5m later with a cold dind pull.
        consolidateAfter = "30m"
      }
      limits = {
        cpu = "64"
      }
    }
  })
}

# ---------------------------------------------------------------------------
# IRSA for the deploy scale set: a pod ServiceAccount whose only AWS power is
# pushing images to the openbank-* ECR repos. The build scale set uses the
# default SA with no such role — PR code can never assume this.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "arc_deploy_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.s.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(local.s.oidc_provider_arn, "/^.*oidc-provider\\//", "")}:sub"
      values   = ["system:serviceaccount:${local.arc_namespace}:openbank-deploy"]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(local.s.oidc_provider_arn, "/^.*oidc-provider\\//", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "arc_deploy" {
  count              = var.arc_runner_enabled ? 1 : 0
  name               = "openbank-arc-deploy"
  assume_role_policy = data.aws_iam_policy_document.arc_deploy_assume.json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0053" }
}

data "aws_iam_policy_document" "arc_deploy_ecr" {
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }
  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
      "ecr:InitiateLayerUpload",
      "ecr:UploadLayerPart",
      "ecr:CompleteLayerUpload",
      "ecr:PutImage"
    ]
    resources = [
      "arn:aws:ecr:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:repository/openbank-*"
    ]
  }
  # CreateRepository REMOVED (#3661, point 2 of #3477): every openbank-* repository this role
  # could have created is now declared in ecr-service-repositories.tf and, as of that PR's apply,
  # imported into Terraform state — a build job that can create registry namespaces is exactly
  # the thing declaring them was meant to remove the need for (#3423's original hole). Verified
  # before removing, not assumed: `aws ecr describe-repositories` against the live account, diffed
  # against the file's own for_each derivation (gitops image pins + the CI runner image), showed
  # zero repositories the declaration does not already cover.
  #
  # Describe STAYS. It answers a question CreateRepository never did: whether a missing repository
  # is a real drift (Terraform's declaration and the account have diverged — someone deleted a
  # repository outside Terraform, or a new service's gitops manifest landed without its
  # ecr-service-repositories.tf entry in the same PR) versus this role simply lacking permission
  # to see it. Collapsing that distinction is what made #3444 fail builds for repositories that
  # already existed (reverted by #3453) — the same failure mode Create's removal must not
  # reintroduce from the opposite direction. ensure-ecr-repository.sh's fail-open path (#3492)
  # keeps working unchanged: it can still observe and still fails open, it just never creates.
  #
  # sid renamed from EcrCreateServiceRepository — the old name asserted a capability this
  # statement no longer grants.
  statement {
    sid = "EcrDescribeServiceRepository"
    actions = [
      "ecr:DescribeRepositories",
    ]
    resources = [
      "arn:aws:ecr:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:repository/openbank-*"
    ]
  }
}

resource "aws_iam_role_policy" "arc_deploy_ecr" {
  count  = var.arc_runner_enabled ? 1 : 0
  name   = "ecr-push"
  role   = aws_iam_role.arc_deploy[0].id
  policy = data.aws_iam_policy_document.arc_deploy_ecr.json
}

# The openbank-deploy runner also rebuilds AND signs/attests the ci-runner image
# itself (runner-image.yml, ADR-0030 D4 step 4 / issue #310) — it needs the same
# cosign KMS grant as the build runner (reuses arc_build_cosign's policy document,
# scoped to the single signing key).
resource "aws_iam_role_policy" "arc_deploy_cosign" {
  count  = var.arc_runner_enabled ? 1 : 0
  name   = "cosign-kms-sign"
  role   = aws_iam_role.arc_deploy[0].id
  policy = data.aws_iam_policy_document.arc_build_cosign.json
}

# ---------------------------------------------------------------------------
# Runner namespace + the deploy pod ServiceAccount (IRSA-annotated).
# ---------------------------------------------------------------------------
resource "kubernetes_namespace" "arc_runners" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata { name = local.arc_namespace }
}

resource "kubernetes_service_account" "arc_deploy" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "openbank-deploy"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
    annotations = {
      "eks.amazonaws.com/role-arn" = aws_iam_role.arc_deploy[0].arn
    }
  }
}

# ---------------------------------------------------------------------------
# Per-registry containerd mirror config — hosts.toml files for quay.io and
# ghcr.io. Mounted read-only into the dind container under
# /etc/containerd/certs.d/<registry>/hosts.toml; dind's --containerd-snapshotter
# flag makes dockerd read these files for per-registry mirror selection.
# docker.io is handled separately via --registry-mirror (Docker native).
# ---------------------------------------------------------------------------
resource "kubernetes_config_map" "dind_mirror_certs" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "dind-mirror-certs"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
  }
  data = {
    # Enables Docker containerd-snapshotter mode so dockerd reads
    # /etc/containerd/certs.d/ for per-registry mirror selection.
    # registry-mirrors / insecure-registries here mirror the CLI args so
    # everything is consistent; CLI args take precedence over daemon.json for
    # keys that appear in both.
    "daemon.json" = jsonencode({
      features = { "containerd-snapshotter" = true }
    })
    "quay-hosts.toml" = <<-TOML
      server = "https://quay.io"
      [host."http://quay-cache.registry-cache.svc.cluster.local:5001"]
        capabilities = ["pull", "resolve"]
    TOML
    "ghcr-hosts.toml" = <<-TOML
      server = "https://ghcr.io"
      [host."http://ghcr-cache.registry-cache.svc.cluster.local:5002"]
        capabilities = ["pull", "resolve"]
    TOML
  }
}

# ---------------------------------------------------------------------------
# build scale set — runs-on: openbank-build
# ---------------------------------------------------------------------------
resource "helm_release" "arc_build" {
  count            = var.arc_runner_enabled ? 1 : 0
  name             = "openbank-build"
  namespace        = kubernetes_namespace.arc_runners[0].metadata[0].name
  create_namespace = false
  repository       = "oci://ghcr.io/actions/actions-runner-controller-charts"
  chart            = "gha-runner-scale-set"
  version          = var.arc_controller_version
  depends_on       = [helm_release.arc_controller, kubectl_manifest.nodepool_runners, kubernetes_config_map.dind_mirror_certs]

  values = [yamlencode({
    githubConfigUrl    = var.github_config_url
    githubConfigSecret = "arc-github-app" # pre-created secret; key never in state
    runnerScaleSetName = "openbank-build"
    minRunners         = var.arc_min_runners # 0 => scale-to-zero
    maxRunners         = var.arc_max_runners
    # No containerMode: we supply the dind sidecar manually (see locals) so we
    # can pin dockerd's --default-address-pool off the Service CIDR.
    template = {
      metadata = { annotations = local.runner_pod_annotations }
      spec = {
        serviceAccountName = var.arc_runner_enabled ? kubernetes_service_account.arc_build_runner[0].metadata[0].name : "default"
        # Below every platform workload (which sit at the default priority 0), so a
        # CI burst can never delay a deploy the way it did on 2026-07-25 — and, via
        # preemptionPolicy: Never on the class, a runner never evicts anything to get
        # scheduled. Object lives in gitops/components/platform/priorityclasses.yaml.
        priorityClassName = "openbank-ci"
        nodeSelector      = local.runner_node_selector
        tolerations       = local.runner_tolerations
        affinity          = local.runner_affinity
        initContainers    = [local.dind_init_container, local.aio_sysctl_init_container, local.gradle_home_init_container, local.jdk_toolcache_preload_init_container]
        containers = [
          {
            name         = "runner"
            image        = local.runner_image
            command      = local.runner_command
            env          = local.runner_docker_env
            volumeMounts = local.runner_docker_volume_mounts
            resources = {
              # ephemeral-storage request (the actual root-cause fix, and FREE —
              # scheduling only): without it the scheduler/Karpenter packed runner pods
              # by CPU alone, so a large spot node took ~5 pods × up-to-14Gi docker-lib
              # ≈ 70Gi onto a 20-30Gi root disk → node DiskPressure evicted a running
              # job ("self-hosted runner lost communication"), failing Trivy/builds
              # fleet-wide. 16Gi (14Gi docker-lib cap + work/slack) bounds packing to
              # ≤2 runner pods on a 50Gi node.
              #
              # memory limit 12Gi -> 9Gi: the 2x request/limit gap was the other half of
              # the eviction bug documented on dind above. The scheduler reserved 6Gi but
              # the pod was allowed 12Gi — nearly 2x the whole 6.6Gi c7gd.xlarge it could
              # legally be placed on. 9Gi is still generous headroom over reality: the
              # Gradle daemon is capped at -Xmx3g (gradle.properties / GRADLE_OPTS) and
              # the measured peak across live builds is ~5Gi (runner container, incl. the
              # forked test JVMs; Testcontainers themselves live in dind's cgroup, not
              # here). Capping it means a runaway build now OOMKills its own container —
              # attributable, and only that job — instead of tripping node memory
              # pressure and taking a healthy neighbour's job down with it.
              requests = { cpu = "3", memory = "6Gi", "ephemeral-storage" = "16Gi" }
              limits   = { memory = "9Gi" }
            }
          },
          local.dind_container,
        ]
        volumes = local.dind_volumes
      }
    }
  })]
}

# ---------------------------------------------------------------------------
# deploy scale set — runs-on: openbank-deploy (post-merge only). Pod runs as the
# IRSA-scoped ServiceAccount; dind for build+push.
# ---------------------------------------------------------------------------
resource "helm_release" "arc_deploy" {
  count            = var.arc_runner_enabled ? 1 : 0
  name             = "openbank-deploy"
  namespace        = kubernetes_namespace.arc_runners[0].metadata[0].name
  create_namespace = false
  repository       = "oci://ghcr.io/actions/actions-runner-controller-charts"
  chart            = "gha-runner-scale-set"
  version          = var.arc_controller_version
  depends_on       = [helm_release.arc_controller, kubectl_manifest.nodepool_runners, kubernetes_service_account.arc_deploy]

  values = [yamlencode({
    githubConfigUrl    = var.github_config_url
    githubConfigSecret = "arc-github-app"
    runnerScaleSetName = "openbank-deploy"
    minRunners         = 0
    maxRunners         = var.arc_deploy_max_runners
    # No containerMode: manual dind sidecar (see locals), same as the build pool.
    template = {
      metadata = { annotations = local.runner_pod_annotations }
      spec = {
        serviceAccountName = kubernetes_service_account.arc_deploy[0].metadata[0].name
        # Below every platform workload (which sit at the default priority 0), so a
        # CI burst can never delay a deploy the way it did on 2026-07-25 — and, via
        # preemptionPolicy: Never on the class, a runner never evicts anything to get
        # scheduled. Object lives in gitops/components/platform/priorityclasses.yaml.
        priorityClassName = "openbank-ci"
        nodeSelector      = local.runner_node_selector
        tolerations       = local.runner_tolerations
        initContainers    = [local.dind_init_container, local.aio_sysctl_init_container, local.gradle_home_init_container, local.jdk_toolcache_preload_init_container]
        containers = [
          {
            name         = "runner"
            image        = local.runner_image
            command      = local.runner_command
            env          = local.runner_docker_env
            volumeMounts = local.runner_docker_volume_mounts
            resources = {
              # ephemeral-storage, same reasoning as the build pool: this pod mounts the
              # SAME local.dind_container, whose docker-lib emptyDir is capped at 14Gi, and
              # runner-image.yml builds the CI runner image here — the most layer-heavy build
              # in the fleet. Without a request the scheduler sizes the node as if the pod
              # needed no disk, and kubelet's DiskPressure ranking puts a pod that is always
              # over its (zero) request at the front of the eviction queue. That is the same
              # shape of bug as the dind memory request above, one resource over.
              requests = { cpu = "2", memory = "4Gi", "ephemeral-storage" = "16Gi" }
              limits   = { memory = "8Gi" }
            }
          },
          local.dind_container,
        ]
        volumes = local.dind_volumes
      }
    }
  })]
}

# ---------------------------------------------------------------------------
# CodeArtifact IRSA for build runners (FinOps 2026-06-10).
# Build runner pods get a dedicated SA with codeartifact:GetAuthorizationToken
# so the CI workflow can mirror Maven Central through CodeArtifact in-VPC
# (no NAT egress for Gradle dependency downloads).
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "arc_build_assume" {
  count = var.arc_runner_enabled ? 1 : 0
  # EKS Pod Identity (preferred): the cluster runs the Pod Identity agent and the
  # IRSA injector webhook no longer reliably mutates the runner pods, so the IRSA
  # statement below silently yields no credentials ("Could not load credentials
  # from any providers" at auto-deploy's ECR login, and the CodeArtifact mirror
  # soft-failing to Maven Central + NAT cost). The association (below) binds this
  # role to the openbank-build-runner SA via the agent, independent of the webhook.
  statement {
    actions = ["sts:AssumeRole", "sts:TagSession"]
    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
  # IRSA (legacy, retained for backward-compat with the SA's role-arn annotation).
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = [local.s.oidc_provider_arn]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(local.s.oidc_provider_arn, "/^.*oidc-provider\\//", "")}:sub"
      values   = ["system:serviceaccount:${local.arc_namespace}:openbank-build-runner"]
    }
    condition {
      test     = "StringEquals"
      variable = "${replace(local.s.oidc_provider_arn, "/^.*oidc-provider\\//", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
  # GitHub OIDC (webhook-independent): auto-deploy assumes this role directly via the GitHub
  # Actions OIDC token (configure-aws-credentials), so a runner pod the Pod Identity / IRSA
  # webhook failed to mutate (failurePolicy: Ignore) still authenticates to AWS. This is the
  # durable fix for the intermittent "Could not load credentials" the comment above describes.
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]
    principals {
      type        = "Federated"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:JiRaska/open-bank-oss:*"]
    }
  }
}

resource "aws_iam_role" "arc_build_runner" {
  count              = var.arc_runner_enabled ? 1 : 0
  name               = "openbank-arc-build-runner"
  assume_role_policy = data.aws_iam_policy_document.arc_build_assume[0].json
  tags               = { Project = "openbank", ManagedBy = "opentofu", Adr = "0053" }
}

# Bind the role to the build-runner SA via EKS Pod Identity (the agent injects
# AWS_CONTAINER_CREDENTIALS_* into the pod, no webhook involved).
resource "aws_eks_pod_identity_association" "arc_build_runner" {
  count           = var.arc_runner_enabled ? 1 : 0
  cluster_name    = local.cluster_name
  namespace       = local.arc_namespace
  service_account = "openbank-build-runner"
  role_arn        = aws_iam_role.arc_build_runner[0].arn
}

resource "aws_iam_role_policy" "arc_build_codeartifact" {
  count = var.arc_runner_enabled ? 1 : 0
  name  = "codeartifact-token"
  role  = aws_iam_role.arc_build_runner[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["codeartifact:GetAuthorizationToken", "sts:GetServiceBearerToken"]
      Resource = "*"
    }]
  })
}

# ECR push for the auto-deploy pipeline (build + push runs on openbank-build).
# Mirrors arc_deploy_ecr — the build runner bakes and pushes service images, so
# it needs the same ECR push grant the deploy runner has.
resource "aws_iam_role_policy" "arc_build_ecr" {
  count  = var.arc_runner_enabled ? 1 : 0
  name   = "ecr-push"
  role   = aws_iam_role.arc_build_runner[0].id
  policy = data.aws_iam_policy_document.arc_deploy_ecr.json
}

# ECR pull-through cache read + import for the CI Testcontainers pre-warm
# (_service-ci.yml). The pre-warm pulls postgres/redpanda/valkey/apicurio from the
# docker-hub/* pull-through repos (ecr-pull-through-cache.tf, rule "docker-hub").
# The push grant above (arc_deploy_ecr) is scoped to repository/openbank-* and has
# only push verbs, so the build runner could log in (GetAuthorizationToken is
# account-wide) but the FIRST pull of an uncached docker-hub/* tag returned 403
# Forbidden — both because the read verbs do not cover docker-hub/* AND because a
# pull-through cache MISS needs ecr:BatchImportUpstreamImage to fetch from upstream
# and ecr:CreateRepository to auto-create the cache repo. Full-fleet build symptom:
# openbank-transaction-service (and any matrix job on a cold cache) could not start
# its Testcontainers. The same import grant exists for Karpenter NODE pods in
# ecr-pull-through-cache.tf; this is its CI-runner counterpart.
data "aws_iam_policy_document" "arc_build_ecr_pullthrough" {
  count = var.arc_runner_enabled ? 1 : 0
  statement {
    sid = "EcrPullThroughReadAndImport"
    actions = [
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
      "ecr:BatchCheckLayerAvailability",
      "ecr:CreateRepository",
      "ecr:BatchImportUpstreamImage",
      "ecr:TagResource",
    ]
    resources = ["arn:aws:ecr:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:repository/docker-hub/*"]
  }
}

resource "aws_iam_role_policy" "arc_build_ecr_pullthrough" {
  count  = var.arc_runner_enabled ? 1 : 0
  name   = "ecr-pull-through-docker-hub"
  role   = aws_iam_role.arc_build_runner[0].id
  policy = data.aws_iam_policy_document.arc_build_ecr_pullthrough[0].json
}

# Cosign image signing (ADR-0029/0030 supply-chain). The auto-deploy build job signs
# every pushed image with the AWS KMS key alias/openbank-cosign-signing (cosign v2,
# tag-based) so kyverno's verify-openbank-image-signatures Enforce policy admits the
# Deployment. Without this grant the build runner pushes UNSIGNED images and every
# deploy is blocked ("no signatures found"). Scoped to the single cosign key.
data "aws_iam_policy_document" "arc_build_cosign" {
  statement {
    sid       = "CosignKmsSign"
    effect    = "Allow"
    actions   = ["kms:Sign", "kms:GetPublicKey", "kms:DescribeKey"]
    resources = ["arn:aws:kms:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:key/939bfe08-2d9b-48ee-a44f-7bf270fb6f5e"]
  }
}

resource "aws_iam_role_policy" "arc_build_cosign" {
  count  = var.arc_runner_enabled ? 1 : 0
  name   = "cosign-kms-sign"
  role   = aws_iam_role.arc_build_runner[0].id
  policy = data.aws_iam_policy_document.arc_build_cosign.json
}

resource "kubernetes_service_account" "arc_build_runner" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "openbank-build-runner"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
    annotations = {
      "eks.amazonaws.com/role-arn" = aws_iam_role.arc_build_runner[0].arn
    }
  }
}

# ---------------------------------------------------------------------------
# batch scale set — runs-on: openbank-batch (ADR-0277, issue #6458).
#
# rules.yaml: ci_runners.pools.batch declared this pool from the start — a
# low-capped lane so a scan/cron burst cannot starve the merge-required build
# lane — and the OpenTofu simply never created it: a job targeting
# openbank-batch queued FOREVER, and "no runner has taken this yet" was
# indistinguishable from "no runner will ever exist" (#6458). Weekly lanes
# (api-fuzz, perf-gate) already route here, so this change turns declared
# capacity into real capacity. Trust level identical to build (no cloud-write
# creds, no secrets): reuses the openbank-build-runner SA.
# ---------------------------------------------------------------------------
resource "helm_release" "arc_batch" {
  count            = var.arc_runner_enabled ? 1 : 0
  name             = "openbank-batch"
  namespace        = kubernetes_namespace.arc_runners[0].metadata[0].name
  create_namespace = false
  repository       = "oci://ghcr.io/actions/actions-runner-controller-charts"
  chart            = "gha-runner-scale-set"
  version          = var.arc_controller_version
  depends_on       = [helm_release.arc_controller, kubectl_manifest.nodepool_runners, kubernetes_config_map.dind_mirror_certs]

  values = [yamlencode({
    githubConfigUrl    = var.github_config_url
    githubConfigSecret = "arc-github-app"
    runnerScaleSetName = "openbank-batch"
    minRunners         = 0 # weekly lanes only; idle cost must be $0 (ADR-0053)
    maxRunners         = var.arc_batch_max_runners
    template = {
      metadata = { annotations = local.runner_pod_annotations }
      spec = {
        serviceAccountName = kubernetes_service_account.arc_build_runner[0].metadata[0].name
        priorityClassName  = "openbank-ci"
        nodeSelector       = local.runner_node_selector
        tolerations        = local.runner_tolerations
        affinity           = local.runner_affinity
        initContainers     = [local.dind_init_container, local.aio_sysctl_init_container, local.gradle_home_init_container, local.jdk_toolcache_preload_init_container]
        containers = [
          {
            name         = "runner"
            image        = local.runner_image
            command      = local.runner_command
            env          = local.runner_docker_env
            volumeMounts = local.runner_docker_volume_mounts
            resources = {
              requests = { cpu = "2", memory = "4Gi", "ephemeral-storage" = "16Gi" }
              limits   = { memory = "8Gi" }
            }
          },
          local.dind_container,
        ]
        volumes = local.dind_volumes
      }
    }
  })]
}

# ---------------------------------------------------------------------------
# dr scale set — runs-on: openbank-dr (ADR-0277). The resilience lane:
# dr-restore-verify (#8347, #4757), the money-path chaos drill (#4755) and the
# attestation evidence jobs (#2365) need a runner that may TALK to the cluster
# — and until this scale set existed, eleven issues sat blocked on that fact.
#
# Trust posture: scheduled-workflow-only by convention enforced in
# rules.yaml (pr_jobs_allowed_pools excludes it, so PR code can never schedule
# here). The pod SA is openbank-dr with NO IRSA/cloud role; its cluster
# permissions come from a Role+RoleBinding scoped to the restore/verify
# namespaces, living in gitops (components/platform/dr-runner-rbac.yaml) so
# RBAC drift is ArgoCD-visible, and pinned by the Kyverno policy beside it.
# minRunners=0: this lane exists to run quarterly; idle spend is $0.
# ---------------------------------------------------------------------------
resource "kubernetes_service_account" "arc_dr" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "openbank-dr"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
    # Deliberately NO eks.amazonaws.com/role-arn: the DR lane has no cloud
    # permissions. Restore/verify evidence is gathered with kubectl against
    # in-cluster APIs; the S3 backup reads go through the CNPG/backup tooling's
    # own identities, not the runner's.
  }
}

resource "helm_release" "arc_dr" {
  count            = var.arc_runner_enabled ? 1 : 0
  name             = "openbank-dr"
  namespace        = kubernetes_namespace.arc_runners[0].metadata[0].name
  create_namespace = false
  repository       = "oci://ghcr.io/actions/actions-runner-controller-charts"
  chart            = "gha-runner-scale-set"
  version          = var.arc_controller_version
  depends_on       = [helm_release.arc_controller, kubectl_manifest.nodepool_runners, kubernetes_service_account.arc_dr]

  values = [yamlencode({
    githubConfigUrl    = var.github_config_url
    githubConfigSecret = "arc-github-app"
    runnerScaleSetName = "openbank-dr"
    minRunners         = 0
    maxRunners         = var.arc_dr_max_runners
    template = {
      metadata = { annotations = local.runner_pod_annotations }
      spec = {
        serviceAccountName = kubernetes_service_account.arc_dr[0].metadata[0].name
        priorityClassName  = "openbank-ci"
        nodeSelector       = local.runner_node_selector
        tolerations        = local.runner_tolerations
        affinity           = local.runner_affinity
        # No dind, no gradle caches: DR/chaos lanes run kubectl + shell, not builds.
        containers = [
          {
            name  = "runner"
            image = local.runner_image
            resources = {
              requests = { cpu = "1", memory = "1Gi", "ephemeral-storage" = "4Gi" }
              limits   = { memory = "2Gi" }
            }
          },
        ]
      }
    }
  })]
}
