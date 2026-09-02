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
# code alone therefore cannot classify, which is why `is_transient` matches on text.
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
# A human who cancels a run WHILE its jobs are running is indistinguishable from a spot reclaim
# with the data GitHub exposes — both leave `cancelled` jobs carrying recorded steps — and is
# still re-run once. That is the accepted waste #2330 priced, not an oversight. The only human
# cancel this can rule out is the queue drain, where no cancelled job ever started a step
# (#3208); the self-test proves that one is declined.
#
# EXIT CODES
#   0  a decision was reached and acted on (re-run issued, or correctly declined)
#   1  the control could not do its job — a human must re-run the target by hand
#
# ENV (all required except the last three)
#   GITHUB_REPOSITORY  owner/repo
#   RUN_ID             the triggering run id
#   CONCLUSION         its conclusion: `cancelled` or `failure`
#   RUN_URL            its html_url (for the human-readable notices)
#   GITHUB_STEP_SUMMARY  optional; the `decide` table is appended here when set
#   GH_BIN             optional; the `gh` executable (the self-test points it at a stub)
#   SPOT_KILL_BACKOFF_BASE  optional; seconds multiplier for the backoff (default 15, 0 in tests)
set -euo pipefail

GH_BIN="${GH_BIN:-gh}"
BACKOFF_BASE="${SPOT_KILL_BACKOFF_BASE:-15}"
ATTEMPTS="${SPOT_KILL_ATTEMPTS:-3}"
RETRY_LAST_ERROR=""

# `-R` ON EVERY CALL IS LOAD-BEARING (issue #2841/#2898). This runs in a job with no
# actions/checkout, so there is no git repository for `gh` to infer the target repo from and a
# bare call dies with `failed to determine base repo`. That defect made the auto-retry from
# #2330 fail 208 times in silence. Do not drop the flag and do not "fix" it with a checkout.
gh_() { "${GH_BIN}" "$@"; }

# A transient failure is one where trying again can succeed. Matched on TEXT, never on status
# code — see the header on 403 being both a rate limit and a permission denial.
is_transient() {
  case "$1" in
    *"rate limit"*|*"Rate Limit"*|*"secondary rate"*) return 0 ;;
    *"HTTP 5"*|*"Server Error"*|*"HTTP 429"*)         return 0 ;;
    *"connection reset"*|*"EOF"*|*"timeout"*|*"Timeout"*|*"no such host"*|*"TLS handshake"*) return 0 ;;
    *) return 1 ;;
  esac
}

decide() { # decide <decision> <human sentence>
  echo "spot-kill-auto-retry run=${RUN_ID} conclusion=${CONCLUSION} decision=$1"
  if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
    echo "| \`${CONCLUSION}\` | [${RUN_ID}](${RUN_URL:-}) | \`$1\` | $2 |" >> "${GITHUB_STEP_SUMMARY}"
  fi
}

# Retry wrapper shared by BOTH the detection query and the re-run call. Before #6255 only the
# re-run had one; the asymmetry was the defect, so there is now exactly one implementation and
# it cannot drift.
with_retry() { # with_retry <label> <cmd...>  -> stdout of the successful call
  local label="$1"; shift
  local out attempt
  out=""
  for (( attempt=1; attempt<=ATTEMPTS; attempt++ )); do
    if out="$("$@" 2>&1)"; then
      printf '%s' "${out}"
      if [ "${attempt}" -gt 1 ]; then
        echo "${label} succeeded on attempt ${attempt}/${ATTEMPTS} after a transient error" >&2
      fi
      return 0
    fi
    echo "${label} attempt ${attempt}/${ATTEMPTS} failed: ${out}" >&2
    # "Already running" is neither transient nor an error: the substance this control exists to
    # achieve has ALREADY happened (issue #5489, measured on run 32100417490). Matched first so
    # it can fall into neither branch below.
    case "${out}" in
      *"already running"*)
        echo "${label}: target is already running (race with a prior attempt or GitHub's own mechanism) — nothing to do, treating as success" >&2
        printf '%s' "${out}"; return 0 ;;
    esac
    if ! is_transient "${out}"; then
      echo "::error title=spot-kill auto-retry::Non-transient error from ${label}; not retrying: ${out}" >&2
      RETRY_LAST_ERROR="${out}"; return 1
    fi
    if [ "${attempt}" -lt "${ATTEMPTS}" ]; then
      sleep $(( attempt * BACKOFF_BASE ))
    fi
  done
  echo "::error title=spot-kill auto-retry::${label} failed ${ATTEMPTS}/${ATTEMPTS} times on ${RUN_URL:-run ${RUN_ID}} (last: ${out}). Needs a manual re-run." >&2
  RETRY_LAST_ERROR="${out}"; return 1
}

# Both branches query attempt 1 explicitly. `/actions/runs/<id>/jobs` returns the LATEST
# attempt's jobs, so a concurrent manual re-run would make this inspect a different (possibly
# green) attempt and silently decline.
jobs_query() { # jobs_query <jq>
  gh_ api "repos/${GITHUB_REPOSITORY}/actions/runs/${RUN_ID}/attempts/1/jobs?per_page=100" --jq "$1"
}

