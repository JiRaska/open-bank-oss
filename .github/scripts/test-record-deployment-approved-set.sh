#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
set -euo pipefail
cd "$(dirname "$0")/../.."
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
cat >"$tmp/pact-broker" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$MOCK_CALLS"
case "$1" in
  can-i-deploy)
    # This mock approves the mutually compatible two-member co-deploy only as a set.
    [[ "$MOCK_VERDICT" = green && "$*" == *"--pacticipant openbank-a --version ${MOCK_A}"* \
      && "$*" == *"--pacticipant openbank-b --version ${MOCK_B}"* \
      && "$*" == *"--to-environment sandbox"* ]] ;;
  record-deployment) exit 0 ;;
  *) exit 1 ;;
esac
SH
chmod +x "$tmp/pact-broker"
export PACT_BROKER_URL=https://pact.example.invalid
export PACT_BROKER_USERNAME=test PACT_BROKER_PASSWORD=test
MOCK_A="$(printf 'a%.0s' {1..40})"
MOCK_B="$(printf 'b%.0s' {1..40})"
export MOCK_A MOCK_B
export MOCK_CALLS="$tmp/calls" MOCK_VERDICT=green
printf 'openbank-a\t%s\nopenbank-b\t%s\n' "$MOCK_A" "$MOCK_B" >"$tmp/plan"

fail() { echo "FAIL: $*" >&2; exit 1; }
approved_set() { bash .github/scripts/record-deployment-approved-set.sh "$1" "$tmp/pact-broker" >/dev/null; }

# A newer byte-equivalent but unverified consumer version must never replace the
# previously approved record merely because the version exists in the broker.
MOCK_VERDICT=unknown
if approved_set "$tmp/plan"; then fail 'unknown matrix verdict was accepted'; fi
[[ $(grep -c '^can-i-deploy ' "$MOCK_CALLS") -eq 1 ]] || fail 'matrix was not checked once'
! grep -q '^record-deployment ' "$MOCK_CALLS" || fail 'unknown wrote a record'
echo 'ok: published-but-unverified equivalent version writes no record'

: >"$MOCK_CALLS"
MOCK_VERDICT=external_unknown
if approved_set "$tmp/plan"; then fail 'unknown external environment counterpart was accepted'; fi
! grep -q '^record-deployment ' "$MOCK_CALLS" || fail 'unknown external counterpart wrote a record'
echo 'ok: compatible pair with unknown external environment writes no record'

: >"$MOCK_CALLS"
MOCK_VERDICT=failed
if approved_set "$tmp/plan"; then fail 'failed matrix verdict was accepted'; fi
! grep -q '^record-deployment ' "$MOCK_CALLS" || fail 'failed matrix wrote a record'
echo 'ok: failed matrix verdict writes no record'

: >"$MOCK_CALLS"
MOCK_VERDICT=green
printf 'openbank-a\t%s\nopenbank-b\tinvalid\n' "$MOCK_A" >"$tmp/partial"
if approved_set "$tmp/partial"; then fail 'partly resolved plan was accepted'; fi
[[ ! -s "$MOCK_CALLS" ]] || fail 'partly resolved plan called broker'
echo 'ok: partial resolution causes zero broker calls'

: >"$MOCK_CALLS"
approved_set "$tmp/plan" || fail 'mutually approved co-deploy was refused'
[[ $(grep -c '^can-i-deploy ' "$MOCK_CALLS") -eq 1 ]] || fail 'set was not gated once'
[[ $(grep -c '^record-deployment ' "$MOCK_CALLS") -eq 2 ]] || fail 'approved pair was not recorded'
[[ $(head -n 1 "$MOCK_CALLS") == 'can-i-deploy '* ]] || fail 'write preceded matrix gate'
echo 'ok: exact mutually approved set gates once before two bounded writes'
