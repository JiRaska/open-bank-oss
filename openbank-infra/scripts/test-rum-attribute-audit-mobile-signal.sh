#!/usr/bin/env bash
# Proves the rum-attribute-audit CronJob (issue #7535) tells a transient Tempo
# query failure apart from a genuine, successfully-queried empty result for
# the mobile service.name liveness check.
#
# Extracts the container's inline command straight from the CronJob manifest
# so this always exercises the script that actually ships, never a copy that
# can drift, then runs it under a fake `curl` that is scripted per scenario.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MANIFEST="${REPO_ROOT}/openbank-infra/gitops/components/observability/cronjob-rum-attribute-audit.yaml"

WORKDIR="$(mktemp -d)"
trap 'rm -rf "${WORKDIR}"' EXIT

python3 - "$MANIFEST" >"${WORKDIR}/audit-script.sh" <<'PY'
import sys
import yaml

with open(sys.argv[1]) as f:
    doc = yaml.safe_load(f)
cmd = doc["spec"]["jobTemplate"]["spec"]["template"]["spec"]["containers"][0]["command"]
sys.stdout.write(cmd[-1])
PY

mkdir -p "${WORKDIR}/bin"
cat >"${WORKDIR}/bin/curl" <<'CURL'
#!/usr/bin/env bash
# Fake curl for test-rum-attribute-audit-mobile-signal.sh. FAKE_CURL_MODE
# selects the scenario; behaviour only diverges for the service.name query,
# every other tag-values query (the per-attribute loop) always succeeds empty.
url="${!#}"
case "$url" in
  *service.name*)
    case "${FAKE_CURL_MODE:-}" in
      always_fail)
        exit 1
        ;;
      fail_then_succeed_empty)
        n=$(cat "${FAKE_CURL_STATE}" 2>/dev/null || echo 0)
        n=$((n + 1))
        echo "$n" >"${FAKE_CURL_STATE}"
        if [ "$n" -lt "${FAKE_CURL_FAIL_COUNT:-2}" ]; then
          exit 1
        fi
        echo '{"tagValues":[]}'
        ;;
      success_present)
        echo '{"tagValues":[{"type":"string","value":"openbank-app"}]}'
        ;;
      *)
        echo '{"tagValues":[]}'
        ;;
    esac
    ;;
  *)
    echo '{"tagValues":[]}'
    ;;
esac
CURL
chmod +x "${WORKDIR}/bin/curl"

run_scenario() {
  local mode="$1"
  FAKE_CURL_MODE="$mode" FAKE_CURL_STATE="${WORKDIR}/state-${mode}" \
    PATH="${WORKDIR}/bin:${PATH}" TEMPO_URL="http://tempo.test:3200" LOOKBACK="168h" \
    MOBILE_SIGNAL_RETRY_DELAY_SECONDS=0 \
    bash "${WORKDIR}/audit-script.sh"
}

fail=0

echo "== scenario: always_fail (transient Tempo outage) =="
out=$(run_scenario always_fail) && rc=0 || rc=$?
echo "$out"
if echo "$out" | grep -q '"event":"rum-mobile-signal-absent"'; then
  echo "FAIL: a transient query failure was reported as rum-mobile-signal-absent"
  fail=1
fi
if ! echo "$out" | grep -q '"event":"rum-mobile-signal-unavailable"'; then
  echo "FAIL: a transient query failure did not emit rum-mobile-signal-unavailable"
  fail=1
fi
if [ "$rc" -eq 0 ]; then
  echo "FAIL: always_fail scenario should exit non-zero"
  fail=1
fi

echo "== scenario: fail_then_succeed_empty (transient blip, then a real empty result) =="
out=$(run_scenario fail_then_succeed_empty) && rc=0 || rc=$?
echo "$out"
if ! echo "$out" | grep -q '"event":"rum-mobile-signal-absent"'; then
  echo "FAIL: a genuine empty response obtained after a retry was not reported as absent"
  fail=1
fi
if echo "$out" | grep -q '"event":"rum-mobile-signal-unavailable"'; then
  echo "FAIL: a query that eventually succeeded should not report unavailable"
  fail=1
fi

echo "== scenario: success_present (signal genuinely there) =="
out=$(run_scenario success_present) && rc=0 || rc=$?
echo "$out"
if [ "$rc" -ne 0 ]; then
  echo "FAIL: success_present scenario should exit 0"
  fail=1
fi
if echo "$out" | grep -qE '"event":"rum-mobile-signal-(absent|unavailable)"'; then
  echo "FAIL: success_present scenario should not report absent or unavailable"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo "test-rum-attribute-audit-mobile-signal: FAILED"
  exit 1
fi
echo "test-rum-attribute-audit-mobile-signal: OK"
