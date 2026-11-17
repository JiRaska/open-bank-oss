#!/usr/bin/env bash
# Decide whether a completed CI run was killed by a spot reclaim, and if so re-run it once.
#
# WHY THIS IS A SCRIPT AND NOT A `run:` BLOCK (issue #6255)
# This logic used to live inline in `.github/workflows/auto-retry-cancelled.yml`. Inline, its
# only executor was GitHub Actions on a real `workflow_run` event, so every branch — including
# the ones that exist precisely for the rare case — was reachable only by waiting for that rare
# case to happen in production. That is how #6255 shipped: the DETECTION call
# (`gh api .../jobs`) had no transient handling at all, while the re-run call next to it had
# three attempts with backoff, and nothing could demonstrate the asymmetry because nothing
# could run the step. As a script it has a `--self-test` that drives every decision and every
# error class against a stub `gh`, so the failure paths are exercised on every PR.
#
# THE #6255 DEFECT, PRECISELY
# Run 32502824965: the `cancelled` branch's detection query answered
#   gh: API rate limit exceeded for installation ... (HTTP 403)
# and, being a plain command substitution under `set -euo pipefail`, killed the step at once —
# before any `decide` line was emitted, so the workflow's own evidence contract ("every exit
# path records ONE line saying what was examined and what was decided") was not honoured on the
# one path where it mattered. Its target, run 32499371733, had TWO jobs interrupted mid-flight
# (22 and 20 steps recorded) — an unambiguous reclaim — and is still at `run_attempt: 1` today.
# The `failure` branch had the mirror-image bug and it is the worse one: its `|| echo ""` turned
# the same rate limit into `decision=jobs-unreadable` + **exit 0**, i.e. a GREEN run of the
# control, about a reclaimed run it never re-ran and told nobody about.
#
# RATE LIMIT vs PERMISSION DENIAL
# On GitHub both are HTTP 403 and are told apart only by the message text (`API rate limit
# exceeded` / `secondary rate limit` vs `Resource not accessible by integration`). The status
# code alone therefore cannot classify, which is why the classification matches on TEXT.
#
# WHERE THE RETRY LIVES NOW (issues #6853, #7976)
# This script used to carry its own `is_transient` + backoff loop. It was correct about WHICH
# failures are transient and wrong about WHAT TO DO with a rate limit: three attempts at
# 15s/30s spend 45 seconds against an installation limit whose window is MINUTES, so the limit
# is still in force when the last attempt lands. Measured on run 33738356244 (2026-09-03): the
# job ran 50 seconds, logged `jobs API attempt 1/3, 2/3, 3/3 failed ... API rate limit exceeded
# for installation (HTTP 403)`, answered `decision=jobs-unreadable` and exited 1 — leaving run
# 33737873956, a genuine `cancelled` reclaim, stranded at attempt 1 exactly as #6255 had.
#
# The loop is therefore `gh_retry` from `.github/scripts/gh-retry.sh`, which waits until the
# limit actually RESETS (bounded by GH_RETRY_MAX_WAIT_SECONDS) for the rate-limit class and
# backs off exponentially for the 5xx/connection class. There is now ONE classifier in the
# fleet rather than one per script — which is the whole reason that library exists, and it had
# ZERO callers from the day it landed (#8371) until this change.
#
# "COULD NOT READ" IS NOT A PASS HERE
# `.github/CLAUDE.md` records that a gate should render an unreadable corpus as a notice + exit
# 0 rather than as a finding. That is right for a gate, whose subject is the PR. It is wrong
# here, and the difference is what happens next: a gate that cannot read simply does not judge,
# whereas this control not judging leaves a reclaimed CI run sitting red forever with no PR to
# redden and no commit status anyone reads. So an unreadable jobs API — after the transient
# retries are spent — exits 1 on purpose, which is what routes it to the `raise-issue` job and
# puts a named human in the loop. Non-transient (a real permission answer) exits 1 immediately.
#
# WHAT THIS CANNOT DO
# A human who cancels a CURRENT run WHILE its jobs are running is indistinguishable from a spot
# reclaim with the data GitHub exposes — both leave `cancelled` jobs carrying recorded steps —
# and is still re-run once. A superseded PR head is distinguishable using the live PR endpoint;
# a queue drain is distinguishable because no cancelled job started a step (#3208).
#
# EXIT CODES
#   0  a decision was reached and acted on (re-run issued, or correctly declined)
#   1  the control could not do its job — a human must re-run the target by hand
#
# ENV (all required except the last three)
#   GITHUB_REPOSITORY  owner/repo
#   RUN_ID             the triggering run id
#   CONCLUSION         its conclusion: `cancelled` or `failure`
#   RUN_EVENT          event from the discovery API (`pull_request`, `push`, ...)
#   RUN_URL            its html_url (for the human-readable notices)
#   GITHUB_STEP_SUMMARY  optional; the `decide` table is appended here when set
#   RETRY_DECISIONS_FILE optional; append JSONL decisions, not proof a rerun was accepted
#   GH_BIN             optional; the `gh` executable (the self-test points it at a stub)
#   SPOT_KILL_ATTEMPTS      optional; attempts per API call (default 5)
#   LOG_READ_CAP            optional; job-log downloads per decision (default 30)
#   ARTIFACT_SETTLE_SECONDS optional; minimum age of an artifact outage before a re-run (1800)
#   TRANSIENT_NOW_EPOCH     optional; "now" for the settle check (the self-test pins it)
#   GH_RETRY_MAX_WAIT_SECONDS optional; cap on a single wait (default 600; 0 in tests)
set -euo pipefail

# shellcheck source=.github/scripts/gh-retry.sh
. "$(dirname "${BASH_SOURCE[0]:-$0}")/gh-retry.sh"

GH_BIN="${GH_BIN:-gh}"
# 5, not 3. With reset-aware waiting an attempt costs a wait rather than a fixed 15s, so the
# question this number answers changed: it is no longer "how many quick retries" but "how many
# reset windows do we sit through before handing a human the run". Four waits capped at 600s
# bound the job at ~40 min, which `timeout-minutes` in the workflow backstops.
ATTEMPTS="${SPOT_KILL_ATTEMPTS:-5}"
GH_RETRY_MAX_WAIT_SECONDS="${GH_RETRY_MAX_WAIT_SECONDS:-600}"
# The reset probe inside gh-retry.sh must go through the SAME `gh` this script uses, or the
# self-test's scripted stub never sees it and its call counter desynchronises.
GH_RETRY_GH_BIN="${GH_BIN}"
export GH_RETRY_GH_BIN GH_RETRY_MAX_WAIT_SECONDS
# `with_retry` is called inside a command substitution — a subshell — so it cannot hand the
# error text back in a variable. That is why `decision=jobs-unreadable` printed `(last: unknown)`
# on run 33738356244 while the rate-limit text was right there. A file crosses the subshell.
RETRY_ERROR_FILE="${RETRY_ERROR_FILE:-$(mktemp)}"
GH_RETRY_LAST_ERROR_FILE="${RETRY_ERROR_FILE}"
export GH_RETRY_LAST_ERROR_FILE
RETRY_LAST_ERROR=""

# `-R` ON EVERY CALL IS LOAD-BEARING (issue #2841/#2898). This runs in a job with no
# actions/checkout, so there is no git repository for `gh` to infer the target repo from and a
# bare call dies with `failed to determine base repo`. That defect made the auto-retry from
# #2330 fail 208 times in silence. Do not drop the flag and do not "fix" it with a checkout.
gh_() { "${GH_BIN}" "$@"; }

# `is_transient` used to live here, a second implementation of the classification `gh-retry.sh`
# already carried. Two copies of one rule is exactly the drift that library exists to remove, so
# the classification is now `_gh_retry_classify` and this script keeps only what is SPECIFIC to
# it: the "already running" answer below.

decide() { # decide <decision> <human sentence>
  echo "spot-kill-auto-retry run=${RUN_ID} conclusion=${CONCLUSION} decision=$1"
  if [ -n "${RETRY_DECISIONS_FILE:-}" ]; then
    # Export only public run identity and the enum decision, never raw API/log prose.
    if ! jq -cn --arg run_id "$RUN_ID" --arg conclusion "$CONCLUSION" --arg decision "$1" \
      '{evidence_kind: "decision_only", run_id: $run_id, conclusion: $conclusion, decision: $decision}' >> "$RETRY_DECISIONS_FILE"; then
      echo "::warning::Could not retain retry decision; consult the job summary."
    fi
  fi
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "| \`${CONCLUSION}\` | [${RUN_ID}](${RUN_URL:-}) | \`$1\` | $2 |" >> "${GITHUB_STEP_SUMMARY}"
  fi
}

# One attempt, adapted to what `gh_retry` expects. Two jobs the library cannot do for us:
#
#  1. "Already running" is neither transient nor an error — the substance this control exists to
#     achieve has ALREADY happened (issue #5489, measured on run 32100417490). It has to become a
#     SUCCESS here, BEFORE gh_retry sees it, because the library would classify that text as
#     `final` and escalate a run that is in fact being re-run.
#  2. `gh` writes its error to stderr and this script's callers read it as stdout (the old
#     wrapper's `2>&1`). gh_retry classifies on STDERR, so merge, then put the text back on
#     stderr when the call actually failed.
_attempt() { # _attempt <cmd...>
  local out rc=0
  out="$("$@" 2>&1)" || rc=$?
  if [ "${rc}" -eq 0 ]; then printf '%s' "${out}"; return 0; fi
  case "${out}" in
    *"already running"*)
      echo "target is already running (race with a prior attempt or GitHub's own mechanism) — nothing to do, treating as success" >&2
      printf '%s' "${out}"; return 0 ;;
  esac
  printf '%s\n' "${out}" >&2
  return "${rc}"
}

# Retry wrapper shared by BOTH the detection query and the re-run call. Before #6255 only the
# re-run had one; the asymmetry was the defect. The loop itself is now `gh_retry` (see the
# header) so the classification cannot drift from the rest of the fleet's automation.
with_retry() { # with_retry <label> <cmd...>  -> stdout of the successful call
  local out rc=0
  local label="$1"; shift
  : > "${RETRY_ERROR_FILE}"
  # stderr — gh_retry's own `attempt N/M` warnings AND the failing call's text — is passed
  # through to the log; only stdout is the value.
  out="$(gh_retry "${ATTEMPTS}" -- _attempt "$@")" || rc=$?
  if [ "${rc}" -ne 0 ]; then
    # Deliberately NOT "failed ${ATTEMPTS}/${ATTEMPTS} times": gh_retry stops at the first
    # NON-transient answer, so a permission denial gets here after ONE call. Asserting the
    # full count would put a number in the log that the run never reached — the `attempt N/M`
    # warnings gh_retry emits above are the record of what actually happened.
    echo "::error title=spot-kill auto-retry::${label} failed on ${RUN_URL:-run ${RUN_ID}} (up to ${ATTEMPTS} attempts; a non-transient error stops at the first). Needs a manual re-run." >&2
    return 1
  fi
  printf '%s' "${out}"
}

# Both branches query attempt 1 explicitly. `/actions/runs/<id>/jobs` returns the LATEST
# attempt's jobs, so a concurrent manual re-run would make this inspect a different (possibly
# green) attempt and silently decline.
jobs_query() { # jobs_query <jq>
  gh_ api "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}/attempts/1/jobs?per_page=100" --jq "$1"
}

