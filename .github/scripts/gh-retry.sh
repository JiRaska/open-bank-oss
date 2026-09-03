#!/usr/bin/env bash
# Central transient-failure retry for GitHub API / gh CLI calls (issues #7976, #6853).
#
# WHY THIS EXISTS
#   The fleet's automation calls `gh api` / `gh run` from a dozen scripts, and each
#   invented its own retry — or none. Measured cost of the none/ad-hoc versions:
#   #6255 (a detection `gh api` with no transient handling next to a rerun call with
#   three attempts; one installation 403 killed the step before any decision line),
#   #6853 (spot-kill auto-retry declines a reclaim when the jobs API returns a 403
#   installation rate limit — a 3x tight retry cannot clear a limit whose window is
#   minutes), #7976 (reclaimed CI runs not re-run, same mechanism). Three issues,
#   one shape: a TRANSIENT GitHub-side failure treated as final.
#
#   The classes that ARE transient and worth waiting out:
#     - HTTP 403 rate-limit / "API rate limit exceeded" (primary or secondary) —
#       the correct wait is until the limit resets, bounded below.
#     - HTTP 5xx, connection resets, "error connecting", TLS timeouts.
#   Everything else (404, 422, auth failure, "not found") is a REAL answer and
#   must fail immediately — retrying it is how a deterministic red becomes a
#   flaky-looking one.
#
# USAGE (source this file, then wrap the call):
#     source .github/scripts/gh-retry.sh
#     gh_retry 5 -- gh api repos/:owner/:repo/rulesets
#   $1 = max attempts (default 5). Output of the wrapped command goes to stdout on
#   success; on exhausted retries the function returns the command's last status
#   and, when GH_RETRY_DEAD_LETTER=1 with GH_TOKEN set, files/updates ONE issue
#   labeled `ci-dead-letter` per (workflow, failing command) so an unrecoverable
#   night never looks like a green one (#7976: "no signal anywhere disagrees").
#
# NOTE: this file is meant to be SOURCED — it deliberately does NOT change the
# caller's shell options (no `set -e` here); the caller owns its own errexit.
#
# SELF-TEST: gh-retry.sh --self-test drives every branch with a stub command and
# asserts the retry counts — a retry library that has only ever retried real API
# calls has never had its branches exercised on purpose.

GH_RETRY_MAX_WAIT_SECONDS="${GH_RETRY_MAX_WAIT_SECONDS:-900}"  # never sleep past 15 min
GH_RETRY_DEAD_LETTER="${GH_RETRY_DEAD_LETTER:-0}"

# The shared vocabulary, read by this library AND by
# .github/scripts/check-ruleset-context-parity.py. Two hand-maintained copies of one question
# is what this file's own header calls the defect; the measured drift (8 disagreements in 31
# messages, five missed transients each, in three DIFFERENT sets) is recorded there.
# ${BASH_SOURCE[0]} and not $0: this file is SOURCED, so $0 is the caller's path.
GH_RETRY_PATTERNS_FILE="${GH_RETRY_PATTERNS_FILE:-$(dirname "${BASH_SOURCE[0]}")/gh-transient-patterns.txt}"

