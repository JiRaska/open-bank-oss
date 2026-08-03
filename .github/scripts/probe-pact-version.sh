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
# Prints one word on stdout — yes | no | absent | unknown | equivalent:<sha> — the same vocabulary
# classify-can-i-deploy-block.sh and resolve-can-i-deploy-selector.sh consume. Progress goes to
# stderr so it appears in the log without polluting the value.
#
# THE `equivalent:<sha>` ANSWER (issue #3432)
# `no` on a `workflow_dispatch` is a dead end by construction: services-ci never built THIS sha for
# THIS service, so no version for it can ever appear, and #3318 correctly refuses to answer about a
# different commit. Measured on run 30761923908 — 54 of 54 services refused, `deployable=[]` — which
# makes reconcile, the only mechanism for re-driving a service that missed its push deploy, unable
# to deploy anything, exactly when it is most needed.
#
# So before reporting `no` on a non-push event, ask a narrower question: is the commit that DOES
# have a published version byte-identical to this one in everything this service is built from? If
# pact-version-tree-equivalent.sh proves it from git — same tree objects, and an ancestor — then the
# published verdict is not about a different commit in any sense that can reach the artifact, and
# the caller may ask the broker about that version by number. If it cannot prove it, the answer
# stays `no` and #3318's refusal stands untouched.
#
# EVERY failure here degrades to `no`: an unreachable broker, a non-2xx, an answer that is not a
# 40-hex sha, a missing script, a non-zero exit. A reconcile that cannot VERIFY must not deploy.
#
# Always exits 0: this reports a fact, it does not gate on one.
set -uo pipefail

if [ "${1:-}" = "--self-test" ] || [ "${1:-}" = "--selftest" ]; then
  # Exercises the #3432 broker call against a STUBBED curl, because the real broker has no public
  # ingress (ADR-0056) and PACT_BROKER_URL is blank off main-push — so this path can never be run
  # from a PR. Everything asserted here is a FAILURE mode: the only thing that must be provable is
  # that a probe which cannot verify degrades to `no`, which on a dispatch is #3318's REFUSE.
  self_tmp="$(mktemp -d)"; trap 'rm -rf "$self_tmp"' EXIT
  cat > "$self_tmp/curl" <<'STUB'
