#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Decide WHICH VERSION record-deployment should record as deployed (issue #3223).
#
# THE DEFECT THIS REMOVES
# record-deployment-on-merge.yml resolved each `openbank-<svc>:sandbox-<short>` image tag to a
# full sha and recorded THAT as deployed. Path-scoped CI skips most services on most commits, so
# for most (service, sha) pairs the broker has no such version — and the workflow created one:
#
#     if [ "$ver_code" = "404" ]; then
#       curl -X PUT ... -d '{}' ".../pacticipants/${svc}/versions/${SHA}"
#     fi
#
# That version carries no pacts and no verifications, and nothing can ever give it any: the
# publishing lane is keyed on the shas it actually built. It then becomes the counterpart every
# consumer's `can-i-deploy --to-environment sandbox` asks about, and the answer can never be yes.
#
# MEASURED on the live broker 2026-08-07, before this change:
#   56 currently-deployed versions, 11 with pacts or verifications, 45 CONTENTLESS
#   19 providers with a deployed version, 13 of them at a version with ZERO verifications
#   last 40 auto-deploy runs: 24 failure / 15 success, 12 of 12 sampled failures at can-i-deploy
#
# WHY NOT SIMPLY SKIP THE PUT
# Then the environment record has a gap rather than a lie, which is better but still wrong: the
# artifact IS deployed and the broker would not know. The honest record exists — some published
# version whose source is byte-identical for this service — and this script finds it.
#
# THE EQUIVALENCE ARGUMENT, AND WHY IT IS NOT NEW
# #3432 already established it for the ASKING side (resolve-can-i-deploy-selector.sh): if every
# build input of <svc> is identical between two commits, a question about one is a question about
# the same source, not about a different commit. Recording is the symmetric case. This script is
# the recording half of that decision; the two halves disagreeing is the state #3223 describes.
#
# WHY VIA THE GITHUB API AND NOT git
# resolve-can-i-deploy-selector.sh compares git tree objects, which needs a checkout with full
# history. The `record-deployment` job has NO `actions/checkout` at all — one step, api-only, by
# design (see GH_REPO in that workflow). Adding a full-history checkout to every gitops merge to
# answer "did anything under <svc>/ change" is a large cost for a question the compare API answers
# directly. So the proof is the same; the evidence source differs.
#
# WHAT COUNTS AS A BUILD INPUT
# Everything under `<svc>/` except that package's release-please `exclude-paths` — `<svc>/src/test`
# for every service (and `openbank-admin-ui/e2e`). Those are the paths release-please already
# declares cannot change the shipped artifact, so reusing them keeps one definition rather than a
# second copy that drifts (this repo's "never let a second hand-maintained copy exist" rule).
#
# OUTPUT — exactly one line on stdout:
#   exact:<sha>        the broker already has this version; record it unchanged
#   equivalent:<sha>   no version for the deployed sha, but <sha> is published and byte-identical
#                      in every build input of this service; record <sha>
#   none               neither — the caller MUST record nothing and warn. Never fabricate.
#
# The caller decides what `none` costs. It is deliberately not an error: a service genuinely
# deployed from a source no lane ever published is a real state, and a gap in the environment
# record is honest about it where a `PUT {}` is not.

set -uo pipefail

