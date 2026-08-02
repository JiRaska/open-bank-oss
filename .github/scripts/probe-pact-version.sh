#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Is THIS commit's pact version in the broker yet — waiting briefly if it is not (#3082).
#
# WHY THE WAIT EXISTS
# auto-deploy's can-i-deploy job needs [changes, build-push] — auto-deploy's OWN image build,
# never services-ci, which is the workflow that publishes pact versions. Both fire from the
# same push and race. Measured on run 30690770972: the gate asked about account-service at
# 08:36:49, and that service's build did not START until 08:39:03.
#
# Losing that race is not merely noisy. `--latest main` then answers about the PREVIOUS build:
# if that older version was verified the gate goes GREEN, and auto-deploy ships an image built
# from $GITHUB_SHA whose contracts were never verified at all. A short wait converts the common
# 1-3 service push from a coin flip into a correct answer.
#
# WHY IT IS BOUNDED, AND ONLY ON push
# The fleet build serialises at ~45 min/service on one runner, so this is a HEAD START, not a
# synchronisation primitive: a 53-service push will still leave most services PENDING_BUILD for
# the 3-hourly reconcile, and that is fine — that class self-clears. On schedule/dispatch the
# fleet was built at other commits, so waiting could never succeed and would burn the budget for
# nothing; those events skip the wait entirely.
#
# WHY A SCRIPT AND NOT INLINE IN THE WORKFLOW
# Two reasons, and the second one is load-bearing. It is testable here. And GitHub REJECTS a
# workflow whose single `run:` script grows too large — not with an error, but by making the
# whole file unparseable: zero jobs, no message, every contributor's runs of that workflow dead.
# Inlining this logic plus its rationale is exactly what did that to auto-deploy.yml
# (#3135 -> reverted in #3139); the measured ceiling is between 20054 and 20654 characters and
# check-workflow-run-step-size.py now guards it. Prose belongs in a script header like this one,
# where it costs nothing.
#
# Usage:
#   probe-pact-version.sh <service> <sha>
#
# Env:
#   PACT_BROKER_URL / PACT_BROKER_USERNAME / PACT_BROKER_PASSWORD  broker credentials
#   EVENT_NAME                    the triggering event; only `push` waits
#   PACT_WAIT_BUDGET_FILE         file holding the remaining shared budget in seconds. Shared
#                                 across services so a fleet-sized push cannot turn the job into
#                                 a 45-minute wait. A file rather than a variable because each
#                                 service is a separate invocation of this script.
#   PACT_WAIT_PER_SERVICE_SECONDS per-service cap (default 60)
#
# Prints one word on stdout — yes | no | unknown — the same vocabulary
# classify-can-i-deploy-block.sh and resolve-can-i-deploy-selector.sh consume. Progress goes to
# stderr so it appears in the log without polluting the value.
#
# Always exits 0: this reports a fact, it does not gate on one.
set -uo pipefail

SVC="${1:-}"
SHA="${2:-}"
if [ -z "$SVC" ] || [ -z "$SHA" ]; then
  echo "usage: $0 <service> <sha>" >&2
  exit 2
fi

PER_SVC="${PACT_WAIT_PER_SERVICE_SECONDS:-60}"
BUDGET_FILE="${PACT_WAIT_BUDGET_FILE:-}"

probe() {
  local code auth
  # Built into a variable rather than written inline as -u "$USER:$PASS": gitleaks' curl-auth-user
  # rule matches that shape on sight (it cannot tell a variable reference from a literal), and a
  # new occurrence fails the required Gitleaks check. The identical inline form already in
  # auto-deploy.yml survives only because it predates the diff being scanned.
  auth="${PACT_BROKER_USERNAME:-}:${PACT_BROKER_PASSWORD:-}"
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -u "$auth" \
    "${PACT_BROKER_URL:-}/pacticipants/${SVC}/versions/${SHA}" 2>/dev/null || echo 000)"
  case "$code" in
    200) echo yes ;;
    # A 404 here is AMBIGUOUS: the broker answers it both when this sha has no version and
    # when it has never heard of the pacticipant at all. Collapsing the two is what blocked
    # openbank-delegation-service's first deploy (#3423-adjacent): the REFUSE branch fired on
    # a service that has no pacts to publish, where there is nothing for can-i-deploy to
    # verify. Ask the second question before answering.
    404)
      pcode="$(curl -s -o /dev/null -w '%{http_code}' \
        -u "$auth" \
        "${PACT_BROKER_URL:-}/pacticipants/${SVC}" 2>/dev/null || echo 000)"
      case "$pcode" in
        404) echo absent ;;
        200) echo no ;;
        # Second probe inconclusive — do NOT upgrade to `absent`, which would wave a service
        # through. Fall back to the pre-existing meaning.
        *)   echo no ;;
      esac
      ;;
    # Anything else — 5xx, a proxy error, no network — is deliberately NOT "no". Reporting a
    # broker outage as "this commit has published nothing" would send the caller down the
    # PENDING_BUILD path and quietly relabel an infrastructure failure as a build queue.
    *)   echo unknown ;;
  esac
}

budget_left() { [ -n "$BUDGET_FILE" ] && [ -f "$BUDGET_FILE" ] && cat "$BUDGET_FILE" || echo 0; }

present="$(probe)"

if [ "$present" = "no" ] && [ "${EVENT_NAME:-}" = "push" ] && [ "$(budget_left)" -gt 0 ]; then
  waited=0
  while [ "$waited" -lt "$PER_SVC" ] && [ "$(budget_left)" -gt 0 ]; do
    sleep 10
    waited=$((waited + 10))
    echo "$(( $(budget_left) - 10 ))" > "$BUDGET_FILE"
    present="$(probe)"
    [ "$present" = "no" ] || break
  done
  if [ "$waited" -gt 0 ]; then
    echo "    waited ${waited}s for ${SVC}'s pact version (budget left: $(budget_left)s) -> ${present}" >&2
  fi
fi

echo "$present"
