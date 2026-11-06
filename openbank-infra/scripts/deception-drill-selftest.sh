#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Known-negative and known-positive for deception-drill.sh (ADR-0361), with aws/curl stubbed on
# PATH so nothing real is called. The drill exists to fail loudly when an alert does not
# arrive; this proves that branch runs (no signal -> exit 1, FAIL rows) and that a real signal
# passes (exit 0), on whatever bash and userland the caller has.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/bin"
cat > "$tmp/bin/curl" <<'STUB'
#!/usr/bin/env bash
echo 401
STUB
cat > "$tmp/bin/aws" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  *ssm\ get-parameter*) echo '{"aws_access_key_id":"AKIASTUB","aws_secret_access_key":"stub"}' ;;
  *sts\ get-caller-identity*) echo "arn:aws:iam::000000000000:user/service/stub" ;;
  *get-metric-statistics*) echo "${STUB_METRIC:-None}" ;;
  *) echo "unexpected aws call: $*" >&2; exit 9 ;;
esac
STUB
chmod +x "$tmp/bin/"*

run() { DRILL_DECOY_URL=https://decoy.invalid/x DRILL_SSM_PARAM=/stub PATH="$tmp/bin:$PATH" TIMEOUT=2 POLL=1 SETTLE=0 DRILL_VERDICT="$tmp/verdict" STUB_METRIC="$1" bash "$HERE/deception-drill.sh" --scope aws >"$tmp/out" 2>&1; echo $?; }

rc=$(run None || true)
if [[ "$rc" != 1 ]] || ! grep -q '^key  *FAIL' "$tmp/out" || ! grep -q '^delivery  *FAIL' "$tmp/out"; then
  echo "SELFTEST FAIL: no signal must exit 1 with key+delivery FAIL (rc=$rc)"; cat "$tmp/out"; exit 1
fi
rc=$(run 1.0 || true)
if [[ "$rc" != 0 ]] || ! grep -q '^key  *PASS' "$tmp/out" || ! grep -q '^sa  *SKIPPED' "$tmp/out"; then
  echo "SELFTEST FAIL: a signal must exit 0 with key PASS and sa SKIPPED (rc=$rc)"; cat "$tmp/out"; exit 1
fi
rc=$(run 2.0 || true)
if [[ "$rc" != 1 ]] || ! grep -q '^count  *FAIL' "$tmp/out"; then
  echo "SELFTEST FAIL: two matched events must exit 1 with a count FAIL row (rc=$rc)"; cat "$tmp/out"; exit 1
fi
if ! grep -q '^run=' "$tmp/verdict" || grep -q 'watched key: called' "$tmp/verdict"; then
  echo "SELFTEST FAIL: the verdict file must hold the verdict and nothing printed before it"; cat "$tmp/verdict"; exit 1
fi
if grep -q 'arn:aws' "$tmp/out"; then
  echo "SELFTEST FAIL: the drill printed an ARN, and its output is posted to a public issue"; cat "$tmp/out"; exit 1
fi
echo "deception-drill selftest: OK — silence fails loudly (exit 1), a signal passes, a second event fails, no ARN printed, scope=aws never claims the ServiceAccount"
