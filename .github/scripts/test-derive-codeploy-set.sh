#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit test for derive-codeploy-set.py (issue #1985). Pure bash, no network, no Gradle.
#
# Every case is driven against output the pact-broker CLI actually prints, including the
# two shapes that must NOT contribute an edge: the "Computer says no" banner (names nobody)
# and the CONSUMER|PROVIDER table, whose service names are TRUNCATED to the column width.
# A parser that reads the table produces `openbank-transaction-servi` — a service that does
# not exist — and would name a co-deploy set nobody can dispatch. Case 5 is that test.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DERIVE="${SCRIPT_DIR}/derive-codeploy-set.py"

fails=0
check() {
  local name="$1" want="$2" changed="$3" stdin="$4"
  local got
  got="$(python3 "$DERIVE" $changed <<< "$stdin")"
  if [ "$got" = "$want" ]; then
    echo "  ok   ${name}"
  else
    echo "  FAIL ${name}"
    echo "       want: $(printf '%q' "$want")"
    echo "       got:  $(printf '%q' "$got")"
    fails=$((fails + 1))
  fi
}

TAB=$'\t'

echo "derive-codeploy-set.py"

# 1. The #1985 case: two blocked services each naming the other. Neither can converge
#    alone; one deploy round can never fix it. This is the set the three manual co-deploys
#    (#1979, #2057, #2059) worked out by hand from the broker matrix.
check "mutual block => one co-deploy set" \
  "CODEPLOY${TAB}openbank-sepa-payment openbank-transaction-service" \
  "openbank-sepa-payment openbank-transaction-service" \
'===SERVICE openbank-sepa-payment
Computer says no ¯\_(ツ)_/¯

There is no verified pact between version abc123 of openbank-sepa-payment and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service
There is no verified pact between version def456 of openbank-transaction-service and the version of openbank-sepa-payment currently deployed to sandbox'

# 2. A single blocked service is NOT a co-deploy set — the per-service gate is the right
#    tool and the reconcile tick already re-drives it. Reporting a "set" of one would turn
#    every ordinary transient block into a co-deploy invitation.
check "lone blocked service => no set" \
  "EXTERNAL${TAB}openbank-sepa-payment${TAB}openbank-ledger-service" \
  "openbank-sepa-payment" \
'===SERVICE openbank-sepa-payment
There is no verified pact between version abc123 of openbank-sepa-payment and the version of openbank-ledger-service currently deployed to sandbox'

# 3. Transitivity: fraud <-> transaction <-> sepa-payment is ONE component, not two pairs.
#    Deploying either pair leaves the third stranded — exactly the campaign that stalled at
#    2/9 in the issue thread.
check "transitive chain => one component of three" \
  "CODEPLOY${TAB}openbank-fraud-service openbank-sepa-payment openbank-transaction-service" \
  "openbank-fraud-service openbank-transaction-service openbank-sepa-payment" \
'===SERVICE openbank-fraud-service
There is no verified pact between version a of openbank-fraud-service and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service
There is no verified pact between version b of openbank-transaction-service and the version of openbank-fraud-service currently deployed to sandbox
===SERVICE openbank-sepa-payment
There is no verified pact between version c of openbank-sepa-payment and the version of openbank-transaction-service currently deployed to sandbox'

# 4. Two independent pairs stay two sets. Merging them would tell an operator to co-deploy
#    four services when two separate dispatches are correct and smaller.
check "two disjoint pairs => two sets" \
  "CODEPLOY${TAB}openbank-fraud-service openbank-transaction-service
CODEPLOY${TAB}openbank-clearing-simulator openbank-swift-service" \
  "openbank-fraud-service openbank-transaction-service openbank-swift-service openbank-clearing-simulator" \
'===SERVICE openbank-fraud-service
There is no verified pact between version a of openbank-fraud-service and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service
There is no verified pact between version b of openbank-transaction-service and the version of openbank-fraud-service currently deployed to sandbox
===SERVICE openbank-swift-service
There is no verified pact between version c of openbank-swift-service and the version of openbank-clearing-simulator currently deployed to sandbox
===SERVICE openbank-clearing-simulator
There is no verified pact between version d of openbank-clearing-simulator and the version of openbank-swift-service currently deployed to sandbox'