# A cancelled PR run can have executed steps because a newer push cancelled it, not because
# spot capacity vanished. Re-running that OLD SHA uses the PR's concurrency group and can
# cancel the NEW head's valid jobs. This happened on #9971: run 35296255139 (88cbf0b58) was
# auto-rerun by 35297029298 and cancelled head a01b680e0's graph producer. Check the live PR
# head immediately before issuing any rerun, not when candidates are discovered minutes earlier.
# Return 10 for a stale/previously rerun PR (a completed decision), 1 for unreadable evidence.
guarded_rerun() { # guarded_rerun [--failed]
  local detail event run_sha current_sha pr_count pr_number pr_detail pr_state attempt
  local head_owner head_branch branch_prs branch_pr_count head_repo
  case "${RUN_EVENT:-}" in
    pull_request|pull_request_target)
      detail="$(with_retry "run metadata API" gh_ api "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}")" || return 1
      event="$(jq -r '.event // ""' <<< "${detail}")" || return 1
      run_sha="$(jq -r '.head_sha // ""' <<< "${detail}")" || return 1
      pr_count="$(jq -r '(.pull_requests // []) | length' <<< "${detail}")" || return 1
      pr_number="$(jq -r '(.pull_requests // [])[0].number // 0' <<< "${detail}")" || return 1
      attempt="$(jq -r '.run_attempt // 0' <<< "${detail}")" || return 1
      # GitHub drops pull_requests from old run metadata after a PR is merged and its
      # branch is deleted. Check the original head identity before escalating: a
      # closed-only match has no current PR run to reanimate. A uniquely identified
      # open PR may prove the run stale. No fallback path authorizes a rerun.
      if [ "${pr_count}" -eq 0 ] && [ "${event}" = "${RUN_EVENT}" ]; then
        head_owner="$(jq -r '.head_repository.owner.login // ""' <<< "${detail}")" || return 1
        head_branch="$(jq -r '.head_branch // ""' <<< "${detail}")" || return 1
        if [ -n "${head_owner}" ] && [ -n "${head_branch}" ]; then
          branch_prs="$(with_retry "PR branch lookup API" gh_ api -X GET "repos/${GITHUB_REPOSITORY}/pulls" -f state=all -f "head=${head_owner}:${head_branch}" -f per_page=100)" || return 1
          branch_pr_count="$(jq -r 'if type == "array" then length else -1 end' <<< "${branch_prs}")" || return 1
          if [ "${branch_pr_count}" -gt 0 ] && [ "${branch_pr_count}" -lt 100 ] &&
             jq -e 'all(.[]; .state == "closed")' <<< "${branch_prs}" >/dev/null; then
            echo "::notice title=spot-kill auto-retry::NOT re-running ${RUN_URL:-${RUN_ID}} — its original PR branch now belongs only to closed PRs."
            decide skipped-closed-pr-no-association "GitHub omitted the old run's PR association; the branch lookup found no open PR"
            return 10
          fi
          head_repo="$(jq -r '.head_repository.full_name // ""' <<< "${detail}")" || return 1
          # Branch names can be reused. Only a unique, fully bound branch match,
          # confirmed again by the PR endpoint, proves this old run is stale.
          # Equal heads and incomplete/ambiguous responses still fail closed.
          if [ "${branch_pr_count}" -eq 1 ] && [[ "${run_sha}" =~ ^[0-9a-f]{40}$ ]] &&
             [[ "${attempt}" =~ ^[0-9]+$ ]] && [ -n "${head_repo}" ] &&
             jq -e --arg repo "${GITHUB_REPOSITORY}" --arg head_repo "${head_repo}" \
               --arg owner "${head_owner}" --arg branch "${head_branch}" --arg sha "${run_sha}" '
               .[0] | .state == "open" and
               (.number | type == "number" and . > 0 and . == floor) and
               .base.repo.full_name == $repo and .head.repo.full_name == $head_repo and
               .head.repo.owner.login == $owner and .head.ref == $branch and
               (.head.sha | type == "string" and test("^[0-9a-f]{40}$")) and .head.sha != $sha
               ' <<< "${branch_prs}" >/dev/null; then
            pr_number="$(jq -r '.[0].number' <<< "${branch_prs}")"
            pr_detail="$(with_retry "PR head API" gh_ api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}")" || return 1
            current_sha="$(jq -r '.[0].head.sha' <<< "${branch_prs}")"
            if jq -e --arg repo "${GITHUB_REPOSITORY}" --arg head_repo "${head_repo}" \
                 --arg owner "${head_owner}" --arg branch "${head_branch}" \
                 --arg sha "${current_sha}" --argjson number "${pr_number}" '
                 .number == $number and .state == "open" and .base.repo.full_name == $repo and
                 .head.repo.full_name == $head_repo and .head.repo.owner.login == $owner and
                 .head.ref == $branch and .head.sha == $sha
                 ' <<< "${pr_detail}" >/dev/null; then
              echo "::notice title=spot-kill auto-retry::NOT re-running ${RUN_URL:-${RUN_ID}} — the uniquely identified PR branch has advanced."
              decide skipped-stale-pr-no-association "GitHub omitted the run association; the exact live PR identity confirms a newer head"
              return 10
            fi
          fi
        fi
      fi
      if [ "${event}" != "${RUN_EVENT}" ] || [ "${pr_count}" -ne 1 ] ||
         [[ ! "${run_sha}" =~ ^[0-9a-f]{40}$ ]] || [[ ! "${pr_number}" =~ ^[1-9][0-9]*$ ]] ||
         [[ ! "${attempt}" =~ ^[0-9]+$ ]]; then
        echo "::error title=spot-kill auto-retry::Cannot establish the current PR head for ${RUN_URL:-${RUN_ID}}; refusing to rerun." >&2
        decide pr-head-undetermined "the live run metadata did not establish exactly one current PR head"
        return 1
      fi
      pr_detail="$(with_retry "PR head API" gh_ api "repos/${GITHUB_REPOSITORY}/pulls/${pr_number}")" || return 1
      pr_state="$(jq -r '.state // ""' <<< "${pr_detail}")" || return 1
      current_sha="$(jq -r '.head.sha // ""' <<< "${pr_detail}")" || return 1
      if [[ ! "${current_sha}" =~ ^[0-9a-f]{40}$ ]] || [ -z "${pr_state}" ]; then
        echo "::error title=spot-kill auto-retry::Cannot read current head of PR #${pr_number}; refusing to rerun." >&2
        decide pr-head-undetermined "the current PR endpoint had no valid head SHA"
        return 1
      fi
      if [ "${pr_state}" != "open" ] || [ "${attempt}" -ne 1 ] || [ "${run_sha}" != "${current_sha}" ]; then
        echo "::notice title=spot-kill auto-retry::NOT re-running ${RUN_URL:-${RUN_ID}} — run head ${run_sha} is not the current PR head ${current_sha}, or it was already retried."
        decide skipped-stale-pr-head "the PR advanced or this run already has another attempt; an old rerun could cancel current CI"
        return 10
      fi
      ;;
    push|workflow_dispatch) ;;
    *)
      echo "::error title=spot-kill auto-retry::Unknown run event ${RUN_EVENT:-<empty>} for ${RUN_URL:-${RUN_ID}}; refusing to rerun." >&2
      decide run-event-undetermined "the discovery API did not identify a supported run event"
      return 1 ;;
  esac
  with_retry "gh run rerun" gh_ run rerun -R "${GITHUB_REPOSITORY}" "${RUN_ID}" "$@"
}

# ── THE TEXTUAL SIGNATURE (#9787) ────────────────────────────────────────────────────────────
# The structural rule above models a reclaim as "the runner agent marked the step cancelled". That
# is not what happens when the step's OWN process takes the SIGTERM and exits first: GitHub then
# records a COMPLETED step with a non-zero exit, i.e. `failure`, and the job carries no cancelled
# step at all. A long-running child that traps SIGTERM produces exactly that — measured on PR
# #9172, run 34678808656, `Admin UI build` (Playwright with its web server): `cancelled_steps=0
# failed_steps=1`, while the log ended with the canonical reclaim text and `exit code 143`.
#
# So this is a SECOND, independent recogniser, consulted only when the structural one declines.
# It is deliberately narrow — BOTH the shutdown line and a 143 exit must be present in the same
# job's log. Either alone is a normal thing: 143 is any SIGTERM (an OOM kill, a `timeout`), and
# the shutdown line appears in jobs that were cancelled cleanly and already match the rule above.
# Requiring the pair is what keeps this from becoming "retry every red job".
RECLAIM_SHUTDOWN_TEXT="The runner has received a shutdown signal"
RECLAIM_EXIT_TEXT="Process completed with exit code 143"

failed_job_ids() {
  jobs_query '.jobs[] | select(.conclusion == "failure") | .id'
}

# 0 = this job's log carries the pair. Non-zero = it does not, OR the log could not be read.
# NOT retried and NOT escalated on an unreadable log: the structural rule has already declined,
# so the run stays red either way, and the worst case of a missing log is the behaviour that was
# there before this recogniser existed.
# Every job-log read goes through here. Up to three recognisers ask about the same failed job,
# and a log download is one call against the very installation quota whose exhaustion is one of
# the classes below — so a log is fetched at most once per decision. A FAILED read is not
# cached: the next recogniser may try again, and an unreadable log always declines.
#
# BOUNDED: at most LOG_READ_CAP downloads per decision, counted in a file because the callers run
# in command substitutions (subshells). Past the cap a read fails, which every recogniser treats
# as "cannot show it is safe" — decline. A run with dozens of failed jobs is not the outage shape
# any of these recognisers exists for, and confirming it would cost dozens of calls.
LOG_READ_CAP="${LOG_READ_CAP:-30}"
job_log() { # job_log <job_id>  -> the log on stdout
  local f="${LOG_CACHE_DIR:-}/$1.log"
  if [ -n "${LOG_CACHE_DIR:-}" ] && [ -f "${f}" ]; then cat "${f}"; return 0; fi
  if [ -n "${LOG_CACHE_DIR:-}" ]; then
    echo x >> "${LOG_CACHE_DIR}/reads"
    [ "$(wc -l < "${LOG_CACHE_DIR}/reads")" -le "${LOG_READ_CAP}" ] || return 1
  fi
  local log
  log="$(gh_ api "repos/${GITHUB_REPOSITORY}/actions/jobs/$1/logs" 2>/dev/null)" || return 1
  if [ -n "${LOG_CACHE_DIR:-}" ]; then printf '%s\n' "${log}" > "${f}"; fi
  printf '%s\n' "${log}"
}

job_log_shows_reclaim() { # job_log_shows_reclaim <job_id>
  local log
  log="$(job_log "$1")" || return 1
  case "${log}" in *"${RECLAIM_SHUTDOWN_TEXT}"*) ;; *) return 1 ;; esac
  case "${log}" in *"${RECLAIM_EXIT_TEXT}"*) ;; *) return 1 ;; esac
  return 0
}

# The id of the first failed job whose log carries the pair, or empty.
reclaimed_job_from_logs() {
  local id
  for id in $(failed_job_ids); do
    if job_log_shows_reclaim "${id}"; then printf '%s' "${id}"; return 0; fi
  done
  return 1
}

# ── THE GRADLE-DISTRIBUTION OUTAGE SIGNATURE ─────────────────────────────────────────────────
# Services CI runs 42461736478 (2026-11-08) and 42570587622 / 42570652696 (2026-11-09): 36+
# `(contract)` jobs went red in one minute because `./gradlew` could not download its own
# distribution — `java.io.IOException: Server returned HTTP response code: 504 for URL:
# https://services.gradle.org/distributions/...` or `Downloading from ... failed: timeout
# (10000ms)`. The wrapper's `main` thread dies before Gradle starts, so NOTHING was built or
# tested; and because auto-deploy waits for a green Services CI, every service's deploy skipped.
#
# The recogniser is EXHAUSTIVE over the job's error annotations, not a substring search: every
# `##[error]` line in the log must be one of
#   * the wrapper's own outage exception (5xx or timeout, for a services.gradle.org distribution),
#   * ensure-gradle-distribution.py's "could not obtain ... (infrastructure, not a build failure)",
#   * `Process completed with exit code N.`, and only inside a step segment that ALSO carries
#     one of the two outage lines above;
# and there must be at least one outage line. So a 404 (a wrong version — will not heal), an
# integrity failure, a 504 from a Maven repository, a compile error or a red test anywhere in
# the job makes it decline. Segments are delimited by the runner's `##[group]Run ` header.
# The awk reads the log on stdin; exit 0 = outage only, 1 = anything else.
wrapper_outage_log() {
  awk '
    { sub(/\r$/, "") }
    /##\[group\]Run / { seg++ }
    # Gradle reports a red build or test WITHOUT an annotation; any such line anywhere means
    # something actually ran and failed, so the job is not a pure outage.
    /BUILD FAILED|FAILURE: Build failed|tests completed, [0-9]+ failed|> .* FAILED$/ { bad = 1 }
    index($0, "##[error]") == 0 { next }
    /##\[error\]Exception in thread "main" java\.io\.IOException: Server returned HTTP response code: 5[0-9][0-9] for URL: https:\/\/services\.gradle\.org\/distributions\/gradle-[0-9A-Za-z.-]+\.zip$/ { outage[seg] = 1; n++; next }
    /##\[error\]Exception in thread "main" java\.io\.IOException: Downloading from https:\/\/services\.gradle\.org\/distributions\/gradle-[0-9A-Za-z.-]+\.zip failed: timeout \([0-9]+ms\)$/ { outage[seg] = 1; n++; next }
    /##\[error\]ensure-gradle-distribution: could not obtain https:\/\/services\.gradle\.org\/distributions\/gradle-[0-9A-Za-z.-]+\.zip after [0-9]+ attempts \(infrastructure, not a build failure\)$/ { outage[seg] = 1; n++; next }
    /##\[error\]Process completed with exit code [0-9]+\.$/ { exits[seg] = 1; next }
    { bad = 1 }
    END {
      if (bad || n == 0) exit 1
      for (s in exits) if (!(s in outage)) exit 1
      exit 0
    }'
}

