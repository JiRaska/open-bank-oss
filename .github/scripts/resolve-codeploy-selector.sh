#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# WHICH Pact version the ATOMIC CO-DEPLOY matrix (#1985) may ask about — issue #5993.
#
# The co-deploy branch of can-i-deploy-gate.sh asks the broker ONE question about a whole
# set of services at once: "are these exact build versions mutually deployable?". That
# question is only meaningful if every pacticipant in it is pinned to the version actually
# being deployed. The gate's own comment has said so since #1985 — "Do not use `--latest
# main` here: a later, unrelated main build can move that tag between selection and the
# question, turning this into a verdict about a different image" — and the code could still
# produce exactly that, because it delegated the choice to resolve-can-i-deploy-selector.sh
# and took whatever came back.
#
# That selector is right for the PER-SERVICE path, where `--latest main` is a deliberate,
# documented fallback (ADR-0092: a service path-scoped CI skipped on this commit has no
# version, and `--version` would error). Two of its branches emit it:
#
#   absent   the broker has never heard of this pacticipant — no contracts either way
#   unknown  the broker probe was inconclusive (non-2xx / unreachable / malformed answer)
#
# On the per-service path both are harmless-to-benign. In the co-deploy matrix they are not
# the same defect at all, and only one of them is loud:
#
#   * `unknown` FAILS OPEN. The broker answers about whatever version currently carries the
#     moving `main` tag for that pacticipant — a later, unrelated build — and can return
#     GREEN for a pair that is not the pair being deployed. Nothing is red anywhere; the
#     deploy proceeds on a verdict about a different artifact. This is the #5993 defect: a
#     control that reports healthy because it structurally cannot report otherwise.
#   * `absent` fails closed, confusingly: `can-i-deploy --pacticipant <unknown> --latest main`
#     errors, and the whole set reads as "not mutually deployable" — a contract break that
#     does not exist. The per-service path already has the right answer for this case (a
#     service with NO contracts cannot break any consumer/provider expectation, so it is
#     trivially deployable, ADR-0092); the co-deploy path had no equivalent, so it is given
#     one here as SKIP — dropped from the matrix, not silently pinned to a moving tag.
#
# So this script is the co-deploy-specific NARROWING of the shared selector, not a second
# copy of it: it delegates the decision and then refuses anything that is not an exact
# version. Acceptance from #5993, verbatim: "resolve each participant to its exact selected
# Pact version, or to a proven tree-equivalent version, and refuse when that cannot be
# established."
#
# USAGE
#   PACT_VERSION_PRESENT=<probe-pact-version.sh output> \
#     resolve-codeploy-selector.sh <service> <deploy-sha>
#
# OUTPUT (one line, tab-separated: <decision>\t<human reason>)
#   --version <sha>   pin this pacticipant to that exact version; put it in the matrix
#   SKIP              not a pacticipant at all — leave it OUT of the matrix, still deployable
#   REFUSE            no exact version can be established — the caller must deploy NOTHING
#
# Exit status is 0 in all three cases: the decision is the stdout line, so a caller cannot
# mistake "refused" for "the script crashed".
#
# Self-test: `resolve-codeploy-selector.sh --self-test` drives every branch, including the
# negative controls this file exists for (unknown/no/garbage must REFUSE, never emit a
# moving tag). Wired into .github/gates/gates.yaml.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SHARED="${HERE}/resolve-can-i-deploy-selector.sh"

emit() { printf '%s\t%s\n' "$1" "$2"; }

resolve_one() { # <svc> <sha>; reads PACT_VERSION_PRESENT
  local svc="$1" sha="$2" present="${PACT_VERSION_PRESENT:-unknown}" sel_line sel why
  if [ "$present" = "absent" ]; then
    emit "SKIP" \
      "the broker has never heard of ${svc} — it publishes no consumer pact and verifies no provider pact, so there is no contract for the matrix to check; it is left OUT of the co-deploy question rather than pinned to the moving main tag (ADR-0092)"
    return 0
  fi
  sel_line="$(PACT_VERSION_PRESENT="$present" EVENT_NAME=workflow_dispatch \
    bash "$SHARED" "$svc" "$sha")"
  sel="$(printf '%s' "$sel_line" | cut -f1)"
  why="$(printf '%s' "$sel_line" | cut -f2-)"
  case "$sel" in
    "--version "*)
      emit "$sel" "$why"
      ;;
    *)
      # Everything else — REFUSE from the shared selector, and every path that would have
      # produced `--latest main`. A co-deploy matrix asked at a moving tag is a verdict
      # about artifacts that are not being deployed, in either direction (#5993).
      emit "REFUSE" \
        "no exact Pact version can be established for ${svc} at ${sha} (probe said '${present}'; the shared selector offered '${sel}') — a co-deploy matrix must be asked at the versions being deployed, and a moving 'latest main' tag can answer about a later, unrelated build (#5993, #1985)"
      ;;
  esac
}

