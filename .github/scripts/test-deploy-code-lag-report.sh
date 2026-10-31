#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
# See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
#
# Unit test for deploy-code-lag-report.sh. Builds a throwaway git repo with commit dates
# pinned to a fixed clock and asserts which services the watch reports: own-source drift
# older than the threshold is reported; fresh drift, marker-only drift and up-to-date
# services are not. Pure git + jq, no network.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT="${SCRIPT_DIR}/deploy-code-lag-report.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

git init -q
git config user.email test@example.com
git config user.name test
git config commit.gpgsign false

NOW_EPOCH="$(date -u -j -f '%Y-%m-%dT%H:%M:%SZ' '2026-10-15T14:00:00Z' +%s 2>/dev/null \
               || date -u -d '2026-10-15T14:00:00Z' +%s)"

at() { # at <iso> <msg> — commit with fixed author/committer dates
  local date="$1" msg="$2"
  git add -A
  GIT_AUTHOR_DATE="${date}" GIT_COMMITTER_DATE="${date}" git commit -qm "$msg"
}

for s in lag-svc fresh-svc marker-svc current-svc pending-svc; do
  mkdir -p "openbank-${s}/src/main/kotlin"
  echo v1 > "openbank-${s}/src/main/kotlin/App.kt"
  echo x > "openbank-${s}/build.gradle.kts"
done
echo x > settings.gradle.kts
at '2026-10-15T02:00:00Z' 'c0: seed all services'
C0="$(git rev-parse --short=8 HEAD)"

# c1: own-source drift 7h old — must be REPORTED (threshold 6h).
echo v2 > openbank-lag-svc/src/main/kotlin/App.kt
at '2026-10-15T07:00:00Z' 'c1: lag-svc src change'

# c2: own-source drift 30min old — must NOT be reported (inside the grace window).
echo v2 > openbank-fresh-svc/src/main/kotlin/App.kt
at '2026-10-15T13:30:00Z' 'c2: fresh-svc src change'

# c3: marker-only drift 7h old (release-please shape) — must NOT be reported here;
# deploy-drift-watch owns that lane.
echo "0.2.0" > openbank-marker-svc/version.txt
at '2026-10-15T07:00:00Z' 'c3: marker-svc version bump'

# c4: placeholder-pin service with own-source drift — must be REPORTED (nothing of it
# has ever run).
echo v2 > openbank-pending-svc/src/main/kotlin/App.kt
at '2026-10-15T07:00:00Z' 'c4: pending-svc src change'
TIP="$(git rev-parse --short=8 HEAD)"

mkdir -p openbank-infra/gitops/components
cat > openbank-infra/gitops/components/deploy.yaml <<EOF
image: repo/openbank-lag-svc:sandbox-${C0}
image: repo/openbank-fresh-svc:sandbox-${C0}
image: repo/openbank-marker-svc:sandbox-${C0}
image: repo/openbank-current-svc:sandbox-${TIP}
image: repo/openbank-pending-svc:sandbox-pending
EOF

REPORT_JSON="$(NOW_EPOCH="$NOW_EPOCH" bash "$REPORT" 6)"
echo "$REPORT_JSON" | jq -e '.threshold_hours == 6' >/dev/null \
  || { echo "FAIL: threshold echo"; echo "$REPORT_JSON"; exit 1; }

GOT="$(jq -c '[.entries[].service] | sort' <<< "$REPORT_JSON")"
WANT='["openbank-lag-svc","openbank-pending-svc"]'
[ "$GOT" = "$WANT" ] || { echo "FAIL: report set"; echo "  want: $WANT"; echo "  got:  $GOT"; exit 1; }

jq -e '.entries[] | select(.service=="openbank-lag-svc")
       | (.oldest_src_age_hours >= 6) and (.pinned_commit != "placeholder")' <<< "$REPORT_JSON" >/dev/null \
  || { echo "FAIL: lag-svc entry must carry age >= threshold and a real pin"; echo "$REPORT_JSON"; exit 1; }
jq -e '.entries[] | select(.service=="openbank-pending-svc")
       | (.pinned_commit == "placeholder") and (.oldest_src_age_hours >= 6)' <<< "$REPORT_JSON" >/dev/null \
  || { echo "FAIL: pending-svc entry must be reported as placeholder"; echo "$REPORT_JSON"; exit 1; }

# Inside the grace window nothing is reported even though the probe still sees the lag.
GRACE_JSON="$(NOW_EPOCH="$NOW_EPOCH" bash "$REPORT" 24)"
[ "$(jq '.entries | length' <<< "$GRACE_JSON")" = 0 ] \
  || { echo "FAIL: 24h threshold must report nothing in this fixture"; echo "$GRACE_JSON"; exit 1; }

echo "PASS: deploy-code-lag-report.sh — aged own-source drift reported, fresh/markers/current excluded, placeholder aged from main"
