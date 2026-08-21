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
# WHY TREE OBJECTS AND NOT THE COMPARE API (#4673)
# This used to ask `compare/<published>...<deployed>` for the changed-file list. That endpoint caps
# its `files` array at 300 entries, does not paginate past it (page=2 returns an empty array,
# measured), and returns `changed_files: null` on a large comparison — so the cap is silent in both
# changed", which is a claim about the PROBE, not about the source.
#
# Measured on the real case that exposed it: document-service between ee974ea3 and 77ebab08 has
# 16 changed build inputs (Dockerfile, build.gradle.kts, 8 under src/main). git reports 1519 changed
# files across the comparison; the API returned 300, and ZERO of them were document-service files.
# So the script declared equivalence, record-deployment wrote the stale version, and every consumer
# stayed phantom-blocked. It also cannot self-heal: the staler the record, the wider the comparison,
# the more certain the truncation.
#
# Tree objects answer the real question directly and are not subject to any of that. A commit's
# top-level tree lists `<svc>` with its own subtree sha; equal subtree shas mean the directory is
# byte-identical, which is a STRONGER proof than "no changed file was named". This still needs no
# checkout — `git/trees` is an API call — so the constraint that motivated the compare API is
# respected. resolve-can-i-deploy-selector.sh compares tree objects too; the two halves now agree
# on both the question and the evidence.
#
# Cost: 2 calls when the subtrees match, 4 when they differ and exclude-paths have to be considered.
# Every tree response carries a `truncated` flag, and this script treats a truncated tree as `none`
# rather than as evidence of anything.
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

# Compare two `<path>TAB<blobsha>` listings of the SAME service subtree and decide whether they
# agree on every BUILD INPUT. Paths arrive relative to the subtree, so they are prefixed with
# `<svc>/` before path_is_build_input sees them — that function's contract is repo-relative paths.
#
# Full listings, not a changed-file list. That is the structural fix for #4673: with a changed-file
# list, "the probe could not show me the change" and "there was no change" are the same empty
# input. With listings, a missing entry is a difference.
build_inputs_agree() {
  local svc="$1" excludes="$2" a_file="$3" b_file="$4"
  local filtered_a filtered_b
  # Fail CLOSED on a broken environment. Without this the function compares two empty strings when
  # the filter cannot run and reports agreement — the same shape as the defect this file exists to
  # remove, one layer down. Found while testing this very change: `sort` was unavailable, both
  # sides filtered to nothing, and a 16-file difference read as "equivalent".
  command -v sort >/dev/null 2>&1 || return 1
  [ -r "$a_file" ] && [ -r "$b_file" ] || return 1

  filtered_a="$(_filter_build_inputs "$svc" "$excludes" <"$a_file")" || return 1
  filtered_b="$(_filter_build_inputs "$svc" "$excludes" <"$b_file")" || return 1
  [ "$filtered_a" = "$filtered_b" ]
}

