#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
#
# Terminal accountability guard for `agent-review`: a run that reviewed nothing must not
# be readable as a review (issue #3488; the fallback half is #2161).
#
# WHY THIS EXISTS — measured, not assumed
#   120 `Agent review` runs sampled over 2026-08-02T15:52 .. 2026-08-03T11:18 (classified
#   from step conclusions, not from log text) contained ZERO model reviews:
#
#     75  deferred-to-human (sensitive scope — correct, no model was ever called)
#     15  job skipped by the workflow `if:`
#     27  primary reviewer failed -> dead Claude fallback -> job RED
#      2  primary reviewer failed -> fallback SKIPPED  -> job GREEN, nothing reviewed
#      1  checkout/git transport 503
#      0  a review actually submitted by any model
#
#   The trigger is not intermittent and it is not a rate limit. Every single call to the
#   primary reviewer returned:
#
#     410 GitHub Models is temporarily unavailable as part of a scheduled retirement brownout.
#
#   GitHub Models was fully retired on 2026-07-30 (GitHub Changelog, "GitHub Models is now
#   retired"). The primary reviewer is therefore permanently dead, not flaky. The apparent
#   ~40% pass rate in #3488 was runs that never called a model at all: the successes are
#   deferred-to-human and skipped runs, and 100% of runs that attempted a review failed.
#   Successes and failures interleaving minute by minute is that mix, not concurrency.
#
#   Two of the sampled runs are the reason this file exists rather than a tweak to
#   check-claude-fallback-result.sh. That guard is gated on the Claude step having RUN
#   (`steps.claude.outcome != 'skipped'`), so when the fallback is skipped — a Dependabot or
#   fork PR, where `secrets.CLAUDE_CODE_OAUTH_TOKEN` is empty in the job context — the guard
#   is skipped too and the job finishes GREEN having reviewed nothing. On the PR page a green
#   `agent-review` reads exactly like a review that happened. That is the default reading once
#   the fallback stops running for any reason, so it is the state worth closing.
#
# WHAT IT CHECKS
#   Drives off STEP OUTCOMES, never log text (a job log embeds each step's own `run:` script,
#   so grepping it matches strings that never executed). Passes only when a review demonstrably
#   happened or was deliberately not attempted:
#
#     scope deferred to a human            -> PASS (a human was told, in a PR comment)
#     primary verdict submitted            -> PASS (a real review was posted)
#     fallback ran and check-claude-
#       fallback-result.sh passed on its
#       transcript                         -> PASS
#     anything else                        -> FAIL, with the reason named
#
#   The transcript itself is NOT re-read here. check-claude-fallback-result.sh already does
#   that, in its own step, unchanged; its step outcome arrives as FALLBACK_GUARD_OUTCOME.
#   Two scripts each invoked directly by the workflow, rather than one calling the other —
#   check-gate-script-registration.py deliberately does not count a caller that lives in
#   .github/scripts, so delegating would have left the #2161 guard reading as an orphan.
#
#   Naming the reason is the point. Every failure in the sample was annotated as the #2161
#   signature, so four separate causes — a retired primary, a dead OAuth token, the action
#   refusing a bot actor, and a skipped fallback — all read as one known issue that nobody
#   could act on. This guard reports which one it was.
#
#   When it fails it also posts the verdict as a PR comment (best-effort; the token is
#   read-only on Dependabot and fork PRs). The job conclusion alone is a weak carrier: it is
#   read as "the reviewer is broken again", and it is the only place the state appears today.
#
# Usage:  check-agent-review-happened.sh
#         check-agent-review-happened.sh --self-test
#
# Environment (all optional; absent is treated as the pessimistic case):
#   SCOPE_SKIP        "true" when the scope gate deferred to a human
#   PRIMARY_OUTCOME   `steps.ghmodels.outcome`  (success|failure|skipped)
#   SUBMIT_OUTCOME    `steps.submit.outcome`    (success|failure|skipped)
#   CLAUDE_OUTCOME    `steps.claude.outcome`    (success|failure|skipped)
#   FALLBACK_GUARD_OUTCOME
#                     `steps.fallback_guard.outcome` — the #2161 transcript guard's verdict
#   HAS_CLAUDE        "true" when the OAuth secret is visible in this job context
#   PR, GH_TOKEN      used only for the best-effort PR comment