# 0 = every failed job of attempt 1 is either the `all-green` aggregator (which fails BECAUSE
# the builds failed, and is re-run with them by `--failed`) or a job whose log is a pure
# distribution outage, and at least one is the latter. One real failure anywhere, or one log
# that cannot be read, declines — `rerun --failed` would re-run that job too, and a real test
# failure must never be retried.
wrapper_outage_run() {
  local rows id name log n=0
  rows="$(jobs_query '.jobs[] | select(.conclusion == "failure") | "\(.id)\t\(.name)"')" || return 1
  [ -n "${rows}" ] || return 1
  while IFS=$'\t' read -r id name; do
    [ -n "${id}" ] || continue
    [ "${name}" = "all-green" ] && continue
    log="$(job_log "${id}")" || return 1
    wrapper_outage_log <<< "${log}" || return 1
    n=$(( n + 1 ))
  done <<< "${rows}"
  [ "${n}" -gt 0 ]
}

# ── GITHUB-SIDE TRANSIENTS: INSTALLATION QUOTA AND ARTIFACT-SERVICE OUTAGE ─────────────────────
# Measured 2026-11-10T15:00Z..11-12T15:30Z over 747 failed runs, the two largest causes nothing
# re-ran:
#   * the GITHUB_TOKEN installation quota — `##[error]API rate limit exceeded for installation`.
#     23 of 23 sampled CodeQL failures died at the SARIF upload after 20-40 min of analysis
#     (runs 43219415182, 43169471422); gate shards then report NOT EVALUATED and go red
#     (`pdp-flag-rollout-complete`, `ruleset-bypass-actors`, run 42995126340). Bursts recur
#     several times a day.
#   * the artifact service, 2026-11-11 ~20:14-22:16Z — `Failed to CreateArtifact ... Unexpected
#     token '<', "<!DOCTYPE "... is not valid JSON`, and downstream `Artifact not found for name`
#     (run 43090840079).
# Neither is a property of the code under test, and both heal on their own — the quota at its
# reset, the service when GitHub restores it. So a run whose failures are ONLY these is re-run
# once, and never before the cause can have cleared: re-running into an exhausted quota burns
# the one automatic attempt on a second identical failure.
#
# Like the distribution-outage recogniser above, this is EXHAUSTIVE per job, never a substring
# search: every `##[error]` annotation of a failed job must be one of
#   * `API rate limit exceeded for installation`                          -> quota
#   * `Failed to CreateArtifact: ...`                                     -> artifact (producer)
#   * `Unable to download artifact(s): Artifact not found for name: ...`  -> artifact (consumer)
#   * `One or more gate shards failed — see the 'gates (...)' jobs.`      -> aggregator
#   * `Process completed with exit code N.`, only in a step segment that also carries one of the
#     above;
# with no Gradle build/test failure line anywhere. A gate shard is judged instead by the roster
# `run-gates.py` prints last: every FAIL row must be `(not evaluated)` and the shard must carry
# its `GATES_RUN_VERDICT=NOT_EVALUATED_ONLY` line. That job's log cannot be judged by its error
# annotations, because the gate self-tests print deliberate `##[error]` fixtures by the hundred.
# Exit 0 + the class on stdout = transient only; 1 = anything else.
GATES_NE_ONLY_LINE="GATES_RUN_VERDICT=NOT_EVALUATED_ONLY"

github_transient_log() {
  awk -v nemark_text="${GATES_NE_ONLY_LINE}" '
    { sub(/\r$/, ""); line = $0; sub(/^[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9:.]+Z /, "", line) }
    line ~ /^--- verdicts \([0-9]+ gates\) ---$/ { roster = 1; rne = 0; rother = 0; nemark = 0; next }
    roster && line ~ /^  (FAIL|UNFALSIFIED) +[^ ]/ { if (line ~ /\(not evaluated\)$/) rne++; else rother++; next }
    roster && line == nemark_text { nemark = 1 }
    line ~ /^##\[group\]Run / { seg++ }
    line ~ /BUILD FAILED|FAILURE: Build failed|tests completed, [0-9]+ failed|> .* FAILED$/ { bad = 1 }
    index(line, "##[error]") != 1 { next }
    line ~ /^##\[error\].*API rate limit exceeded for installation/ { rl++; ok[seg] = 1; next }
    line ~ /^##\[error\]Failed to CreateArtifact: / { art++; ok[seg] = 1; next }
    line ~ /^##\[error\]Unable to download artifact\(s\): Artifact not found for name: / { cons++; ok[seg] = 1; next }
    line ~ /^##\[error\]One or more gate shards failed — see the .gates \(\.\.\.\). jobs\.$/ { agg++; ok[seg] = 1; next }
    line ~ /^##\[error\]Process completed with exit code [0-9]+\.$/ { exits[seg] = 1; next }
    { bad = 1 }
    END {
      if (roster && nemark && rne > 0 && rother == 0) { print "gates-not-evaluated"; exit 0 }
      if (bad) exit 1
      for (s in exits) if (!(s in ok)) exit 1
      if (rl) { print "rate-limit"; exit 0 }
      if (art) { print "artifact"; exit 0 }
      if (cons) { print "artifact-consumer"; exit 0 }
      if (agg) { print "aggregator"; exit 0 }
      exit 1
    }'
}

# 0 = every failed job of attempt 1 is a GitHub-side transient (all-green excluded, as above),
# with at least one PRIMARY cause (quota, gates NOT EVALUATED, or an artifact producer). So a
# consumer's "Artifact not found" counts only beside a primary cause in the same run: alone it
# can be a real naming bug, which a re-run would only repeat. Its logs come from job_log's cache
# (the recognisers above have usually read them already), so this adds one jobs-list call.
# stdout: "<needs>\t<latest epoch>"
# where <needs> is `quota`, `artifact` or `quota+artifact`.
github_transient_run() {
  local rows id name done_at log cls n=0 quota=0 art=0 latest=0 ts
  rows="$(jobs_query '.jobs[] | select(.conclusion == "failure") | "\(.id)\t\(.name)\t\(.completed_at // "")"')" || return 1
  [ -n "${rows}" ] || return 1
  while IFS=$'\t' read -r id name done_at; do
    [ -n "${id}" ] || continue
    [ "${name}" = "all-green" ] && continue
    n=$(( n + 1 ))
    log="$(job_log "${id}")" || return 1
    cls="$(github_transient_log <<< "${log}")" || return 1
    case "${cls}" in
      rate-limit|gates-not-evaluated) quota=1 ;;
      artifact) art=1 ;;
      artifact-consumer) ;;  # admissible only beside a primary cause, checked below
      aggregator) ;;
      *) return 1 ;;
    esac
    ts="$(jq -rn --arg t "${done_at}" '$t | fromdateiso8601? // 0')" || ts=0
    [ "${ts}" -gt "${latest}" ] && latest="${ts}"
  done <<< "${rows}"
  [ "${n}" -gt 0 ] && { [ "${quota}" -eq 1 ] || [ "${art}" -eq 1 ]; } || return 1
  local needs=""
  [ "${quota}" -eq 1 ] && needs="quota"
  [ "${art}" -eq 1 ] && needs="${needs:+${needs}+}artifact"
  printf '%s\t%s\n' "${needs}" "${latest}"
}

# The quota has RESET when more than 20% of it is left again — the same threshold the
# quota-meter steps warn at. The failing job saw 0. `GET /rate_limit` does not count against the
# limit, and it is this job's own GITHUB_TOKEN bucket, which is the one the failed run spent.
# Unreadable = not reset: deferring costs one tick, a premature re-run costs the only attempt.
quota_has_reset() {
  local q remaining limit
  q="$(gh_ api rate_limit --jq '.resources.core | "\(.remaining) \(.limit)"' 2>/dev/null)" || return 1
  read -r remaining limit <<< "${q}"
  [[ "${remaining}" =~ ^[0-9]+$ ]] && [[ "${limit}" =~ ^[1-9][0-9]*$ ]] || return 1
  [ $(( remaining * 5 )) -gt "${limit}" ]
}

# The artifact outage of 2026-11-11 lasted two hours, so a re-run issued minutes after the
# failure would most likely die the same way. Wait this long after the last failed job finished;
# the hourly deep scan re-reads completions for three hours, which covers the wait.
ARTIFACT_SETTLE_SECONDS="${ARTIFACT_SETTLE_SECONDS:-1800}"

