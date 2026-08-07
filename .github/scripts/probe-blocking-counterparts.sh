#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# ---------------------------------------------------------------------------------------
# Are the counterpart versions this can-i-deploy verdict names CAPABLE of ever being
# verified?  (issue #3223)
#
# WHY THIS EXISTS
# can-i-deploy blocks with a per-pair line naming the version of a counterpart "currently
# in <environment>":
#
#   There is no verified pact between the latest version of openbank-transaction-service
#   with tag main (a26ad6a9...) and the version of openbank-balance-service currently in
#   sandbox (e88849d6...)
#
# classify-can-i-deploy-block.sh then labels that block, and BOTH labels it can reach for
# this shape carry a self-clearing promise: PENDING_BUILD says "the 3-hourly reconcile
# re-drives it automatically", UNVERIFIED says "normally clears within one reconcile tick".
# Neither is a property of the block — they are hardcoded per verdict, and the classifier
# has no evidence for either, because it deliberately makes no network call.
#
# For a large share of today's blocks the promise is FALSE, and provably so. The version
# named as "currently in sandbox" is frequently a bookkeeping version that carries ZERO
# pacts: record-deployment-on-merge.yml PUTs a version into the broker when the deployed
# sha has none (services-ci is path-scoped, so most shas publish nothing for most services)
# and then records THAT as deployed. Nothing will ever verify a version with no pacts — no
# verification run targets it, and no reconcile tick creates one. Measured 2026-08-06:
# openbank-transaction-service's "currently in sandbox" version was created 2026-07-24 by
# such a PUT (HTTP 201, run 30077670109) and still had 0 pacts and no tags 13 days and
# dozens of reconcile ticks later.
#
# A verdict that tells an operator to wait, when waiting is structurally incapable of
# helping, is worse than no message: it is the reason four deploy-drift issues sat open
# while money-path services fell five patch releases behind.
#
# WHAT THIS DOES
# Reads a can-i-deploy CLI output on stdin, extracts every (counterpart, version) pair the
# verdict names as currently deployed, and asks the broker one question per pair: does that
# version have any pacts?  It reports a STATE for the block as a whole, which
# classify-can-i-deploy-block.sh turns into a class and a message. The classifier stays a
# pure function; the network call lives here, where the self-test can stub it.
#
# Usage:
#   probe-blocking-counterparts.sh < cli-output
#
# Env:
#   PACT_BROKER_URL / PACT_BROKER_USERNAME / PACT_BROKER_PASSWORD  broker credentials
#
# Prints ONE tab-separated line: <state>\t<detail>
#   contentless  at least one named counterpart version carries no pacts at all. Durable:
#                no reconcile tick can clear it. <detail> lists them as <svc>@<sha8>.
#   has-pacts    every named counterpart version has pacts, so the missing piece really is
#                a verification result and waiting may genuinely help.
#   none         the output names no counterpart version — nothing to say about it.
#   unknown      a probe failed (broker unreachable, non-2xx, unparseable body). The caller
#                must NOT read this as either "durable" or "transient".
#
# WHY EVERY FAILURE IS `unknown` AND NOT `has-pacts`
# `has-pacts` is what preserves today's self-clearing promise. Degrading a failed probe to
# it would restore the exact false reassurance this script exists to remove, on no evidence
# — the safe direction here is to admit ignorance, because an unclassifiable block must not
# borrow a transient class's comfort (the rule classify-can-i-deploy-block.sh's own header
# already states for UNKNOWN).
#
# WHY THE SHA COMES FROM THE VERDICT AND NOT FROM THE BROKER
# The question is about the version the GATE actually compared against, which the verdict
# states verbatim. Re-deriving "what is currently deployed" from the broker would answer a
# second, later question that can already disagree with the one that produced the block.
#
# Always exits 0: this reports a fact, it does not gate on one.
set -uo pipefail

# "and the version of <pacticipant> currently in <env> (<40-hex>)" — the shape the pact CLI
# prints for a deployed-version comparison. Older/other phrasings that carry no sha simply
# do not match, and the answer is then `none`, which changes nothing downstream.
PAIR_RE='and the version of (openbank-[a-z0-9][a-z0-9-]*) currently in [a-zA-Z0-9_-]+ \(([0-9a-f]{40})\)'

probe_one() { # <service> <sha> -> prints: contentless | has-pacts | unknown
  local svc="$1" sha="$2" body auth
  # Assembled into ONE variable before the flag: gitleaks' `curl-auth-user` rule matches the
  # SHAPE `-u "$A:$B"` and cannot tell a variable reference from a literal, so the inline form
  # fails the required check with no secret in it.
  auth="${PACT_BROKER_USERNAME:-}:${PACT_BROKER_PASSWORD:-}"
  body="$(curl -sf --max-time 20 -u "$auth" \
    "${PACT_BROKER_URL:-}/pacticipants/${svc}/versions/${sha}" 2>/dev/null)" || { echo unknown; return; }
  local n
  # `pb:pact-versions` is an ARRAY of the pacts published at this version. Absent or empty
  # both mean "no contract evidence at this version". `jq` failing on a non-JSON body is a
  # probe failure, not an answer.
  n="$(printf '%s' "$body" | jq -r '(._links["pb:pact-versions"] // []) | length' 2>/dev/null)" || { echo unknown; return; }
  case "$n" in
    ''|*[!0-9]*) echo unknown ;;
    0)           echo contentless ;;
    *)           echo has-pacts ;;
  esac
}