set -uo pipefail

# Set by evaluate(): a one-line, human-first statement of what happened.
VERDICT=""

evaluate() {
  local scope_skip="${SCOPE_SKIP:-false}"
  local primary="${PRIMARY_OUTCOME:-unknown}"
  local submit="${SUBMIT_OUTCOME:-unknown}"
  local claude="${CLAUDE_OUTCOME:-unknown}"
  local has_claude="${HAS_CLAUDE:-false}"
  local guard="${FALLBACK_GUARD_OUTCOME:-unknown}"

  echo "step outcomes: scope_skip=$scope_skip primary=$primary submit=$submit claude=$claude guard=$guard has_claude=$has_claude"

  if [ "$scope_skip" = "true" ]; then
    VERDICT="No agent review — the change is in the sensitive class and was deliberately deferred to a human reviewer."
    echo "$VERDICT"
    return 0
  fi

  if [ "$submit" = "success" ]; then
    VERDICT="Reviewed by the primary reviewer."
    echo "$VERDICT"
    return 0
  fi

  # Nothing was submitted by the primary. Name why, so the failure is actionable rather
  # than another instance of "the known reviewer breakage".
  local why
  case "$primary" in
    failure)
      why="the primary reviewer (GitHub Models) failed. GitHub Models was RETIRED on 2026-07-30 and now answers every inference call with HTTP 410, so this is permanent, not transient — see the 'GitHub Models review' step for the exact error (#3488)." ;;
    skipped)
      why="the primary reviewer step did not run." ;;
    success)
      why="the primary reviewer answered but its verdict was not submitted (submit outcome=$submit)." ;;
    *)
      why="the primary reviewer's outcome could not be determined (outcome=$primary)." ;;
  esac

  case "$claude" in
    skipped)
      if [ "$has_claude" = "true" ]; then
        VERDICT="NO REVIEW HAPPENED: $why The Claude fallback did not run."
      else
        VERDICT="NO REVIEW HAPPENED: $why The Claude fallback could not run either — CLAUDE_CODE_OAUTH_TOKEN is not visible in this job context, which is normal for a Dependabot or fork PR. Nothing reviewed this change; without this guard the job would be GREEN."
      fi
      echo "::error title=agent-review reviewed nothing::$VERDICT"
      return 1
      ;;
    unknown)
      VERDICT="NO REVIEW HAPPENED: $why The Claude fallback's outcome could not be determined, so there is no evidence any review took place."
      echo "::error title=agent-review reviewed nothing::$VERDICT"
      return 1
      ;;
  esac

  # The fallback ran. Its own step outcome carries no information (the action exits 0 on a
  # recorded failure), so the evidence is the transcript — read by the preceding
  # `Verify the Claude fallback actually reviewed` step, whose outcome arrives here.
  if [ "$guard" = "success" ]; then
    VERDICT="Reviewed by the Claude fallback."
    echo "$VERDICT"
    return 0
  fi

  if [ "$guard" = "failure" ]; then
    VERDICT="NO REVIEW HAPPENED: $why The Claude fallback ran but its transcript shows it did not review — see that step's annotations for which signature it was (a gateway rejection at turn 1 is #2161; a refusal to act for a bot actor is the action's own allowed_bots policy, not a credential problem)."
  else
    VERDICT="NO REVIEW HAPPENED: $why The Claude fallback ran, but the transcript guard did not report a verdict (outcome=$guard), so the run cannot be shown to have reviewed anything."
  fi
  echo "::error title=agent-review reviewed nothing::$VERDICT"
  return 1
}

