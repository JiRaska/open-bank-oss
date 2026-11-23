#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Turns a resolve-record-deployment-version.sh selection into the version record-deployment-on-merge
# records, or a FAILURE (#16961). A `none` for a service whose pin changed used to be a ::warning::
# and a `continue`, so the run concluded success while the broker kept the PREVIOUS deployed
# version as current — consumers kept being verified against a tree that is no longer running.
#
#   record-deployment-selection.sh <service> <sha> <selection>
#     exact:<v> | equivalent:<v>  -> prints <v>, exit 0
#     none (or anything else)     -> prints ::error:: naming service, sha and reason, exit 1
#   record-deployment-selection.sh --decide-all   (stdin: "<service> <sha> <selection>" per line)
#     exit 1 if any line is not recordable; empty input (no pin changed) exits 0
#   record-deployment-selection.sh --self-test
set -euo pipefail

decide() {
  local svc="$1" sha="$2" sel="$3"
  case "$sel" in
    exact:?*)      printf '%s\n' "${sel#exact:}"; return 0 ;;
    equivalent:?*) printf '%s\n' "${sel#equivalent:}"; return 0 ;;
  esac
  echo "::error::${svc}: pin changed to ${sha} but the resolver returned '${sel}' — no published pact version for ${sha:0:8} and none provably equivalent in every recorded source, so NOTHING was recorded and the broker still names the previous deployment of ${svc} as current (#16961, #3223). Remedy: publish the deployed tree (verify-provider.yml ref=${sha}), then re-run this workflow by workflow_dispatch with services=${svc}."
  return 1
}

decide_all() {
  local svc sha sel failed=0
  while read -r svc sha sel; do
    [ -n "${svc:-}" ] || continue
    decide "$svc" "$sha" "${sel:-}" || failed=1
  done
  return "$failed"
}

self_test() {
  local fails=0 out sha=0123456789abcdef0123456789abcdef01234567
  expect() { # <want-exit> <label> <cmd...>
    local want="$1" label="$2"; shift 2
    local got=0; out="$("$@" 2>&1)" || got=$?
    if [ "$got" -eq "$want" ]; then echo "  ok   $label"; else echo "  FAIL $label (exit $got, want $want): $out"; fails=1; fi
  }
  # must-FAIL: pin changed and resolver returned none
  expect 1 "none for a changed pin fails" decide openbank-customer-edge "$sha" none
  case "$out" in *"::error::openbank-customer-edge"*"$sha"*"resolver returned 'none'"*) ;; *) echo "  FAIL error text lacks service/sha/reason: $out"; fails=1 ;; esac
  expect 1 "empty selection fails" decide openbank-customer-edge "$sha" ""
  expect 1 "none among exact fails the batch" decide_all <<<"openbank-a $sha exact:$sha
openbank-b $sha none"
  # must-PASS
  expect 0 "exact records" decide openbank-ledger-service "$sha" "exact:$sha"
  [ "$out" = "$sha" ] || { echo "  FAIL exact printed '$out'"; fails=1; }
  expect 0 "equivalent records the equivalent version" decide openbank-ledger-service "$sha" "equivalent:abc123"
  [ "$out" = "abc123" ] || { echo "  FAIL equivalent printed '$out'"; fails=1; }
  expect 0 "no pin changed (empty batch) passes" decide_all </dev/null
  [ "$fails" -eq 0 ] && { echo "record-deployment-selection: self-test PASS"; return 0; }
  echo "record-deployment-selection: self-test FAIL"; return 1
}

case "${1:-}" in
  --self-test)  self_test ;;
  --decide-all) decide_all ;;
  *)            [ "$#" -eq 3 ] || { echo "usage: $0 <service> <sha> <selection> | --decide-all | --self-test" >&2; exit 2; }
                decide "$@" ;;
esac
