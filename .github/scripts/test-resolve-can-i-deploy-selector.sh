#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit test for resolve-can-i-deploy-selector.sh (issue #3082, defect 1). Pure bash, no
# network, no Gradle — same shape as test-classify-can-i-deploy-block.sh next door.
#
# The case that matters is #2. Before this selector existed, a service whose pact for the
# deployed commit WAS already in the broker was still asked about as `--latest main`, so a
# green could be inherited from an earlier build while the image being shipped came from a
# commit nobody had verified. That is a false GREEN on a contract gate, and it is silent —
# there is no red anywhere to notice. If someone "simplifies" this script back to always
# returning `--latest main`, case 2 is what goes red.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RESOLVE="${SCRIPT_DIR}/resolve-can-i-deploy-selector.sh"
SHA="0e32873f8c52d37e1b6530e5bd5e94275e5cefae"

fails=0
check() {
  local name="$1" want="$2" present="$3" event="${4:-}"
  local got
  got="$(PACT_VERSION_PRESENT="$present" EVENT_NAME="$event" bash "$RESOLVE" openbank-demo-service "$SHA" | cut -f1)"
  if [ "$got" = "$want" ]; then
    echo "  ok   ${name} → ${got}"
  else
    echo "  FAIL ${name}: want '${want}', got '${got}'"
    fails=$((fails + 1))
  fi
}

echo "resolve-can-i-deploy-selector.sh"

# 1. The common case: path-scoped CI skipped this service on this commit, so the broker has
#    no version for it. Must keep today's behaviour exactly — `--version` would error here.
check "no version for this commit → latest/main" "--latest main" no

# 2. THE POINT OF THE FILE. This commit's version IS published, so ask about it precisely.
#    Returning `--latest main` here is the false-green path: the verdict would be about a
#    different, older version than the image being deployed.
check "version present → ask about THIS commit" "--version ${SHA}" yes

# 3. Broker probe failed. Must fall back, not invent precision — asking `--version` for a
#    version that may not exist would turn an unreachable broker into a fleet-wide block.
check "probe inconclusive → latest/main" "--latest main" unknown

# 4. Unset variable behaves as `unknown`, not as `yes`. A caller that forgets to export the
#    probe must not silently get the precise-but-possibly-wrong question.
got_unset="$(unset PACT_VERSION_PRESENT; bash "$RESOLVE" openbank-demo-service "$SHA" | cut -f1)"
if [ "$got_unset" = "--latest main" ]; then
  echo "  ok   unset probe → latest/main"
else
  echo "  FAIL unset probe: want '--latest main', got '${got_unset}'"
  fails=$((fails + 1))
fi

# 5. Every branch must emit a non-empty human reason — the workflow prints it, and a blank
#    reason is how a decision becomes unexplainable after the fact.
for p in yes no unknown; do
  reason="$(PACT_VERSION_PRESENT="$p" bash "$RESOLVE" openbank-demo-service "$SHA" | cut -f2)"
  if [ -n "$reason" ]; then
    echo "  ok   reason present for present=${p}"
  else
    echo "  FAIL reason missing for present=${p}"
    fails=$((fails + 1))
  fi
done

# 6. The selector must be usable as literal CLI args without re-splitting by the caller.
#    Two words for `--latest main`, two for `--version <sha>` — a caller doing `${SEL[@]}`
#    after `read -ra` gets exactly what the broker CLI expects.
sel="$(PACT_VERSION_PRESENT=yes bash "$RESOLVE" openbank-demo-service "$SHA" | cut -f1)"
read -ra sel_arr <<< "$sel"
if [ "${#sel_arr[@]}" -eq 2 ] && [ "${sel_arr[0]}" = "--version" ] && [ "${sel_arr[1]}" = "$SHA" ]; then
  echo "  ok   selector splits into exactly the two CLI args"
else
  echo "  FAIL selector split: got ${#sel_arr[@]} arg(s): ${sel_arr[*]}"
  fails=$((fails + 1))
fi

# 7. Missing arguments are a usage error, not a silent default. Defaulting here would send
#    the gate a question about an empty version.
if bash "$RESOLVE" openbank-demo-service >/dev/null 2>&1; then
  echo "  FAIL missing-sha-arg: expected non-zero exit"
  fails=$((fails + 1))
else
  echo "  ok   missing-sha-arg → non-zero exit"
fi


