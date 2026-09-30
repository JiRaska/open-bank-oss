#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Move the money-path databases (and temporal-db) off spot, ONE CLUSTER AT A TIME, by switchover
# -- never by rolling the fleet.
#
# TARGET SET: exactly the clusters the policy routes, taken from
# `check-stateful-not-on-spot.py --list-targets` (derived from rules.yaml money_path_services +
# the service -> cluster mapping in gitops; the script refuses to run if that derivation has any
# unresolved money-path service). Order: money-path clusters alphabetically, temporal-db LAST.
# Every other CNPG cluster stays on spot by the owner's decision (#11608) and is never touched.
#
# The Kyverno policy `stateful-on-demand-cel` only affects pods when they are CREATED, so a pod
# moves to on-demand capacity only when it is recreated. Spot interruptions do that by themselves
# (each one moves the pods it evicts), but as an unplanned failover. This script does it planned:
#
#   per cluster (every sandbox cluster runs instances: 2 since ADR-0325):
#     1. for each REPLICA on a spot node: delete the pod; CNPG recreates it on the same PVC, the
#        policy routes it to an on-demand node in the volume's AZ; wait until the cluster is
#        healthy again. No write outage: the primary is untouched.
#     2. if the PRIMARY is on spot: `kubectl cnpg promote` a replica that is now on on-demand
#        (a switchover: seconds of write unavailability, the same as a CNPG minor update), wait
#        healthy, then delete the old primary's pod -- now a replica -- and wait healthy again.
#   then sleep, then the next cluster.
#
# It never touches more than one cluster at once, and it stops at the first cluster that does not
# return to healthy, or whose recreated pod did NOT receive the on-demand affinity (Kyverno down
# or the policy not synced -- the pod would just land on spot again).
#
# Preconditions it checks: the `stateful` NodePool exists (sandbox-platform applied), and the
# MutatingPolicy `stateful-on-demand-cel` exists (kyverno-policies synced).
#
# Usage:
#   migrate-stateful-to-on-demand.sh                          # DRY RUN: print the plan only
#   migrate-stateful-to-on-demand.sh --apply --max 5          # migrate up to 5 clusters
#   migrate-stateful-to-on-demand.sh --apply --only ledger/ledger-db
# Options: --context <kubectx> (default openbank-sandbox), --pause <s> between clusters (120),
#          --timeout <s> per healthy-wait (900).
set -euo pipefail

CTX=openbank-sandbox
APPLY=0
MAX=5
ONLY=""
PAUSE=120
TIMEOUT=900
while [ $# -gt 0 ]; do
  case "$1" in
    --apply) APPLY=1 ;;
    --max) MAX="$2"; shift ;;
    --only) ONLY="$2"; shift ;;
    --context) CTX="$2"; shift ;;
    --pause) PAUSE="$2"; shift ;;
    --timeout) TIMEOUT="$2"; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

k() { kubectl --context "$CTX" "$@"; }
log() { printf '%s %s\n' "$(date -u +%H:%M:%SZ)" "$*"; }
run() { if [ "$APPLY" = 1 ]; then log "RUN: $*"; "$@"; else log "DRY: $*"; fi; }

capacity_of_pod() { # ns pod -> spot | on-demand | unknown
  local node
  node="$(k -n "$1" get pod "$2" -o jsonpath='{.spec.nodeName}' 2>/dev/null || true)"
  [ -n "$node" ] || { echo unknown; return; }
  k get node "$node" -o jsonpath='{.metadata.labels.karpenter\.sh/capacity-type}' 2>/dev/null || echo unknown
}

wait_healthy() { # ns cluster
  local deadline=$(( $(date +%s) + TIMEOUT )) phase ready inst
  while :; do
    phase="$(k -n "$1" get cluster "$2" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
    ready="$(k -n "$1" get cluster "$2" -o jsonpath='{.status.readyInstances}' 2>/dev/null || true)"
    inst="$(k -n "$1" get cluster "$2" -o jsonpath='{.spec.instances}' 2>/dev/null || true)"
    if [ "$phase" = "Cluster in healthy state" ] && [ -n "$ready" ] && [ "$ready" = "$inst" ]; then
      log "  $1/$2 healthy ($ready/$inst ready)"; return 0
    fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      log "  STOP: $1/$2 not healthy after ${TIMEOUT}s (phase='$phase' ready=$ready/$inst)"; return 1
    fi
    sleep 10
  done
}

