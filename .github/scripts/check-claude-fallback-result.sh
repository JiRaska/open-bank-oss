#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Guard: the Claude fallback reviewer must not report success while having reviewed
# nothing (issue #2161, follow-up half).
#
# WHY THIS EXISTS
#   `anthropics/claude-code-action` exits 0 even when its own transcript records that the
#   run failed. In #2161 the OAuth token was rejected at the gateway on the first model
#   call — `is_error: true`, `num_turns: 1`, `total_cost_usd: 0`, roughly two seconds — and
#   GitHub recorded that step as a **successful** step in a **successful** job.
#
#   That is the failure class this repo keeps paying for: the standby reviewer was dead,
#   and every signal available said it was fine. Nobody could have noticed from the Actions
#   tab, because there was nothing to notice. The credential is the owner's to re-mint, but
#   "a broken fallback reads as a green review" is a separate defect and is fixable here.
#
#   Note the ordering that makes this worth having *before* the token is fixed: the
#   fallback only runs when the primary reviewer fails, and since #2088 the primary
#   succeeds on exactly the diffs that used to route here. So the standby is now LESS
#   exercised than it was — the next time it runs may be the next time it matters, and
#   that run must not be able to lie.
#
# WHAT IT CHECKS
#   Reads the action's `execution_file` transcript and fails when the run did not actually
#   review anything:
#     * no transcript at all, or one that cannot be parsed  -> FAIL (not "fine")
#     * the terminal `result` entry has `is_error: true`    -> FAIL
#     * `num_turns` <= 1                                    -> FAIL (never read the diff)
#   And warns (does not fail) when the transcript shows no `gh pr review` invocation, since
#   a run that completed without submitting a verdict produced no signal either — but that
#   can legitimately happen and is not worth a red on its own.
#
#   "Cannot parse" deliberately fails rather than passes. A guard that treats its own
#   blindness as a pass is how the thing it guards stayed broken.
#
# Usage:  check-claude-fallback-result.sh <execution-file>
#         check-claude-fallback-result.sh --self-test

set -uo pipefail

# ---------------------------------------------------------------------------------------

evaluate() {
  local file="$1"
  local problems=0

  if [ -z "$file" ]; then
    echo "::error title=Claude fallback::The action produced no \`execution_file\` output at all. There is no transcript, so it cannot be shown that any review happened. Treating as a failed review (#2161)."
    return 1
  fi
  if [ ! -r "$file" ]; then
    echo "::error title=Claude fallback::execution_file '$file' is missing or unreadable — no transcript, so no evidence a review happened (#2161)."
    return 1
  fi
  if ! jq -e . "$file" >/dev/null 2>&1; then
    echo "::error title=Claude fallback::execution_file '$file' is not valid JSON. Refusing to read an unparseable transcript as a successful review (#2161)."
    return 1
  fi

  # The transcript is a stream of message objects; the terminal one has type "result".
  # `.[]?` tolerates a top-level object instead of an array without erroring.
  local result
  result="$(jq -c 'if type=="array" then (.[] | select(.type=="result")) else (select(.type=="result")) end' "$file" 2>/dev/null | tail -1)"

  if [ -z "$result" ]; then
    echo "::error title=Claude fallback::No terminal \`result\` entry in the transcript — the run did not complete. Not a successful review (#2161)."
    return 1
  fi

  local is_error num_turns cost subtype
  is_error="$(printf '%s' "$result" | jq -r '.is_error // false')"
  num_turns="$(printf '%s' "$result" | jq -r '.num_turns // 0')"
  cost="$(printf '%s' "$result" | jq -r '.total_cost_usd // 0')"
  subtype="$(printf '%s' "$result" | jq -r '.subtype // "unknown"')"

  echo "Claude fallback transcript: is_error=$is_error num_turns=$num_turns total_cost_usd=$cost subtype=$subtype"

  if [ "$is_error" = "true" ]; then
    echo "::error title=Claude fallback failed::The transcript reports \`is_error: true\` (subtype=$subtype, num_turns=$num_turns, cost=\$$cost). The action exits 0 in this case, so without this check the step would read as a SUCCESSFUL review of a PR nobody reviewed. See #2161."
    problems=1
  fi

  # A single turn means the run died on its first model call — the #2161 signature. A real
  # review needs at least a diff read and a verdict, so it can never be one turn.
  if [ "$num_turns" -le 1 ] 2>/dev/null; then
    echo "::error title=Claude fallback did nothing::The run used num_turns=$num_turns. A real review reads the diff and submits a verdict, so it cannot complete in one turn — this is the #2161 signature (gateway rejection on the first model call)."
    problems=1
  fi

  # Advisory: completed, but never submitted a verdict. Real, but not worth a red.
  if ! grep -q 'gh pr review' "$file" 2>/dev/null; then
    echo "::warning title=Claude fallback submitted no review::The run completed but the transcript contains no \`gh pr review\` invocation, so this PR received no agent verdict."
  fi

  return "$problems"
}