# 5. THE PARSER TEST. The CLI's matrix table truncates names to the column width. A parser
#    that reads the table derives `openbank-transaction-servi`, a service that exists
#    nowhere, and names a co-deploy set no dispatch can satisfy. Only the prose lines are
#    read, so a block whose ONLY counterpart evidence is the table yields nothing at all —
#    which is the honest answer.
check "truncated matrix table contributes no edge" \
  "" \
  "openbank-sepa-payment openbank-transaction-service" \
'===SERVICE openbank-sepa-payment
Computer says no ¯\_(ツ)_/¯

CONSUMER            | C.VERSION | PROVIDER           | P.VERSION | SUCCESS?
openbank-sepa-payme | abc123    | openbank-transacti | def456    | false'

# 6. A counterpart outside the run is EXTERNAL, never a co-deploy set: nothing in this run
#    can move it, so telling an operator to "co-deploy" it would be a command that fails.
check "counterpart outside the run => EXTERNAL, not CODEPLOY" \
  "EXTERNAL${TAB}openbank-sepa-payment${TAB}openbank-ledger-service" \
  "openbank-sepa-payment openbank-transaction-service" \
'===SERVICE openbank-sepa-payment
There is no verified pact between version abc123 of openbank-sepa-payment and the version of openbank-ledger-service currently deployed to sandbox'

# 7. A verification FAILURE is a contract regression (#2549) and is reported as an edge too
#    — a regression between two services in the same run is still a set that has to move
#    together once the contract is fixed — but the classifier, not this script, is what
#    stops it being silenced as transient.
check "failed-verification prose also yields an edge" \
  "CODEPLOY${TAB}openbank-fraud-service openbank-transaction-service" \
  "openbank-fraud-service openbank-transaction-service" \
'===SERVICE openbank-fraud-service
The verification for the pact between openbank-fraud-service and openbank-transaction-service failed
===SERVICE openbank-transaction-service
The verification for the pact between openbank-transaction-service and openbank-fraud-service failed'

# 8. No blocks at all: silence. A reporter that printed a header on a clean run would put
#    a co-deploy hint in every green log.
check "no blocked services => no output" "" "openbank-fraud-service" ""

# 9. THE REGRESSION THIS FIXES (#1985, observed on run 30761740836). Two services blocked
#    with PENDING_BUILD reference each other exactly like a deadlocked pair — but that class
#    means only "their main-push build has not finished", which self-clears. Recommending a
#    co-deploy (a weaker check, and these two are money-path) on that evidence is wrong, so
#    the set must NOT be named; the pending services are reported instead.
check "PENDING_BUILD pair is not a co-deploy set" \
  "PENDING${TAB}openbank-fraud-service openbank-transaction-service" \
  "openbank-fraud-service openbank-transaction-service" \
"===SERVICE openbank-fraud-service${TAB}PENDING_BUILD
There is no verified pact between version abc123 of openbank-fraud-service and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service${TAB}PENDING_BUILD
There is no verified pact between version def456 of openbank-transaction-service and the version of openbank-fraud-service currently deployed to sandbox"

# 10. A DURABLE pair still is one — the fix must not silence the case the script exists for.
check "UNVERIFIED pair is still a co-deploy set" \
  "CODEPLOY${TAB}openbank-fraud-service openbank-transaction-service" \
  "openbank-fraud-service openbank-transaction-service" \
"===SERVICE openbank-fraud-service${TAB}UNVERIFIED
There is no verified pact between version abc123 of openbank-fraud-service and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service${TAB}UNVERIFIED
There is no verified pact between version def456 of openbank-transaction-service and the version of openbank-fraud-service currently deployed to sandbox"

# 11. Mixed: a durable block paired with a transient one cannot be called a deadlock yet —
#     half the evidence is "CI is behind". Report the pending one, name no set.
check "mixed durable+transient pair names no set" \
  "PENDING${TAB}openbank-transaction-service" \
  "openbank-fraud-service openbank-transaction-service" \