run() {
  local out pairs seen_any=0 saw_unknown=0 contentless=()
  out="$(cat)"
  # sed -E over grep -oE: we need two capture groups per match, and BSD grep has no -P.
  pairs="$(printf '%s\n' "$out" \
    | sed -nE "s/.*${PAIR_RE}.*/\1 \2/p" | sort -u)"
  if [ -z "$pairs" ]; then
    printf 'none\t%s\n' "the verdict names no counterpart version currently in the environment"
    return
  fi
  while read -r cp sha; do
    [ -n "${cp:-}" ] || continue
    seen_any=1
    case "$(probe_one "$cp" "$sha")" in
      contentless) contentless+=("${cp}@${sha:0:8}") ;;
      unknown)     saw_unknown=1 ;;
    esac
  done <<< "$pairs"
  if [ "${#contentless[@]}" -gt 0 ]; then
    printf 'contentless\t%s\n' "$(IFS=,; echo "${contentless[*]}")"
    return
  fi
  if [ "$saw_unknown" -eq 1 ] || [ "$seen_any" -eq 0 ]; then
    printf 'unknown\t%s\n' "could not probe every counterpart version named in the verdict"
    return
  fi
  printf 'has-pacts\t%s\n' "every counterpart version named in the verdict has pacts published"
}

if [ "${1:-}" = "--self-test" ] || [ "${1:-}" = "--selftest" ]; then
  # The broker has no public ingress (ADR-0056) and PACT_BROKER_URL is blank off main-push,
  # so the real call can never run from a PR — curl is stubbed. What is NOT stubbed is the
  # parsing and the aggregation, which is where every branch of the answer is decided.
  self_tmp="$(mktemp -d)"; trap 'rm -rf "$self_tmp"' EXIT
  cat > "$self_tmp/curl" <<'STUB'
#!/usr/bin/env bash
url="${@: -1}"
case "$url" in
  *openbank-empty-service/versions/*)  echo '{"_links":{"pb:pact-versions":[]}}' ;;
  *openbank-full-service/versions/*)   echo '{"_links":{"pb:pact-versions":[{"name":"a"}]}}' ;;
  *openbank-broken-service/versions/*) echo 'not json at all' ;;
  *)                                   exit 7 ;;
esac
STUB
  chmod +x "$self_tmp/curl"
  me_abs="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  S40_A="$(printf 'a%.0s' $(seq 40))"
  S40_B="$(printf 'b%.0s' $(seq 40))"
  fails=0
  line_for() { printf 'There is no verified pact between the latest version of openbank-demo-service with tag main (%s) and the version of %s currently in sandbox (%s)\n' "$S40_B" "$1" "$2"; }
  case_is() { # <label> <want-state> <stdin>
    local label="$1" want="$2" got
    got="$(PATH="$self_tmp:$PATH" bash "$me_abs" <<< "$3" | cut -f1)"
    if [ "$got" = "$want" ]; then echo "  ok   ${label} → ${got}"
    else echo "  FAIL ${label}: want ${want}, got ${got}"; fails=$((fails + 1)); fi
  }
  echo "probe-blocking-counterparts.sh --self-test"
  case_is "no counterpart line"            none        "Computer says no"
  case_is "counterpart with 0 pacts"       contentless "$(line_for openbank-empty-service "$S40_A")"
  case_is "counterpart with pacts"         has-pacts   "$(line_for openbank-full-service "$S40_A")"
  case_is "unreachable broker"             unknown     "$(line_for openbank-missing-service "$S40_A")"
  case_is "non-JSON body"                  unknown     "$(line_for openbank-broken-service "$S40_A")"
  # contentless DOMINATES a mixed verdict: one unverifiable counterpart is enough to make
  # waiting pointless, so it must not be diluted by a healthy sibling or a failed probe.
  case_is "mixed contentless + healthy"    contentless "$(line_for openbank-full-service "$S40_A"; line_for openbank-empty-service "$S40_A")"
  case_is "mixed contentless + unknown"    contentless "$(line_for openbank-missing-service "$S40_A"; line_for openbank-empty-service "$S40_A")"
  case_is "healthy + unknown is unknown"   unknown     "$(line_for openbank-full-service "$S40_A"; line_for openbank-missing-service "$S40_A")"
  # A short sha, or a phrasing without one, must not be read as a version to probe.
  case_is "short sha is not a version"     none        "and the version of openbank-empty-service currently in sandbox (a5e5d32a)"
  # The detail field must NAME the unverifiable versions — the whole point is that an
  # operator can act on it without opening the broker.
  detail="$(PATH="$self_tmp:$PATH" bash "$me_abs" <<< "$(line_for openbank-empty-service "$S40_A")" | cut -f2)"
  if [ "$detail" = "openbank-empty-service@aaaaaaaa" ]; then echo "  ok   detail names the version"
  else echo "  FAIL detail names the version: got '${detail}'"; fails=$((fails + 1)); fi
  if [ "$fails" -eq 0 ]; then echo "probe-blocking-counterparts.sh self-test: PASS"; exit 0; fi
  echo "probe-blocking-counterparts.sh self-test: ${fails} FAILURE(S)"; exit 1
fi

run