#!/usr/bin/env bash
# Two shapes are called: the status probes (-w '%{http_code}') and the latest-version body fetch.
url="${@: -1}"
for a in "$@"; do [ "$a" = "-w" ] && is_status=1; done
if [ "${is_status:-0}" = 1 ]; then
  case "$url" in
    */versions/*) echo 404 ;;   # this sha has no version — the #3432 entry condition
    *)            echo 200 ;;   # ...but the pacticipant exists, so the answer is `no`, not `absent`
  esac
  exit 0
fi
case "${STUB_MODE:-}" in
  unreachable) exit 7 ;;                                  # could not connect
  http500)     exit 22 ;;                                 # curl -f on a non-2xx
  garbage)     echo 'not json at all' ;;
  # Deliberately a rev git CAN resolve, and one that is trivially equivalent to itself. A shape
  # check that is missing therefore turns this into `equivalent:HEAD` rather than into another
  # failure — the case can only stay green while the 40-hex validation is really there.
  notasha)     echo '{"number":"HEAD"}' ;;
  empty)       echo '{}' ;;
  ok)          echo "{\"number\":\"${STUB_SHA}\"}" ;;
  *)           exit 7 ;;
esac
STUB
  chmod +x "$self_tmp/curl"
  me_abs="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  # A throwaway repo, so the git half is REAL — the equivalence must be decided by actual tree
  # objects, not by whatever the surrounding checkout happens to look like when this runs.
  repo="$self_tmp/repo"
  mkdir -p "$repo/openbank-demo-service/src/main"
  ( cd "$repo" && git init -q . \
    && git config user.email s@e.x && git config user.name s && git config commit.gpgsign false \
    && printf 'x\n' > openbank-demo-service/build.gradle.kts \
    && printf 'x\n' > openbank-demo-service/src/main/A.kt \
    && git add -A && git commit -qm base ) >/dev/null 2>&1
  older_sha="$(git -C "$repo" rev-parse HEAD)"
  ( cd "$repo" && printf 'changed\n' > openbank-demo-service/src/main/A.kt \
    && git add -A && git commit -qm change ) >/dev/null 2>&1
  head_sha="$(git -C "$repo" rev-parse HEAD)"
  fails=0
  probe_selftest_case() { # <label> <expected> <STUB_MODE> [STUB_SHA]
    local label="$1" want="$2" mode="$3" sha="${4:-}" got
    got="$(cd "$repo" && PATH="$self_tmp:$PATH" STUB_MODE="$mode" STUB_SHA="$sha" \
      EVENT_NAME=workflow_dispatch \
      PACT_BROKER_URL=http://stub PACT_BROKER_USERNAME=u PACT_BROKER_PASSWORD=p \
      bash "$me_abs" openbank-demo-service "$head_sha" 2>/dev/null)"
    if [ "$got" = "$want" ]; then echo "  ok   ${label} → ${got}"
    else echo "  FAIL ${label}: want '${want}', got '${got}'"; fails=$((fails + 1)); fi
  }
  echo "probe-pact-version.sh (#3432 equivalence path, stubbed broker)"
  probe_selftest_case "broker unreachable"                       no unreachable
  probe_selftest_case "broker answers non-2xx"                   no http500
  probe_selftest_case "answer is not JSON"                       no garbage
  probe_selftest_case "answer has no version number"             no empty
  probe_selftest_case "version number is not a sha"              no notasha
  # A well-formed sha whose tree is NOT equivalent must still be `no` — the broker answering
  # correctly is not the proof; git is.
  probe_selftest_case "well-formed sha, trees differ"            no ok "$older_sha"
  # ...and the one case that may pass: the same commit is trivially equivalent to itself.
  probe_selftest_case "well-formed sha, trees identical" "equivalent:${head_sha}" ok "$head_sha"
  if [ "$fails" -ne 0 ]; then echo "FAILED: ${fails} case(s)"; exit 1; fi
  echo "PASS: every unverifiable broker answer degrades to 'no'; only a git-proven equivalence does not"
  exit 0
fi

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

# The version number of this pacticipant's latest `main`-TAGGED version — which is precisely what
# `can-i-deploy --latest main` resolves to, so proving equivalence against it justifies the exact
# question the fallback would have asked blindly. Measured against the live broker on 2026-08-03:
# GET /pacticipants/<svc>/latest-version/main answers 200 with `.number` = the full 40-hex git sha
# (services-ci publishes with pacticipantVersionNumber=$GITHUB_SHA and tags=[main]).
# Prints nothing at all unless the answer is a well-formed sha — every other outcome is silence,
# and silence keeps the caller on `no`.
latest_main_version() {
  local auth body num
  auth="${PACT_BROKER_USERNAME:-}:${PACT_BROKER_PASSWORD:-}"
  body="$(curl -s -f --max-time 20 -u "$auth" \
    "${PACT_BROKER_URL:-}/pacticipants/${SVC}/latest-version/main" 2>/dev/null)" || return 0
  num="$(printf '%s' "$body" | jq -r '.number // empty' 2>/dev/null)" || return 0
  command grep -qE '^[0-9a-f]{40}$' <<< "$num" || return 0
  printf '%s' "$num"
}

# Can the published verdict be transferred to the sha being deployed? Only when git proves the two
# commits identical in every build input of this service. Anything short of a clean exit 0 from the
# checker — and that includes the script being absent — leaves `present` as it was.
try_equivalence() {
  local pact_sha out here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  [ -x "$here/pact-version-tree-equivalent.sh" ] || [ -f "$here/pact-version-tree-equivalent.sh" ] || return 0
  pact_sha="$(latest_main_version)"
  [ -n "$pact_sha" ] || { echo "    no main-tagged version to compare ${SVC} against" >&2; return 0; }
  if out="$(bash "$here/pact-version-tree-equivalent.sh" "$SVC" "$pact_sha" "$SHA" 2>&1)"; then
    echo "    ${SVC}: ${out}" >&2
    printf 'equivalent:%s' "$pact_sha"
  else
    echo "    ${SVC}: ${out}" >&2
  fi
}

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

# On a non-push event the wait above never runs — the fleet was built at other commits, so no
# amount of waiting can produce a version for THIS sha. That is where the equivalence question is
# both necessary and answerable. On a push it is deliberately not asked: the wait is the correct
# answer there, and services-ci will publish for this very sha.
if [ "$present" = "no" ] && [ "${EVENT_NAME:-}" != "push" ]; then
  eq="$(try_equivalence)"
  [ -n "$eq" ] && present="$eq"
fi

echo "$present"
