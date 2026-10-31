#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Known-negative and known-positive for deception-drill.sh (ADR-0357), with aws/curl stubbed on
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

run() { PATH="$tmp/bin:$PATH" TIMEOUT=2 POLL=1 STUB_METRIC="$1" bash "$HERE/deception-drill.sh" --scope aws >"$tmp/out" 2>&1; echo $?; }

rc=$(run None || true)
if [[ "$rc" != 1 ]] || ! grep -q '^canary  *FAIL' "$tmp/out" || ! grep -q '^delivery  *FAIL' "$tmp/out"; then
  echo "SELFTEST FAIL: no signal must exit 1 with canary+delivery FAIL (rc=$rc)"; cat "$tmp/out"; exit 1
fi
rc=$(run 1.0 || true)
if [[ "$rc" != 0 ]] || ! grep -q '^canary  *PASS' "$tmp/out" || ! grep -q '^honeysa  *SKIPPED' "$tmp/out"; then
  echo "SELFTEST FAIL: a signal must exit 0 with canary PASS and honeysa SKIPPED (rc=$rc)"; cat "$tmp/out"; exit 1
fi
echo "deception-drill selftest: OK — silence fails loudly (exit 1), a signal passes, scope=aws never claims the honey SA"
