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
# CALLERS (keep this list honest — a library with none is not a control)
#   .github/scripts/spot-kill-retry.sh — both the jobs-API detection query and `gh run rerun`.
#   This file shipped in #8371 with ZERO callers and stayed that way while the failure it was
#   written for kept happening; `grep -rl gh-retry.sh .github/` matched only itself.
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
# The `gh` used by the reset probe below. A caller that drives its own scripted `gh` stub
# (spot-kill-retry.sh does) MUST be able to point this at that stub, or the probe issues a real
# network call the stub never scripted — which both leaves the suite non-hermetic and, where the
# stub counts calls, desynchronises its call counter from its script.
GH_RETRY_GH_BIN="${GH_RETRY_GH_BIN:-gh}"
# Optional: a path the LAST attempt's stderr is copied to. `gh_retry` is normally called inside a
# command substitution, which is a subshell, so a variable it sets cannot reach the caller — a
# file can. Without this the caller can only report "last: unknown", which is what
# spot-kill-retry.sh printed on run 33738356244 while holding the rate-limit text all along.
GH_RETRY_LAST_ERROR_FILE="${GH_RETRY_LAST_ERROR_FILE:-}"

# _gh_retry_classify <stderr-file> -> echoes one of: rate_limit | transient | final
_gh_retry_classify() {
  local err="$1"
  if grep -qiE 'rate.?limit|HTTP 403.*(rate|limit)|secondary rate' "$err"; then
    echo rate_limit
  elif grep -qiE 'HTTP 5[0-9][0-9]|connection (reset|refused|timed? ?out)|error connecting|EOF|TLS handshake|timeout' "$err"; then
    echo transient
  else
    echo final
  fi
}

# _gh_retry_reset_wait -> seconds until the core rate limit resets (0 if unknown)
_gh_retry_reset_wait() {
  local reset now
  # A zero cap (tests) clamps the answer to 0 whatever it is, so the probe can only cost a real
  # network call and, for a caller with a scripted stub, a desynchronised call counter.
  if [[ "$GH_RETRY_MAX_WAIT_SECONDS" -eq 0 ]]; then echo 0; return 0; fi
  reset=$("$GH_RETRY_GH_BIN" api rate_limit --jq '.resources.core.reset' 2>/dev/null || echo 0)
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
    if [[ "$class" == "final" || $attempt -eq $max ]]; then
      cat "$err" >&2 || true
      # Survives the subshell a command substitution puts this function in — see the knob above.
      # An `if` and not `[[ ... ]] && cat`: this file is SOURCED into callers that run under
      # `set -e`, where a trailing AND-OR list whose left side is false is a footgun not worth
      # arguing about.
      if [[ -n "$GH_RETRY_LAST_ERROR_FILE" ]]; then
        cat "$err" > "$GH_RETRY_LAST_ERROR_FILE" 2>/dev/null || true
      fi
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
# `BASH_SOURCE[0] == $0` means EXECUTED, not sourced, and it is load-bearing rather than
# stylistic. `source x.sh` with no arguments leaves the CALLER's positional parameters in place,
# so a plain `[[ $1 == --self-test ]]` here fires while running the caller's suite — the sourcing
# script's `--self-test` runs THIS file's cases and `exit 0`s before the caller's ever start.
# Measured while wiring the first caller (spot-kill-retry.sh): its 13-case suite printed the five
# lines below and exited 0, i.e. a green that had tested nothing the caller owns. A library whose
# whole contract is "source me" cannot key anything off the caller's argv.
if [[ "${BASH_SOURCE[0]}" == "${0}" && "${1:-}" == "--self-test" ]]; then
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
  subjects=0

  subjects=$(( subjects + 1 ))
  rm -f "$tmp/state"; STUB_FAILURES=2 STUB_ERROR="HTTP 502 Bad Gateway"
  out=$(gh_retry 4 -- stub) && [[ "$out" == stub-ok ]] \
    && echo "self-test ok: transient retried to success" || { echo "SELF-TEST FAIL: transient"; fails=1; }

  subjects=$(( subjects + 1 ))
  rm -f "$tmp/state"; STUB_FAILURES=1 STUB_ERROR="HTTP 403: API rate limit exceeded"
  out=$(gh_retry 3 -- stub) && [[ "$out" == stub-ok ]] \
    && echo "self-test ok: rate-limit class retried" || { echo "SELF-TEST FAIL: rate_limit"; fails=1; }

  subjects=$(( subjects + 1 ))
  rm -f "$tmp/state"; STUB_FAILURES=5 STUB_ERROR="HTTP 404 Not Found"
  gh_retry 3 -- stub 2>/dev/null && { echo "SELF-TEST FAIL: final must not succeed"; fails=1; } \
    || { [[ $(cat "$tmp/state" 2>/dev/null || echo 5) == "1" ]] \
         && echo "self-test ok: final fails immediately (1 attempt)" \
         || { echo "SELF-TEST FAIL: final retried"; fails=1; }; }

  subjects=$(( subjects + 1 ))
  rm -f "$tmp/state"; STUB_FAILURES=9 STUB_ERROR="HTTP 503"
  gh_retry 2 -- stub 2>/dev/null \
    && { echo "SELF-TEST FAIL: exhaustion should fail"; fails=1; } \
    || { [[ $(cat "$tmp/state") == "2" ]] \
         && echo "self-test ok: exhaustion fails after max attempts" \
         || { echo "SELF-TEST FAIL: exhaustion attempt count"; fails=1; }; }

  rm -rf "$tmp"
  # `SUBJECTS=` is what run-gates.py reads to enforce `min_subjects`. Without it a gate that
  # declares a floor fails outright — deliberately, so a suite cannot go green about a corpus
  # that quietly emptied (#4339).
  echo "SUBJECTS=${subjects}"
  [[ $fails == 0 ]] || exit 1
  echo "gh-retry self-test: all $subjects cases behaved"
  exit 0
fi
