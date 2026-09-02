#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit test for classify-can-i-deploy-block.sh (issue #2549). Pure bash, no network, no
# Gradle — runs on every PR touching either file (ci.yml / rules.yaml: deploy_reconcile).
#
# Every case below was run against the classifier BEFORE the classifier was written the way
# it is now: with the REGRESSION check ordered AFTER the 404 probe (the obvious ordering),
# case 5 returns PENDING_BUILD and this test goes red. That ordering is precisely the bug
# that would silence a real contract regression as "still building", so the test that
# catches it is the point of the file.
#
# Cases 8-13 (#3454) are the second load-bearing group. Case 13 is a DRIFT test rather than
# a classification one: it drives resolve-can-i-deploy-selector.sh over the same input
# cross-product and asserts NOT_ASKED fires on exactly the inputs where that script REFUSEs.
# Nothing else forces the two files to agree, and a REFUSE with no matching class is how
# every refusal came to print as "[UNKNOWN] — no classification recorded".
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSIFY="${SCRIPT_DIR}/classify-can-i-deploy-block.sh"
RESOLVE="${SCRIPT_DIR}/resolve-can-i-deploy-selector.sh"

fails=0
check() {
  local name="$1" want="$2" present="$3" out="$4" event="${5:-}"
  local got
  # COUNTERPART_STATE is pinned to `none` here so a leaked environment variable cannot
  # decide a case; the #3223 cases below set it explicitly via check_cp.
  got="$(PACT_VERSION_PRESENT="$present" EVENT_NAME="$event" COUNTERPART_STATE=none \
    bash "$CLASSIFY" openbank-demo-service <<< "$out" | cut -f1)"
  if [ "$got" = "$want" ]; then
    echo "  ok   ${name} → ${got}"
  else
    echo "  FAIL ${name}: want ${want}, got ${got}"
    fails=$((fails + 1))
  fi
}

# Representative pact-broker CLI output fragments.
NO_VERIFIED_PACT="Computer says no ¯\\_(ツ)_/¯

There is no verified pact between version abc123 of openbank-demo-service and the version of openbank-ledger-service currently deployed to sandbox"

VERIFICATION_FAILED="Computer says no ¯\\_(ツ)_/¯

CONSUMER            | C.VERSION | PROVIDER           | P.VERSION | SUCCESS?
openbank-demo-servi | abc123    | openbank-ledger-se | def456    | false

The verification for the pact between openbank-demo-service and openbank-ledger-service failed"

echo "classify-can-i-deploy-block.sh"

# 1. The #2549 case: a fleet sweep's build has not published anything for this sha yet.
check "404 on the version + generic block" PENDING_BUILD no "$NO_VERIFIED_PACT"

# 2. Version published, counterpart simply has not verified it yet (minutes, self-clearing).
check "version present + unverified pair" UNVERIFIED yes "$NO_VERIFIED_PACT"

# 3. A real regression with the version present — the class that never self-clears.
check "version present + failed verification" REGRESSION yes "$VERIFICATION_FAILED"

# 4. Broker probe itself failed: must NOT borrow PENDING_BUILD's reassurance.
check "unprobeable broker" UNKNOWN unknown "$NO_VERIFIED_PACT"

# 5. THE ORDERING TEST. `--latest main` can resolve to an OLDER version carrying a failed
#    verification while the sha under test has published nothing (404). A classifier that
#    checks the 404 first labels this PENDING_BUILD and silences a live regression forever.
check "404 on the sha BUT latest-main verification failed" REGRESSION no "$VERIFICATION_FAILED"

# 6. Version present, blocked, but no recognised explanation — must not be guessed at.
check "version present + unrecognised output" UNKNOWN yes "Computer says no ¯\\_(ツ)_/¯"

# `absent` must NOT read as a probe failure: the probe worked, the pacticipant does not exist.
# Different remedy — a contract, not a retry — so it needs its own label, and UNKNOWN's text
# ("could not probe the broker") would describe an outage that never happened.
check "pacticipant absent" NO_CONTRACTS absent "no versions for openbank-demo-service"