# Keep only the `<path>TAB<blobsha>` lines that are build inputs, sorted for a stable comparison.
_filter_build_inputs() {
  local svc="$1" excludes="$2" line path
  while IFS= read -r line; do
    [ -n "$line" ] || continue
    path="${line%%$'\t'*}"
    if path_is_build_input "$svc" "${svc}/${path}" "$excludes"; then
      printf '%s\n' "$line"
    fi
  done | LC_ALL=C sort
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


  # ── #4673: the entry-map comparison the tree path uses ────────────────────────────────
  # These are what the old compare-API shape could not express. Its input was a list of CHANGED
  # filenames, so "the probe did not show me the change" and "there was no change" were the same
  # empty list. These compare full listings, where a missing entry is a difference.
  local A B
  A="$(mktemp)"; B="$(mktemp)"

  printf 'src/main/kotlin/A.kt\taaa\nsrc/test/kotlin/T.kt\tttt\n' >"$A"
  printf 'src/main/kotlin/A.kt\taaa\nsrc/test/kotlin/T.kt\tZZZ\n' >"$B"
  build_inputs_agree openbank-ledger-service "$EX" "$A" "$B"; check "differing src/test only IS equivalent" 0 $?

  printf 'src/main/kotlin/A.kt\taaa\n' >"$A"
  printf 'src/main/kotlin/A.kt\tbbb\n' >"$B"
  build_inputs_agree openbank-ledger-service "$EX" "$A" "$B"; check "a changed src/main blob is NOT equivalent" 1 $?

  printf 'src/main/kotlin/A.kt\taaa\n' >"$A"
  printf 'src/main/kotlin/A.kt\taaa\nsrc/main/kotlin/B.kt\tbbb\n' >"$B"
  build_inputs_agree openbank-ledger-service "$EX" "$A" "$B"; check "an ADDED build input is NOT equivalent" 1 $?

  printf 'src/main/kotlin/A.kt\taaa\nDockerfile\tddd\n' >"$A"
  printf 'Dockerfile\tddd\nsrc/main/kotlin/A.kt\taaa\n' >"$B"
  build_inputs_agree openbank-ledger-service "$EX" "$A" "$B"; check "listing order does not matter" 0 $?

  # THE #4673 CASE: a truncated listing must never read as agreement. Here the deployed side is
  # missing an entry it really has, exactly as the 300-cap compare response was — and the answer
  # must be "differs", never "equivalent".
  printf 'src/main/kotlin/A.kt\taaa\nDockerfile\tddd\n' >"$A"
  printf 'src/main/kotlin/A.kt\taaa\n' >"$B"
  build_inputs_agree openbank-ledger-service "$EX" "$A" "$B"; check "a listing missing a build input is NOT equivalent" 1 $?

  printf '' >"$A"; printf '' >"$B"
  build_inputs_agree openbank-ledger-service "$EX" "$A" "$B"; check "two empty listings agree" 0 $?

  # Fail-closed: an unreadable listing is not agreement. Same rule as a truncated tree — the
  # absence of evidence must never be read as evidence of sameness.
  build_inputs_agree openbank-ledger-service "$EX" "$A" /nonexistent-listing; check "an unreadable listing is NOT agreement" 1 $?

  # ── truncation guards (#4673) — the branch the compare-API shape could not have ────────
  # These exist because the ORIGINAL defect was a silently truncated response read as "unchanged".
  # A guard in the network path is unreachable by a test, so the JSON handling is split out and
  # driven here directly.
  local TREE_OK='{"truncated":false,"tree":[{"path":"openbank-ledger-service","type":"tree","sha":"abc123"}]}'
  local TREE_TRUNC='{"truncated":true,"tree":[{"path":"openbank-ledger-service","type":"tree","sha":"abc123"}]}'

  printf '%s' "$TREE_OK" | tree_pick_subtree openbank-ledger-service >/dev/null; check "a complete tree yields the subtree sha" 0 $?
  printf '%s' "$TREE_TRUNC" | tree_pick_subtree openbank-ledger-service >/dev/null; check "a TRUNCATED tree is refused, not read" 1 $?
  printf '%s' "$TREE_OK" | tree_pick_subtree openbank-absent-service >/dev/null; check "an absent service is refused" 1 $?
  printf 'not json' | tree_pick_subtree openbank-ledger-service >/dev/null; check "unparseable JSON is refused" 1 $?

  local BLOBS_OK='{"truncated":false,"tree":[{"path":"src/main/A.kt","type":"blob","sha":"aaa"},{"path":"src","type":"tree","sha":"ttt"}]}'
  local BLOBS_TRUNC='{"truncated":true,"tree":[{"path":"src/main/A.kt","type":"blob","sha":"aaa"}]}'

  local got
  got="$(printf '%s' "$BLOBS_OK" | tree_blobs)"; check "blobs are listed, trees skipped" 0 $?
  [ "$got" = "$(printf 'src/main/A.kt\taaa')" ]; check "the blob listing is path TAB sha" 0 $?
  printf '%s' "$BLOBS_TRUNC" | tree_blobs >/dev/null; check "a TRUNCATED blob listing is refused" 1 $?

  rm -f "$A" "$B"
  # A service with no exclude-paths declared: src/test then counts, which is the safe direction.

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
  # Captured, then fed via a here-string rather than `curl | python3` (OpenSSF Scorecard
  # Pinned-Dependencies: downloadThenRun flags any curl-into-interpreter pipe, code or not —
  # this is JSON data, not a downloaded script, but the check can't tell the two apart).
  local latest_json published
  latest_json="$(curl -s -u "${PACT_BROKER_USERNAME}:${PACT_BROKER_PASSWORD}" \
    "${PACT_BROKER_URL}/pacticipants/${svc}/latest-version")"
  published="$(python3 -c 'import json,sys;
try: print(json.load(sys.stdin).get("number",""))
except Exception: print("")' <<<"$latest_json" 2>/dev/null)"
  case "$published" in
    [0-9a-f]*) ;;
    *) echo "none"; return 0 ;;
  esac
  [ "$published" != "$deployed" ] || { echo "none"; return 0; }

  # Tree objects, not the compare API (#4673): equal subtree shas prove the directory is
  # byte-identical, and nothing here is subject to a 300-entry cap.
  local tree_pub tree_dep
  tree_pub="$(subtree_sha "$published" "$svc")" || { echo "none"; return 0; }
  tree_dep="$(subtree_sha "$deployed" "$svc")" || { echo "none"; return 0; }
  [ -n "$tree_pub" ] && [ -n "$tree_dep" ] || { echo "none"; return 0; }

  if [ "$tree_pub" = "$tree_dep" ]; then
    echo "equivalent:${published}"
    return 0
  fi

  # The directories differ. That is still equivalence if every difference sits inside an
  # exclude-path, so list both subtrees and compare the build inputs entry by entry.
  local excludes list_pub list_dep rc
  excludes="$(excludes_for "$svc")"
  list_pub="$(mktemp)" || { echo "none"; return 0; }
  list_dep="$(mktemp)" || { rm -f "$list_pub"; echo "none"; return 0; }

  if ! subtree_entries "$tree_pub" >"$list_pub" || ! subtree_entries "$tree_dep" >"$list_dep"; then
    rm -f "$list_pub" "$list_dep"
    echo "none"
    return 0
  fi

  if build_inputs_agree "$svc" "$excludes" "$list_pub" "$list_dep"; then
    rc="equivalent:${published}"
  else
    rc="none"
  fi
  rm -f "$list_pub" "$list_dep"
  echo "$rc"
}

