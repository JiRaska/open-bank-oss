#!/usr/bin/env bash
# Offline request-equivalence and public-log safety checks for #7376.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
cat > "$tmp_dir/mock-post.sh" <<'MOCK'
#!/usr/bin/env bash
set -euo pipefail
[[ "$1" == *"/pacts/provider/openbank-case-coordinator-agent/for-verification" ]]
[[ "$4" == 'application/hal+json, application/json' ]]
jq -e '
  .consumerVersionSelectors == [] and .includePendingStatus == true and
  .providerVersionTags == [] and .providerVersionBranch == "main" and
  (keys | sort) == (["consumerVersionSelectors", "includePendingStatus",
                     "providerVersionBranch", "providerVersionTags"] | sort)
' "$2" >/dev/null
case "$TEST_SCENARIO" in
  success)
    printf '{"_links":{"pb:provider-pacts-for-verification":{},"secret-token":{},"private.example/secret":{}},"_embedded":{"pacts":[]}}' > "$3"
    printf 'HTTP/1.1 200 OK\r\nContent-Type: application/hal+json; charset=utf-8\r\nSet-Cookie: private-cookie\r\n' > "$5"
    printf 200 > "$6"
    printf 200
    ;;
  no_type)
    printf '{"error":"private-response-body"}' > "$3"
    printf 'HTTP/1.1 503 Unavailable\r\nSet-Cookie: private-cookie\r\n' > "$5"
    printf 503 > "$6"
    exit 1
    ;;
  bad_type)
    printf '{"_links":{}}' > "$3"
    printf 'HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n' > "$5"
    printf 200 > "$6"
    printf 200
    ;;
  secret_type)
    printf '{"_embedded":{"pacts":[]}}' > "$3"
    printf 'HTTP/1.1 200 OK\r\nContent-Type: private.example/secret-token\r\n' > "$5"
    printf 200 > "$6"
    printf 200
    ;;
  missing_pacts)
    printf '{"_links":{"secret-token":{}},"_embedded":{}}' > "$3"
    printf 'HTTP/1.1 200 OK\r\nContent-Type: application/hal+json\r\n' > "$5"
    printf 200 > "$6"
    printf 200
    ;;
  wrong_type)
    printf '{"_links":{},"_embedded":{"pacts":{"secret-token":"private-response-body"}}}' > "$3"
    printf 'HTTP/1.1 200 OK\r\nContent-Type: application/hal+json\r\n' > "$5"
    printf 200 > "$6"
    printf 200
    ;;
  *) exit 2 ;;
esac
MOCK
chmod 700 "$tmp_dir/mock-post.sh"
export PACT_POST_HELPER="$tmp_dir/mock-post.sh"
export PACT_BROKER_URL='https://private-hostname.invalid/private-path'
export PACT_BROKER_USERNAME='private-user'
export PACT_BROKER_PASSWORD='private-password'
export RUNNER_TEMP="$tmp_dir"

TEST_SCENARIO=success bash "$here/pact-provider-selector-preflight.sh" openbank-case-coordinator-agent main > "$tmp_dir/out" 2>&1
grep -q 'HTTP 200; Content-Type application/hal+json; HAL relations pacts' "$tmp_dir/out"
if grep -Eq 'private[.-]|secret-token|pb:|Set-Cookie|request.json|response.json' "$tmp_dir/out"; then
  echo 'FAIL: private response data reached public output' >&2
  exit 1
fi

for TEST_SCENARIO in no_type bad_type secret_type missing_pacts wrong_type; do
  if TEST_SCENARIO="$TEST_SCENARIO" bash "$here/pact-provider-selector-preflight.sh" openbank-case-coordinator-agent main > "$tmp_dir/out" 2>&1; then
    echo "FAIL: $TEST_SCENARIO was accepted" >&2
    exit 1
  fi
  if grep -Eq 'private[.-]|secret-token|pb:|Set-Cookie|request.json|response.json' "$tmp_dir/out"; then
    echo "FAIL: $TEST_SCENARIO leaked private response data" >&2
    exit 1
  fi
  if [[ "$TEST_SCENARIO" == bad_type ]]; then
    grep -q 'HTTP 200; Content-Type text/html' "$tmp_dir/out"
  fi
  if [[ "$TEST_SCENARIO" == secret_type ]]; then
    grep -q 'HTTP 200; Content-Type other' "$tmp_dir/out"
  fi
  if [[ "$TEST_SCENARIO" == missing_pacts || "$TEST_SCENARIO" == wrong_type ]]; then
    grep -q 'required HAL pacts array' "$tmp_dir/out"
  fi
done
echo 'pact selector preflight self-test: exact pending request, metadata redaction, failure gates passed'