# ── issue #3454: the REFUSE record carried no class, so every refusal read as UNKNOWN ────
# 8. THE POINT OF THIS ADDITION. On (no version + workflow_dispatch) the selector REFUSEs,
#    so can-i-deploy never runs and there is no verdict. Measured on run 30765380309, a
#    fleet dispatch of e4821ef6: 54 of 54 services refused and all 54 printed
#    "[UNKNOWN] — no classification recorded for this service (issue #1420)".
check "dispatch + no version → NOT_ASKED" NOT_ASKED no "" workflow_dispatch

# 9. THE LABEL THIS MUST NOT BE. PENDING_BUILD promises "the 3-hourly reconcile re-drives it
#    automatically". services-ci is path-scoped, so a dispatched sha that did not touch this
#    service NEVER gains a pact version and that promise is false. If someone "simplifies"
#    NOT_ASKED away into PENDING_BUILD, this is what goes red.
if [ "$(PACT_VERSION_PRESENT=no EVENT_NAME=workflow_dispatch bash "$CLASSIFY" openbank-demo-service </dev/null | cut -f1)" = "PENDING_BUILD" ]; then
  echo "  FAIL dispatch must not borrow PENDING_BUILD's self-clearing promise"
  fails=$((fails + 1))
else
  echo "  ok   dispatch is not labelled PENDING_BUILD"
fi

# 10. REGRESSION GUARD, the direction that would break the fleet. The push path is where
#     "no version for this commit" really does mean "the build has not finished", and that
#     is most services on most commits. Widening NOT_ASKED to all events relabels every
#     ordinary push block and silences the reconcile story. This is case 1 with the event
#     made explicit.
check "push + no version → still PENDING_BUILD" PENDING_BUILD no "$NO_VERIFIED_PACT" push
check "no EVENT_NAME + no version → still PENDING_BUILD" PENDING_BUILD no "$NO_VERIFIED_PACT"

# 11. NOT_ASKED is narrow. A dispatch is only "never asked" when the version is genuinely
#     missing AND the pacticipant is known; every other probe result keeps its own class, or
#     the label would spread over blocks the gate DID answer.
check "dispatch + version present + unverified → UNVERIFIED" UNVERIFIED yes "$NO_VERIFIED_PACT" workflow_dispatch
check "dispatch + probe inconclusive → UNKNOWN" UNKNOWN unknown "$NO_VERIFIED_PACT" workflow_dispatch
check "dispatch + pacticipant absent → NO_CONTRACTS" NO_CONTRACTS absent "" workflow_dispatch

# 12. Ordering: NOT_ASKED is checked BEFORE the regression rule, unlike every other class.
#     On these inputs can-i-deploy was never invoked, so text on stdin is not a verdict and
#     reporting REGRESSION from it would name a verification failure the gate never observed.
#     This also makes the equivalence in case 13 hold for ANY stdin, not just empty stdin.
check "dispatch + no version + regression-looking stdin → still NOT_ASKED" NOT_ASKED no "$VERIFICATION_FAILED" workflow_dispatch

# 13. THE DRIFT TEST. NOT_ASKED must fire on exactly the inputs where the selector REFUSEs —
#     nothing structural forces that, they are two files. Assert the equivalence over the
#     whole (probe × event) cross-product, so changing either condition alone goes red.
SHA_FIXTURE="0e32873f8c52d37e1b6530e5bd5e94275e5cefae"
for p in yes no absent unknown "equivalent:${SHA_FIXTURE}"; do
  for e in push workflow_dispatch schedule; do
    sel="$(PACT_VERSION_PRESENT="$p" EVENT_NAME="$e" bash "$RESOLVE" openbank-demo-service "$SHA_FIXTURE" | cut -f1)"
    cls="$(PACT_VERSION_PRESENT="$p" EVENT_NAME="$e" bash "$CLASSIFY" openbank-demo-service </dev/null | cut -f1)"
    if [ "$sel" = "REFUSE" ] && [ "$cls" != "NOT_ASKED" ]; then
      echo "  FAIL (${p},${e}): selector REFUSEd but classifier said ${cls}, not NOT_ASKED"
      fails=$((fails + 1))
    elif [ "$sel" != "REFUSE" ] && [ "$cls" = "NOT_ASKED" ]; then
      echo "  FAIL (${p},${e}): classifier said NOT_ASKED but the selector chose '${sel}' — the gate WAS asked"
      fails=$((fails + 1))
    else
      echo "  ok   (${p},${e}) selector/classifier agree"
    fi
  done
done