# The subtree sha of `<svc>` at <commit>, read from the commit's top-level tree. Fails (rc 1) when
# the call fails, the tree is TRUNCATED, or the service is absent — each of which means "cannot
# establish equivalence", never "equivalent". Truncation matters most: entries would be missing, so
# an absent <svc> could not be told apart from a service that genuinely is not there.
subtree_sha() {
  local commit="$1" svc="$2" json
  json="$(gh api "repos/${GH_REPO}/git/trees/${commit}" 2>/dev/null)" || return 1
  printf '%s' "$json" | tree_pick_subtree "$svc"
}

# PURE half of subtree_sha: reads a git-trees JSON on stdin, prints the subtree sha of <svc>.
# Fails (rc 1) on unparseable JSON, a TRUNCATED tree, or an absent service. Split out from the
# network call precisely so the self-test can drive the truncation branch — the guard that stops
# #4673 recurring is otherwise unreachable by any test.
tree_pick_subtree() {
  SVC="$1" python3 -c '
import json, os, sys
svc = os.environ["SVC"]
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
if d.get("truncated"):
    sys.exit(1)
for e in d.get("tree", []):
    if e.get("path") == svc and e.get("type") == "tree":
        print(e.get("sha", ""))
        sys.exit(0)
sys.exit(1)
'
}

subtree_entries() {
  local tree="$1" json
  json="$(gh api "repos/${GH_REPO}/git/trees/${tree}?recursive=1" 2>/dev/null)" || return 1
  printf '%s' "$json" | tree_blobs
}

# PURE half of subtree_entries: `<path>TAB<blobsha>` per blob, from a git-trees JSON on stdin.
# Fails (rc 1) on unparseable JSON or a TRUNCATED tree. A service tree is a few hundred entries so
# truncation should never fire; if it ever does, the honest answer is that we could not look — not
# that nothing changed. That distinction is the whole of #4673.
tree_blobs() {
  python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(1)
if d.get("truncated"):
    sys.exit(1)
for e in d.get("tree", []):
    if e.get("type") == "blob":
        sys.stdout.write(e.get("path", "") + "\t" + e.get("sha", "") + "\n")
sys.exit(0)
'
}

case "${1:-}" in
  --self-test) self_test ;;
  "") echo "usage: $0 <pacticipant> <deployed-sha> | --self-test" >&2; exit 2 ;;
  *) main "$1" "${2:?deployed sha required}" ;;
esac