# _gh_retry_patterns <section> -> the section's patterns as one ERE alternation, empty if the
# file or the section cannot be read. The caller MUST treat empty as an error and never as
# "no patterns matched" — see _gh_retry_classify.
_gh_retry_patterns() {
  local section="$1"
  [[ -r "$GH_RETRY_PATTERNS_FILE" ]] || return 1
  awk -v s="[$section]" '
    $0 == s { inside = 1; next }
    /^\[/   { inside = 0 }
    inside && $0 !~ /^#/ && NF { print }
  ' "$GH_RETRY_PATTERNS_FILE" | paste -sd'|' -
}

# _gh_retry_classify <stderr-file> -> rate_limit | transient | final | unloadable
#
# `unloadable` is a FOURTH state and not a synonym for `final`. An unreadable vocabulary makes
# every message match nothing, so folding it into `final` would turn a missing file into
# "nothing is ever transient" — a control silently doing nothing while reporting a normal
# answer, the exact shape this repo keeps paying for (the APNs adapter whose skipped() carried
# success = true; the CNPG archiver that reports success with no bucket). It is loud instead.
_gh_retry_classify() {
  local err="$1" rl_pat tr_pat
  rl_pat=$(_gh_retry_patterns rate_limit) || rl_pat=""
  tr_pat=$(_gh_retry_patterns transient) || tr_pat=""
  if [[ -z "$rl_pat" || -z "$tr_pat" ]]; then
    echo unloadable
  elif grep -qiE "$rl_pat" "$err"; then
    echo rate_limit
  elif grep -qiE "$tr_pat" "$err"; then
    echo transient
  else
    echo final
  fi
}

# _gh_retry_reset_wait -> seconds until the core rate limit resets (0 if unknown)
_gh_retry_reset_wait() {
  local reset now
  reset=$(gh api rate_limit --jq '.resources.core.reset' 2>/dev/null || echo 0)
  now=$(date +%s)
  if [[ "$reset" =~ ^[0-9]+$ && "$reset" -gt "$now" ]]; then
    echo $(( reset - now ))
  else
    echo 60
  fi
}

# _gh_retry_dead_letter <command...> — open or update the dead-letter issue.
_gh_retry_dead_letter() {
  [[ "$GH_RETRY_DEAD_LETTER" == "1" ]] || return 0
  [[ -n "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ]] || return 0
  local title="CI dead-letter: exhausted retries — $*"
  local body
  body=$(printf 'Command exhausted all retries on transient failures:\n\n```\n%s\n```\n\nRun: %s\nWorkflow: %s\nTime: %s UTC\n\nFiled by `.github/scripts/gh-retry.sh` — an exhausted retry is a lost night of automation unless it is VISIBLE (#7976, #6853).' \
    "$*" "${GITHUB_SERVER_URL:-https://github.com}/${GITHUB_REPOSITORY:-?}/actions/runs/${GITHUB_RUN_ID:-?}" "${GITHUB_WORKFLOW:-local}" "$(date -u +%FT%TZ)")
  # One issue per command signature: search first, update if found.
  local existing
  existing=$(gh issue list --search "\"$title\" in:title" --state open \
               --label ci-dead-letter --limit 1 --json number --jq '.[0].number' 2>/dev/null || true)
  if [[ -n "$existing" ]]; then
    gh issue comment "$existing" --body "$body" >/dev/null || true
  else
    gh issue create --title "$title" --body "$body" --label ci-dead-letter >/dev/null || true
  fi
}

# gh_retry [max_attempts] -- <command...>
gh_retry() {
  local max=5
  if [[ "${1:-}" =~ ^[0-9]+$ ]]; then max="$1"; shift; fi
  [[ "${1:-}" == "--" ]] && shift
  local attempt=1 err class wait
  err=$(mktemp)
  while (( attempt <= max )); do
    local rc=0
    # NOTE: rc must be captured in the else branch — after a compound `if`
    # whose condition was false, $? is 0 (bash manual), so `local rc=$?`
    # AFTER the if would read 0 and report every final failure as success.
    if "$@" 2>"$err"; then
      rm -f "$err"
      return 0
    else
      rc=$?
    fi
    class=$(_gh_retry_classify "$err")
    if [[ "$class" == "unloadable" ]]; then
      cat "$err" >&2 || true
      echo "::error::gh_retry: cannot read the transient-pattern vocabulary at" \
           "'$GH_RETRY_PATTERNS_FILE'. Refusing to classify: with no patterns loaded EVERY" \
           "failure would look final, so a deleted file would silently disable every retry" \
           "in the fleet while each call still returned a plausible answer. Restore the file" \
           "or point GH_RETRY_PATTERNS_FILE at it. (rc 2 = library misconfiguration, which is" \
           "deliberately NOT the wrapped command's status.)" >&2
      rm -f "$err"
      return 2
    fi
    if [[ "$class" == "final" || $attempt -eq $max ]]; then
      cat "$err" >&2 || true
      if [[ "$class" != "final" ]]; then
        echo "::warning::gh_retry: exhausted $max attempts on transient failures: $*" >&2
        _gh_retry_dead_letter "$@"
      fi
      rm -f "$err"
      return "$rc"
    fi
    if [[ "$class" == "rate_limit" ]]; then
      wait=$(_gh_retry_reset_wait)
    else
      wait=$(( (2 ** (attempt - 1)) * 5 + RANDOM % 5 ))   # exp backoff + jitter
    fi
    (( wait > GH_RETRY_MAX_WAIT_SECONDS )) && wait=$GH_RETRY_MAX_WAIT_SECONDS
    echo "::warning::gh_retry: attempt $attempt/$max failed ($class); retrying in ${wait}s: $*" >&2
    sleep "$wait"
    (( attempt++ ))
  done
}

# ------------------------------------------------------------------ self-test
if [[ "${1:-}" == "--self-test" ]]; then
  set +e
  tmp=$(mktemp -d)
  export GH_RETRY_MAX_WAIT_SECONDS=0   # tests never actually sleep
  # Stub: fails N times with a given error class, then succeeds.
  stub() {
    local file="$tmp/state"
    local n; n=$(cat "$file" 2>/dev/null || echo 0)
    if (( n < STUB_FAILURES )); then
      echo $(( n + 1 )) > "$file"
      echo "$STUB_ERROR" >&2
      return 1
    fi
    echo "stub-ok"
    return 0
  }
  fails=0

  rm -f "$tmp/state"; STUB_FAILURES=2 STUB_ERROR="HTTP 502 Bad Gateway"
  out=$(gh_retry 4 -- stub) && [[ "$out" == stub-ok ]] \
    && echo "self-test ok: transient retried to success" || { echo "SELF-TEST FAIL: transient"; fails=1; }

  rm -f "$tmp/state"; STUB_FAILURES=1 STUB_ERROR="HTTP 403: API rate limit exceeded"
  out=$(gh_retry 3 -- stub) && [[ "$out" == stub-ok ]] \
    && echo "self-test ok: rate-limit class retried" || { echo "SELF-TEST FAIL: rate_limit"; fails=1; }

  rm -f "$tmp/state"; STUB_FAILURES=5 STUB_ERROR="HTTP 404 Not Found"
  gh_retry 3 -- stub 2>/dev/null && { echo "SELF-TEST FAIL: final must not succeed"; fails=1; } \
    || { [[ $(cat "$tmp/state" 2>/dev/null || echo 5) == "1" ]] \
         && echo "self-test ok: final fails immediately (1 attempt)" \
         || { echo "SELF-TEST FAIL: final retried"; fails=1; }; }

  rm -f "$tmp/state"; STUB_FAILURES=9 STUB_ERROR="HTTP 503"
  gh_retry 2 -- stub 2>/dev/null \
    && { echo "SELF-TEST FAIL: exhaustion should fail"; fails=1; } \
    || { [[ $(cat "$tmp/state") == "2" ]] \
         && echo "self-test ok: exhaustion fails after max attempts" \
         || { echo "SELF-TEST FAIL: exhaustion attempt count"; fails=1; }; }

  # ---- the shared vocabulary, driven over its own falsification corpus ---------------
  # `_corpus_mismatches <patterns-file>` echoes how many [cases] rows the file misclassifies.
  # Asserting 0 against the real file is only half a test — a corpus that cannot go non-zero
  # proves nothing — so the SAME function is then run against a deliberately poisoned copy and
  # required to be non-zero. That is the negative case: it shows the check can still reject.
  _corpus_mismatches() {
    local pf="$1" bad=0 want msg cls got
    local errf; errf=$(mktemp)
    while IFS=$'\t' read -r want msg; do
      [[ -n "$want" ]] || continue
      printf '%s\n' "$msg" > "$errf"
      cls=$(GH_RETRY_PATTERNS_FILE="$pf" _gh_retry_classify "$errf")
      [[ "$cls" == final ]] && got=FINAL || got=TRANSIENT
      if [[ "$got" != "$want" ]]; then
        bad=$(( bad + 1 ))
        [[ "${CORPUS_VERBOSE:-0}" == 1 ]] && echo "  want=$want got=$got ($cls): $msg" >&2
      fi
    done < <(awk '/^\[cases\]/ { c = 1; next } /^\[/ { c = 0 } c && $0 !~ /^#/ && NF' "$pf")
    rm -f "$errf"
    echo "$bad"
  }

  real_patterns="$(dirname "${BASH_SOURCE[0]}")/gh-transient-patterns.txt"
  n_cases=$(awk '/^\[cases\]/ { c = 1; next } /^\[/ { c = 0 } c && $0 !~ /^#/ && NF' "$real_patterns" | wc -l | tr -d ' ')
  n_final=$(awk '/^\[cases\]/ { c = 1; next } /^\[/ { c = 0 } c && $0 !~ /^#/ && NF' "$real_patterns" | grep -c '^FINAL' || true)
  # A floor on the corpus itself: the assertions ARE the subjects, so silently deleting the
  # negatives must not read as a pass. 13 terminal messages today; never fewer than 10.
  if (( n_cases >= 30 && n_final >= 10 )); then
    echo "self-test ok: corpus has $n_cases cases, $n_final of them must-stay-FINAL"
  else
    echo "SELF-TEST FAIL: corpus shrank ($n_cases cases, $n_final FINAL) — negatives deleted?"; fails=1
  fi

  CORPUS_VERBOSE=1
  if [[ "$(_corpus_mismatches "$real_patterns")" == 0 ]]; then
    echo "self-test ok: every corpus case classifies as documented"
  else
    echo "SELF-TEST FAIL: shipped vocabulary misclassifies its own corpus"; fails=1
  fi
  CORPUS_VERBOSE=0

  # NEGATIVE CASE 1 — an over-broad pattern must be REJECTED. `not found` in [transient] would
  # retry a 404 forever, and for the Python reader would degrade a permission denial to a green
  # UNRESOLVED. If this assertion ever passes with 0 mismatches, the corpus has stopped testing.
  poisoned="$tmp/poisoned.txt"
  sed 's/^\[transient\]$/[transient]\nnot found\nnot accessible/' "$real_patterns" > "$poisoned"
  if [[ "$(_corpus_mismatches "$poisoned")" != 0 ]]; then
    echo "self-test ok: an over-broad pattern is caught by the corpus (negative case)"
  else
    echo "SELF-TEST FAIL: poisoned vocabulary passed — the corpus proves nothing"; fails=1
  fi

  # NEGATIVE CASE 2 — a pattern DELETED must be caught too. Widening is not the only way to
  # break this; a narrowed vocabulary silently stops retrying and looks like a clean run.
  narrowed="$tmp/narrowed.txt"
  grep -v '^rate.?limit$' "$real_patterns" > "$narrowed"
  if [[ "$(_corpus_mismatches "$narrowed")" != 0 ]]; then
    echo "self-test ok: a deleted pattern is caught by the corpus (negative case)"
  else
    echo "SELF-TEST FAIL: removing rate.?limit changed nothing — corpus is vacuous"; fails=1
  fi

  # NEGATIVE CASE 3 — an unreadable vocabulary must be LOUD and must not retry. The failure to
  # prevent is the silent one: no patterns loaded means nothing ever matches, so a deleted file
  # would read as "every failure is final" and disable the whole library with a normal-looking
  # answer at every call site.
  rm -f "$tmp/state"; STUB_FAILURES=9 STUB_ERROR="HTTP 503 Service Unavailable"
  ul_err="$tmp/unloadable.err"
  GH_RETRY_PATTERNS_FILE="$tmp/does-not-exist.txt" gh_retry 3 -- stub >/dev/null 2>"$ul_err"
  ul_rc=$?
  if [[ "$ul_rc" == 2 ]] && grep -q '::error::' "$ul_err" && [[ "$(cat "$tmp/state")" == 1 ]]; then
    echo "self-test ok: an unreadable vocabulary is loud, rc 2, and retries nothing"
  else
    echo "SELF-TEST FAIL: unloadable vocabulary rc=$ul_rc attempts=$(cat "$tmp/state" 2>/dev/null) err=$(cat "$ul_err")"; fails=1
  fi

  rm -rf "$tmp"
  [[ $fails == 0 ]] || exit 1
  echo "gh-retry self-test: all cases behaved"
  exit 0
fi
