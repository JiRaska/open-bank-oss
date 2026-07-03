# Overnight scale-down of the EKS bootstrap node group (FinOps, issue #196).
#
# WHY: the bootstrap pool (envs/sandbox-substrate, 2x t4g.medium on-demand,
# node_min_size=2) runs the cluster's own system pods — CoreDNS, the Karpenter
# controller, ArgoCD, cert-manager — 24/7, even though Karpenter's own
# consolidation freeze (this root's main.tf, "0 20 * * *" / 11h) already
# proves this platform treats 20:00-07:00 UTC as a low-traffic window. That
# freeze only stops node CHURN though; it doesn't reduce bootstrap capacity.
#
# THIS IS DELIBERATELY CONSERVATIVE: scale 2 -> 1, never to 0. Losing the
# second bootstrap node overnight halves on-demand cost for that window
# (~$11-22/month) while keeping ONE node alive at all times for CoreDNS /
# ArgoCD / the Karpenter controller itself — a true 0 would risk the cluster
# being unable to self-heal or serve DNS during the window.
#
# TRADE-OFF (read before merging): the public demo (open-bank.tech) may see
# degraded control-plane responsiveness (slower ArgoCD sync, single point of
# failure for CoreDNS) during 20:00-07:00 UTC. It should NOT take the demo
# fully offline — the customer-facing services run on the separate Karpenter
# "runners" workload NodePool, which this schedule does not touch — but this
# has NOT been load-tested. Recommend a manual dry run (watch `kubectl get
# nodes -l openbank.io/pool=bootstrap` and cluster health across one
# overnight window) before trusting it unattended.
#
# Lives in THIS root (sandbox-platform), not sandbox-substrate where the node
# group itself is defined: platform-tofu.yml only plans/applies this root
# (ENV_DIR), and sandbox-substrate has no CI pipeline at all — a schedule
# resource placed there would never actually be planned or applied.
#
# Looked up by TAG, not by a sandbox-substrate output/remote-state reference:
# EKS stamps every managed-node-group ASG with `eks:cluster-name` +
# `eks:nodegroup-name` automatically, so this root can find it without any
# change to (or re-apply of) sandbox-substrate — avoiding a cross-root
# apply-ordering dependency that CI can't satisfy on its own (an earlier
# version of this file used terraform_remote_state for a new substrate
# output, which failed `tofu plan` here because substrate has no CI to ever
# apply that output — self-caught before merge).
#
# Same 20:00-07:00 UTC boundary as the Karpenter consolidation freeze, for one
# consistent "off hours" definition across the platform.

data "aws_autoscaling_groups" "bootstrap" {
  filter {
    name   = "tag:eks:nodegroup-name"
    values = ["${local.cluster_name}-bootstrap"]
  }
}

resource "aws_autoscaling_schedule" "bootstrap_scale_down" {
  scheduled_action_name  = "openbank-bootstrap-scale-down"
  autoscaling_group_name = data.aws_autoscaling_groups.bootstrap.names[0]

  recurrence = "0 20 * * *" # 20:00 UTC daily
  time_zone  = "UTC"

  min_size         = 1
  max_size         = 4 # unchanged; only the floor drops overnight
  desired_capacity = 1
}

resource "aws_autoscaling_schedule" "bootstrap_scale_up" {
  scheduled_action_name  = "openbank-bootstrap-scale-up"
  autoscaling_group_name = data.aws_autoscaling_groups.bootstrap.names[0]

  recurrence = "0 7 * * *" # 07:00 UTC daily — matches the Karpenter freeze end
  time_zone  = "UTC"

  min_size         = 2
  max_size         = 4
  desired_capacity = 2
}
