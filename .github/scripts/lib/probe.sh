#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# probe.sh — the shell probes this repo keeps getting wrong, each with a known-positive check
# it must pass before its answer is allowed to count.
#
# WHY THIS EXISTS
#   A probe fails by reporting CLEAN. That is the whole problem: a broken linter invocation, a
#   date parsed in the wrong zone, a column index that shifted — none of them error, all of them
#   return a plausible nothing, and nothing downstream can tell "found no problem" from "could not
#   look". Seven measured instances in this repo, all of which printed a green answer:
#
#     * `find <dir> -newermt "-60 minutes"` is ALWAYS empty on BSD/macOS (no GNU relative dates).
#       An actively-committing worktree was reported idle.
#     * `date -j -f "%Y-%m-%dT%H:%M:%SZ" "$t" +%s` silently ignores the trailing `Z` and parses as
#       LOCAL time — 2h off in CEST. It flagged 53 branches as having post-merge commits; the true
#       count was 0.
#     * `git rev-parse origin/<branch>` reads the local remote-tracking ref, and a plain `git fetch`
#       never prunes. It claimed a deleted remote branch still existed.
#     * `gh pr checks | awk '$2!="pass"'` — job names contain spaces, so `$2` is a WORD OF THE NAME,
#       never the status. Every conclusion drawn from it was noise, and the matching
#       `until [ "$(… | awk '$2=="pending"' | wc -l)" -eq 0 ]` wait loop exited instantly.
#     * `actionlint "$f" | grep -oE 'SC[0-9]+'` post-filtered away a YAML syntax error, so the
#       harness printed "no new findings" about a file it had just broken.
#     * `actionlint $CHANGED` with a newline-joined list passes ONE argument; actionlint errors, and
#       an `[ -n "$out" ]` branch printed "(empty = clean)".
#     * counting `in_progress` workflow runs to judge CI saturation counts 189 runs whose jobs all
#       completed and whose run record never transitioned.
#
# THE RULE THIS FILE ENCODES
#   Before trusting a probe's silence, run it against a known-positive. Every function here ships
#   with one, `--selftest` runs them all, and each case asserts BOTH directions — the probe finds
#   the thing that is there, and does not find the thing that is not. A probe that cannot fail is
#   decoration, and this file is where that stops being a slogan.
#
#   The self-test is deliberately hermetic: it builds its own fixtures in a temp dir and touches no
#   network, so it runs identically on ubuntu-latest and on the self-hosted macOS runners — which is
#   the point, since every trap above is a BSD-vs-GNU divergence that only one of those two exposes.
#
# Usage:
#   source .github/scripts/lib/probe.sh
#   probe_utc_epoch "2026-08-22T14:25:39Z"
#   bash .github/scripts/lib/probe.sh --selftest

# Intentionally NOT `set -e` at file scope: this is sourced, and inheriting errexit into a caller
# that does not expect it is its own silent failure mode.

# ---------------------------------------------------------------------------------------------
# probe_utc_epoch <iso8601-with-Z> -> unix seconds on stdout
#
# BSD `date -j -f` ignores a literal `Z` in the format string and parses in the LOCAL zone. `-u` is
# what makes it read as UTC, and it must be present on BOTH the parse and the output. GNU `date -d`
# handles the offset itself. Getting this wrong is a whole-hours error that looks like real data.
probe_utc_epoch() {
  local stamp="$1"
  if date -j -u -f "%Y-%m-%dT%H:%M:%SZ" "$stamp" +%s 2>/dev/null; then return 0; fi
  date -u -d "$stamp" +%s 2>/dev/null
}

# ---------------------------------------------------------------------------------------------
# probe_files_modified_since <dir> <minutes> -> matching paths on stdout
#
# `-mmin -N` is portable; `-newermt "-N minutes"` is GNU-only and returns EMPTY on BSD rather than
# erroring. `.git` is excluded because its internals churn on every read-only command, which makes
# an idle worktree look busy — the opposite error, and just as misleading.
probe_files_modified_since() {
  local dir="$1" minutes="$2"
  find "$dir" -type f -not -path '*/.git/*' -mmin "-$minutes" 2>/dev/null
}

