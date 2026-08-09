#!/usr/bin/env bash
# check-clock-injection.sh — ADR-0100 Layer 1 gate
#
# Enforces that domain/ and application/ layers of money-path services do NOT
# call wall-clock APIs directly (Instant.now(), LocalDateTime.now(),
# LocalDate.now(), System.currentTimeMillis()). All time reads must go through
# an injected Clock / ClockProvider so that DST/timezone/leap-second tests can
# control time deterministically.
#
# Refs: ADR-0100 (DST harness), Issue #1612
#
# Usage: bash .github/scripts/check-clock-injection.sh
# Exit:  0 = clean, 1 = violations found

set -euo pipefail

MONEY_PATH_SERVICES=(
  openbank-ledger-service
  openbank-transaction-service
  openbank-account-service
  openbank-balance-service
  openbank-sepa-payment
  openbank-domestic-payment
  openbank-settlement-service
  openbank-clearing-service
)

# Layers that must never call wall-clock APIs directly
TARGET_LAYERS=(domain application)

# Pattern — matches any of the banned direct clock calls
PATTERN='Instant[.]now[(][)]\|LocalDateTime[.]now[(][)]\|LocalDate[.]now[(][)]\|System[.]currentTimeMillis[(][)]'

VIOLATIONS=0
SCANNED=0

for svc in "${MONEY_PATH_SERVICES[@]}"; do
  for layer in "${TARGET_LAYERS[@]}"; do
    search_dir="${svc}/src/main/kotlin"
    # The layer directory can sit at any depth under the kotlin source root
    # (e.g. com/openbank/ledger/domain/), so we use find + grep per file.
    if [ ! -d "$search_dir" ]; then
      continue
    fi

    while IFS= read -r -d '' file; do
      # Check whether the file lives under a domain/ or application/ path segment
      if ! echo "$file" | grep -qE "/${layer}/"; then
        continue
      fi

      matches=$(grep -n "$PATTERN" "$file" | grep -v '[[:space:]]*//' | grep -v '[[:space:]]\*' || true)
      SCANNED=$((SCANNED + 1))
      if [ -n "$matches" ]; then
        echo "VIOLATION [$svc/$layer] $file"
        echo "$matches"
        echo "---"
        VIOLATIONS=$((VIOLATIONS + 1))
      fi
    done < <(find "$search_dir" -type f -name "*.kt" -print0)
  done
done

echo "SUBJECTS=$SCANNED"

if [ "$VIOLATIONS" -gt 0 ]; then
  echo ""
  echo "ERROR: $VIOLATIONS file(s) contain direct wall-clock calls in domain/application layers."
  echo "Inject a Clock or ClockProvider instead (ADR-0100, Refs #1612)."
  exit 1
fi

echo "OK: no direct wall-clock calls found in domain/application layers of money-path services."
exit 0
