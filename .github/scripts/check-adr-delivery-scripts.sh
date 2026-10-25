#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# check-adr-delivery-scripts.sh — an ADR's `delivery-status` must not contradict its own
# `## Delivery check`, measured by RUNNING the check, not by reading it.
#
# WHY THIS EXISTS
#   A Delivery check block is the falsifier of `delivery-status`, and as prose nobody runs it.
#   ADR-0344 read `planned` while code for its decisions was landing, so the registry said one
#   thing and the tree another, and the gap was found by a hand audit. This gate executes each
#   ADR's extracted block (docs/adr/checks/<nnnn>.sh, DERIVED by gen-adr-delivery-checks.py) in a
#   checkout of --ref (origin/main by default) and compares the measured result with the claim:
#     every measurable line passes  => delivery-status must not be `planned`
#     no measurable line passes     => delivery-status must not be `shipped`
#   Anything in between is consistent with `partial`, and nothing else is asserted here.
#
# HOW A LINE IS JUDGED
#   A command line's expectation is its trailing `# ...` comment, else the comment line directly
#   above it. Parsed, in order:
#     "prints nothing" / "empty"     stdout is empty (exit status ignored: `grep` exits 1 on empty)
#     ">= N"                          stdout's last integer >= N  (">= <prose>": last integer >= 1)
#     "expect N" / "== N"             stdout's last integer == N
#     a single bare word ("enforced") stdout, trimmed, equals that word
#     anything else                   exit 0 and stdout non-empty
#   Exit 127 (a tool the runner lacks, e.g. yq) makes the line UNMEASURED — never a pass, never a
#   fail. A block with no measurable line produces no verdict, and says so.
#
# ADVISORY FIRST (ADR-0144): prints findings and exits 0 unless --enforce.
#
# Usage:
#   bash .github/scripts/check-adr-delivery-scripts.sh [--ref origin/main] [--enforce] [--only NNNN]
#   bash .github/scripts/check-adr-delivery-scripts.sh --self-test

set -uo pipefail

ROOT="$(git rev-parse --show-toplevel)"
REF="origin/main"
ENFORCE=0
ONLY=""
SELFTEST=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --ref) REF="$2"; shift 2 ;;
    --enforce) ENFORCE=1; shift ;;
    --only) ONLY="$2"; shift 2 ;;
    --self-test) SELFTEST=1; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
TMPC="$(mktemp)"
trap 'rm -f "$TMPC"' EXIT
GEN="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/gen-adr-delivery-checks.py"

# judge <expectation> <exit> <stdout>  -> prints PASS / FAIL / UNMEASURED
judge() {
  local exp rc out n last
  exp="$(tr '[:upper:]' '[:lower:]' <<<"$1")"; rc="$2"; out="$3"
  if [[ "$rc" -eq 127 ]]; then echo UNMEASURED; return; fi
  last="$(grep -oE '[0-9]+' <<<"$out" | tail -1)"
  if [[ "$exp" == *"prints nothing"* || "$exp" == *empty* ]]; then
    if [[ -z "${out//[[:space:]]/}" ]]; then echo PASS; else echo FAIL; fi
  elif [[ "$exp" =~ \>=[[:space:]]*([0-9]+) ]]; then
    n="${BASH_REMATCH[1]}"
    if [[ -n "$last" && "$last" -ge "$n" ]]; then echo PASS; else echo FAIL; fi
  elif [[ "$exp" == *">="* ]]; then
    # ">= <prose>" (e.g. "number of product types"): at least a count of one, never zero
    if [[ -n "$last" && "$last" -ge 1 ]]; then echo PASS; else echo FAIL; fi
  elif [[ "$exp" =~ (expect|==)[[:space:]]+([0-9]+) ]]; then
    n="${BASH_REMATCH[2]}"
    if [[ -n "$last" && "$last" -eq "$n" ]]; then echo PASS; else echo FAIL; fi
  elif [[ "$exp" =~ ^[[:space:]]*([a-z][a-z_-]*)[[:space:]]*$ ]]; then
    if [[ "$(tr -d '[:space:]' <<<"$out")" == "${BASH_REMATCH[1]}" ]]; then echo PASS; else echo FAIL; fi
  else
    if [[ "$rc" -eq 0 && -n "${out//[[:space:]]/}" ]]; then echo PASS; else echo FAIL; fi
  fi
}

