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
#   PACT_VERSION_PRESENT=yes|no|absent|unknown|equivalent:<sha> [EVENT_NAME=<github.event_name>] \
#     classify-can-i-deploy-block.sh <service> < cli-output
#
#   EVENT_NAME — the triggering event, same vocabulary and same default (push) as
#     resolve-can-i-deploy-selector.sh. Only (no + workflow_dispatch) is treated specially;
#     see the NOT_ASKED note below.
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
#   NOT_ASKED     — the gate was never asked (#3454). resolve-can-i-deploy-selector.sh
#                   REFUSEd to pick a selector, so no can-i-deploy verdict exists at all.
#                   See the NOT_ASKED note below for why this is not PENDING_BUILD.
#   UNKNOWN       — could not tell. Deliberately distinct from the three above: an
#                   unclassifiable block must not borrow PENDING_BUILD's "it'll fix
#                   itself" reassurance.
#
# WHY NOT_ASKED IS A CLASS AND NOT `PENDING_BUILD` (issue #3454)
# The REFUSE branch in auto-deploy.yml recorded a blocked service with NO class, so every
# refusal printed downstream as "[UNKNOWN] — no classification recorded for this service".
# Measured on run 30765380309, a fleet workflow_dispatch of e4821ef6: all 54 services
# refused, all 54 reported UNKNOWN, deployable=[].
#
# The tempting fix is to tag it PENDING_BUILD — the observable condition is byte-identical
# (no published pact version for this sha). It is the wrong label, because PENDING_BUILD
# does not only describe a state, it PROMISES one: "the 3-hourly reconcile re-drives it
# automatically". That promise holds on a push, where the sha IS main's head and its
# services-ci build is genuinely in flight. It does not hold for a dispatch, because
# services-ci is PATH-SCOPED: on any given commit it builds only the services that commit
# touched, so for the other ~53 no pact version for that sha will ever exist, no matter how
# many reconcile ticks pass. Tagging those PENDING_BUILD asserts a self-clearing property
# that is false — a confident label on a guess, which is the failure this file's header
# already warns against.
#
# It is equally not UNKNOWN. UNKNOWN means "could not tell"; here we can tell exactly, and
# the remedy is specific and different from every other class: nothing about the CONTRACTS
# is wrong, the REQUEST is unanswerable. Deploy the sha services-ci actually built for this
# service (let the push/reconcile lane carry it), or publish a pact version for this sha.
#
# WHY ONE CLASS AND NOT TWO. Two sub-cases hide behind one refusal — "dispatched sha is
# main's head with a build in flight" (will gain a version) and "dispatched sha can never
# gain one". Splitting them needs evidence this script does not have and cannot get without
# a network call, and the two sub-cases have the SAME consequences everywhere the class is
# consumed: neither is co-deploy evidence, neither may be silenced as transient, and
# re-running the same dispatch fixes neither. A distinction with no distinct consequence,
# bought with a guess, is exactly what the header forbids.
#
# WHY THE EVENT IS AN INPUT HERE TOO. NOT_ASKED must fire on precisely the inputs on which
# resolve-can-i-deploy-selector.sh emits REFUSE, and nothing structural forces that: they
# are two files. So both derive their answer from the SAME pair (PACT_VERSION_PRESENT,
# EVENT_NAME) rather than the workflow hardcoding a class string next to the REFUSE branch,
# and test-classify-can-i-deploy-block.sh asserts the equivalence over the whole input
# cross-product. A future edit to either file's condition reddens that test.
#
# Always exits 0 — this is a labeller, not a gate.
set -uo pipefail

SVC="${1:-}"
if [ -z "$SVC" ]; then
  echo "usage: PACT_VERSION_PRESENT=yes|no|absent|unknown|equivalent:<sha> [EVENT_NAME=<event>] $0 <service> < cli-output" >&2
  exit 2
fi

PRESENT="${PACT_VERSION_PRESENT:-unknown}"
# Same default as resolve-can-i-deploy-selector.sh: an absent EVENT_NAME behaves as a push,
# so a caller that forgets to pass it keeps today's labelling instead of inventing NOT_ASKED.
EVENT="${EVENT_NAME:-push}"
OUT="$(cat)"

emit() { printf '%s\t%s\n' "$1" "$2"; }

# 0. NOT_ASKED is tested before EVERYTHING, including the regression check, and that is
#    deliberate. On exactly these inputs resolve-can-i-deploy-selector.sh emits REFUSE, so
#    can-i-deploy is never invoked and there is no verdict — whatever arrives on stdin (the
#    caller sends nothing) is not one. Reporting REGRESSION from it would name a
#    verification failure the gate never observed. Keeping this first also makes the
#    NOT_ASKED ⇔ REFUSE equivalence hold for ANY stdin, which is what the cross-product
#    test in test-classify-can-i-deploy-block.sh asserts.
if [ "$PRESENT" = "no" ] && [ "$EVENT" = "workflow_dispatch" ]; then
  emit NOT_ASKED "the contract gate was never asked about this commit — a manual dispatch of a sha with no published pact version for ${SVC}, so the selector REFUSEd rather than answer about a different commit (#3318). This is NOT self-clearing: services-ci is path-scoped, so a sha that did not touch ${SVC} never gains a version, and re-running the same dispatch cannot change that. Deploy the sha services-ci built for ${SVC} (the push/reconcile lane), or publish a pact version for this one"
  exit 0
fi

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
  # #3432: the gate WAS asked, and by version number — the probe proved from git that the version
  # it named is byte-identical to the sha being deployed in every build input of this service. So
  # this is the `yes` shape, not the `no` one: a real verdict about the real source exists, and
  # PENDING_BUILD would be a lie (nothing is building; the answer arrived).
  equivalent:*)
    if grep -q 'There is no verified pact' <<< "$OUT"; then
      emit UNVERIFIED "the counterpart has not verified this version yet — normally clears within one reconcile tick"
      exit 0
    fi
    emit UNKNOWN "blocked with a proven-equivalent version asked about by number and no recognised reason in the CLI output — read the job log for ${SVC}"
    exit 0
    ;;
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
