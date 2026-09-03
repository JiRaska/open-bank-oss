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
# probe_zombie_runs [repo] [min_age_hours] -> "<id>\t<created_at>\t<name>" per wedged run
#
# A run whose record says `in_progress` while every one of its jobs is `completed` is wedged in
# GitHub's own state machine, and neither `POST /actions/runs/{id}/cancel` nor `.../force-cancel`
# can clear it -- both answer HTTP 500 (3/3 sampled, #6472). Subtracting them is the only correct
# handling before any statement about CI saturation.
#
# AGE IS PART OF THE TEST, NOT A TIDY-UP. "Every job completed" alone is NOT sufficient, and the
# first version of this probe shipped without the age bound and over-counted because of it: a live
# run whose remaining jobs have not been CREATED yet also has every existing job completed. Two
# false positives measured on 2026-08-22 within minutes of each other -- a `Services CI` run with
# 30/30 jobs successful and still fanning out, and four runs created 90 seconds earlier. The
# corrected census: 184 in_progress, of which 177 older than 24h (genuinely wedged, all created in
# a 2026-08-06..08-10 window) and 6 under an hour old (simply running).
#
# 24h is not arbitrary and it is not a heuristic about "old": nothing in Actions creates a job a
# day after the run started, so past that bound "all created jobs are done" and "all jobs are
# done" cannot differ.
#
# It is also what makes the probe cheap. Filtering on the list payload first costs 2 API calls;
# asking the jobs endpoint per run, as the first version did, costs N+1 -- 185 calls to answer one
# question.
#
# THE ROOT CAUSE, for whoever reads this next: every one of the three workflows that produced 164
# of the 177 (`Auto-retry spot-killed CI runs` 72, `main red watch` 53, `Dependabot auto-merge` 39)
# has the same shape -- an event-triggered guard whose jobs skip on almost every run, so ALL of its
# jobs are `completed/skipped`. That is a GitHub-side transition failure, not a defect in those
# workflows. It appears fixed upstream: recent all-skipped runs of `main red watch` complete
# normally, and exactly one run has wedged since 2026-08-10.
# probe_utc_cutoff <hours_ago> -> an ISO-8601 UTC instant that many hours in the past
#
# Split out of probe_zombie_runs so it is reachable from --selftest without the network. Portable
# across BSD `date -v` and GNU `date -d`, and ALWAYS `-u`: the Actions API reports created_at in
# UTC, and comparing it against a local wall-clock string is the whole-hours error that once
# flagged 53 branches as having post-merge commits when the true count was 0 (see probe_utc_epoch).
probe_utc_cutoff() {
  local hours="$1"
  date -u -v-"${hours}"H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
    || date -u -d "${hours} hours ago" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null
}