# ---------------------------------------------------------------------------------------

self_test() {
  local tmp rc fails=0
  tmp="$(mktemp -d)" || return 2

  # (1) The EXACT #2161 shape. This MUST be flagged; it is the whole reason the file exists.
  cat > "$tmp/dead.json" <<'JSON'
[{"type":"system","subtype":"init"},
 {"type":"result","subtype":"error_during_execution","is_error":true,"num_turns":1,"total_cost_usd":0}]
JSON

  # (2) A healthy review. This MUST pass, or the guard is just a red light.
  cat > "$tmp/healthy.json" <<'JSON'
[{"type":"assistant","message":{"content":[{"type":"tool_use","input":{"command":"gh pr diff 42"}}]}},
 {"type":"assistant","message":{"content":[{"type":"tool_use","input":{"command":"gh pr review 42 --comment --body x"}}]}},
 {"type":"result","subtype":"success","is_error":false,"num_turns":7,"total_cost_usd":0.031}]
JSON

  # (3) Completed but never submitted a verdict -> warn, still pass.
  cat > "$tmp/noverdict.json" <<'JSON'
[{"type":"result","subtype":"success","is_error":false,"num_turns":5,"total_cost_usd":0.02}]
JSON

  # (4) Truncated/incomplete transcript -> MUST fail, not pass.
  cat > "$tmp/noresult.json" <<'JSON'
[{"type":"system","subtype":"init"}]
JSON

  # (5) Not JSON at all -> MUST fail. A guard that cannot read its input must not pass.
  echo 'not json {{{' > "$tmp/garbage.json"

  check() { # <label> <file-or-empty> <expected-rc>
    local label="$1" file="$2" want="$3" got
    evaluate "$file" >/dev/null 2>&1; got=$?
    if [ "$got" -ne "$want" ]; then
      echo "  FAIL: $label expected rc=$want got rc=$got"
      fails=$((fails + 1))
    else
      echo "  ok:   $label (rc=$got)"
    fi
  }

  echo "self-test — every failure path is fed an input it MUST flag:"
  check "#2161 shape (is_error + 1 turn)" "$tmp/dead.json"     1
  check "healthy review"                  "$tmp/healthy.json"  0
  check "completed, no verdict (warn)"    "$tmp/noverdict.json" 0
  check "truncated transcript"            "$tmp/noresult.json" 1
  check "unparseable transcript"          "$tmp/garbage.json"  1
  check "missing file"                    "$tmp/absent.json"   1
  check "empty execution_file output"     ""                   1

  rm -rf "$tmp"
  if [ "$fails" -gt 0 ]; then
    echo "self-test FAILED ($fails case(s))"
    return 1
  fi
  echo "self-test OK — 7 cases."
  return 0
}

# ---------------------------------------------------------------------------------------

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi

evaluate "${1:-}"
rc=$?
if [ "$rc" -ne 0 ]; then
  echo
  echo "The Claude fallback reviewer did NOT review this PR. This job is non-blocking and"
  echo "not a required check, so the PR is unaffected — but the run is now red instead of"
  echo "reporting a review that never happened. Root cause is tracked in #2161."
fi
exit "$rc"
