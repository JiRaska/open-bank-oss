#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# check-pr-shared-files.sh — name every file this PR carries byte-identical to another open PR.
#
# WHY THIS EXISTS
#   A stacked or copied PR carries the same file as its sibling: same path, same blob, both
#   differing from main. Whichever merges first makes that file in the other a no-op diff that
#   still conflicts, still reads as authored work in review, and inflates the PR. The treasury
#   end-to-end branch carried byte-identical copies of files from two sibling PRs that merged
#   first, and nothing said so — git sees two independent additions, CI sees two green PRs.
#
# WHAT IT REPORTS
#   For each path changed by this PR (vs --base), the blob at the PR head is compared with the
#   blob of the same path at the head of every OTHER open PR. Reported when the two blobs are equal
#   AND differ from --base:   `<path> identical to #<n>`.
#   Advisory: it names the overlap; deciding whether one PR should drop the file is a human call.
#
# Usage:
#   bash .github/scripts/check-pr-shared-files.sh --pr <n> [--base origin/main]   # live, via gh
#   bash .github/scripts/check-pr-shared-files.sh --head <ref> --base <ref> --other <label>=<ref> ...
#   bash .github/scripts/check-pr-shared-files.sh --self-test

set -uo pipefail

HEAD_REF="HEAD"
BASE_REF="origin/main"
PR=""
SELFTEST=0
declare -a OTHERS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --pr) PR="$2"; shift 2 ;;
    --head) HEAD_REF="$2"; shift 2 ;;
    --base) BASE_REF="$2"; shift 2 ;;
    --other) OTHERS+=("$2"); shift 2 ;;
    --self-test) SELFTEST=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

# compare <head> <base> <label> <other-ref>  -> one finding line per shared file
compare() {
  # One `diff --raw` (merge-base blob -> head blob per changed path) and one `ls-tree` of the
  # other head, joined in awk: per-path rev-parse is minutes on a 700-file PR.
  local head="$1" base="$2" label="$3" other="$4"
  awk -v label="$label" '
    FNR == NR { split($0, t, "\t"); split(t[1], m, " "); blob[t[2]] = m[3]; next }
    { split($0, t, "\t"); split(t[1], m, " "); p = t[2]
      if ((p in blob) && blob[p] == m[3]) print p " identical to " label }
  ' <(git diff --raw --no-abbrev --no-renames "$base...$head" |
        awk -F'\t' '{ split($1, m, " "); if (m[4] !~ /^0+$/) print "- - " m[4] "\t" $2 }') \
    <(git ls-tree -r --full-tree "$other")
}

selftest() {
  local t fails=0 out
  t="$(mktemp -d)"
  (
    cd "$t" || exit 1
    git init -q -b main . && git config user.email t@t && git config user.name t
    echo base >same.txt; echo base >x.txt; git add . && git commit -qm base
    git switch -qc a; echo shared >x.txt; echo mine >y.txt; echo base >same.txt; git add . && git commit -qm a
    git switch -q main; git switch -qc b; echo shared >x.txt; echo theirs >y.txt; git add . && git commit -qm b
    git switch -q main; git switch -qc c; echo unrelated >z.txt; git add . && git commit -qm c
  ) >/dev/null 2>&1 || { echo "  [FAIL] fixture repo"; return 1; }
  # known-positive: a and b carry the same x.txt, both differing from main
  out="$(cd "$t" && compare a main '#2' b)"
  [[ "$out" == "x.txt identical to #2" ]] || { echo "  [FAIL] shared x.txt not reported, got: '$out'"; fails=$((fails+1)); }
  # known-negative: same path different content (y.txt), and a PR touching nothing in common
  out="$(cd "$t" && compare a main '#3' c)"
  [[ -z "$out" ]] || { echo "  [FAIL] unrelated PR reported: '$out'"; fails=$((fails+1)); }
  # known-negative: a file both sides left equal to base is not "shared work"
  out="$(cd "$t" && git switch -q main && echo base >same.txt && compare a main '#4' main)"
  [[ -z "$out" ]] || { echo "  [FAIL] base-equal file reported: '$out'"; fails=$((fails+1)); }
  rm -rf "$t"
  [[ $fails -eq 0 ]] && echo "check-pr-shared-files self-test: ok (1 known-positive reported, 2 known-negatives clean)"
  return "$fails"
}

if [[ $SELFTEST -eq 1 ]]; then selftest; exit $?; fi

if [[ -n "$PR" ]]; then
  repo="${GITHUB_REPOSITORY:-JiRaska/open-bank-oss}"
  mine="$(git diff --name-only --no-renames "$BASE_REF...$HEAD_REF" | sort -u)"
  if ! listing="$(gh pr list -R "$repo" --state open --limit 300 --json number,headRefOid,files \
      --jq ".[] | select(.number != $PR) | \"\(.number) \(.headRefOid) \([.files[].path] | join(\" \"))\"" 2>&1)"; then
    echo "::notice::could not list open PRs (${listing:0:200}) — UNRESOLVED, not a pass and not a failure"
    echo "SUBJECTS=UNRESOLVED"
    exit 0
  fi
  while read -r num sha paths; do
    [[ -n "$num" ]] || continue
    # only PRs whose file list intersects this one are worth fetching
    overlap=0
    for p in $paths; do grep -qxF -- "$p" <<<"$mine" && { overlap=1; break; }; done
    [[ $overlap -eq 1 ]] || continue
    git cat-file -e "$sha^{commit}" 2>/dev/null || git fetch -q --no-tags --depth=1 origin "$sha" 2>/dev/null || continue
    OTHERS+=("#$num=$sha")
  done <<<"$listing"
fi

n=0
for o in ${OTHERS[@]+"${OTHERS[@]}"}; do
  label="${o%%=*}"; ref="${o#*=}"
  while IFS= read -r line; do
    [[ -n "$line" ]] || continue
    echo "::warning::$line"
    n=$((n + 1))
  done < <(compare "$HEAD_REF" "$BASE_REF" "$label" "$ref")
done
echo "SUBJECTS=$(git diff --name-only --no-renames "$BASE_REF...$HEAD_REF" | wc -l | tr -d ' ')"
echo "compared against ${#OTHERS[@]} open PR(s) sharing a path"
echo "check-pr-shared-files: $n file(s) identical to another open PR"
exit 0