# run_script <checks/NNNN.sh> <workdir>  -> per-line detail on stderr, "pass fail unmeasured" on stdout
run_script() {
  local script="$1" wd="$2" line prev="" cmd exp rc out v p=0 f=0 u=0
  while IFS= read -r line || [[ -n "$line" ]]; do
    if [[ -z "${line//[[:space:]]/}" ]]; then prev=""; continue; fi
    if [[ "$line" =~ ^[[:space:]]*# ]]; then prev="${line#*#}"; continue; fi
    cmd="$line"; exp="$prev"
    if [[ "$line" =~ ^(.*[^[:space:]])[[:space:]]{2,}#[[:space:]]?(.*)$ ]]; then
      cmd="${BASH_REMATCH[1]}"; exp="${BASH_REMATCH[2]}"
    fi
    out="$(cd "$wd" && bash -c "$cmd" 2>/dev/null </dev/null)"; rc=$?
    v="$(judge "$exp" "$rc" "$out")"
    case "$v" in PASS) p=$((p+1)) ;; FAIL) f=$((f+1)) ;; *) u=$((u+1)) ;; esac
    printf '    %-10s %s\n' "$v" "$cmd" >&2
    printf '               expect: %s | got: %s\n' "${exp:-<none>}" "$(head -c 120 <<<"${out//$'\n'/ }")" >&2
    prev=""
  done <"$script"
  echo "$p $f $u"
}

# check_tree <root holding the ADRs> <workdir the commands run in>  -> returns the finding count
check_tree() {
  local root="$1" wd="$2" findings=0 subjects=0 s nnnn adr status counts p f u
  if ! python3 "$GEN" --root "$root" --check; then
    echo "::warning::derived docs/adr/checks/*.sh are stale — run python3 .github/scripts/gen-adr-delivery-checks.py"
    findings=$((findings + 1))
  fi
  for s in "$root"/docs/adr/checks/*.sh; do
    [[ -f "$s" ]] || continue
    nnnn="$(basename "$s" .sh)"
    [[ -n "$ONLY" && "$ONLY" != "$nnnn" ]] && continue
    adr="$(ls "$root"/docs/adr/"$nnnn"-*.md 2>/dev/null | head -1)"
    [[ -n "$adr" ]] || continue
    status="$(awk '/^---$/{n++; next} n==1 && /^delivery-status:/{print $2; exit}' "$adr")"
    echo "ADR-$nnnn (delivery-status: $status)"
    run_script "$s" "$wd" >"$TMPC"
    counts="$(cat "$TMPC")"
    subjects=$((subjects + 1))
    read -r p f u <<<"$counts"
    echo "  measured: $p pass, $f fail, $u unmeasured"
    if [[ $((p + f)) -eq 0 ]]; then
      echo "  no measurable line — no verdict"
    elif [[ $f -eq 0 && "$status" == "planned" ]]; then
      echo "::warning::ADR-$nnnn says delivery-status: planned but all $p measurable delivery-check lines pass"
      findings=$((findings + 1))
    elif [[ $p -eq 0 && "$status" == "shipped" ]]; then
      echo "::warning::ADR-$nnnn says delivery-status: shipped but none of its $f delivery-check lines pass"
      findings=$((findings + 1))
    fi
  done
  echo "SUBJECTS=$subjects"
  return "$findings"
}

selftest() {
  local t fails=0 rc
  t="$(mktemp -d)"
  mkdir -p "$t/docs/adr"
  mk() { # mk NNNN status body
    printf -- '---\ndelivery-status: %s\n---\n# ADR-%s\n\n## Delivery check\n\n```bash\n%s\n```\n\n## Next\n' "$2" "$1" "$3" >"$t/docs/adr/$1-fixture.md"
  }
  # known-positive A: planned, but every line passes
  mk 0001 planned $'echo hello   # at least one line\nprintf ""   # prints nothing\necho 9   # >= 8'
  # known-positive B: shipped, but nothing passes
  mk 0002 shipped $'echo 3   # >= 8\n# expect empty\necho x\necho 0   # >= number of things'
  python3 "$GEN" --root "$t" >/dev/null
  check_tree "$t" "$t" >"$t/out" 2>&1; rc=$?
  grep -q 'ADR-0001 says delivery-status: planned' "$t/out" || { echo "  [FAIL] planned+all-pass not flagged"; fails=$((fails+1)); }
  grep -q 'ADR-0002 says delivery-status: shipped' "$t/out" || { echo "  [FAIL] shipped+none-pass not flagged"; fails=$((fails+1)); }
  [[ $rc -eq 2 ]] || { echo "  [FAIL] expected 2 findings, got $rc"; cat "$t/out"; fails=$((fails+1)); }
  # known-negative: consistent statuses, an unmeasurable tool, fresh derived files
  rm -f "$t"/docs/adr/*.md
  mk 0001 partial $'echo hello   # at least one line\necho 3   # >= 8'
  mk 0002 planned $'definitely-not-a-tool-xyz   # enforced\necho 2   # expect 3'
  python3 "$GEN" --root "$t" >/dev/null
  check_tree "$t" "$t" >"$t/out" 2>&1; rc=$?
  [[ $rc -eq 0 ]] || { echo "  [FAIL] known-negative produced $rc findings:"; cat "$t/out"; fails=$((fails+1)); }
  grep -q '0 pass, 1 fail, 1 unmeasured' "$t/out" || { echo "  [FAIL] exit 127 not classified unmeasured"; cat "$t/out"; fails=$((fails+1)); }
  # known-positive C: a hand-edited derived file is stale
  echo '# hand edit' >>"$t/docs/adr/checks/0001.sh"
  check_tree "$t" "$t" >"$t/out" 2>&1
  grep -q 'stale derived file' "$t/out" || { echo "  [FAIL] hand-edited derived file not flagged"; fails=$((fails+1)); }
  rm -rf "$t"
  [[ $fails -eq 0 ]] && echo "check-adr-delivery-scripts self-test: ok (3 known-positives red, 1 known-negative clean)"
  return "$fails"
}

if [[ $SELFTEST -eq 1 ]]; then selftest; exit $?; fi

WD="$ROOT"; CLEANUP=""
if git -C "$ROOT" rev-parse --verify -q "$REF^{commit}" >/dev/null; then
  CLEANUP="$(mktemp -d)"
  WD="$CLEANUP/tree"
  git -C "$ROOT" worktree add -q --detach "$WD" "$REF" || exit 2
  echo "measuring delivery checks in a checkout of $REF ($(git -C "$WD" rev-parse --short HEAD))"
else
  echo "::warning::ref $REF not available — measuring the working tree instead"
fi
check_tree "$ROOT" "$WD"; FINDINGS=$?
if [[ -n "$CLEANUP" ]]; then git -C "$ROOT" worktree remove --force "$WD"; rm -rf "$CLEANUP"; fi
echo "check-adr-delivery-scripts: $FINDINGS finding(s)"
if [[ $ENFORCE -eq 1 && $FINDINGS -gt 0 ]]; then exit 1; fi
exit 0
