# ===========================================================================
# ARC stuck-runner reaper (ADR-0053 self-heal guard).
#
# WHY: a known ARC edge case leaks a node. When a queued GitHub job is
# cancelled/superseded (e.g. a newer push replaces the workflow run) AFTER ARC
# has already scaled a runner up for it, the EphemeralRunner registers, never
# receives a job, and never exits. ARC's listener keeps counting it as
# currentRunners>0 and will NOT reap a runner in phase=Running (it assumes it
# may be mid-job), so the orphan pins a Karpenter spot node indefinitely even
# though the scale set is otherwise idle. Observed 2026-06-02: an
# openbank-build runner sat Running+jobless for ~10h holding a c6gd.2xlarge.
#
# Upstream has no knob for this — the gha-runner-scale-set chart / controller
# expose no runner idle/registration timeout that reaps an already-registered,
# jobless runner (issues actions/actions-runner-controller#4307, #4203, #4423).
# The documented community fix is exactly this: a small periodic job that
# deletes EphemeralRunners stuck Running with no job. So we codify it.
#
# SAFETY — the reaper must never kill a runner that has (or is about to get)
# real work, and must not fight the warm-runner lever:
#   1. Triple "never had a job" guard: jobRequestId == 0 AND jobId == "" AND
#      jobRepositoryName == "". A runner mid-job has jobRequestId>0, so it is
#      never a candidate; a finished job leaves phase Succeeded/Failed, not
#      Running.
#   2. Age threshold (default 30m): tolerates the transient jobless window while
#      a healthy burst's freshly-scaled runners wait to be assigned. Jobs are
#      1-31 min (ADR-0053), so >30 min idle-and-jobless is unambiguously stuck.
#   3. Two reap rules per scale set, so the warm-runner lever survives AND a
#      frozen orphan is always healed (minRunners read live from each
#      AutoscalingRunnerSet, never hardcoded — rule #7):
#        (a) FAST path — excess over minRunners, idle past the threshold. Keeps
#            the minRunners OLDEST idle runners (the persistent warm runner, which
#            in the common quiet-pool case is older than a freshly-orphaned burst
#            runner) and reaps the younger excess. Catches a coexisting orphan in
#            ~one threshold.
#        (b) BACKSTOP — ANY idle runner past a hard cap (4x threshold), even the
#            lone min-slot runner. This is REQUIRED, not belt-and-suspenders:
#            ARC will not reap a phase=Running runner during scale-down (assumes
#            mid-job), so a stuck orphan SURVIVES scale-down and can become the
#            sole runner occupying the minRunners slot — at which point there is
#            no "excess" for (a) to find and the node would leak forever. The
#            backstop recycles it; ARC replaces it with a FRESH runner (age 0).
#            Churn is bounded and cheap (a respin in seconds, usually on the same
#            warm node): in steady state with no orphan a healthy warm runner is
#            recycled at most once per hard cap, and only during quiet periods
#            when it serves no burst. (During an active incident where a coexisting
#            orphan is OLDER than the warm runner, rule (a) protects the orphan and
#            recycles the warm runner ~once per threshold until the orphan crosses
#            the hard cap and is backstop-reaped — a few recycles, self-resolving.)
#            Either way a frozen orphan is guaranteed gone within the hard cap.
#   4. Atomic compare-and-delete: each reap carries the runner's resourceVersion
#      as a delete precondition. If the runner changed between the scan and the
#      delete — e.g. ARC just assigned it a job (a status write bumps
#      resourceVersion) — the DELETE is a no-op (409) and is skipped, so we never
#      kill a runner that picked up work in the interim. Benign periodic token
#      refresh doesn't hit the sub-second scan->delete window.
#
# Least-moving-parts by design: one CronJob + its RBAC + a script ConfigMap, in
# the arc-runners namespace, talking to the in-cluster API with a namespaced SA
# (list/delete EphemeralRunners only). No Lambda, no CloudWatch, no webhook, no
# second autoscaler. Self-hosted-friendly. Gated behind var.arc_runner_enabled
# alongside the scale sets.
# ===========================================================================