probe_zombie_runs() {
  local repo="${1:-JiRaska/open-bank-oss}"
  local min_age_hours="${2:-24}"
  local page=1 ids cutoff

  cutoff="$(probe_utc_cutoff "$min_age_hours")"
  if [ -z "$cutoff" ]; then
    echo "probe_zombie_runs: could not compute a UTC cutoff — refusing to report a census that" \
         "would silently include live runs" >&2
    return 1
  fi

  while [ "$page" -le 10 ]; do
    # `gh api --jq` takes exactly ONE argument and does NOT accept jq's `--arg`: passing it dies
    # with "accepts 1 arg(s), received 4". With the old `2>/dev/null` that error was swallowed,
    # `ids` came back empty, and `[ -z "$ids" ] && break` left the loop on page 1 — so this probe
    # reported a clean census of zero while 87 wedged runs sat on page 1 alone. That is precisely
    # the failure this library exists to prevent, in the library itself. The cutoff is interpolated
    # into the filter instead, and its shape is asserted first so a malformed value cannot become
    # jq source.
    case "$cutoff" in
      [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]Z) : ;;
      *) echo "probe_zombie_runs: cutoff $cutoff is not an ISO-8601 UTC instant — refusing to" \
              "build a jq filter from it" >&2; return 1 ;;
    esac

    # stderr is captured, never discarded: a failed call must not be indistinguishable from a page
    # with no wedged runs.
    local err rc
    err="$(mktemp)"
    ids="$(gh api "repos/$repo/actions/runs?status=in_progress&per_page=100&page=$page" \
      --jq ".workflow_runs[] | select(.created_at < \"$cutoff\") | \"\\(.id)\\t\\(.created_at)\\t\\(.name)\"" \
      2>"$err")"
    rc=$?
    if [ "$rc" -ne 0 ]; then
      echo "probe_zombie_runs: gh api failed on page $page (exit $rc): $(head -1 "$err")" >&2
      rm -f "$err"
      return 1
    fi
    rm -f "$err"
    [ -z "$ids" ] && break
    while IFS=$'\t' read -r id created name; do
      [ -z "$id" ] && continue
      # Still confirmed per run: age alone would also catch a genuinely long-running job, which is
      # slow but healthy and must not be subtracted from a saturation count.
      #
      # The same two traps as the page call above, and they were left here when that one was fixed.
      # `2>/dev/null` made a failed jobs call identical to "this run is not wedged", so the run was
      # dropped from the census silently -- the under-counting direction of the very bug this
      # function is named after. And `gh api ... | grep -q` reports GREP's status, so even without
      # the redirect the failure could not be seen: the exit code has to be read off the assignment,
      # never off the pipeline (the repo's own "measure the exit code, not the pipeline's" rule).
      local jerr jrc jout
      jerr="$(mktemp)"
      jout="$(gh api "repos/$repo/actions/runs/$id/jobs" \
        --jq 'if (.jobs|length) > 0 and (all(.jobs[]; .status == "completed")) then "wedged" else empty end' \
        2>"$jerr")"
      jrc=$?
      if [ "$jrc" -ne 0 ]; then
        echo "probe_zombie_runs: gh api failed on jobs for run $id (exit $jrc):" \
             "$(head -1 "$jerr") — refusing to report a partial census" >&2
        rm -f "$jerr"
        return 1
      fi
      rm -f "$jerr"
      case "$jout" in *wedged*) printf '%s\t%s\t%s\n' "$id" "$created" "$name" ;; esac
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

  # --- probe_utc_cutoff (the age half of probe_zombie_runs, #6472) --------------------
  # The age bound is not a tidy-up: "every job completed" alone also matches a LIVE run whose
  # remaining jobs have not been created yet, which is how the first version of the census
  # over-counted. So the cutoff itself is held to both directions.
  local cut now_s cut_s delta
  cut="$(probe_utc_cutoff 24)"
  ran+=("probe_utc_cutoff: shape" "probe_utc_cutoff: 24h back" "probe_utc_cutoff: TZ-invariant")
  _check "probe_utc_cutoff returns a Z-suffixed UTC instant (got '$cut')" \
    "$(printf '%s' "$cut" | grep -qE '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$' && echo 1 || echo 0)"
  now_s="$(date -u +%s)"
  cut_s="$(probe_utc_epoch "$cut")"
  delta=$(( now_s - cut_s ))
  _check "probe_utc_cutoff 24 is 24h back within a minute (delta=${delta}s)" \
    "$([ "$delta" -ge 86340 ] && [ "$delta" -le 86460 ] && echo 1 || echo 0)"
  # These are two separate calls to the live clock, so an exact string equality is flaky at a
  # second boundary. A timezone regression is hours apart; allow one second only for that clock
  # tick while still rejecting local-wall-clock arithmetic.
  local tokyo_cut utc_cut tokyo_s utc_s cutoff_skew
  tokyo_cut="$(TZ=Asia/Tokyo probe_utc_cutoff 24)"
  utc_cut="$(TZ=UTC probe_utc_cutoff 24)"
  tokyo_s="$(probe_utc_epoch "$tokyo_cut")"
  utc_s="$(probe_utc_epoch "$utc_cut")"
  cutoff_skew=$(( tokyo_s - utc_s ))
  [ "$cutoff_skew" -lt 0 ] && cutoff_skew=$(( -cutoff_skew ))
  _check "probe_utc_cutoff is TZ-invariant within live-clock rounding (skew=${cutoff_skew}s)" \
    "$([ "$cutoff_skew" -le 1 ] && echo 1 || echo 0)"

  # --- probe_zombie_runs ------------------------------------------------------------------
  # These were "network-bound, unexercised" until 2026-09-03, and that gap let the probe ship
  # broken: it passed jq's `--arg` to `gh api --jq`, which rejects it, and `2>/dev/null` turned the
  # error into an empty page. It reported ZERO wedged runs while 177 existed.
  # `gh` is a shell function here, so this is hermetic — no network, no fixtures on disk. The stub
  # parses argv properly rather than doing string surgery on "$*": a first attempt sliced the filter
  # out of the joined arguments, mangled it, and jq exited 3 on every call. That mattered less for
  # what it broke than for what it revealed — BOTH negative assertions passed while every call was
  # failing, because an absence assertion is satisfied when everything is absent. The
  # known-positive is the only reason it was caught.
  gh() {
    local url="" filter="" body=""
    while [ $# -gt 0 ]; do
      case "$1" in
        api)   shift ;;
        --jq)  filter="$2"; shift 2 ;;
        *)     url="$1"; shift ;;
      esac
    done
    case "$url" in
      */actions/runs/1/jobs*) body='{"jobs":[{"status":"completed"},{"status":"completed"}]}' ;;
      */actions/runs/3/jobs*) body='{"jobs":[{"status":"completed"},{"status":"in_progress"}]}' ;;
      # A run whose jobs list is EMPTY satisfies `all(.jobs[]; ...)` vacuously — jq's `all` over an
      # empty array is true — so without the `(.jobs|length) > 0` guard this would be reported
      # wedged. The guard was written but never falsified until this fixture existed.
      */actions/runs/4/jobs*) body='{"jobs":[]}' ;;
      *page=1*) body='{"workflow_runs":[
        {"id":1,"created_at":"2026-08-09T10:00:00Z","name":"wedged-old"},
        {"id":2,"created_at":"2099-01-01T00:00:00Z","name":"fresh"},
        {"id":3,"created_at":"2026-08-09T10:00:00Z","name":"old-but-live"},
        {"id":4,"created_at":"2026-08-09T10:00:00Z","name":"old-no-jobs"}]}' ;;
      *) body='{"workflow_runs":[]}' ;;
    esac
    printf '%s' "$body" | jq -r "$filter"
  }
  if command -v jq >/dev/null 2>&1; then
    local zr
    zr="$(probe_zombie_runs owner/repo 24 2>/dev/null)"
    _check "probe_zombie_runs returns the wedged old run (known-positive)" \
      "$(printf '%s' "$zr" | grep -q 'wedged-old' && echo 1 || echo 0)"
    _check "probe_zombie_runs excludes a run newer than the cutoff" \
      "$(printf '%s' "$zr" | grep -q 'fresh' && echo 0 || echo 1)"
    # The false positive the probe was burned by: old, but a job is still running — slow and
    # healthy, and subtracting it would understate real load.
    _check "probe_zombie_runs excludes an old run with a job still in flight" \
      "$(printf '%s' "$zr" | grep -q 'old-but-live' && echo 0 || echo 1)"
    _check "probe_zombie_runs excludes an old run whose jobs list is empty (vacuous-all guard)" \
      "$(printf '%s' "$zr" | grep -q 'old-no-jobs' && echo 0 || echo 1)"
  else
    echo "  [note] probe_zombie_runs fixture cases need jq and were NOT run"
  fi
  # No jq needed, and this is the case that would have caught the shipped bug: a failing call must
  # be distinguishable from a page with nothing on it.
  gh() { echo "accepts 1 arg(s), received 4" >&2; return 1; }
  probe_zombie_runs owner/repo 24 >/dev/null 2>&1
  _check "probe_zombie_runs FAILS loudly when gh errors (never reads as a clean census)" \
    "$([ "$?" -ne 0 ] && echo 1 || echo 0)"
  unset -f gh

  # The PAGE call and the PER-RUN JOBS call are two separate `gh` invocations, and fixing only the
  # first leaves the second able to fail silently — it drops the run from the census, which is the
  # under-counting direction of the same defect. This stub answers the page and fails the jobs call,
  # so it is red unless BOTH calls surface their failure. It also pins the pipeline trap: while the
  # jobs call ended in `| grep -q wedged` the status read was grep's, so this case passed even with
  # the redirect removed.
  if command -v jq >/dev/null 2>&1; then
    gh() {
      local url="" filter=""
      while [ $# -gt 0 ]; do
        case "$1" in
          api)   shift ;;
          --jq)  filter="$2"; shift 2 ;;
          *)     url="$1"; shift ;;
        esac
      done
      case "$url" in
        */jobs*) echo "HTTP 500" >&2; return 1 ;;
        *page=1*) printf '%s' '{"workflow_runs":[{"id":1,"created_at":"2026-08-09T10:00:00Z","name":"x"}]}' | jq -r "$filter" ;;
        *) printf '%s' '{"workflow_runs":[]}' | jq -r "$filter" ;;
      esac
    }
    probe_zombie_runs owner/repo 24 >/dev/null 2>&1
    _check "probe_zombie_runs FAILS loudly when the per-run JOBS call errors" \
      "$([ "$?" -ne 0 ] && echo 1 || echo 0)"
    unset -f gh
  fi

  # probe_pr_failing_checks remains network-bound with no hermetic fixture: its subject is a live
  # PR's check rollup. Declared rather than silently skipped — an unexercised probe is a third
  # state, and pretending it passed is the failure this file is about.
  echo "  [note] probe_pr_failing_checks is network-bound and unexercised here"

  # Counted, never hard-coded: a literal here would keep reporting a full corpus after someone
  # deleted half the cases, which is the exact shape min_subjects exists to catch (#4339).
  echo "SUBJECTS=$executed  # self-test assertions executed"

  if [ "$failures" -gt 0 ]; then
    echo "SELF-TEST FAILED: $failures probe(s) do not behave as documented."
    return 1
  fi
  echo "SELF-TEST OK: $executed assertion(s); every probe exercised here was run against a"
  echo "  known-positive AND a known-negative. probe_pr_failing_checks is declared unexercised"
  echo "  above and is NOT covered by that statement."
  return 0
}

# Executed directly rather than sourced?
if [ "${BASH_SOURCE[0]}" = "${0}" ]; then
  case "${1:-}" in
    --selftest) _probe_selftest; exit $? ;;
    *) echo "usage: source this file, or run it with --selftest" >&2; exit 2 ;;
  esac
fi