# ── the testable core ────────────────────────────────────────────────────────────────────
# True when a changed path is a build input of <svc>: under `<svc>/` and not under one of that
# package's exclude-paths. Pure, no network — the self-test drives every branch through it.
path_is_build_input() {
  local svc="$1" path="$2" excludes="$3" ex
  case "$path" in
    "$svc"/*) ;;
    *) return 1 ;;
  esac
  # `excludes` is newline-separated; a path under any of them is not a build input.
  while IFS= read -r ex; do
    [ -n "$ex" ] || continue
    case "$path" in
      "$ex"/*|"$ex") return 1 ;;
    esac
  done <<EOF
$excludes
EOF
  return 0
}

# True when NONE of the changed paths is a build input of <svc>.
trees_equivalent() {
  local svc="$1" excludes="$2" p
  while IFS= read -r p; do
    [ -n "$p" ] || continue
    if path_is_build_input "$svc" "$p" "$excludes"; then
      return 1
    fi
  done
  return 0
}

# Read a package's exclude-paths out of release-please-config.json, one per line.
# Absent config or absent package yields an empty list, which is the safe direction: with no
# excludes every changed path under `<svc>/` counts, so equivalence is harder to claim.
excludes_for() {
  local svc="$1" cfg="${2:-release-please-config.json}"
  [ -f "$cfg" ] || return 0
  python3 - "$cfg" "$svc" <<'PY'
import json, sys
cfg, svc = sys.argv[1], sys.argv[2]
try:
    pkg = json.load(open(cfg)).get("packages", {}).get(svc, {})
except Exception:
    sys.exit(0)
for p in pkg.get("exclude-paths") or []:
    print(p)
PY
}

# ── self-test ────────────────────────────────────────────────────────────────────────────
# Feeds the core the cases it MUST flag and the near-misses it must NOT, per this repo's rule
# that a check which has only ever seen correct input is unfalsified.
self_test() {
  local fails=0
  check() { # name expected_rc actual_rc
    if [ "$2" -eq "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (want rc=$2, got rc=$3)"; fails=1; fi
  }
  local EX="openbank-ledger-service/src/test"

  path_is_build_input openbank-ledger-service openbank-ledger-service/src/main/kotlin/A.kt "$EX"; check "src/main is a build input" 0 $?
  path_is_build_input openbank-ledger-service openbank-ledger-service/build.gradle.kts "$EX"; check "build.gradle.kts is a build input" 0 $?
  path_is_build_input openbank-ledger-service openbank-ledger-service/Dockerfile "$EX"; check "Dockerfile is a build input" 0 $?
  path_is_build_input openbank-ledger-service openbank-ledger-service/src/test/kotlin/T.kt "$EX"; check "src/test is excluded" 1 $?
  path_is_build_input openbank-ledger-service openbank-ledger-service/src/test "$EX"; check "the exclude dir itself is excluded" 1 $?
  path_is_build_input openbank-ledger-service openbank-fx-service/src/main/kotlin/A.kt "$EX"; check "another service is not our input" 1 $?
  path_is_build_input openbank-ledger-service docs/adr/0001.md "$EX"; check "a doc is not our input" 1 $?
  # PREFIX TRAP: a sibling whose name starts with ours must not match.
  path_is_build_input openbank-ledger openbank-ledger-service/src/main/kotlin/A.kt "$EX"; check "prefix sibling does not match" 1 $?

  printf 'openbank-ledger-service/src/test/kotlin/T.kt\ndocs/adr/0001.md\n' \
    | trees_equivalent openbank-ledger-service "$EX"; check "test-only + docs change IS equivalent" 0 $?
  printf 'openbank-ledger-service/src/test/kotlin/T.kt\nopenbank-ledger-service/src/main/kotlin/A.kt\n' \
    | trees_equivalent openbank-ledger-service "$EX"; check "one main change is NOT equivalent" 1 $?
  printf '' | trees_equivalent openbank-ledger-service "$EX"; check "empty diff IS equivalent" 0 $?
  # A service with no exclude-paths declared: src/test then counts, which is the safe direction.
  printf 'openbank-x/src/test/T.kt\n' | trees_equivalent openbank-x ""; check "no excludes => src/test counts" 1 $?

  [ "$fails" -eq 0 ] && { echo "resolve-record-deployment-version: self-test PASS"; return 0; }
  echo "resolve-record-deployment-version: self-test FAIL"; return 1
}

# ── resolution ───────────────────────────────────────────────────────────────────────────
main() {
  local svc="$1" deployed="$2"
  : "${PACT_BROKER_URL:?}" "${PACT_BROKER_USERNAME:?}" "${PACT_BROKER_PASSWORD:?}" "${GH_REPO:?}"

  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' \
    -u "${PACT_BROKER_USERNAME}:${PACT_BROKER_PASSWORD}" \
    "${PACT_BROKER_URL}/pacticipants/${svc}/versions/${deployed}")"
  if [ "$code" = "200" ]; then
    echo "exact:${deployed}"
    return 0
  fi

  # Newest published version for this pacticipant. `latest` is the broker's own ordering, so we
  # inherit its definition of newest rather than inventing one.
  local published
  published="$(curl -s -u "${PACT_BROKER_USERNAME}:${PACT_BROKER_PASSWORD}" \
    "${PACT_BROKER_URL}/pacticipants/${svc}/latest-version" \
    | python3 -c 'import json,sys;
try: print(json.load(sys.stdin).get("number",""))
except Exception: print("")' 2>/dev/null)"
  case "$published" in
    [0-9a-f]*) ;;
    *) echo "none"; return 0 ;;
  esac
  [ "$published" != "$deployed" ] || { echo "none"; return 0; }

  local files excludes
  files="$(gh api "repos/${GH_REPO}/compare/${published}...${deployed}" \
    --jq '.files[].filename' 2>/dev/null)" || { echo "none"; return 0; }
  excludes="$(excludes_for "$svc")"

  if printf '%s\n' "$files" | trees_equivalent "$svc" "$excludes"; then
    echo "equivalent:${published}"
  else
    echo "none"
  fi
}

case "${1:-}" in
  --self-test) self_test ;;
  "") echo "usage: $0 <pacticipant> <deployed-sha> | --self-test" >&2; exit 2 ;;
  *) main "$1" "${2:?deployed sha required}" ;;
esac
