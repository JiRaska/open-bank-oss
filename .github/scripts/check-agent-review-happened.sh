#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# PROOF THAT A REVIEW HAPPENED — the half of ADR-0251 that ADR-0154 got wrong.
#
# ADR-0154's workflow had a step named "Verify the Claude fallback actually reviewed". It
# never ran. It carried the SAME `if:` condition as the review it was verifying, so the two
# were switched off together, and across 10 consecutive runs both were `skipped` while every
# run concluded `success` (#4281). A control that can be disabled by the thing it controls
# is not a control.
#
# So this script takes NO condition, reads the model's REPLY TEXT rather than the driver's
# exit code, and is invoked from a step with `if: always()`. It exists to make the difference
# between "reviewed and found nothing" and "never asked a model" impossible to confuse.
#
# Why not the exit code: the previous driver (`claude-code-action`) exits 0 on its own
# recorded failure — that is the #2161 finding. During ADR-0251's construction five separate
# probe failures (workflow_dispatch needing the default branch, `Unsupported event type:
# push`, a missing `id-token: write`, the GitHub App gate, and a dead token) would each have
# reported a PASS to a verifier reading an exit code. Not one of them called a model.
#
# Usage:  check-agent-review-happened.sh <review-output-file>
#         check-agent-review-happened.sh --self-test
#
# Exit 0  a review demonstrably happened (findings, or an explicit no-findings verdict)
# Exit 1  no review happened, or the output cannot be told apart from one that did not

set -uo pipefail

# The reviewer is required to end with exactly one of these. Requiring a POSITIVE marker for
# the clean case is the point: an empty file, a crashed driver and a genuinely clean review
# are otherwise identical, and the last one is the only one that may pass.
VERDICT_FINDINGS='REVIEW-VERDICT: FINDINGS'
VERDICT_CLEAN='REVIEW-VERDICT: NO FINDINGS'

# Substrings that prove the driver never reached a model. Matched case-insensitively. These
# are the real strings observed on this repo, not invented ones.
declare -a DEAD_MARKERS=(
  'OAuth access token is invalid'
  'Failed to authenticate'
  'Claude Code is not installed on this repository'
  'Unsupported event type'
  'Could not fetch an OIDC token'
  'Credit balance is too low'
  'Reached max turns'          # measured: --max-turns 1 cuts the model off before it answers
  'rate limit'
)

verify() {
  local f="${1:-}"

  if [ -z "$f" ] || [ ! -f "$f" ]; then
    echo "::error::no review output file at '${f:-<empty>}' — NO REVIEW HAPPENED." >&2
    return 1
  fi

  # An empty or whitespace-only file is the shape a crashed driver leaves behind. It is not
  # a clean review, and the distinction is the whole reason this script exists.
  if [ ! -s "$f" ] || ! grep -q '[^[:space:]]' "$f"; then
    echo "::error::review output is empty — NO REVIEW HAPPENED (a crashed driver and a clean review look identical without a verdict line)." >&2
    return 1
  fi

  local m
  for m in "${DEAD_MARKERS[@]}"; do
    if grep -qiF -- "$m" "$f"; then
      echo "::error::review output contains '${m}' — the driver never reached a model. NO REVIEW HAPPENED." >&2
      return 1
    fi
  done

  if grep -qF -- "$VERDICT_FINDINGS" "$f"; then
    echo "review happened: findings reported."
    return 0
  fi
  if grep -qF -- "$VERDICT_CLEAN" "$f"; then
    echo "review happened: no findings."
    return 0
  fi

  echo "::error::review output carries neither '${VERDICT_FINDINGS}' nor '${VERDICT_CLEAN}' — cannot distinguish a clean review from a review that never ran. NO REVIEW HAPPENED." >&2
  return 1
}

self_test() {
  local tmp rc fails=0 ran=0
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' RETURN

  # check <label> <expected-rc> <file-or-empty> [required-substring-of-the-message]
  #
  # The 4th argument is not decoration. Several branches here reject the same input for
  # different reasons, so asserting only on the exit code leaves the earlier branch
  # unfalsifiable: deleting the empty-file check kept this self-test GREEN, because an empty
  # file also carries no verdict line and failed one branch later. An unfalsifiable branch
  # inside the proof-of-review script is precisely the defect this whole file exists to
  # avoid, so where two branches agree on the verdict the message is what tells them apart.
  check() {
    local label="$1" want="$2" file="${3:-}" want_msg="${4:-}"
    ran=$((ran + 1))
    local msg
    msg=$(verify "$file" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then
      echo "::error::self-test: ${label} — expected rc=${want}, got rc=${rc}" >&2
      fails=$((fails + 1))
    elif [ -n "$want_msg" ] && ! printf '%s' "$msg" | grep -qF -- "$want_msg"; then
      echo "::error::self-test: ${label} — rc was right but the reason was not: expected a message containing '${want_msg}', got '${msg}'" >&2
      fails=$((fails + 1))
    fi
  }

  # MUST PASS. Only these two shapes are a review.
  printf 'blah\n%s\n' "$VERDICT_CLEAN"    > "$tmp/clean";    check "a clean verdict must pass"    0 "$tmp/clean"
  printf 'x: bug\n%s\n' "$VERDICT_FINDINGS" > "$tmp/findings"; check "a findings verdict must pass" 0 "$tmp/findings"

  # MUST FAIL. Each of these is a way the previous control reported success having done nothing.
  check "a missing file must fail"        1 "$tmp/does-not-exist"
  check "no argument at all must fail"    1 ""
  # These two must fail FOR THE EMPTINESS, not for the missing verdict line — see check().
  : > "$tmp/empty"
  check "an empty file must fail as empty"     1 "$tmp/empty" "review output is empty"
  printf '   \n\t\n' > "$tmp/blank"
  check "a whitespace file must fail as empty" 1 "$tmp/blank" "review output is empty"
  printf 'Looks fine to me, no problems found.\n' > "$tmp/prose"
  check "plausible prose without a verdict line must fail" 1 "$tmp/prose"

  # The measured dead-driver outputs. These are the exact strings this repo has seen, and
  # each one accompanied a run that read as successful somewhere.
  printf 'Failed to authenticate. API Error: 401 OAuth access token is invalid.\n' > "$tmp/dead401"
  check "a 401 must fail" 1 "$tmp/dead401"
  printf 'Claude Code is not installed on this repository.\n%s\n' "$VERDICT_CLEAN" > "$tmp/app"
  check "a dead marker must beat a verdict line" 1 "$tmp/app"
  # The real output of the first live run: the model WAS reached, then cut off mid-answer.
  printf 'Error: Reached max turns (1)\n' > "$tmp/maxturns"
  check "a max-turns cutoff must fail" 1 "$tmp/maxturns"

  if [ "$fails" -gt 0 ]; then
    echo "self-test FAILED (${fails} case(s))" >&2
    return 1
  fi
  # Count DERIVED from the cases actually run, never typed: a hardcoded number stays green
  # when an added case silently fails to be inserted, which is how this very edit nearly went.
  echo "self-test ok: proof-of-review is falsifiable (${ran} cases)"
  return 0
}

case "${1:-}" in
  --self-test) self_test ;;
  *)           verify "${1:-}" ;;
esac