# 14-17 (#3223). The counterpart probe decides DURABILITY, and it must beat both classes
#     that carry a self-clearing promise. The measured case is the one that matters: on run
#     31080511057 every blocked service had PACT_VERSION_PRESENT=no, so all six reported
#     PENDING_BUILD and promised a reconcile, while all seven counterpart versions the
#     verdicts named carried zero pacts. Case 14 is that exact input.
check_cp() { # <name> <want> <present> <cp-state> [event]
  local name="$1" want="$2" present="$3" cp="$4" event="${5:-}" got
  got="$(PACT_VERSION_PRESENT="$present" EVENT_NAME="$event" COUNTERPART_STATE="$cp" \
    COUNTERPART_DETAIL="openbank-ledger-service@a5e5d32a" \
    bash "$CLASSIFY" openbank-demo-service <<< "$NO_VERIFIED_PACT" | cut -f1)"
  if [ "$got" = "$want" ]; then
    echo "  ok   ${name} → ${got}"
  else
    echo "  FAIL ${name}: want ${want}, got ${got}"
    fails=$((fails + 1))
  fi
}
check_cp "contentless counterpart beats PENDING_BUILD" UNVERIFIABLE no contentless
check_cp "contentless counterpart beats UNVERIFIED"    UNVERIFIABLE yes contentless
check_cp "healthy counterparts keep PENDING_BUILD"     PENDING_BUILD no has-pacts
check_cp "healthy counterparts keep UNVERIFIED"        UNVERIFIED yes has-pacts
check_cp "unprobed counterparts keep the class"        PENDING_BUILD no unknown
# A REGRESSION is an observed verification FAILURE and outranks everything: relabelling it
# UNVERIFIABLE would hide a real broken contract behind a bookkeeping explanation.
got_reg="$(PACT_VERSION_PRESENT=yes COUNTERPART_STATE=contentless bash "$CLASSIFY" \
  openbank-demo-service <<< "$VERIFICATION_FAILED" | cut -f1)"
if [ "$got_reg" = "REGRESSION" ]; then
  echo "  ok   regression outranks contentless → REGRESSION"
else
  echo "  FAIL regression outranks contentless: got ${got_reg}"
  fails=$((fails + 1))
fi
# The UNVERIFIABLE message must NAME the versions and must not promise a reconcile.
msg_uv="$(PACT_VERSION_PRESENT=no COUNTERPART_STATE=contentless \
  COUNTERPART_DETAIL="openbank-ledger-service@a5e5d32a" bash "$CLASSIFY" \
  openbank-demo-service <<< "$NO_VERIFIED_PACT" | cut -f2-)"
case "$msg_uv" in
  *"openbank-ledger-service@a5e5d32a"*) echo "  ok   UNVERIFIABLE names the version" ;;
  *) echo "  FAIL UNVERIFIABLE does not name the version: ${msg_uv}"; fails=$((fails + 1)) ;;
esac
if grep -qE 'clears within one reconcile|re-drives it automatically' <<< "$msg_uv"; then
  echo "  FAIL UNVERIFIABLE repeats a self-clearing promise: ${msg_uv}"
  fails=$((fails + 1))
else
  echo "  ok   UNVERIFIABLE makes no self-clearing promise"
fi
# An unprovable promise must be MARKED unprovable, not silently repeated.
msg_unk="$(PACT_VERSION_PRESENT=no COUNTERPART_STATE=unknown bash "$CLASSIFY" \
  openbank-demo-service <<< "$NO_VERIFIED_PACT" | cut -f2-)"
case "$msg_unk" in
  *unproven*) echo "  ok   unprobed counterparts qualify the promise" ;;
  *) echo "  FAIL unprobed counterparts do not qualify the promise: ${msg_unk}"; fails=$((fails + 1)) ;;
esac