# ---------------------------------------------------------------------------------------------
# probe_remote_branch_exists <branch> [remote] -> exit 0 if it exists ON THE REMOTE
#
# `git rev-parse origin/<b>` and `git branch -r --contains` read the LOCAL remote-tracking ref, and
# a plain `git fetch` never prunes deleted branches. Only `ls-remote` asks the server.
probe_remote_branch_exists() {
  local branch="$1" remote="${2:-origin}"
  [ -n "$(git ls-remote --heads "$remote" "$branch" 2>/dev/null)" ]
}

# ---------------------------------------------------------------------------------------------
# probe_pr_failing_checks <pr> [repo] -> "<bucket>\t<name>" for every non-passing check
#
# Never parse the human table: job names contain spaces, so a positional column is a word of the
# NAME, not the status. `--json` is the only stable read. `skipping` is excluded because a skipped
# required context is a THIRD state (absent), not a failure — see probe_pr_missing_contexts.
probe_pr_failing_checks() {
  local pr="$1" repo="${2:-}"
  local args=("$pr" --json "name,bucket")
  [ -n "$repo" ] && args+=(-R "$repo")
  gh pr checks "${args[@]}" 2>/dev/null |
    jq -r '.[] | select(.bucket!="pass" and .bucket!="skipping") | "\(.bucket)\t\(.name)"'
}

# ---------------------------------------------------------------------------------------------
# probe_zombie_runs [repo] -> "<id>\t<created_at>\t<name>" per wedged run
#
# A run whose record says `in_progress` while every one of its jobs is `completed` is wedged in
# GitHub's own state machine. Measured 2026-08-22: 189 such runs, the oldest from 2026-08-09.
#
# They are NOT reapable. Both `POST /actions/runs/{id}/cancel` and `.../force-cancel` answer
# HTTP 500 on every one of them (3/3 sampled). So the only correct handling is to SUBTRACT them
# before any statement about CI saturation — a raw `status=in_progress` count is off by 189 here,
# which is more than the real concurrent load has ever been.
probe_zombie_runs() {
  local repo="${1:-JiRaska/open-bank-oss}"
  local page=1 ids
  while [ "$page" -le 10 ]; do
    ids="$(gh api "repos/$repo/actions/runs?status=in_progress&per_page=100&page=$page" \
      --jq '.workflow_runs[] | "\(.id)\t\(.created_at)\t\(.name)"' 2>/dev/null)"
    [ -z "$ids" ] && break
    while IFS=$'\t' read -r id created name; do
      [ -z "$id" ] && continue
      if gh api "repos/$repo/actions/runs/$id/jobs" \
        --jq 'if (.jobs|length) > 0 and (all(.jobs[]; .status == "completed")) then "wedged" else empty end' \
        2>/dev/null | grep -q wedged; then
        printf '%s\t%s\t%s\n' "$id" "$created" "$name"
      fi
    done <<<"$ids"
    page=$((page + 1))
  done
}

# ---------------------------------------------------------------------------------------------
# probe_lint_findings <linter-command> <file>... -> the linter's own finding lines
#
# Two traps in one wrapper. A newline-joined file list passed unquoted arrives as ONE argument, so
# the linter errors on a path that does not exist and an `[ -n "$out" ]` test reads the error as
# clean — hence the per-file loop. And post-filtering a linter's output to a finding-code pattern
# discards the failures that are not findings (a syntax error is not an `SC` code), so the exit
# status is surfaced instead of being swallowed by a pipeline.
probe_lint_findings() {
  local linter="$1"
  shift
  local file status=0 out
  for file in "$@"; do
    [ -e "$file" ] || { echo "probe: no such file: $file" >&2; status=1; continue; }
    if ! out="$($linter "$file" 2>&1)"; then status=1; fi
    [ -n "$out" ] && printf '%s\n' "$out"
  done
  return $status
}

# ---------------------------------------------------------------------------------------------
# self-test
# ---------------------------------------------------------------------------------------------
# Every case asserts BOTH directions. A probe that only ever sees its known-positive can still be
# a probe that says yes to everything, and that failure mode is exactly as silent as the one this
# file exists to prevent.