# ── issue #3318: a manual dispatch of a sha with no pact version ────────────────────────
# 5. THE POINT OF THIS ADDITION. auto-deploy builds sandbox-${GITHUB_SHA::8} from the CURRENT
#    main tip on a dispatch, a commit services-ci never built for this service — so no version
#    can exist for it. Falling back to `--latest main` answers about a DIFFERENT commit than
#    the image being pinned. Measured on openbank-fx-service: five dispatches, five verdicts
#    about an unrelated commit (#3306). Refuse instead.
check "dispatch + no version → REFUSE" "REFUSE" no workflow_dispatch

# 6. REGRESSION GUARD. The push path must be untouched: most of the fleet legitimately has no
#    version on most commits, and refusing there would block every deploy. If someone widens
#    the refusal to all events, this is what goes red.
check "push + no version → still latest/main (unchanged)" "--latest main" no push

# 7. Same guard for the implicit case: no EVENT_NAME exported behaves as a push, so a caller
#    that forgets to pass it keeps today's behaviour rather than blocking the fleet.
check "no EVENT_NAME + no version → latest/main" "--latest main" no

# 8. A dispatch is only refused when the version is genuinely absent. With the version present
#    the precise question is still the right one, dispatch or not. Co-deploy reuses this exact
#    selector for every member: `--latest main` would instead ask about a later, unrelated image.
check "dispatch/co-deploy + version present → ask about THIS commit" "--version ${SHA}" yes workflow_dispatch

# 9. A dispatch with an inconclusive probe must NOT be refused: an unreachable broker is not
#    evidence that the version is missing, and turning a probe outage into a hard stop would
#    make the gate fail closed on infrastructure rather than on contracts.
check "dispatch + probe inconclusive → latest/main, not REFUSE" "--latest main" unknown workflow_dispatch

# The distinction the REFUSE branch got wrong: a service the broker has never heard of has no
# contracts to verify, so refusing makes its FIRST deploy impossible. Measured on
# openbank-delegation-service, whose first dispatch was blocked by exactly this.
check "dispatch + pacticipant absent → latest/main, NOT REFUSE" "--latest main" absent workflow_dispatch
check "push + pacticipant absent → latest/main" "--latest main" absent push
# And the #3318 case must still refuse — 'absent' must not have widened it.
check "dispatch + version missing but pacticipant KNOWN → still REFUSE" "REFUSE" no workflow_dispatch

# ── issue #3432: the equivalence answer ─────────────────────────────────────────────────
# 10. THE POINT OF THIS ADDITION. A dispatch whose sha has no version, where the probe PROVED
#     from git that the commit which does have one is byte-identical in every build input of
#     this service, must ask about that version BY NUMBER — not `--latest main`, which would be
#     the same verdict borrowed without an argument, and not REFUSE, which is what made the
#     whole reconcile path unable to deploy anything (54 of 54 refused, run 30761923908).
EQ_SHA="1111111111111111111111111111111111111111"
check "dispatch + proven-equivalent version → ask about THAT version" \
  "--version ${EQ_SHA}" "equivalent:${EQ_SHA}" workflow_dispatch
check "schedule + proven-equivalent version → ask about THAT version, not latest/main" \
  "--version ${EQ_SHA}" "equivalent:${EQ_SHA}" schedule

# 11. THE GUARD THAT MATTERS MORE. The equivalence must never be reachable by accident: only
#     the probe can produce this value, and it produces it only after a clean exit 0 from
#     pact-version-tree-equivalent.sh. So a plain `no` on a dispatch — the same service, the
#     same sha, the equivalence NOT proved — must still REFUSE. If someone ever widens the new
#     branch into a fallback, this goes red.
check "dispatch + no version and no proof → still REFUSE (#3318 intact)" "REFUSE" no workflow_dispatch

# 12. The selector must not invent a version out of a malformed value. `equivalent` with no sha
#     is not a proof; it must not become `--version ` with an empty argument.
got_eq="$(PACT_VERSION_PRESENT="equivalent:" EVENT_NAME=workflow_dispatch bash "$RESOLVE" openbank-demo-service "$SHA" | cut -f1)"
if [ "$got_eq" = "--version" ] || [ "$got_eq" = "--version " ]; then
  echo "  FAIL malformed equivalent: produced a bare '--version' with no argument"
  fails=$((fails + 1))
else
  echo "  ok   malformed 'equivalent:' does not produce a bare --version (got '${got_eq}')"
fi

if [ "$fails" -ne 0 ]; then
  echo "FAILED: ${fails} case(s)"
  exit 1
fi
echo "all cases behaved as declared"