"===SERVICE openbank-fraud-service${TAB}UNVERIFIED
There is no verified pact between version abc123 of openbank-fraud-service and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service${TAB}PENDING_BUILD
There is no verified pact between version def456 of openbank-transaction-service and the version of openbank-fraud-service currently deployed to sandbox"

# 11b (#3223). UNVERIFIABLE is durable BY MEASUREMENT — the counterpart version carries no
#     pacts — and a co-deploy is the remedy for exactly that, so it must be set-eligible.
#     Before the class existed these blocks arrived as UNVERIFIED or PENDING_BUILD, so this
#     asserts the allow-list did not lose the pair when the label changed.
check "UNVERIFIABLE pair is a co-deploy set" \
  "CODEPLOY${TAB}openbank-fraud-service openbank-transaction-service" \
  "openbank-fraud-service openbank-transaction-service" \
"===SERVICE openbank-fraud-service${TAB}UNVERIFIABLE
There is no verified pact between version abc123 of openbank-fraud-service and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service${TAB}UNVERIFIABLE
There is no verified pact between version def456 of openbank-transaction-service and the version of openbank-fraud-service currently deployed to sandbox"

# ── issue #3454: NOT_ASKED, the class where no verdict exists at all ─────────────────────
# 12. A NOT_ASKED pair references each other exactly like a deadlocked one, and on a fleet
#     dispatch this is EVERY blocked service (54 of 54 on run 30765380309). Recommending a
#     co-deploy there would not be a weaker check, it would be no check: can-i-deploy was
#     never asked about any of them. The set must not be named.
check "NOT_ASKED pair is not a co-deploy set" \
  "UNGATED${TAB}openbank-fraud-service openbank-transaction-service" \
  "openbank-fraud-service openbank-transaction-service" \
"===SERVICE openbank-fraud-service${TAB}NOT_ASKED
There is no verified pact between version abc123 of openbank-fraud-service and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service${TAB}NOT_ASKED
There is no verified pact between version def456 of openbank-transaction-service and the version of openbank-fraud-service currently deployed to sandbox"

# 13. NOT_ASKED must not ride on the PENDING report either. PENDING prints "waiting on their
#     own builds, not deadlocked; no action" — the self-clearing promise NOT_ASKED exists to
#     avoid. If someone folds NOT_ASKED into TRANSIENT_CLASSES, this goes red.
check "NOT_ASKED reports separately from PENDING_BUILD" \
  "PENDING${TAB}openbank-fraud-service
UNGATED${TAB}openbank-transaction-service" \
  "openbank-fraud-service openbank-transaction-service" \
"===SERVICE openbank-fraud-service${TAB}PENDING_BUILD
Computer says no
===SERVICE openbank-transaction-service${TAB}NOT_ASKED
Computer says no"

# 14. A durable pair stays a set even when a NOT_ASKED service names one of them — the
#     addition must not silence the case the script exists for.
check "NOT_ASKED alongside a durable pair does not suppress the set" \
  "CODEPLOY${TAB}openbank-fraud-service openbank-transaction-service
UNGATED${TAB}openbank-swift-service" \
  "openbank-fraud-service openbank-transaction-service openbank-swift-service" \
"===SERVICE openbank-fraud-service${TAB}UNVERIFIED
There is no verified pact between version a of openbank-fraud-service and the version of openbank-transaction-service currently deployed to sandbox
===SERVICE openbank-transaction-service${TAB}UNVERIFIED
There is no verified pact between version b of openbank-transaction-service and the version of openbank-fraud-service currently deployed to sandbox
===SERVICE openbank-swift-service${TAB}NOT_ASKED
There is no verified pact between version c of openbank-swift-service and the version of openbank-fraud-service currently deployed to sandbox"

if [ "$fails" -gt 0 ]; then
  echo "derive-codeploy-set.py: ${fails} case(s) FAILED"
  exit 1
fi
echo "derive-codeploy-set.py: all cases passed"