# ── issue #6568: provider-live is a THIRD counterpart state, not a flavour of contentless ──
# The six services blocked for 40 hours on run 32599859753 all reached UNVERIFIABLE through
# COUNTERPART_STATE=contentless on a counterpart that was demonstrably verifiable. These cases
# pin the new state's class, its precedence, and — the load-bearing part — that its message
# carries a runnable remedy rather than the advice to redeploy a healthy provider.
PL_DETAIL="openbank-product-catalog@af04613a74c7d2cb27baf1b681448ebd3e3c7c33"
check_pl() { # <name> <want> <present> [event]
  local name="$1" want="$2" present="$3" event="${4:-}" got
  got="$(PACT_VERSION_PRESENT="$present" EVENT_NAME="$event" COUNTERPART_STATE=provider-live \
    COUNTERPART_DETAIL="$PL_DETAIL" bash "$CLASSIFY" openbank-demo-service <<< "$NO_VERIFIED_PACT" | cut -f1)"
  if [ "$got" = "$want" ]; then echo "  ok   ${name} → ${got}"
  else echo "  FAIL ${name}: want ${want}, got ${got}"; fails=$((fails + 1)); fi
}
check_pl "provider-live beats PENDING_BUILD" PROVIDER_UNVERIFIED no
check_pl "provider-live beats UNVERIFIED"    PROVIDER_UNVERIFIED yes
# It must NOT be UNVERIFIABLE — that is the exact mislabel #6568 is about, and the one a
# "simplification" back to a single contentless branch would reintroduce.
got_pl="$(PACT_VERSION_PRESENT=no COUNTERPART_STATE=provider-live COUNTERPART_DETAIL="$PL_DETAIL" \
  bash "$CLASSIFY" openbank-demo-service <<< "$NO_VERIFIED_PACT" | cut -f1)"
if [ "$got_pl" = "UNVERIFIABLE" ]; then
  echo "  FAIL provider-live must not be labelled UNVERIFIABLE (#6568)"; fails=$((fails + 1))
else
  echo "  ok   provider-live is not UNVERIFIABLE"
fi
# A REGRESSION still outranks it: an observed verification FAILURE is not a missing one.
got_plr="$(PACT_VERSION_PRESENT=yes COUNTERPART_STATE=provider-live COUNTERPART_DETAIL="$PL_DETAIL" \
  bash "$CLASSIFY" openbank-demo-service <<< "$VERIFICATION_FAILED" | cut -f1)"
if [ "$got_plr" = "REGRESSION" ]; then echo "  ok   regression outranks provider-live → REGRESSION"
else echo "  FAIL regression outranks provider-live: got ${got_plr}"; fails=$((fails + 1)); fi
# The message is the whole point: it must name a runnable dispatch at the counterpart's exact
# deployed sha, and must not repeat either self-clearing promise.
msg_pl="$(PACT_VERSION_PRESENT=no COUNTERPART_STATE=provider-live COUNTERPART_DETAIL="$PL_DETAIL" \
  bash "$CLASSIFY" openbank-demo-service <<< "$NO_VERIFIED_PACT" | cut -f2-)"
case "$msg_pl" in
  *"verify-provider.yml -f service=openbank-product-catalog -f ref=af04613a74c7d2cb27baf1b681448ebd3e3c7c33"*)
    echo "  ok   PROVIDER_UNVERIFIED names a runnable remedy at the exact sha" ;;
  *) echo "  FAIL PROVIDER_UNVERIFIED remedy is not runnable: ${msg_pl}"; fails=$((fails + 1)) ;;
esac
if grep -qE 'clears within one reconcile|re-drives it automatically' <<< "$msg_pl"; then
  echo "  FAIL PROVIDER_UNVERIFIED repeats a self-clearing promise: ${msg_pl}"; fails=$((fails + 1))
else
  echo "  ok   PROVIDER_UNVERIFIED makes no self-clearing promise"
fi
# ...and it must not repeat UNVERIFIABLE's remedy, which is the wrong action for a healthy
# provider and is what kept #6568 open.
if grep -q 'deployed at a version that actually published pacts' <<< "$msg_pl"; then
  echo "  FAIL PROVIDER_UNVERIFIED repeats UNVERIFIABLE's redeploy advice: ${msg_pl}"; fails=$((fails + 1))
else
  echo "  ok   PROVIDER_UNVERIFIED does not advise redeploying a healthy provider"
fi

# 7. Missing argument is a usage error, not a silent classification.
if PACT_VERSION_PRESENT=no bash "$CLASSIFY" </dev/null >/dev/null 2>&1; then
  echo "  FAIL missing-service-arg: expected non-zero exit"
  fails=$((fails + 1))
else
  echo "  ok   missing-service-arg → non-zero exit"
fi

if [ "$fails" -ne 0 ]; then
  echo "FAILED: ${fails} case(s)"
  exit 1
fi
echo "PASS"