main() {
  : "${GITHUB_REPOSITORY:?}" "${RUN_ID:?}" "${CONCLUSION:?}" "${RUN_EVENT:?}"
  LOG_CACHE_DIR="$(mktemp -d)"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    printf '%s\n' '| triggering conclusion | run | decision | detail |' '|---|---|---|---|' \
      >> "${GITHUB_STEP_SUMMARY}"
  fi

  local q count
  if [ "${CONCLUSION}" = "cancelled" ]; then
    # A reclaim kills a RUNNING job. So if not one cancelled job ever started a step, nothing
    # was running and a reclaim is impossible — this was a queue cancel, and re-running it puts
    # back exactly the load whoever cancelled was removing (#3208, #2039).
    #
    # ONE-DIRECTIONAL on purpose: this rules a reclaim OUT, it never claims one happened. See
    # the WHAT THIS CANNOT DO note in the header.
    # Dependency submission has a 30-minute job limit. Its cancelled resolver at
    # that limit is a timeout, not a spot reclaim; retrying it repeats the same
    # work. A shorter cancellation (or any other job) still takes the ordinary
    # reclaim path. Missing timestamps make this query fail closed instead of
    # authorizing an unclassified rerun.
    q='{running: ([.jobs[] | select(.conclusion == "cancelled")
                    | select((.steps | length) > 0)] | length),
        timed_out: ([.jobs[] | select(.conclusion == "cancelled")
                    | select(.name == "Submit fleet dependency graph")
                    | select((.steps | length) > 0)
                    | select((.completed_at | fromdateiso8601) -
                             (.started_at | fromdateiso8601) >= 1800)] | length)}'
  else
    # conclusion == failure: retry ONLY on the partial-spot-kill signature — a failed job with a
    # cancelled step and no failed step (issue #2841).
    q='[.jobs[]
        | select(.conclusion == "failure")
        | select(([.steps[]? | select(.conclusion == "cancelled")] | length) > 0)
        | select(([.steps[]? | select(.conclusion == "failure")]  | length) == 0)]
       | length'
  fi

  if ! count="$(with_retry "jobs API" jobs_query "${q}")" || [ -z "${count}" ]; then
    # The error text comes off the FILE, not the variable: `with_retry` ran inside the command
    # substitution above, i.e. in a subshell, so any variable it set is already gone. That is
    # why run 33738356244 printed `(last: unknown)` about a rate limit it had read in full.
    RETRY_LAST_ERROR="$(tr '\n' ' ' < "${RETRY_ERROR_FILE}" 2>/dev/null | cut -c1-300)"
    # See the header: for THIS control an unreadable corpus is not a pass. Exiting 1 is what
    # routes it to raise-issue, because a reclaimed run that nobody re-runs sits red forever.
    echo "::error title=spot-kill auto-retry::Could not read the jobs of ${RUN_URL:-${RUN_ID}} (last: ${RETRY_LAST_ERROR:-unknown}); NOT re-running. A genuine reclaim here needs a MANUAL re-run." >&2
    decide jobs-unreadable "the jobs API could not be read after ${ATTEMPTS} attempts — a genuine reclaim here needs a MANUAL re-run"
    return 1
  fi

  if [ "${CONCLUSION}" = "cancelled" ]; then
    local timed_out
    timed_out="$(jq -er '.timed_out | numbers' <<< "${count}")" || {
      echo "::error title=spot-kill auto-retry::Malformed cancelled-job classification; refusing to rerun."
      decide jobs-unreadable "the jobs API returned an invalid cancelled-job classification"
      return 1
    }
    count="$(jq -er '.running | numbers' <<< "${count}")" || {
      echo "::error title=spot-kill auto-retry::Malformed cancelled-job count; refusing to rerun."
      decide jobs-unreadable "the jobs API returned an invalid cancelled-job count"
      return 1
    }
    if [ "${timed_out}" -gt 0 ]; then
      echo "::notice title=spot-kill auto-retry::NOT re-running ${RUN_URL:-${RUN_ID}} — dependency graph resolver reached its 30-minute job limit; a full rerun would repeat the timeout."
      decide skipped-job-timeout "dependency-submission resolver reached its configured job limit"
      return 0
    fi
    if [ "${count}" -eq 0 ]; then
      echo "::notice title=spot-kill auto-retry::NOT re-running ${RUN_URL:-${RUN_ID}} — no cancelled job had started a step, so no runner was reclaimed. Treating it as a deliberate cancel (#3208)."
      decide skipped-queue-cancel "0 cancelled jobs had started a step — deliberate cancel, not a reclaim (#3208)"
      return 0
    fi
    echo "Checking cancelled run ${RUN_URL:-${RUN_ID}} for a safe rerun (${count} job(s) interrupted mid-flight; issue #2330)"
    # `--failed` is a no-op against `cancelled`, so this is always the full re-run.
    local rerun_rc=0
    guarded_rerun || rerun_rc=$?
    [ "${rerun_rc}" -eq 10 ] && return 0
    [ "${rerun_rc}" -eq 0 ] || return 1
    echo "::notice title=spot-kill auto-retry::Re-ran cancelled run ${RUN_URL:-${RUN_ID}} (attempt 2 of max 2; a second kill stays for a human)"
    decide rerun-cancelled "${count} job(s) interrupted mid-flight — full re-run issued (attempt 2 of max 2)"
    return 0
  fi

  if [ "${count}" -eq 0 ]; then
    # The structural rule declined. Before calling this a real failure, ask the logs (#9787) —
    # a step that took the SIGTERM itself leaves no cancelled step anywhere in the job data, so
    # the reclaim is visible ONLY as text, and the red check is otherwise indistinguishable from
    # a genuine test failure at every level a human or a gate looks at.
    local reclaimed_id=""
    reclaimed_id="$(reclaimed_job_from_logs || true)"
    if [ -n "${reclaimed_id}" ]; then
      echo "Re-running ${RUN_URL:-${RUN_ID}}: job ${reclaimed_id} has no cancelled step, but its log carries the reclaim signature and exit 143 (issue #9787)"
      local rerun_rc=0
      guarded_rerun --failed || rerun_rc=$?
      [ "${rerun_rc}" -eq 10 ] && return 0
      [ "${rerun_rc}" -eq 0 ] || return 1
      echo "::notice title=spot-kill auto-retry::Re-ran ${RUN_URL:-${RUN_ID}} — job ${reclaimed_id}'s own process took the SIGTERM and exited 143, so no step was marked cancelled (attempt 2 of max 2)."
      decide rerun-log-signature "job ${reclaimed_id} exited 143 after a runner shutdown signal — failed jobs re-run (attempt 2 of max 2)"
      return 0
    fi
    if wrapper_outage_run; then
      echo "Re-running ${RUN_URL:-${RUN_ID}}: every failed job died downloading the Gradle distribution before any build or test ran"
      local rerun_rc=0
      guarded_rerun --failed || rerun_rc=$?
      [ "${rerun_rc}" -eq 10 ] && return 0
      [ "${rerun_rc}" -eq 0 ] || return 1
      echo "::notice title=spot-kill auto-retry::Re-ran ${RUN_URL:-${RUN_ID}} — the Gradle distribution download failed (5xx/timeout) before anything was built (attempt 2 of max 2)."
      decide rerun-gradle-dist-outage "every failed job died on a Gradle distribution 5xx/timeout before any test — failed jobs re-run (attempt 2 of max 2)"
      return 0
    fi
    local transient="" needs latest
    if transient="$(github_transient_run)"; then
      IFS=$'\t' read -r needs latest <<< "${transient}"
      if [[ "${needs}" == *quota* ]] && ! quota_has_reset; then
        echo "::notice title=spot-kill auto-retry::NOT YET re-running ${RUN_URL:-${RUN_ID}} — its failures are an exhausted installation API quota, which has not reset (20% or less left). A later tick re-evaluates it."
        decide deferred-quota-not-reset "failures are installation-quota only; quota not yet reset — re-evaluated on a later tick"
        return 0
      fi
      if [[ "${needs}" == *artifact* ]] && [ $(( ${TRANSIENT_NOW_EPOCH:-$(date +%s)} - latest )) -lt "${ARTIFACT_SETTLE_SECONDS}" ]; then
        echo "::notice title=spot-kill auto-retry::NOT YET re-running ${RUN_URL:-${RUN_ID}} — its failures are a GitHub artifact-service outage less than ${ARTIFACT_SETTLE_SECONDS}s old. A later tick re-evaluates it."
        decide deferred-artifact-settle "failures are artifact-service only; waiting ${ARTIFACT_SETTLE_SECONDS}s for the service to recover"
        return 0
      fi
      echo "Re-running ${RUN_URL:-${RUN_ID}}: every failed job died on a GitHub-side transient (${needs})"
      local rerun_rc=0
      guarded_rerun --failed || rerun_rc=$?
      [ "${rerun_rc}" -eq 10 ] && return 0
      [ "${rerun_rc}" -eq 0 ] || return 1
      echo "::notice title=spot-kill auto-retry::Re-ran ${RUN_URL:-${RUN_ID}} — every failed job died on a GitHub-side transient (${needs}), not on the code (attempt 2 of max 2)."
      decide "rerun-github-transient-${needs/+/-}" "every failed job died on ${needs} (GitHub-side) — failed jobs re-run (attempt 2 of max 2)"
      return 0
    fi
    echo "::notice title=spot-kill auto-retry::${RUN_URL:-${RUN_ID}} failed with no spot-kill signature (no job has a cancelled step and no failed step, and no failed job's log carries a shutdown signal with exit 143) — treating it as a real failure and NOT re-running."
    decide no-spot-kill-signature "no failed job has the structural or the textual reclaim signature — a real build failure"
    return 0
  fi
  echo "Re-running ${RUN_URL:-${RUN_ID}}: ${count} job(s) killed mid-step by a runner reclaim (issue #2841)"
  local rerun_rc=0
  guarded_rerun --failed || rerun_rc=$?
  [ "${rerun_rc}" -eq 10 ] && return 0
  [ "${rerun_rc}" -eq 0 ] || return 1
  echo "::notice title=spot-kill auto-retry::Re-ran ${count} spot-killed job(s) in ${RUN_URL:-${RUN_ID}} (attempt 2 of max 2; a second kill stays for a human)"
  decide rerun-partial "${count} job(s) carry the spot-kill signature — failed jobs re-run (attempt 2 of max 2)"
}

# ── SELF-TEST ────────────────────────────────────────────────────────────────────────────────
# Drives the real `main` against a stub `gh` whose answers are scripted per call, and asserts
# BOTH the exit code and what was actually invoked. The call log is the load-bearing half: an
# assertion on the exit code alone cannot tell "declined to re-run" from "re-ran and the stub
# happened to succeed", which is the exact confusion the queue-cancel case exists to prevent.
self_test() {
  local tmp; tmp="$(mktemp -d)"
  cat > "${tmp}/gh" <<'STUB'
#!/usr/bin/env bash
# Scripted stub. STUB_SCRIPT holds one line per expected call: "<exit>|<output>".
# An output of `@<fixture>` means: run the REAL jq program this call was given against
# tests/<fixture>.json. That is not a nicety — a stub that returns a canned count exercises the
# branch logic and NOT the jq predicate, so deleting the `(.steps | length) > 0` clause (the
# entire queue-cancel guard, #3208) left the suite green. Measured: with canned counts the
# negative control passed; with the fixtures it reddens the queue-cancel case.
#
# The call is flattened to ONE line before logging: the jq programs are multi-line, and a
# newline inside a logged call would desynchronise the call counter from the script — the exact
# way this stub was wrong on its first run, which showed up as two cases failing.
echo "$*" | tr '\n' ' ' >> "${CALL_LOG}"; echo >> "${CALL_LOG}"
n=$(( $(cat "${CALL_LOG}.n" 2>/dev/null || echo 0) + 1 )); echo "${n}" > "${CALL_LOG}.n"
line="$(sed -n "${n}p" "${STUB_SCRIPT}")"
[ -n "${line}" ] || { echo "stub: no scripted answer for call ${n}: $*" >&2; exit 99; }
out="${line#*|}"
case "${out}" in
  @raw:*) cat "${FIXTURE_DIR}/${out#@raw:}.json"; exit "${line%%|*}" ;;
  @*) prog="${!#}"          # the jq program is the last argument of `api ... --jq <prog>`
      jq -r "${prog}" "${FIXTURE_DIR}/${out#@}.json" || exit 1
      exit "${line%%|*}" ;;
esac
printf '%s\n' "${out}"
exit "${line%%|*}"
STUB
  chmod +x "${tmp}/gh"

  # Fixtures — real jobs-API shapes, fed to the real jq programs by the stub above.
  export FIXTURE_DIR="${tmp}"
  # A spot reclaim: two cancelled jobs that were RUNNING (they carry recorded steps), alongside
  # queued siblings that never started. Modelled on run 32499371733, the run #6255 stranded.
  cat > "${tmp}/reclaim.json" <<'FIX'
{"jobs":[
 {"name":"build (balance)","conclusion":"cancelled","steps":[{"name":"Set up job","conclusion":"success"},{"name":"Gradle build","conclusion":"cancelled"}]},
 {"name":"build (billing)","conclusion":"cancelled","steps":[{"name":"Set up job","conclusion":"success"},{"name":"Gradle build","conclusion":"cancelled"}]},
 {"name":"build (campaign)","conclusion":"cancelled","steps":[]},
 {"name":"nightly fleet-health alert","conclusion":"cancelled","steps":[]}]}
FIX
  # A deliberate cancel of a QUEUE: every cancelled job was still queued, so nothing was running
  # and no runner can have been reclaimed. Modelled on run 30693441813 (an operator drain).
  cat > "${tmp}/queue-cancel.json" <<'FIX'
{"jobs":[
 {"name":"build (a)","conclusion":"cancelled","steps":[]},
 {"name":"build (b)","conclusion":"cancelled","steps":[]},
 {"name":"build (c)","conclusion":"cancelled","steps":[]}]}
FIX
  # A cancelled step after the configured job limit is not evidence of a spot
  # reclaim. A short cancellation still may be a reclaim.
  cat > "${tmp}/dependency-timeout.json" <<'FIX'
{"jobs":[{"name":"Submit fleet dependency graph","conclusion":"cancelled",
 "started_at":"2026-09-18T10:40:18Z","completed_at":"2026-09-18T11:10:35Z",
 "steps":[{"name":"Set up job","conclusion":"success"},
          {"name":"Resolve fleet dependency graph","conclusion":"cancelled"}]}]}