locals {
  arc_reaper_image = "public.ecr.aws/docker/library/alpine:3.21.3" # ECR Public (anonymous, un-throttled), arch-multi; jq+curl via apk

  # POSIX sh + jq, run against the in-cluster Kubernetes API with the reaper SA
  # token. No ${...}/%{...} sequences here on purpose, so the script passes
  # through the HCL heredoc verbatim (no escaping). See the header for the
  # selection logic; the jq below is its executable form.
  arc_reaper_script = <<-SCRIPT
    #!/bin/sh
    set -eu
    apk add --no-cache --quiet jq curl >/dev/null

    API=https://kubernetes.default.svc
    NS=arc-runners
    GROUP=actions.github.com/v1alpha1
    TOKEN=$(cat /var/run/secrets/kubernetes.io/serviceaccount/token)
    CACERT=/var/run/secrets/kubernetes.io/serviceaccount/ca.crt
    THRESHOLD=$IDLE_THRESHOLD_SECONDS
    HARDCAP=$HARD_CAP_SECONDS

    get() {
      curl -sS --fail --cacert "$CACERT" -H "Authorization: Bearer $TOKEN" \
        "$API/apis/$GROUP/namespaces/$NS/$1"
    }

    get ephemeralrunners      > /tmp/er.json
    get autoscalingrunnersets > /tmp/ars.json

    # Candidates: phase=Running EphemeralRunners that have never been assigned a
    # job (the triple guard), reaped by rule (a) excess-over-minRunners past the
    # threshold, or rule (b) the hard-cap backstop. See the header for why both.
    NAMES=$(jq -r --slurpfile ars /tmp/ars.json \
      --argjson threshold "$THRESHOLD" --argjson hardcap "$HARDCAP" '
      ( ($ars[0].items // [])
        | map({ key: .metadata.name, value: (.spec.minRunners // 0) })
        | from_entries
      ) as $min
      | (.items // [])
      | map(select(
            (.status.phase == "Running")
            and ((.status.jobRequestId // 0) == 0)
            and ((.status.jobId // "") == "")
            and ((.status.jobRepositoryName // "") == "")
        ))
      | map({
          name: .metadata.name,
          rv:   .metadata.resourceVersion,
          set:  (.metadata.labels["actions.github.com/scale-set-name"] // "UNKNOWN"),
          age:  (now - (.metadata.creationTimestamp | fromdateiso8601))
        })
      | group_by(.set)
      | map(
          .[0].set as $set
          | ($min[$set] // 0) as $keep
          | (sort_by(.age) | reverse) as $byage   # oldest first
          | (
              # (a) excess over minRunners, idle past threshold: protect the
              #     $keep OLDEST (the persistent warm runner), reap younger excess.
              ($byage[$keep:] | map(select(.age > $threshold)))
              # (b) backstop: ANY idle runner past the hard cap, even the lone
              #     min-slot runner — heals a frozen orphan ARC kept through
              #     scale-down. Recycling forces a fresh (age 0) replacement, so
              #     warm-runner churn is bounded to once per hard cap.
              + ($byage | map(select(.age > $hardcap)))
            )
          | unique_by(.name)
        )
      | flatten
      | .[] | "\(.name)\t\(.rv)"
    ' /tmp/er.json)

    if [ -z "$NAMES" ]; then
      echo "reaper: no stuck runners (excess idle > $THRESHOLD s, or any idle > $HARDCAP s)"
      exit 0
    fi

    echo "$NAMES" | while read -r n rv; do
      [ -z "$n" ] && continue
      echo "reaper: deleting stuck EphemeralRunner $n (Running, no job, idle > threshold; rv=$rv)"
      # Atomic compare-and-delete: the resourceVersion precondition makes the
      # DELETE a no-op (409) if the runner changed since the scan — e.g. ARC just
      # assigned it a job (status write bumps resourceVersion). Closes the
      # scan->delete race; benign token refresh won't hit that sub-second window.
      if curl -sS --fail -X DELETE \
           -H "Content-Type: application/json" \
           --cacert "$CACERT" -H "Authorization: Bearer $TOKEN" \
           --data "{\"kind\":\"DeleteOptions\",\"apiVersion\":\"v1\",\"preconditions\":{\"resourceVersion\":\"$rv\"}}" \
           "$API/apis/$GROUP/namespaces/$NS/ephemeralrunners/$n" >/dev/null; then
        echo "reaper: deleted $n"
      else
        echo "reaper: skipped $n (changed since scan — likely just assigned a job, or already gone)"
      fi
    done
  SCRIPT
}

# --- RBAC: namespaced SA that can list+delete EphemeralRunners only ----------
resource "kubernetes_service_account" "arc_reaper" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "arc-stuck-runner-reaper"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
  }
}

resource "kubernetes_role" "arc_reaper" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "arc-stuck-runner-reaper"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
  }
  # list to find candidates + the AutoscalingRunnerSet minRunners; delete to reap.
  # No create/patch — the reaper can only remove orphans, never mutate live ones.
  rule {
    api_groups = ["actions.github.com"]
    resources  = ["ephemeralrunners"]
    verbs      = ["get", "list", "delete"]
  }
  rule {
    api_groups = ["actions.github.com"]
    resources  = ["autoscalingrunnersets"]
    verbs      = ["get", "list"]
  }
}

resource "kubernetes_role_binding" "arc_reaper" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "arc-stuck-runner-reaper"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "Role"
    name      = kubernetes_role.arc_reaper[0].metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account.arc_reaper[0].metadata[0].name
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
  }
}

resource "kubernetes_config_map" "arc_reaper" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "arc-stuck-runner-reaper"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
  }
  data = { "reap.sh" = local.arc_reaper_script }
}

# --- The reaper itself: a low-frequency CronJob on the default (non-runner)
# nodes, so the guard never itself provokes a runner-pool scale-up. ----------
resource "kubernetes_cron_job_v1" "arc_reaper" {
  count = var.arc_runner_enabled ? 1 : 0
  metadata {
    name      = "arc-stuck-runner-reaper"
    namespace = kubernetes_namespace.arc_runners[0].metadata[0].name
  }
  spec {
    schedule                      = var.arc_reaper_schedule
    concurrency_policy            = "Forbid"
    starting_deadline_seconds     = 200
    successful_jobs_history_limit = 3
    # 3 -> 1: KubeJobFailed fires per surviving failed Job OBJECT, so keeping three
    # turns one recurring failure into three permanent alerts, and the pods are
    # garbage-collected long before the objects are — the extra tombstones carry the
    # alert and none of the evidence. Measured 2026-08-02: this reaper alone accounted
    # for 3 of the 11 firing KubeJobFailed, its oldest from 07-28 with pods=0.
    failed_jobs_history_limit = 1

    job_template {
      metadata {}
      spec {
        active_deadline_seconds = 300 # a hung API call can never outlive one window
        backoff_limit           = 1

        template {
          metadata {}
          spec {
            service_account_name = kubernetes_service_account.arc_reaper[0].metadata[0].name
            restart_policy       = "OnFailure"

            container {
              name    = "reaper"
              image   = local.arc_reaper_image
              command = ["/bin/sh", "/scripts/reap.sh"]

              env {
                name  = "IDLE_THRESHOLD_SECONDS"
                value = tostring(var.arc_reaper_idle_threshold_minutes * 60)
              }

              # Hard-cap backstop = 4x the idle threshold. Past this, ANY idle
              # runner is recycled even within minRunners, so a frozen orphan that
              # took the min slot is healed; the fresh replacement resets the clock,
              # so steady-state warm-runner churn is at most once per cap.
              env {
                name  = "HARD_CAP_SECONDS"
                value = tostring(var.arc_reaper_idle_threshold_minutes * 60 * 4)
              }

              volume_mount {
                name       = "script"
                mount_path = "/scripts"
                read_only  = true
              }

              resources {
                requests = { cpu = "50m", memory = "64Mi" }
                limits   = { memory = "128Mi" }
              }
            }

            volume {
              name = "script"
              config_map {
                name         = kubernetes_config_map.arc_reaper[0].metadata[0].name
                default_mode = "0555"
              }
            }
          }
        }
      }
    }
  }
}