assert_routed() { # ns pod -- the recreated pod must carry the policy's affinity AND be on-demand
  local aff cap
  aff="$(k -n "$1" get pod "$2" -o jsonpath='{.spec.affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution}' 2>/dev/null || true)"
  case "$aff" in *on-demand*) ;; *)
    log "  STOP: $1/$2 was recreated WITHOUT the on-demand affinity -- is Kyverno up and stateful-on-demand-cel synced?"
    return 1 ;;
  esac
  cap="$(capacity_of_pod "$1" "$2")"
  [ "$cap" = "on-demand" ] || { log "  STOP: $1/$2 is on '$cap' capacity after recreation"; return 1; }
}

recreate_pod() { # ns cluster pod
  run k -n "$1" delete pod "$3" --wait=true
  [ "$APPLY" = 1 ] || return 0
  sleep 15
  wait_healthy "$1" "$2"
  assert_routed "$1" "$3"
}

# --- preconditions --------------------------------------------------------------------------
k get nodepool stateful >/dev/null 2>&1 || { echo "NodePool 'stateful' not found: apply sandbox-platform first" >&2; exit 1; }
k get mutatingpolicies.policies.kyverno.io stateful-on-demand-cel >/dev/null 2>&1 \
  || { echo "MutatingPolicy 'stateful-on-demand-cel' not found: kyverno-policies not synced" >&2; exit 1; }
command -v kubectl-cnpg >/dev/null 2>&1 || { echo "kubectl cnpg plugin not found" >&2; exit 1; }
[ "$APPLY" = 1 ] || log "DRY RUN -- nothing will be changed; pass --apply to act"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TARGETS="$(python3 "$ROOT/.github/scripts/check-stateful-not-on-spot.py" --list-targets)" \
  || { echo "target derivation failed (unresolved money-path service) -- refusing to guess" >&2; exit 1; }
[ -n "$TARGETS" ] || { echo "derived target set is empty -- refusing to run" >&2; exit 1; }
log "targets: $(printf '%s\n' "$TARGETS" | wc -l | tr -d ' ') clusters (temporal-db last)"

done_n=0
printf '%s\n' "$TARGETS" | while IFS=/ read -r ns cl; do
  [ -z "$ONLY" ] || [ "$ONLY" = "$ns/$cl" ] || continue
  [ "$done_n" -lt "$MAX" ] || { log "reached --max $MAX; stopping"; break; }
  primary="$(k -n "$ns" get cluster "$cl" -o jsonpath='{.status.currentPrimary}')"
  pods="$(k -n "$ns" get pods -l "cnpg.io/cluster=$cl,cnpg.io/podRole=instance" -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}')"
  spot=""
  for p in $pods; do [ "$(capacity_of_pod "$ns" "$p")" = "on-demand" ] || spot="$spot $p"; done
  [ -n "$spot" ] || { log "$ns/$cl: already on on-demand, skipping"; continue; }
  log "$ns/$cl: primary=$primary, on spot:$spot"
  wait_healthy "$ns" "$cl" || exit 1
  # 1. replicas first -- no write outage
  for p in $spot; do
    [ "$p" = "$primary" ] && continue
    recreate_pod "$ns" "$cl" "$p" || exit 1
  done
  # 2. then the primary, by switchover
  case " $spot " in *" $primary "*)
    target=""
    for p in $pods; do [ "$p" != "$primary" ] && target="$p"; done
    [ -n "$target" ] || { log "  STOP: $ns/$cl has no replica to switch over to"; exit 1; }
    run kubectl cnpg --context "$CTX" -n "$ns" promote "$cl" "$target"
    if [ "$APPLY" = 1 ]; then sleep 20; wait_healthy "$ns" "$cl" || exit 1; fi
    recreate_pod "$ns" "$cl" "$primary" || exit 1
    ;;
  esac
  done_n=$((done_n + 1))
  log "$ns/$cl: done ($done_n/$MAX); pausing ${PAUSE}s"
  [ "$APPLY" = 1 ] && sleep "$PAUSE"
done