FIX
  cat > "${tmp}/dependency-reclaim.json" <<'FIX'
{"jobs":[{"name":"Submit fleet dependency graph","conclusion":"cancelled",
 "started_at":"2026-09-18T10:40:18Z","completed_at":"2026-09-18T10:48:35Z",
 "steps":[{"name":"Set up job","conclusion":"success"},
          {"name":"Resolve fleet dependency graph","conclusion":"cancelled"}]}]}
FIX
  # GitHub's run detail for an old PR run reports its original head_sha alongside the
  # currently associated PR's head.sha. This is the exact #9971 shape from 2026-09-18.
  cat > "${tmp}/stale-pr.json" <<'FIX'
{"event":"pull_request","head_sha":"88cbf0b58930f4bce63b813d6093c8d0aa4f2085","run_attempt":1,
 "pull_requests":[{"number":9971,"head":{"sha":"a01b680e0be85051435023b0adc2cce8830d23e8"}}]}
FIX
  cat > "${tmp}/current-pr.json" <<'FIX'
{"event":"pull_request","head_sha":"a01b680e0be85051435023b0adc2cce8830d23e8","run_attempt":1,
 "pull_requests":[{"number":9971,"head":{"sha":"a01b680e0be85051435023b0adc2cce8830d23e8"}}]}
FIX
  cat > "${tmp}/unknown-pr.json" <<'FIX'
{"event":"pull_request","head_sha":"a01b680e0be85051435023b0adc2cce8830d23e8","run_attempt":1,"pull_requests":[]}
FIX
  # #11870 was already merged when a cancelled Security scan was discovered. GitHub
  # removed the PR association from the old run, although its original head remains.
  cat > "${tmp}/merged-pr-unassociated.json" <<'FIX'
{"event":"pull_request","head_sha":"834a4374500868edc5388502d943365c52dfbd20","run_attempt":1,
 "head_branch":"refactor/sepa-payment-kernel-invalid-money-75463",
 "head_repository":{"owner":{"login":"owner"}},"pull_requests":[]}
FIX
  cat > "${tmp}/closed-branch-prs.json" <<'FIX'
[{"number":11870,"state":"closed","head":{"sha":"16d65d792afda6caad8e6315b8bef17ba7822e79"}}]
FIX
  cat > "${tmp}/open-branch-prs.json" <<'FIX'
[{"number":11870,"state":"open","head":{"sha":"16d65d792afda6caad8e6315b8bef17ba7822e79"}}]
FIX
  cat > "${tmp}/bound-unassociated.json" <<'FIX'
{"event":"pull_request","head_sha":"834a4374500868edc5388502d943365c52dfbd20","run_attempt":1,
 "head_branch":"feature","head_repository":{"full_name":"owner/repo","owner":{"login":"owner"}},"pull_requests":[]}
FIX
  cat > "${tmp}/bound-open-head.json" <<'FIX'
{"number":11870,"state":"open","base":{"repo":{"full_name":"owner/repo"}},
 "head":{"sha":"16d65d792afda6caad8e6315b8bef17ba7822e79","ref":"feature",
 "repo":{"full_name":"owner/repo","owner":{"login":"owner"}}}}
FIX
  jq -s '.' "${tmp}/bound-open-head.json" > "${tmp}/bound-open-branch.json"
  jq '.head.sha="834a4374500868edc5388502d943365c52dfbd20"' "${tmp}/bound-open-head.json" > "${tmp}/bound-equal-head.json"
  jq -s '.' "${tmp}/bound-equal-head.json" > "${tmp}/bound-equal-branch.json"
  jq -s '.[0] as $pr | [$pr,$pr]' "${tmp}/bound-open-head.json" > "${tmp}/bound-duplicate-branch.json"
  jq '.head.ref="other"' "${tmp}/bound-open-head.json" > "${tmp}/bound-wrong-ref.json"
  jq -s '.' "${tmp}/bound-wrong-ref.json" > "${tmp}/bound-wrong-branch.json"
  jq '.base.repo.full_name="owner/other"' "${tmp}/bound-open-head.json" > "${tmp}/bound-wrong-repo.json"
  jq -s '.' "${tmp}/bound-wrong-repo.json" > "${tmp}/bound-wrong-repo-branch.json"
  jq '.head.sha="invalid"' "${tmp}/bound-open-head.json" > "${tmp}/bound-invalid-sha.json"
  jq -s '.' "${tmp}/bound-invalid-sha.json" > "${tmp}/bound-invalid-sha-branch.json"
  cat > "${tmp}/stale-pr-head.json" <<'FIX'
{"state":"open","head":{"sha":"a01b680e0be85051435023b0adc2cce8830d23e8"}}
FIX
  cat > "${tmp}/closed-pr-head.json" <<'FIX'
{"state":"closed","head":{"sha":"a01b680e0be85051435023b0adc2cce8830d23e8"}}
FIX
  # A PARTIAL reclaim: the run is `failure` because one job died mid-step while a sibling passed.
  cat > "${tmp}/partial-kill.json" <<'FIX'
{"jobs":[
 {"name":"CodeQL java-kotlin","conclusion":"failure","steps":[{"name":"Init","conclusion":"success"},{"name":"Analyze","conclusion":"cancelled"}]},
 {"name":"Trivy","conclusion":"success","steps":[{"name":"Scan","conclusion":"success"}]}]}
FIX
  # An ordinary red build: the failed job has a FAILED step, so it is not a reclaim.
  cat > "${tmp}/real-failure.json" <<'FIX'
{"jobs":[
 {"name":"build (ledger)","conclusion":"failure","steps":[{"name":"Set up job","conclusion":"success"},{"name":"Gradle build","conclusion":"failure"}]},
 {"name":"build (party)","conclusion":"success","steps":[{"name":"Gradle build","conclusion":"success"}]}]}
FIX

  # The #9787 shape: a failed job with NO cancelled step — the reclaim is only in the log text.
  # Ids are present because this is the one case that goes on to fetch a job log by id.
  cat > "${tmp}/sigterm-kill.json" <<'FIX'
{"jobs":[
 {"id":103517002677,"name":"Admin UI build","conclusion":"failure","steps":[{"name":"Install","conclusion":"success"},{"name":"E2E tests (Playwright)","conclusion":"failure"},{"name":"Publish evidence","conclusion":"skipped"}]}]}
FIX

  # Assign the VARIABLE, not just the env var: the cap is read from the environment at script
  # startup, which is before this function runs, so exporting it here changes nothing. Measured
  # on the pre-library version: the suite took 196 s of pure `sleep` at the default backoff and
  # every case still passed — a self-test can be right about behaviour and wrong about what it
  # is exercising, and the only tell was 0% CPU.
  #
  # 0 is also what keeps the suite HERMETIC: at a zero cap `gh_retry` skips its rate-limit reset
  # probe entirely, so no real `gh api rate_limit` call is issued and the stub's call counter
  # stays in step with its script.
  GH_RETRY_MAX_WAIT_SECONDS=0
  export GH_RETRY_MAX_WAIT_SECONDS
  export GH_BIN="${tmp}/gh"
  GH_RETRY_GH_BIN="${tmp}/gh"; export GH_RETRY_GH_BIN
  RETRY_ERROR_FILE="${tmp}/last-error"; GH_RETRY_LAST_ERROR_FILE="${RETRY_ERROR_FILE}"
  export GH_RETRY_LAST_ERROR_FILE
  export GITHUB_REPOSITORY="owner/repo" RUN_ID=1 RUN_URL="http://x/1" RUN_EVENT=push
  unset GITHUB_STEP_SUMMARY || true

  # Gradle-distribution outage fixtures. The first two are verbatim excerpts (error lines, step
  # headers and stack frames; env dumps dropped) of jobs 128680610511 (run 42461736478) and
  # 129009228675 (run 42570587622). The rest are the same shape with ONE thing changed.
  cat > "${tmp}/dist-outage.json" <<'FIX'
{"jobs":[
 {"id":128680610511,"name":"build (openbank-domestic-payment) / openbank-domestic-payment (contract)","conclusion":"failure","steps":[{"name":"Warm Gradle dependency cache (self-heal corrupted entries)","conclusion":"success"},{"name":"Discover provider Pact task","conclusion":"failure"}]},
 {"id":129009228675,"name":"build (openbank-engagement-service) / openbank-engagement-service (contract)","conclusion":"failure","steps":[{"name":"Discover provider Pact task","conclusion":"failure"}]},
 {"id":129012558683,"name":"all-green","conclusion":"failure","steps":[{"name":"Require every selected service build to pass","conclusion":"failure"}]},
 {"id":129012558684,"name":"build (openbank-ledger-service) / openbank-ledger-service (contract)","conclusion":"success","steps":[]}]}
FIX
  cat > "${tmp}/log-dist-1108.json" <<'FIX'
2026-11-08T08:48:51.9184809Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/warm-gradle-cache.sh"
2026-11-08T08:49:02.5913614Z Downloading https://services.gradle.org/distributions/gradle-9.8.2-bin.zip
2026-11-08T08:49:02.5915419Z Attempt 1/1 failed. Reason: Downloading from https://services.gradle.org/distributions/gradle-9.8.2-bin.zip failed: timeout (10000ms)
2026-11-08T08:49:02.5940480Z ##[error]Exception in thread "main" java.io.IOException: Downloading from https://services.gradle.org/distributions/gradle-9.8.2-bin.zip failed: timeout (10000ms)
2026-11-08T08:49:02.5942906Z 	at org.gradle.wrapper.Download.download(SourceFile:1)
2026-11-08T08:49:04.9081740Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/discover-provider-task.sh"
2026-11-08T08:49:04.9119470Z ##[endgroup]
2026-11-08T08:49:05.5421166Z ##[error]Exception in thread "main" java.io.IOException: Server returned HTTP response code: 504 for URL: https://services.gradle.org/distributions/gradle-9.8.2-bin.zip
2026-11-08T08:49:05.5423733Z 	at org.gradle.wrapper.Download.download(SourceFile:1)
2026-11-08T08:49:05.5428427Z 	at org.gradle.wrapper.GradleWrapperMain.main(SourceFile:2)
2026-11-08T08:49:05.5505106Z ##[error]Process completed with exit code 1.
FIX
  cat > "${tmp}/log-dist-1109.json" <<'FIX'
2026-11-09T06:11:54.1905418Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/warm-gradle-cache.sh"
2026-11-09T06:11:54.9558201Z Downloading https://services.gradle.org/distributions/gradle-9.8.2-bin.zip
2026-11-09T06:11:54.9560220Z Attempt 1/1 failed. Reason: Server returned HTTP response code: 504 for URL: https://services.gradle.org/distributions/gradle-9.8.2-bin.zip
2026-11-09T06:11:54.9583322Z ##[error]Exception in thread "main" java.io.IOException: Server returned HTTP response code: 504 for URL: https://services.gradle.org/distributions/gradle-9.8.2-bin.zip
2026-11-09T06:12:03.6501010Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/discover-provider-task.sh"
2026-11-09T06:12:03.6537102Z ##[endgroup]
2026-11-09T06:12:04.2780533Z ##[error]Exception in thread "main" java.io.IOException: Server returned HTTP response code: 504 for URL: https://services.gradle.org/distributions/gradle-9.8.2-bin.zip
2026-11-09T06:12:04.2787895Z 	at org.gradle.wrapper.GradleWrapperMain.main(SourceFile:2)
2026-11-09T06:12:04.2859194Z ##[error]Process completed with exit code 1.
FIX
  sed 's/$/\r/' "${tmp}/log-dist-1109.json" > "${tmp}/log-dist-crlf.json"
  cat > "${tmp}/log-dist-ensure.json" <<'FIX'