# Best-effort: make the state visible where humans read, not only in the job conclusion.
post_pr_comment() {
  local body="$1"
  [ -n "${PR:-}" ] || return 0
  [ -n "${GH_TOKEN:-}" ] || return 0
  command -v gh >/dev/null 2>&1 || return 0
  printf '%s\n' \
    "⚠️ **Agent review did not review this PR.**" \
    "" \
    "$body" \
    "" \
    "_This job is advisory and not a required check — the PR is not rejected. It is red because no review happened, which must not be reported as a review (#3488)._" \
    > /tmp/agent-review-no-review.md 2>/dev/null || return 0
  gh pr comment "$PR" --body-file /tmp/agent-review-no-review.md >/dev/null 2>&1 \
    || echo "::warning::could not post the no-review comment (the token is read-only on Dependabot and fork PRs)"
}

# ---------------------------------------------------------------------------------------

self_test() {
  local fails=0

  check() { # <label> <expected-rc> <env assignments...>
    local label="$1" want="$2"; shift 2
    local got
    ( unset SCOPE_SKIP PRIMARY_OUTCOME SUBMIT_OUTCOME CLAUDE_OUTCOME FALLBACK_GUARD_OUTCOME \
            HAS_CLAUDE PR GH_TOKEN
      # shellcheck disable=SC2163  # each "$@" element is a literal NAME=value assignment
      export "$@"
      evaluate >/dev/null 2>&1 )
    got=$?
    if [ "$got" -ne "$want" ]; then
      echo "  FAIL: $label expected rc=$want got rc=$got"
      fails=$((fails + 1))
    else
      echo "  ok:   $label (rc=$got)"
    fi
  }

  echo "self-test — each branch is fed the input it must classify:"
  # Passes. A guard that only ever reds is not a signal.
  check "deferred to a human"              0 SCOPE_SKIP=true PRIMARY_OUTCOME=skipped SUBMIT_OUTCOME=skipped CLAUDE_OUTCOME=skipped
  check "primary submitted a verdict"      0 SCOPE_SKIP=false PRIMARY_OUTCOME=success SUBMIT_OUTCOME=success CLAUDE_OUTCOME=skipped
  check "fallback reviewed for real"       0 SCOPE_SKIP=false PRIMARY_OUTCOME=failure SUBMIT_OUTCOME=skipped CLAUDE_OUTCOME=success FALLBACK_GUARD_OUTCOME=success HAS_CLAUDE=true
  # Fails. Each is a state observed in the 120-run sample.
  check "410 primary + dead fallback"      1 SCOPE_SKIP=false PRIMARY_OUTCOME=failure SUBMIT_OUTCOME=skipped CLAUDE_OUTCOME=success FALLBACK_GUARD_OUTCOME=failure HAS_CLAUDE=true
  check "410 primary + fallback SKIPPED"   1 SCOPE_SKIP=false PRIMARY_OUTCOME=failure SUBMIT_OUTCOME=skipped CLAUDE_OUTCOME=skipped HAS_CLAUDE=false
  check "410 primary + guard not reached"  1 SCOPE_SKIP=false PRIMARY_OUTCOME=failure SUBMIT_OUTCOME=skipped CLAUDE_OUTCOME=failure FALLBACK_GUARD_OUTCOME=skipped HAS_CLAUDE=true
  check "primary ok, submit failed"        1 SCOPE_SKIP=false PRIMARY_OUTCOME=success SUBMIT_OUTCOME=failure CLAUDE_OUTCOME=skipped HAS_CLAUDE=true
  check "no environment at all"            1 IGNORED=1

  if [ "$fails" -gt 0 ]; then
    echo "self-test FAILED ($fails case(s))"
    return 1
  fi
  echo "self-test OK — 8 cases."
  return 0
}

# ---------------------------------------------------------------------------------------

if [ "${1:-}" = "--self-test" ]; then
  self_test
  exit $?
fi

evaluate
rc=$?
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  printf '### Agent review outcome\n\n%s\n' "$VERDICT" >> "$GITHUB_STEP_SUMMARY"
fi
if [ "$rc" -ne 0 ]; then
  post_pr_comment "$VERDICT"
  echo
  echo "This job is advisory and NOT a required check, so the PR is not rejected. It is red"
  echo "because nothing reviewed this change — see #3488 for the retired primary reviewer and"
  echo "#2161 for the fallback credential."
fi
exit "$rc"
