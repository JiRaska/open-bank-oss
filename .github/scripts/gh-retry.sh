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

  rm -rf "$tmp"
  [[ $fails == 0 ]] || exit 1
  echo "gh-retry self-test: all cases behaved"
  exit 0
fi