2026-11-10T06:00:00.0000000Z ##[group]Run python3 -I "$SERVICE_CI_HELPERS/ensure-gradle-distribution.py" --lock "$SERVICE_CI_HELPERS/artifacts.lock" --fetcher "$SERVICE_CI_HELPERS/fetch-artifact.py"
2026-11-10T06:01:10.0000000Z UNVERIFIED (artifact unavailable): gradle-distribution
2026-11-10T06:01:10.0000001Z ##[error]ensure-gradle-distribution: could not obtain https://services.gradle.org/distributions/gradle-9.8.2-bin.zip after 3 attempts (infrastructure, not a build failure)
2026-11-10T06:01:10.0100000Z ##[error]Process completed with exit code 75.
FIX
  sed 's/response code: 504 for URL/response code: 404 for URL/' "${tmp}/log-dist-1109.json" > "${tmp}/log-dist-404.json"
  cat > "${tmp}/log-maven-504.json" <<'FIX'
2026-11-09T06:12:03.6501010Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/build-test.sh"
2026-11-09T06:12:40.0000000Z > Could not GET 'https://repo.maven.apache.org/maven2/io/quarkus/quarkus-bom/3.0/quarkus-bom-3.0.pom'. Received status code 504 from server: Gateway Timeout
2026-11-09T06:12:41.0000000Z ##[error]Process completed with exit code 1.
FIX
  { cat "${tmp}/log-dist-1108.json"
    printf '%s\n' '2026-11-08T08:52:00.0000000Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/provider-pact-test.sh"' \
      '2026-11-08T08:55:00.0000000Z provider verification: 1 interaction did not match' \
      '2026-11-08T08:55:01.0000000Z ##[error]Process completed with exit code 1.'; } > "${tmp}/log-dist-then-test.json"
  cat > "${tmp}/log-dist-integrity.json" <<'FIX'
2026-11-10T06:00:00.0000000Z ##[group]Run python3 -I "$SERVICE_CI_HELPERS/ensure-gradle-distribution.py"
2026-11-10T06:00:05.0000000Z ##[error]ensure-gradle-distribution: INTEGRITY FAILURE for gradle-distribution; not retrying
2026-11-10T06:00:05.0100000Z ##[error]Process completed with exit code 65.
FIX
  printf '%s\n' '2026-11-09T06:12:03.0Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/build-test.sh"' \
    '2026-11-09T06:20:00.0Z 1 test failed' '2026-11-09T06:20:01.0Z ##[error]Process completed with exit code 1.' \
    > "${tmp}/log-plain-failure.json"
  # The outage text quoted mid-line (a script echo / grep in a step header) carries no
  # `##[error]` annotation of its own at the anchored position, so the exit stays unexplained.
  printf '%s\n' '2026-11-09T06:12:03.0Z ##[group]Run echo "Exception in thread \"main\" java.io.IOException: Server returned HTTP response code: 504 for URL: https://services.gradle.org/distributions/gradle-9.8.2-bin.zip"' \
    '2026-11-09T06:12:04.0Z ##[error]Process completed with exit code 1.' > "${tmp}/log-dist-echoed.json"
  printf '%s\n' '2026-11-09T06:30:00.0Z ##[group]Run python3 .github/scripts/all-green.py' \
    '2026-11-09T06:30:01.0Z ##[error]Process completed with exit code 1.' > "${tmp}/log-allgreen.json"
  # An action step (`uses:`) fails with an annotation and no "Process completed" line.
  { cat "${tmp}/log-dist-1109.json"
    printf '%s\n' '2026-11-09T06:12:05.0Z ##[group]Run actions/download-artifact@v8' \
      '2026-11-09T06:12:06.0Z ##[error]Unable to download artifact(s): Artifact not found'; } > "${tmp}/log-dist-plus-other.json"
  printf '%s\n' '2026-11-09T06:12:03.0Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/build-test.sh"' \
    '2026-11-09T06:12:04.0Z step ended without an annotation' > "${tmp}/log-no-error.json"
  # One step whose first ./gradlew died on the download and whose second ran tests and failed.
  printf '%s\n' '2026-11-09T06:12:03.0Z ##[group]Run bash -e "$SERVICE_CI_HELPERS/build-test.sh"' \
    '2026-11-09T06:12:04.0Z ##[error]Exception in thread "main" java.io.IOException: Server returned HTTP response code: 504 for URL: https://services.gradle.org/distributions/gradle-9.8.2-bin.zip' \
    '2026-11-09T06:20:00.0Z 143 tests completed, 1 failed' '2026-11-09T06:20:00.1Z FAILURE: Build failed with an exception.' \
    '2026-11-09T06:20:01.0Z ##[error]Process completed with exit code 1.' > "${tmp}/log-dist-same-step-red.json"

  local pass=0 fail=0 subjects=0
  # Job-log fixtures for the #9787 recogniser. Written as one line each: the stub returns its
  # scripted output verbatim, and only the presence of the two phrases is being tested.
  local RECLAIM_LOG="[WebServer] error: aborted ##[error]The runner has received a shutdown signal. This can happen when the runner service is stopped. ##[error]Process completed with exit code 143."
  local EXIT143_ONLY_LOG="Killed ##[error]Process completed with exit code 143."
  local SHUTDOWN_ONLY_LOG="##[error]The runner has received a shutdown signal. ##[error]Process completed with exit code 1."
  local PLAIN_FAILURE_LOG="1 failed - should render the audit trail ##[error]Process completed with exit code 1."
  local RL="gh: API rate limit exceeded for installation ... (HTTP 403)"
  local PERM="gh: Resource not accessible by integration (HTTP 403)"

  case_() { # case_ <name> <conclusion> <expect_exit> <expect_rerun_calls> <scripted lines...>
    local name="$1" concl="$2" want_exit="$3" want_reruns="$4"; shift 4
    subjects=$(( subjects + 1 ))
    export CALL_LOG="${tmp}/calls" STUB_SCRIPT="${tmp}/script"
    : > "${CALL_LOG}"; : > "${CALL_LOG}.n"; printf '%s\n' "$@" > "${STUB_SCRIPT}"
    local rc reruns
    # Redirect and read $? — a pipeline would report the LAST command's status, not main's.
    set +e
    CONCLUSION="${concl}" main > "${tmp}/out" 2>&1
    rc=$?
    set -e
    reruns=$(grep -c '^run rerun' "${CALL_LOG}" || true)
    # Optional: the decision line too. Exit code and re-run count alone cannot tell "declined
    # as a real failure" from "declined because the stub ran out of answers".
    local got_decision=ok
    if [ -n "${WANT_DECISION:-}" ] && ! grep -q "decision=${WANT_DECISION}\$" "${tmp}/out"; then got_decision=missing; fi
    # Optional: the exact number of `gh` calls — the only way to assert a call was NOT made.
    if [ -n "${WANT_CALLS:-}" ] && [ "$(grep -c . "${CALL_LOG}")" -ne "${WANT_CALLS}" ]; then got_decision="calls=$(grep -c . "${CALL_LOG}") want ${WANT_CALLS}"; fi
    if [ "${rc}" -eq "${want_exit}" ] && [ "${reruns}" -eq "${want_reruns}" ] && [ "${got_decision}" = ok ]; then
      echo "PASS  ${name} (exit=${rc}, rerun calls=${reruns})"; pass=$(( pass + 1 ))
    else
      echo "FAIL  ${name}: exit=${rc} (want ${want_exit}), rerun calls=${reruns} (want ${want_reruns}), decision ${WANT_DECISION:-any}: ${got_decision}"
      sed 's/^/        /' "${tmp}/out"
      fail=$(( fail + 1 ))
    fi
  }

  # ── the two prevent-proofs the control exists for ──────────────────────────────────────────
  # PROVE-RETRY: a reclaim (jobs were running when cancelled) IS re-run.
  case_ "spot-kill (cancelled, 2 jobs mid-flight) is re-run" cancelled 0 1 "0|@reclaim" "0|ok"
  # PROVE-NO-RETRY: a deliberate cancel of a QUEUE (no cancelled job ever started a step) is NOT
  # re-run. This is the only human cancel GitHub's data can distinguish; see the header.
  case_ "deliberate queue cancel is NOT re-run" cancelled 0 0 "0|@queue-cancel"
  case_ "dependency-submission job timeout is NOT a spot reclaim" cancelled 0 0 "0|@dependency-timeout"
  case_ "short dependency-submission reclaim remains retryable" cancelled 0 1 "0|@dependency-reclaim" "0|ok"

  # An old PR run with a real cancelled step is NOT a spot retry candidate once its branch
  # advances: the full rerun would cancel the newer run through the same concurrency group.
  RUN_EVENT=pull_request
  case_ "stale PR head cannot cancel current CI" cancelled 0 0 "0|@reclaim" "0|@raw:stale-pr" "0|@raw:stale-pr-head"
  case_ "current PR head may retry a genuine reclaim" cancelled 0 1 "0|@reclaim" "0|@raw:current-pr" "0|@raw:stale-pr-head" "0|ok"
  case_ "missing PR association never authorizes a rerun" cancelled 1 0 "0|@reclaim" "0|@raw:unknown-pr"
  case_ "merged PR with omitted run association is skipped" cancelled 0 0 "0|@reclaim" "0|@raw:merged-pr-unassociated" "0|@raw:closed-branch-prs"
  case_ "open PR with omitted run association remains red" cancelled 1 0 "0|@reclaim" "0|@raw:merged-pr-unassociated" "0|@raw:open-branch-prs"
  case_ "bound stale unassociated PR is skipped" cancelled 0 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-open-branch" "0|@raw:bound-open-head"
  case_ "equal unassociated PR never authorizes rerun" cancelled 1 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-equal-branch"
  case_ "duplicate branch matches remain red" cancelled 1 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-duplicate-branch"
  case_ "wrong branch identity remains red" cancelled 1 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-wrong-branch"
  case_ "live head race remains red" cancelled 1 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-open-branch" "0|@raw:bound-equal-head"
  case_ "live PR identity mismatch remains red" cancelled 1 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-open-branch" "0|@raw:bound-wrong-ref"
  case_ "wrong base repository remains red" cancelled 1 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-wrong-repo-branch"
  case_ "malformed branch head remains red" cancelled 1 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-invalid-sha-branch"
  case_ "live head API failure remains red" cancelled 1 0 "0|@reclaim" "0|@raw:bound-unassociated" "0|@raw:bound-open-branch" "1|${PERM}"
  case_ "closed PR cannot be reanimated by a spot retry" cancelled 0 0 "0|@reclaim" "0|@raw:current-pr" "0|@raw:closed-pr-head"
  RUN_EVENT=push

  # ── the #6255 regression, in both directions ───────────────────────────────────────────────
  case_ "rate-limited jobs query recovers and still re-runs" cancelled 0 1 "1|${RL}" "1|${RL}" "0|@reclaim" "0|ok"
  case_ "jobs query rate-limited 5/5 escalates (exit 1)"     cancelled 1 0 "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}"
  case_ "jobs query permission-denied escalates immediately" cancelled 1 0 "1|${PERM}"
  # The `failure` branch had the same hole and used to answer exit 0 (green, silent).
  case_ "rate-limited jobs query on the failure branch escalates" failure 1 0 "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}"
  # THE #6853/#7976 CASE: an installation rate limit whose window is MINUTES. The limit is
  # still in force for the first four attempts and has reset by the fifth.
  case_ "a rate limit outlasting the tight window still re-runs (#6853, #7976)" cancelled 0 1 \
    "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}" "0|@reclaim" "0|ok"

  # ── the failure branch's signature ─────────────────────────────────────────────────────────
  case_ "partial spot-kill (failure) re-runs the failed jobs" failure 0 1 "0|@partial-kill" "0|ok"
  case_ "a real build failure is NOT re-run"                  failure 0 0 "0|@real-failure"

  # ── the TEXTUAL signature (#9787), in both directions ──────────────────────────────────────
  # The scripted calls are: structural jq (0 hits) -> failed-job-id jq -> that job's log -> rerun.
  # PROVE-RETRY: no cancelled step anywhere, but the log carries the shutdown line AND exit 143.
  case_ "a step that exited 143 on a shutdown signal IS re-run (#9787)" failure 0 1 \
    "0|@sigterm-kill" "0|@sigterm-kill" "0|${RECLAIM_LOG}" "0|ok"
  # PROVE-NO-RETRY, half the pair: 143 with no shutdown line is an ordinary SIGTERM — an OOM
  # kill, a `timeout(1)`, a test harness killing its own child. Not a reclaim.
  case_ "exit 143 WITHOUT a shutdown signal is NOT re-run" failure 0 0 \
    "0|@sigterm-kill" "0|@sigterm-kill" "0|${EXIT143_ONLY_LOG}"
  # PROVE-NO-RETRY, the other half: the shutdown line can appear in a job that was cancelled
  # cleanly, which the STRUCTURAL rule already covers. Without a 143 this is not our case.
  case_ "a shutdown signal WITHOUT exit 143 is NOT re-run" failure 0 0 \
    "0|@sigterm-kill" "0|@sigterm-kill" "0|${SHUTDOWN_ONLY_LOG}"
  # PROVE-NO-RETRY: an ordinary red suite. This is the case the widening must not swallow.
  case_ "an ordinary failing test log is NOT re-run" failure 0 0 \
    "0|@sigterm-kill" "0|@sigterm-kill" "0|${PLAIN_FAILURE_LOG}"
  # An unreadable log leaves the pre-#9787 behaviour: declined, exit 0, nothing escalated.
  case_ "an unreadable job log declines rather than escalating" failure 0 0 \
    "0|@sigterm-kill" "0|@sigterm-kill" "1|HTTP 404: Not Found"

  # ── the GRADLE-DISTRIBUTION OUTAGE signature, in both directions ───────────────────────────
  # Classifier alone, on REAL log excerpts (job logs of runs 42461736478 and 42570587622).
  log_case() { # log_case <name> <want 0|1> <fixture>
    subjects=$(( subjects + 1 ))
    local rc=0
    wrapper_outage_log < "${tmp}/$3.json" || rc=$?
    if [ "${rc}" -eq "$2" ]; then echo "PASS  $1 (classifier=${rc})"; pass=$(( pass + 1 ))
    else echo "FAIL  $1: classifier=${rc} (want $2)"; fail=$(( fail + 1 )); fi
  }
  log_case "real 11-08 log: warm-up timeout + discover 504 IS an outage"        0 log-dist-1108
  log_case "real 11-09 log: warm-up 504 + discover 504 IS an outage"            0 log-dist-1109
  log_case "ensure-gradle-distribution giving up after its retries IS an outage" 0 log-dist-ensure
  log_case "CRLF line endings do not defeat the classifier"                     0 log-dist-crlf
  log_case "a 404 (wrong version, will not heal) is NOT an outage"              1 log-dist-404
  log_case "a 504 from a Maven repository is NOT a wrapper outage"              1 log-maven-504
  log_case "outage in warm-up but a red TEST step is NOT an outage"             1 log-dist-then-test
  log_case "an integrity failure is NOT an outage"                              1 log-dist-integrity
  log_case "an ordinary failing build log is NOT an outage"                     1 log-plain-failure
  log_case "the outage text inside a quoted script line is NOT an outage"       1 log-dist-echoed
  log_case "outage plus any OTHER error annotation is NOT an outage"            1 log-dist-plus-other
  log_case "a failed job whose log carries no error at all is NOT an outage"    1 log-no-error
  log_case "outage then a red Gradle build in the SAME step is NOT an outage"   1 log-dist-same-step-red
  # Whole decision. Calls: structural jq -> (#9787) failed ids + one log each -> failed id/name
  # -> one log per non-aggregator job -> rerun.
  # Logs are fetched ONCE per decision (job_log's cache), so the later recognisers re-read the
  # job list but not the logs it already holds.
  WANT_DECISION=rerun-gradle-dist-outage \
  case_ "a run whose failed jobs all died on the distribution download IS re-run" failure 0 1 \
    "0|@dist-outage" "0|@dist-outage" "0|@raw:log-dist-1108" "0|@raw:log-dist-1109" "0|@raw:log-allgreen" \
    "0|@dist-outage" "0|ok"
  WANT_DECISION=no-spot-kill-signature \
  case_ "one real failure among outage jobs is NOT re-run" failure 0 0 \
    "0|@dist-outage" "0|@dist-outage" "0|@raw:log-dist-1108" "0|@raw:log-dist-then-test" "0|@raw:log-allgreen" \
    "0|@dist-outage" "0|@dist-outage"
  # A FAILED read is not cached, so the outage recogniser asks again and is refused again.
  WANT_DECISION=no-spot-kill-signature \
  case_ "an unreadable log among outage jobs is NOT re-run" failure 0 0 \
    "0|@dist-outage" "0|@dist-outage" "0|@raw:log-dist-1108" "1|HTTP 404: Not Found" "0|@raw:log-allgreen" \
    "0|@dist-outage" "1|HTTP 404: Not Found" "0|@dist-outage"

  # ── GITHUB-SIDE TRANSIENTS (quota, artifact service), in both directions ────────────────────
  # Classifier alone. The first three are verbatim excerpts of real failed jobs: 131186969462
  # (CodeQL, run 43219415182), 130440797437 (gate shard, run 42995126340) and 130780720741 /
  # a publish-evidence job of run 43090840079 (artifact outage). Request ids dropped.
  transient_case() { # transient_case <name> <want class, or "-" for not transient> <fixture>
    subjects=$(( subjects + 1 ))
    local got rc=0
    got="$(github_transient_log < "${tmp}/$3.json")" || rc=$?
    [ "${rc}" -eq 0 ] || got="-"
    if [ "${got}" = "$2" ]; then echo "PASS  $1 (class=${got})"; pass=$(( pass + 1 ))
    else echo "FAIL  $1: class=${got} (want $2)"; fail=$(( fail + 1 )); fi
  }
  cat > "${tmp}/log-codeql-quota.json" <<'FIX'