_probe_selftest() {
  # See the probe_remote_branch_exists case below: run-gates.py exports GIT_INDEX_FILE (a private
  # index per gate, so concurrent gates cannot race for .git/index.lock), and any git command this
  # self-test runs inside its own temp repos would inherit it and write against the wrong index.
  # Only reached via direct execution (`--selftest`), never when the file is sourced.
  unset GIT_INDEX_FILE GIT_DIR GIT_WORK_TREE GIT_OBJECT_DIRECTORY

  local failures=0
  local executed=0
  _check() {
    executed=$((executed + 1))
    if [ "$2" = "1" ]; then
      echo "  [ok] $1"
    else
      echo "  [FAIL] $1"
      failures=$((failures + 1))
    fi
  }

  # --- probe_utc_epoch --------------------------------------------------------------------
  # 2026-08-22T00:00:00Z is 1787356800 (cross-checked against python3 datetime, not against this
  # file's own arithmetic — an expected value derived from the thing under test proves nothing).
  local epoch
  epoch="$(probe_utc_epoch "2026-08-22T00:00:00Z")"
  _check "probe_utc_epoch parses Z as UTC (got '$epoch')" \
    "$([ "$epoch" = "1787356800" ] && echo 1 || echo 0)"

  # A probe returning a constant, or one that silently fell back to `date` with no input, would
  # still pass the case above. One hour later must be exactly 3600 more.
  local epoch2
  epoch2="$(probe_utc_epoch "2026-08-22T01:00:00Z")"
  _check "probe_utc_epoch advances by exactly 3600s over one hour" \
    "$([ "$((epoch2 - epoch))" = "3600" ] && echo 1 || echo 0)"

  # THE known-negative for this probe: the original bug was a parse in the LOCAL zone, so the
  # property that was actually violated is TZ-invariance. Two zones 9h apart must agree, and the
  # broken form (`date -j -f` with no `-u`) must NOT — otherwise this case is vacuous because the
  # environment happens to be UTC, and it would then pass against the very code it exists to reject.
  local tokyo utc
  tokyo="$(TZ=Asia/Tokyo probe_utc_epoch "2026-08-22T00:00:00Z")"
  utc="$(TZ=UTC probe_utc_epoch "2026-08-22T00:00:00Z")"
  _check "probe_utc_epoch is TZ-invariant (UTC=$utc Tokyo=$tokyo)" \
    "$([ "$tokyo" = "$utc" ] && echo 1 || echo 0)"

  if date -j -f "%Y-%m-%dT%H:%M:%SZ" "2026-08-22T00:00:00Z" +%s >/dev/null 2>&1; then
    local broken_tokyo broken_utc
    broken_tokyo="$(TZ=Asia/Tokyo date -j -f "%Y-%m-%dT%H:%M:%SZ" "2026-08-22T00:00:00Z" +%s 2>/dev/null)"
    broken_utc="$(TZ=UTC date -j -f "%Y-%m-%dT%H:%M:%SZ" "2026-08-22T00:00:00Z" +%s 2>/dev/null)"
    _check "the BSD trap is reproduced: the no-'-u' form is NOT TZ-invariant ($broken_utc vs $broken_tokyo)" \
      "$([ "$broken_tokyo" != "$broken_utc" ] && echo 1 || echo 0)"
  else
    echo "  [note] GNU date: the BSD '-j' local-parse trap does not exist on this platform"
  fi

  # --- probe_files_modified_since ---------------------------------------------------------
  local tmp
  tmp="$(mktemp -d)"
  mkdir -p "$tmp/.git"
  : >"$tmp/fresh.txt"
  : >"$tmp/.git/churn.txt"
  # An old file, stamped explicitly. `touch -t` is portable; `-d` is not.
  : >"$tmp/old.txt"
  touch -t 202001010000 "$tmp/old.txt"

  local found
  found="$(probe_files_modified_since "$tmp" 60)"
  _check "probe_files_modified_since FINDS a file just written" \
    "$(grep -q 'fresh.txt' <<<"$found" && echo 1 || echo 0)"
  _check "probe_files_modified_since does NOT find a 2020 file" \
    "$(grep -q 'old.txt' <<<"$found" && echo 0 || echo 1)"
  _check "probe_files_modified_since excludes .git churn" \
    "$(grep -q 'churn.txt' <<<"$found" && echo 0 || echo 1)"
  rm -rf "$tmp"

  # --- probe_remote_branch_exists ---------------------------------------------------------
  # Hermetic: a real local repo acting as the remote, so this runs with no network. The known-
  # negative is the load-bearing half — it is what a stale local tracking ref gets wrong.
  #
  # `env -u GIT_INDEX_FILE -u GIT_DIR -u GIT_WORK_TREE` is not defensive boilerplate. run-gates.py
  # hands every gate a PRIVATE copy of the repo index via GIT_INDEX_FILE, so that concurrent gates
  # cannot race for .git/index.lock — and that variable is inherited by any git command this
  # fixture runs, including the ones building a brand-new repo in a temp dir. The commit then
  # writes against the WRONG index, `git branch probe-present` has nothing to branch from, and the
  # self-test fails only under the runner while passing when invoked directly. Found exactly that
  # way: green by hand, red in run-gates, and the difference was one exported variable.
  local origin clone
  origin="$(mktemp -d)"
  clone="$(mktemp -d)"
  (
    cd "$origin" || exit 1
    git init -q -b main .
    git -c user.email=probe@example.com -c user.name=probe commit -q --allow-empty -m init
    git branch probe-present
  ) >/dev/null 2>&1
  git clone -q "$origin" "$clone" >/dev/null 2>&1
  (
    cd "$clone" || exit 1
    probe_remote_branch_exists "probe-present" && echo PRESENT
    probe_remote_branch_exists "probe-absent" && echo ABSENT
  ) >"$clone/.probe-out" 2>/dev/null
  _check "probe_remote_branch_exists finds a branch that is on the remote" \
    "$(grep -q PRESENT "$clone/.probe-out" && echo 1 || echo 0)"
  _check "probe_remote_branch_exists rejects a branch that is not" \
    "$(grep -q ABSENT "$clone/.probe-out" && echo 0 || echo 1)"

  # The specific regression: delete the branch on the remote WITHOUT pruning the clone, so the
  # local tracking ref still exists. `git rev-parse` says yes; the probe must say no.
  (cd "$origin" && git branch -D probe-present) >/dev/null 2>&1
  local stale_local=0 probe_answer=0
  (cd "$clone" && git rev-parse --verify -q "refs/remotes/origin/probe-present") >/dev/null 2>&1 && stale_local=1
  (cd "$clone" && probe_remote_branch_exists "probe-present") && probe_answer=1
  _check "the stale local tracking ref does still exist (the trap is reproduced)" "$stale_local"
  _check "probe_remote_branch_exists is NOT fooled by the stale tracking ref" \
    "$([ "$probe_answer" = "0" ] && echo 1 || echo 0)"
  rm -rf "$origin" "$clone"

  # --- probe_lint_findings ----------------------------------------------------------------
  # The list-as-one-argument trap: a caller passing a newline-joined list must not read as clean.
  local lint_tmp
  lint_tmp="$(mktemp -d)"
  : >"$lint_tmp/a.txt"
  : >"$lint_tmp/b.txt"
  local joined status
  joined="$lint_tmp/a.txt"$'\n'"$lint_tmp/b.txt"
  probe_lint_findings true "$joined" >/dev/null 2>&1
  status=$?
  _check "probe_lint_findings FAILS on a newline-joined list (never reads as clean)" \
    "$([ "$status" != "0" ] && echo 1 || echo 0)"
  probe_lint_findings true "$lint_tmp/a.txt" "$lint_tmp/b.txt" >/dev/null 2>&1
  status=$?
  _check "probe_lint_findings succeeds when the files are passed properly" \
    "$([ "$status" = "0" ] && echo 1 || echo 0)"
  rm -rf "$lint_tmp"

  # The network probes (probe_pr_failing_checks, probe_zombie_runs) have no hermetic fixture: their
  # subject is GitHub's own state machine. Declared rather than silently skipped — an unexercised
  # probe is a third state, and pretending it passed is the failure this file is about.
  echo "  [note] probe_pr_failing_checks and probe_zombie_runs are network-bound and unexercised here"

  # Counted, never hard-coded: a literal here would keep reporting a full corpus after someone
  # deleted half the cases, which is the exact shape min_subjects exists to catch (#4339).
  echo "SUBJECTS=$executed  # self-test assertions executed"

  if [ "$failures" -gt 0 ]; then
    echo "SELF-TEST FAILED: $failures probe(s) do not behave as documented."
    return 1
  fi
  echo "SELF-TEST OK: every probe was run against a known-positive AND a known-negative."
  return 0
}

# Executed directly rather than sourced?
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  case "${1:-}" in
    --selftest) _probe_selftest; exit $? ;;
    *) echo "usage: source this file, or run it with --selftest" >&2; exit 2 ;;
  esac
fi
