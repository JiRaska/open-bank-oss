#!/usr/bin/env bash
# Request cancellation of a workflow run, treating GitHub's benign race answers as a notice.
#
#   cancel-workflow-run.sh <repo> <run_id>   POST .../actions/runs/<run_id>/cancel
#   cancel-workflow-run.sh --selftest        known-positive and known-negative cases
#
# Exit 0: cancellation requested (HTTP 202), or the run is not cancellable for a benign
#         reason (HTTP 409 "has not been queued yet" or an already-completed run).
# Exit 1: any other failure (403, 404, 5xx, a 409 with another message, ...).
set -euo pipefail

# Classify the combined stdout+stderr of a failed `gh api` call: prints "benign" or "fatal".
classify_cancel_error() {
  local out="$1"
  if grep -q 'HTTP 409' <<<"$out" &&
    grep -Eqi 'has not been queued yet|already completed|run that is completed' <<<"$out"; then
    echo benign
  else
    echo fatal
  fi
}

selftest() {
  local fails=0 got
  check() {
    got=$(classify_cancel_error "$2")
    if [ "$got" != "$1" ]; then
      echo "FAIL: expected $1, got $got: $2" >&2
      fails=$((fails + 1))
    fi
  }
  check benign '{"message":"Cannot cancel a workflow run that has not been queued yet.","status":"409"}gh: Cannot cancel a workflow run that has not been queued yet. (HTTP 409)'
  check benign '{"message":"Cannot cancel a workflow run that is completed.","status":"409"}gh: Cannot cancel a workflow run that is completed. (HTTP 409)'
  check fatal '{"message":"Resource not accessible by integration","status":"403"}gh: Resource not accessible by integration (HTTP 403)'
  check fatal 'gh: Not Found (HTTP 404)'
  check fatal 'gh: Server Error (HTTP 500)'
  check fatal '{"message":"Conflict","status":"409"}gh: Conflict (HTTP 409)'
  check fatal 'gh: Cannot cancel a workflow run that has not been queued yet. (HTTP 403)'
  if [ "$fails" -ne 0 ]; then
    echo "cancel-workflow-run selftest: $fails failure(s)" >&2
    return 1
  fi
  echo "cancel-workflow-run selftest: ok"
}

if [ "${1:-}" = --selftest ]; then
  selftest
  exit $?
fi
[ $# -eq 2 ] || {
  echo "usage: $0 <repo> <run_id> | --selftest" >&2
  exit 2
}
repo="$1"
run_id="$2"
if out=$(gh api --method POST "repos/${repo}/actions/runs/${run_id}/cancel" 2>&1); then
  exit 0
fi
if [ "$(classify_cancel_error "$out")" = benign ]; then
  echo "::notice title=Run not cancellable::run ${run_id} was not cancellable (benign race): $(tail -n1 <<<"$out")"
  exit 0
fi
echo "$out" >&2
exit 1
