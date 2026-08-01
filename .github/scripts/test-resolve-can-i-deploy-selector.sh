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
  local name="$1" want="$2" present="$3"
  local got
  got="$(PACT_VERSION_PRESENT="$present" bash "$RESOLVE" openbank-demo-service "$SHA" | cut -f1)"
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

if [ "$fails" -ne 0 ]; then
  echo "FAILED: ${fails} case(s)"
  exit 1
fi
echo "all cases behaved as declared"