2026-11-12T12:35:10.0000000Z ##[group]Run github/codeql-action/analyze@v4
2026-11-12T12:37:03.6407694Z ##[warning]Failed to gather information for telemetry: API rate limit exceeded for installation. If you reach out to GitHub Support for help, please include the request ID and timestamp 2026-11-12 12:37:03 UTC.
2026-11-12T12:38:45.0267970Z ##[error]API rate limit exceeded for installation. If you reach out to GitHub Support for help, please include the request ID and timestamp 2026-11-12 12:38:44 UTC. For more on scraping GitHub and how it may affect your rights, please review our Terms of Service.
2026-11-12T12:39:29.7548253Z ##[warning]Failed to gather information for telemetry: API rate limit exceeded for installation.
2026-11-12T12:39:31.5549000Z Cleaning up orphan processes
FIX
  cat > "${tmp}/log-gates-ne.json" <<'FIX'
2026-11-11T09:40:00.0000000Z ##[group]Run python3 .github/scripts/run-gates.py $FLAGS
2026-11-11T09:43:56.4871031Z ##[error]checksum changed for an already pinned artifact: g:a:1:a-1.jar
2026-11-11T09:43:56.5664883Z ##[error]ensure-gradle-distribution: INTEGRITY FAILURE for gradle-distribution; not retrying
2026-11-11T09:43:56.5986042Z case [undeclared-actor] rc=1 output: ::error::ruleset-bypass-actors: Team#999 can bypass main-protection
2026-11-11T09:43:56.6056546Z gh: API rate limit exceeded for installation
2026-11-11T09:43:56.6059070Z GATE_VERDICT=NOT_EVALUATED
2026-11-11T09:43:56.6060747Z ##[error]could not read OpaSidecarPolicyDecisionPoint.kt history from the GitHub API (rate limit or transport) — rollout not evaluated
2026-11-11T09:43:56.6070656Z 121 gates  PASS=111  WARN=1  FAIL=1  SKIP=8   cpu=708.2s  slowest=190.0s  jobs=4
2026-11-11T09:43:56.6071000Z --- verdicts (3 gates) ---
2026-11-11T09:43:56.6072000Z   PASS         gate-runner-self-test          2.0s  [lint]
2026-11-11T09:43:56.6087230Z   FAIL         pdp-flag-rollout-complete      1.1s  [security]  (not evaluated)
2026-11-11T09:43:56.6088000Z   SKIP         something-else                 0.0s  [lint]
2026-11-11T09:43:56.6089000Z ##[error]gate failed: every libs-runtime consumer is pinned
2026-11-11T09:43:56.6089500Z GATES_RUN_VERDICT=NOT_EVALUATED_ONLY
2026-11-11T09:43:56.6091778Z ##[error]Process completed with exit code 1.
FIX
  # Same shard, but a SECOND gate failed with a finding: NOT transient.
  sed 's/^\(.*\)  SKIP         something-else                 0.0s  \[lint\]$/\1  FAIL         ruleset-bypass-actors          0.4s  [security]/' \
    "${tmp}/log-gates-ne.json" > "${tmp}/log-gates-finding.json"
  # Same shard, roster says not-evaluated but the run-level marker is missing (an older
  # run-gates, or a truncated log): NOT transient — the marker is what the roster is checked by.
  grep -v 'GATES_RUN_VERDICT=NOT_EVALUATED_ONLY' "${tmp}/log-gates-ne.json" > "${tmp}/log-gates-nomark.json"
  # A self-test FIXTURE roster earlier in the log must not stand in for the real one: here the
  # real (last) roster carries a finding.
  { printf '%s\n' '2026-11-11T09:41:00.0Z --- verdicts (1 gates) ---' \
      '2026-11-11T09:41:00.1Z   FAIL         not-evaluated   0.5s  [t]  (not evaluated)' \
      '2026-11-11T09:41:00.2Z GATES_RUN_VERDICT=NOT_EVALUATED_ONLY'
    grep -v 'GATES_RUN_VERDICT=NOT_EVALUATED_ONLY' "${tmp}/log-gates-finding.json"; } > "${tmp}/log-gates-fixture-first.json"
  cat > "${tmp}/log-artifact-create.json" <<'FIX'
2026-11-11T20:19:15.0000000Z ##[group]Run actions/upload-artifact@v7
2026-11-11T20:21:18.1077126Z Attempt 1 of 5 failed with error: Unexpected token '<', "<!DOCTYPE "... is not valid JSON. Retrying request in 8000 ms...
2026-11-11T20:30:27.2461560Z ##[error]Failed to CreateArtifact: Failed to make request after 5 attempts: Unexpected token '<', "<!DOCTYPE "... is not valid JSON
2026-11-11T20:30:30.0000000Z ##[group]Run actions/upload-artifact@v7
2026-11-11T20:41:18.2527574Z ##[error]Failed to CreateArtifact: Failed to make request after 5 attempts: Unexpected token '<', "<!DOCTYPE "... is not valid JSON
FIX
  cat > "${tmp}/log-artifact-consumer.json" <<'FIX'
