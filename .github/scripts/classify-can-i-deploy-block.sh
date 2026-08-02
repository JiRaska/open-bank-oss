#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Classify WHY can-i-deploy blocked a service (issue #2549, proposal 2).
#
# THE PROBLEM THIS SOLVES
# `can-i-deploy` returns the same verdict — "NOT deployable" — for two situations with
# opposite operational meanings:
#
#   * the service's build has not finished, so it has published NO pact for this commit
#     yet. Measured on 9f138c133 (#2475, a 29-module sweep): the main-push lane serialises
#     on one ARC runner at ~45 min per service, so the broker legitimately 404s on
#     /pacticipants/<svc>/versions/<sha> for HOURS. This clears itself — the every-3h
#     reconcile tick (auto-deploy-reconcile-lag.sh, #2020) re-drives the service and it
#     deploys the moment its pacts land.
#
#   * a contract genuinely regressed. This NEVER clears itself. Every reconcile tick will
#     re-offer it, be blocked again, and — before this script — be silenced as an
#     "expected" reconcile strand, forever.
#
# Both read identically in the one place a human looks, so a queue delay and a real
# regression are indistinguishable, and the second is retried forever in silence. That is
# the composition defect #2549 describes.
#
# WHY THIS CHANGES NO DEPLOY BEHAVIOUR
# The gate is untouched: a blocked service stays blocked under every classification. This
# only labels the block, so the summary says which kind it is and so a REGRESSION can keep
# failing the run's status on a scheduled tick where a PENDING_BUILD deliberately does not.
#
# WHY A SEPARATE SCRIPT
# Inline in auto-deploy.yml this logic is unreachable by any test, and a classifier whose
# wrong branch is invisible is worse than no classifier — it puts a confident label on a
# guess. Here it is a pure function of (broker probe result, CLI output) with no network of
# its own, so test-classify-can-i-deploy-block.sh can drive every branch.
#
# Usage:
#   PACT_VERSION_PRESENT=yes|no|absent|unknown classify-can-i-deploy-block.sh <service> < cli-output
#
#   PACT_VERSION_PRESENT — the caller's probe of
#     GET <broker>/pacticipants/<svc>/versions/<sha>:
#       no      → 404, this commit's build has published nothing yet
#       yes     → 200, a version exists for this commit
#       unknown → the probe itself failed (broker unreachable / non-2xx-non-404)
#
# Prints one TAB-separated line: <CLASS>\t<human reason>
#   PENDING_BUILD — transient; the reconcile tick clears it. Not a regression.
#   REGRESSION    — a published pact failed verification. Will never self-clear.
#   UNVERIFIED    — a version exists but no verification result yet. Usually the
#                   counterpart provider lagging by minutes; treated as transient, but
#                   named separately so it is not mistaken for a passed verification.
#   UNKNOWN       — could not tell. Deliberately distinct from the three above: an
#                   unclassifiable block must not borrow PENDING_BUILD's "it'll fix
#                   itself" reassurance.
# Always exits 0 — this is a labeller, not a gate.
set -uo pipefail

SVC="${1:-}"
if [ -z "$SVC" ]; then
  echo "usage: PACT_VERSION_PRESENT=yes|no|absent|unknown $0 <service> < cli-output" >&2
  exit 2
fi

PRESENT="${PACT_VERSION_PRESENT:-unknown}"
OUT="$(cat)"

emit() { printf '%s\t%s\n' "$1" "$2"; }

# 1. A genuine verification failure is the only class that never self-clears, so it is
#    tested FIRST — even when the broker probe says "no version for this sha". Those two
#    can coexist: the sha under test may have published nothing while the LATEST main
#    version (which is what can-i-deploy actually queries, `--latest main`) has a failed
#    verification. Ordering it after the 404 probe would relabel a live regression as
#    "still building" and silence it — the exact failure this script exists to prevent.
if grep -qiE 'pact (verification )?failed|verification.*(^| )failed|failed verification' <<< "$OUT"; then
  emit REGRESSION "a published pact FAILED verification — this will not clear on its own; a reconcile tick will re-offer it forever"
  exit 0
fi

case "$PRESENT" in
  no)
    emit PENDING_BUILD "no pact version published for this commit yet — its main-push build has not finished (one ARC runner, ~45 min/service); the 3-hourly reconcile re-drives it automatically"
    exit 0
    ;;
  yes)
    if grep -q 'There is no verified pact' <<< "$OUT"; then
      emit UNVERIFIED "a version exists for this commit but the counterpart has not verified it yet — normally clears within one reconcile tick"
      exit 0
    fi
    emit UNKNOWN "blocked with a version present and no recognised reason in the CLI output — read the job log for ${SVC}"
    exit 0
    ;;
  absent)
    # The probe SUCCEEDED and the answer was "no such pacticipant". Distinct from the `*` branch
    # below, which means the probe itself failed — labelling this one "could not probe" would
    # describe an outage that did not happen, and hide the real state: a service with no
    # contracts at all. No reconcile tick can change this; publishing a pact can.
    emit NO_CONTRACTS "the broker does not know ${SVC} — it publishes no consumer pact and verifies no provider pact, so no reconcile tick will clear this; the fix is a contract (#2991), not a retry"
    exit 0
    ;;
  *)
    emit UNKNOWN "could not probe the broker for ${SVC}'s version, so the block is unclassified — do NOT read this as transient"
    exit 0
    ;;
esac