main() {
  : "${GITHUB_REPOSITORY:?}" "${RUN_ID:?}" "${CONCLUSION:?}"
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
    q='[.jobs[] | select(.conclusion == "cancelled") | select((.steps | length) > 0)] | length'
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
    # See the header: for THIS control an unreadable corpus is not a pass. Exiting 1 is what
    # routes it to raise-issue, because a reclaimed run that nobody re-runs sits red forever.
    echo "::error title=spot-kill auto-retry::Could not read the jobs of ${RUN_URL:-${RUN_ID}} (last: ${RETRY_LAST_ERROR:-unknown}); NOT re-running. A genuine reclaim here needs a MANUAL re-run." >&2
    decide jobs-unreadable "the jobs API could not be read after ${ATTEMPTS} attempts — a genuine reclaim here needs a MANUAL re-run"
    return 1
  fi

  if [ "${CONCLUSION}" = "cancelled" ]; then
    if [ "${count}" -eq 0 ]; then
      echo "::notice title=spot-kill auto-retry::NOT re-running ${RUN_URL:-${RUN_ID}} — no cancelled job had started a step, so no runner was reclaimed. Treating it as a deliberate cancel (#3208)."
      decide skipped-queue-cancel "0 cancelled jobs had started a step — deliberate cancel, not a reclaim (#3208)"
      return 0
    fi
    echo "Re-running cancelled run ${RUN_URL:-${RUN_ID}} (${count} job(s) interrupted mid-flight; issue #2330)"
    # `--failed` is a no-op against `cancelled`, so this is always the full re-run.
    with_retry "gh run rerun" gh_ run rerun -R "${GITHUB_REPOSITORY}" "${RUN_ID}" || return 1
    echo "::notice title=spot-kill auto-retry::Re-ran cancelled run ${RUN_URL:-${RUN_ID}} (attempt 2 of max 2; a second kill stays for a human)"
    decide rerun-cancelled "${count} job(s) interrupted mid-flight — full re-run issued (attempt 2 of max 2)"
    return 0
  fi

  if [ "${count}" -eq 0 ]; then
    echo "::notice title=spot-kill auto-retry::${RUN_URL:-${RUN_ID}} failed with no spot-kill signature (no job has a cancelled step and no failed step) — treating it as a real failure and NOT re-running."
    decide no-spot-kill-signature "no failed job has a cancelled step and no failed step — a real build failure"
    return 0
  fi
  echo "Re-running ${RUN_URL:-${RUN_ID}}: ${count} job(s) killed mid-step by a runner reclaim (issue #2841)"
  with_retry "gh run rerun" gh_ run rerun -R "${GITHUB_REPOSITORY}" "${RUN_ID}" --failed || return 1
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

  # Assign the VARIABLE, not just the env var: `BACKOFF_BASE` is read from the environment at
  # script startup, which is before this function runs, so exporting `SPOT_KILL_BACKOFF_BASE`
  # here changes nothing. Measured: the suite took 196 s of pure `sleep` at the default 15 s
  # backoff, and every case still passed — a self-test can be right about behaviour and wrong
  # about what it is exercising, and the only tell was 0% CPU.
  BACKOFF_BASE=0
  export GH_BIN="${tmp}/gh"
  export GITHUB_REPOSITORY="owner/repo" RUN_ID=1 RUN_URL="http://x/1"
  unset GITHUB_STEP_SUMMARY || true

  local pass=0 fail=0 subjects=0
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
    if [ "${rc}" -eq "${want_exit}" ] && [ "${reruns}" -eq "${want_reruns}" ]; then
      echo "PASS  ${name} (exit=${rc}, rerun calls=${reruns})"; pass=$(( pass + 1 ))
    else
      echo "FAIL  ${name}: exit=${rc} (want ${want_exit}), rerun calls=${reruns} (want ${want_reruns})"
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

  # ── the #6255 regression, in both directions ───────────────────────────────────────────────
  case_ "rate-limited jobs query recovers and still re-runs" cancelled 0 1 "1|${RL}" "1|${RL}" "0|@reclaim" "0|ok"
  case_ "jobs query rate-limited 3/3 escalates (exit 1)"     cancelled 1 0 "1|${RL}" "1|${RL}" "1|${RL}"
  case_ "jobs query permission-denied escalates immediately" cancelled 1 0 "1|${PERM}"
  # The `failure` branch had the same hole and used to answer exit 0 (green, silent).
  case_ "rate-limited jobs query on the failure branch escalates" failure 1 0 "1|${RL}" "1|${RL}" "1|${RL}"

  # ── the failure branch's signature ─────────────────────────────────────────────────────────
  case_ "partial spot-kill (failure) re-runs the failed jobs" failure 0 1 "0|@partial-kill" "0|ok"
  case_ "a real build failure is NOT re-run"                  failure 0 0 "0|@real-failure"

  # ── the re-run call's own error classes ────────────────────────────────────────────────────
  case_ "rerun 502 then success"           cancelled 0 2 "0|@reclaim" "1|failed to rerun: HTTP 502: Server Error" "0|ok"
  case_ "rerun already-running is success" cancelled 0 1 "0|@reclaim" "1|run 1 cannot be rerun; This workflow is already running"
  case_ "rerun rate-limited 3/3 escalates" cancelled 1 3 "0|@reclaim" "1|${RL}" "1|${RL}" "1|${RL}"
  case_ "rerun 404 escalates immediately"  cancelled 1 1 "0|@reclaim" "1|HTTP 404: Not Found"

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