if [ "${1:-}" = "--self-test" ] || [ "${1:-}" = "--selftest" ]; then
  SHA="0e32873f8c52d37e1b6530e5bd5e94275e5cefae"
  OTHER="1111111111111111111111111111111111111111"
  fails=0
  # Every assertion below is a SUBJECT: one probe answer the resolver can be handed. The
  # count is printed at the end and floored in .github/gates/gates.yaml (#4339) — a suite
  # that quietly stops driving cases would otherwise pass by examining nothing.
  subjects=0
  check() { # <label> <want-decision> <present>
    local label="$1" want="$2" present="$3" got
    subjects=$((subjects + 1))
    got="$(PACT_VERSION_PRESENT="$present" resolve_one openbank-demo-service "$SHA" | cut -f1)"
    if [ "$got" = "$want" ]; then echo "  ok   ${label} -> ${got}"
    else echo "  FAIL ${label}: want '${want}', got '${got}'"; fails=$((fails + 1)); fi
  }
  echo "resolve-codeploy-selector.sh (#5993)"
  # THE POSITIVE CONTROL — an exact version for the deployed sha is what the matrix wants.
  check "version present -> pin this commit"            "--version ${SHA}"   yes
  # ...and the #3432 equivalence, which is an exact version number too.
  check "tree-equivalent version -> pin that version"   "--version ${OTHER}" "equivalent:${OTHER}"
  # THE NEGATIVE CONTROLS. Each of these produced, or could produce, a matrix asked at a
  # moving tag before #5993. None of them may ever emit a selector again.
  check "broker probe inconclusive -> REFUSE"           REFUSE               unknown
  check "no version for this sha -> REFUSE"             REFUSE               no
  check "unparseable probe answer -> REFUSE"            REFUSE               "not-a-probe-answer"
  check "equivalence sha is not 40-hex -> REFUSE"       REFUSE               "equivalent:HEAD"
  # A pacticipant the broker does not know has no contracts to break — it leaves the matrix.
  check "unknown pacticipant -> SKIP"                   SKIP                 absent
  # Unset must behave as the worst case, never as 'yes'.
  subjects=$((subjects + 1))
  got_unset="$(unset PACT_VERSION_PRESENT; resolve_one openbank-demo-service "$SHA" | cut -f1)"
  if [ "$got_unset" = "REFUSE" ]; then echo "  ok   unset probe -> REFUSE"
  else echo "  FAIL unset probe: want 'REFUSE', got '${got_unset}'"; fails=$((fails + 1)); fi
  # NOTHING may ever come back as a moving tag, whatever the probe said. This is the
  # assertion that fails if someone delegates straight to the shared selector again.
  for p in yes no unknown absent "equivalent:${OTHER}" "equivalent:HEAD" garbage; do
    subjects=$((subjects + 1))
    line="$(PACT_VERSION_PRESENT="$p" resolve_one openbank-demo-service "$SHA" | cut -f1)"
    if [ "$line" = "--latest main" ]; then
      echo "  FAIL present=${p} emitted a MOVING TAG: ${line}"; fails=$((fails + 1))
    else
      echo "  ok   present=${p} did not emit a moving tag"
    fi
  done
  # Every branch must carry a non-empty reason — the gate prints it, and a blank reason is
  # how a refused deploy becomes unexplainable after the fact.
  for p in yes no unknown absent "equivalent:${OTHER}"; do
    subjects=$((subjects + 1))
    reason="$(PACT_VERSION_PRESENT="$p" resolve_one openbank-demo-service "$SHA" | cut -f2-)"
    if [ -n "$reason" ]; then echo "  ok   reason present for present=${p}"
    else echo "  FAIL reason missing for present=${p}"; fails=$((fails + 1)); fi
  done
  # A selector must be usable as literal CLI args without the caller re-splitting it.
  subjects=$((subjects + 1))
  read -ra sel_arr <<< "$(PACT_VERSION_PRESENT=yes resolve_one openbank-demo-service "$SHA" | cut -f1)"
  if [ "${#sel_arr[@]}" -eq 2 ] && [ "${sel_arr[0]}" = "--version" ] && [ "${sel_arr[1]}" = "$SHA" ]; then
    echo "  ok   selector splits into exactly the two CLI args"
  else
    echo "  FAIL selector split: got ${#sel_arr[@]} arg(s): ${sel_arr[*]}"; fails=$((fails + 1))
  fi
  echo "SUBJECTS=${subjects}  # probe answers driven through the resolver"
  if [ "$fails" -ne 0 ]; then echo "FAILED: ${fails} case(s)"; exit 1; fi
  echo "PASS: the co-deploy matrix is pinned to exact versions or refused — never asked at a moving tag"
  exit 0
fi

SVC="${1:-}"
SHA="${2:-}"
if [ -z "$SVC" ] || [ -z "$SHA" ]; then
  echo "usage: PACT_VERSION_PRESENT=<probe output> $0 <service> <sha>" >&2
  echo "       $0 --self-test" >&2
  exit 2
fi
resolve_one "$SVC" "$SHA"