2026-11-11T20:44:20.0000000Z ##[group]Run actions/download-artifact@v8
2026-11-11T20:44:27.3008739Z ##[error]Unable to download artifact(s): Artifact not found for name: test-intelligence-run-openbank-pension-service-43090840079-a1
FIX
  printf '%s\n' '2026-11-11T09:51:30.0Z ##[group]Run python3 .github/scripts/validate-gate-shards.py' \
    "2026-11-11T09:51:37.4766921Z ##[error]One or more gate shards failed — see the 'gates (...)' jobs." \
    '2026-11-11T09:51:37.4780467Z ##[error]Process completed with exit code 1.' > "${tmp}/log-shards-aggregator.json"
  # The artifact outage hit a job whose BUILD had also gone red: a real failure, not transient.
  { cat "${tmp}/log-artifact-create.json"
    printf '%s\n' '2026-11-11T20:42:00.0Z ##[group]Run bash -e build-test.sh' \
      '2026-11-11T20:45:00.0Z 143 tests completed, 1 failed' '2026-11-11T20:45:01.0Z ##[error]Process completed with exit code 1.'; } > "${tmp}/log-artifact-plus-test.json"
  # Quota text in a script step, then the step's own exit: the exit is not explained by an
  # annotation, so the failure may be anything — NOT transient.
  printf '%s\n' '2026-11-12T12:00:00.0Z ##[group]Run gh api repos/x/y' 'gh: API rate limit exceeded for installation' \
    '2026-11-12T12:00:01.0Z ##[error]Process completed with exit code 1.' > "${tmp}/log-quota-unannotated.json"
  # The outage and a red Gradle build in the SAME step segment (no separate exit line to give
  # it away): the red build line alone must decline.
  printf '%s\n' '2026-11-11T20:19:15.0Z ##[group]Run bash -e build-and-upload.sh' '2026-11-11T20:20:00.0Z BUILD FAILED in 2m' \
    "2026-11-11T20:30:27.0Z ##[error]Failed to CreateArtifact: Failed to make request after 5 attempts: Unexpected token '<', \"<!DOCTYPE \"... is not valid JSON" \
    > "${tmp}/log-artifact-same-step-red.json"
  # The outage beside an UNRELATED annotation in an action step (no exit line): decline.
  { cat "${tmp}/log-artifact-create.json"
    printf '%s\n' '2026-11-11T20:42:00.0Z ##[group]Run some/action@v1' '2026-11-11T20:42:01.0Z ##[error]Input required and not supplied: token'; } \
    > "${tmp}/log-artifact-plus-other.json"
  # A quota annotation BESIDE a real test failure in the same job: NOT transient.
  { cat "${tmp}/log-codeql-quota.json"; cat "${tmp}/log-plain-failure.json"; } > "${tmp}/log-quota-plus-test.json"

  transient_case "real CodeQL SARIF upload on an exhausted quota IS transient"     rate-limit          log-codeql-quota
  transient_case "real gate shard whose only FAIL is NOT EVALUATED IS transient"   gates-not-evaluated log-gates-ne
  transient_case "real CreateArtifact <!DOCTYPE outage IS transient"               artifact            log-artifact-create
  transient_case "real 'Artifact not found' downstream is a consumer"              artifact-consumer   log-artifact-consumer
  transient_case "the gate-shard aggregator is an aggregator"                      aggregator          log-shards-aggregator
  transient_case "a shard with a REAL finding beside NOT EVALUATED is NOT transient" -                 log-gates-finding
  transient_case "a NOT EVALUATED roster without the run marker is NOT transient"    -                 log-gates-nomark
  transient_case "a self-test fixture roster cannot stand in for the real one"       -                 log-gates-fixture-first
  transient_case "an artifact outage beside a red test is NOT transient"             -                 log-artifact-plus-test
  transient_case "an artifact outage and BUILD FAILED in one step is NOT transient"  -                 log-artifact-same-step-red
  transient_case "an artifact outage beside any other annotation is NOT transient"   -                 log-artifact-plus-other
  transient_case "quota text with no annotation, then an exit, is NOT transient"     -                 log-quota-unannotated
  transient_case "a quota annotation beside a red test is NOT transient"             -                 log-quota-plus-test
  transient_case "an ordinary failing build log is NOT transient"                    -                 log-plain-failure
  transient_case "a Gradle-distribution outage is not THIS class"                    -                 log-dist-1109

  # Whole decision. Calls: structural jq -> (#9787) failed ids + one log each (cached) ->
  # distribution-outage job list -> transient job list -> [rate_limit] -> rerun.
  cat > "${tmp}/codeql-quota.json" <<'FIX'
{"jobs":[
 {"id":131186969462,"name":"CodeQL (actions, none)","conclusion":"failure","completed_at":"2026-11-12T12:39:31Z","steps":[{"name":"Perform CodeQL Analysis","conclusion":"failure"}]},
 {"id":131186969463,"name":"Trivy","conclusion":"success","completed_at":"2026-11-12T12:20:00Z","steps":[]}]}
FIX
  cat > "${tmp}/gates-quota.json" <<'FIX'
{"jobs":[
 {"id":130440797437,"name":"gates (lint-supplychain-security)","conclusion":"failure","completed_at":"2026-11-11T09:44:00Z","steps":[{"name":"Run gates","conclusion":"failure"}]},
 {"id":130448998667,"name":"Validate manifests","conclusion":"failure","completed_at":"2026-11-11T09:51:38Z","steps":[{"name":"Require every shard","conclusion":"failure"}]}]}
FIX
  cat > "${tmp}/artifact-outage.json" <<'FIX'
{"jobs":[
 {"id":130780720741,"name":"build (openbank-consent-service) / openbank-consent-service (build)","conclusion":"failure","completed_at":"2026-11-11T20:41:20Z","steps":[{"name":"Upload test results","conclusion":"failure"}]},
 {"id":130780720799,"name":"build (openbank-pension-service) / openbank-pension-service (publish test evidence)","conclusion":"failure","completed_at":"2026-11-11T20:44:28Z","steps":[{"name":"Download","conclusion":"failure"}]},
 {"id":130780720800,"name":"all-green","conclusion":"failure","completed_at":"2026-11-11T20:45:00Z","steps":[{"name":"all","conclusion":"failure"}]}]}
FIX
  jq '.jobs |= map(select(.name | test("publish test evidence|all-green")))' "${tmp}/artifact-outage.json" > "${tmp}/artifact-consumer-only.json"
  local ART_DONE; ART_DONE="$(jq -rn '"2026-11-11T20:44:28Z" | fromdateiso8601')"
  local QUOTA_OK="4321 5000" QUOTA_LOW="999 5000"

  WANT_DECISION=rerun-github-transient-quota \
  case_ "CodeQL killed by the quota IS re-run once the quota has reset" failure 0 1 \
    "0|@codeql-quota" "0|@codeql-quota" "0|@raw:log-codeql-quota" "0|@codeql-quota" "0|@codeql-quota" "0|${QUOTA_OK}" "0|ok"
  WANT_DECISION=deferred-quota-not-reset \
  case_ "CodeQL killed by the quota is NOT re-run while the quota is still low" failure 0 0 \
    "0|@codeql-quota" "0|@codeql-quota" "0|@raw:log-codeql-quota" "0|@codeql-quota" "0|@codeql-quota" "0|${QUOTA_LOW}"
  WANT_DECISION=deferred-quota-not-reset \
  case_ "an unreadable rate_limit defers rather than re-running into the quota" failure 0 0 \
    "0|@codeql-quota" "0|@codeql-quota" "0|@raw:log-codeql-quota" "0|@codeql-quota" "0|@codeql-quota" "1|HTTP 500"
  WANT_DECISION=rerun-github-transient-quota \
  case_ "a NOT EVALUATED gate shard plus its aggregator IS re-run after reset" failure 0 1 \
    "0|@gates-quota" "0|@gates-quota" "0|@raw:log-gates-ne" "0|@raw:log-shards-aggregator" \
    "0|@gates-quota" "0|@gates-quota" "0|${QUOTA_OK}" "0|ok"
  WANT_DECISION=no-spot-kill-signature \
  case_ "a gate shard with a real finding is NOT re-run" failure 0 0 \
    "0|@gates-quota" "0|@gates-quota" "0|@raw:log-gates-finding" "0|@raw:log-shards-aggregator" \
    "0|@gates-quota" "0|@gates-quota"
  WANT_DECISION=no-spot-kill-signature \
  case_ "a real test failure beside a quota annotation is NOT re-run" failure 0 0 \
    "0|@codeql-quota" "0|@codeql-quota" "0|@raw:log-quota-plus-test" "0|@codeql-quota" "0|@codeql-quota"
  TRANSIENT_NOW_EPOCH=$(( ART_DONE + 3600 )) WANT_DECISION=rerun-github-transient-artifact \
  case_ "an artifact-service outage IS re-run once it has had time to recover" failure 0 1 \
    "0|@artifact-outage" "0|@artifact-outage" "0|@raw:log-artifact-create" "0|@raw:log-artifact-consumer" "0|@raw:log-allgreen" \
    "0|@artifact-outage" "0|@artifact-outage" "0|ok"
  TRANSIENT_NOW_EPOCH=$(( ART_DONE + 60 )) WANT_DECISION=deferred-artifact-settle \
  case_ "an artifact-service outage is NOT re-run a minute after it happened" failure 0 0 \
    "0|@artifact-outage" "0|@artifact-outage" "0|@raw:log-artifact-create" "0|@raw:log-artifact-consumer" "0|@raw:log-allgreen" \
    "0|@artifact-outage" "0|@artifact-outage"
  TRANSIENT_NOW_EPOCH=$(( ART_DONE + 3600 )) WANT_DECISION=no-spot-kill-signature \
  case_ "'Artifact not found' with no producer outage is NOT re-run" failure 0 0 \
    "0|@artifact-consumer-only" "0|@artifact-consumer-only" "0|@raw:log-artifact-consumer" "0|@raw:log-allgreen" \
    "0|@artifact-consumer-only" "0|@artifact-consumer-only"
  TRANSIENT_NOW_EPOCH=$(( ART_DONE + 3600 )) WANT_DECISION=no-spot-kill-signature \
  case_ "an artifact outage beside a red test is NOT re-run" failure 0 0 \
    "0|@artifact-outage" "0|@artifact-outage" "0|@raw:log-artifact-plus-test" "0|@raw:log-artifact-consumer" "0|@raw:log-allgreen" \
    "0|@artifact-outage" "0|@artifact-outage"
  # The cap. Uncapped, this exact run IS re-run (the case above). With one download allowed, the
  # #9787 recogniser spends it on the first log, every later read is refused WITHOUT a call —
  # across the subshells each recogniser runs in — and the run is declined, never re-run.
  LOG_READ_CAP=1 TRANSIENT_NOW_EPOCH=$(( ART_DONE + 3600 )) WANT_DECISION=no-spot-kill-signature WANT_CALLS=5 \
  case_ "past the log-read cap the run is declined, and no further log is fetched" failure 0 0 \
    "0|@artifact-outage" "0|@artifact-outage" "0|@raw:log-artifact-create" \
    "0|@artifact-outage" "0|@artifact-outage"

  # ── the re-run call's own error classes ────────────────────────────────────────────────────
  case_ "rerun 502 then success"           cancelled 0 2 "0|@reclaim" "1|failed to rerun: HTTP 502: Server Error" "0|ok"
  case_ "rerun already-running is success" cancelled 0 1 "0|@reclaim" "1|run 1 cannot be rerun; This workflow is already running"
  case_ "rerun rate-limited 5/5 escalates" cancelled 1 5 "0|@reclaim" "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}"
  case_ "rerun 404 escalates immediately"  cancelled 1 1 "0|@reclaim" "1|HTTP 404: Not Found"

  # ── the decision line must NAME the failure, not call it "unknown" ─────────────────────────
  # Run 33738356244 answered `(last: unknown)` while holding the full rate-limit text: the old
  # `with_retry` assigned RETRY_LAST_ERROR inside a command substitution — a subshell — so the
  # value never reached `main`. Asserted on the OUTPUT because that is the only place the defect
  # was ever visible; an exit code cannot see it (the run exited 1 either way, correctly).
  subjects=$(( subjects + 1 ))
  export CALL_LOG="${tmp}/calls" STUB_SCRIPT="${tmp}/script"
  : > "${CALL_LOG}"; : > "${CALL_LOG}.n"
  printf '%s\n' "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}" "1|${RL}" > "${STUB_SCRIPT}"
  set +e
  CONCLUSION=cancelled main > "${tmp}/out" 2>&1
  set -e
  if grep -q 'decision=jobs-unreadable' "${tmp}/out" \
     && grep -q 'rate limit exceeded' "${tmp}/out" \
     && ! grep -q 'last: unknown' "${tmp}/out"; then
    echo "PASS  the decision line names the real last error, not 'unknown'"; pass=$(( pass + 1 ))
  else
    echo "FAIL  the decision line lost the error text (the subshell bug from run 33738356244)"
    sed 's/^/        /' "${tmp}/out"; fail=$(( fail + 1 ))
  fi

  rm -rf "${tmp}"
  echo "SUBJECTS=${subjects}"
  echo "spot-kill-retry self-test: ${pass} passed, ${fail} failed"
  [ "${fail}" -eq 0 ]
}

if [ "${1:-}" = "--self-test" ]; then
  self_test
else
  main
fi
