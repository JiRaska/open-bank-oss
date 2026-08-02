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
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSIFY="${SCRIPT_DIR}/classify-can-i-deploy-block.sh"

fails=0
check() {
  local name="$1" want="$2" present="$3" out="$4"
  local got
  got="$(PACT_VERSION_PRESENT="$present" bash "$CLASSIFY" openbank-demo-service <<< "$out" | cut -f1)"
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
