#!/usr/bin/env bash
# Guard: a service that ships an OutboxDispatcher class must explicitly set
# openbank.outbox.dispatch-enabled: true in application.yaml.
#
# openbank.outbox.dispatch-enabled defaults to false in the shared outbox
# relay (openbank-libs). A service can have a fully working outbox entity,
# a working *OutboxDispatcher class, and correct tests — and still never
# actually publish an event in a real deployment, silently, with no error
# and attempt_count stuck at 0, because nobody added one line to
# application.yaml. This footgun is documented in CLAUDE.md but was, until
# this script, purely tribal knowledge: nothing caught it. Confirmed live
# on origin/main at the time this script was written: openbank-sepa-instant
# (money-path), openbank-psd2-service, and openbank-security-scanner all
# have a dispatcher class and no dispatch-enabled key.
#
# Scope: any RELEASED component (sibling version.txt present, same scoping rule
# as check-app-version-override.sh) that has at least one *OutboxDispatcher.kt
# under src/main/kotlin. A module without a dispatcher class has nothing to
# guard; a module without version.txt (e.g. openbank-libs-runtime, a shared
# library consumed via Gradle project dependency, never independently deployed)
# has no application.yaml of its own and is skipped, not flagged.
#
# stdlib-only (awk); no PyYAML/yamllint dependency, matching
# check-app-version-override.sh. ENFORCED.
# Usage: check-outbox-dispatch-enabled.sh [root]   (default root: .)
set -euo pipefail
root="${1:-.}"
fail=0
checked=0
skipped=0

while IFS= read -r moddir; do
  if [ ! -f "$moddir/version.txt" ]; then
    skipped=$((skipped + 1)); continue
  fi
  yml="$moddir/src/main/resources/application.yaml"
  if [ ! -f "$yml" ]; then
    fail=1
    echo "::error file=$moddir::is a released component with an OutboxDispatcher but no src/main/resources/application.yaml — cannot verify dispatch-enabled."
    continue
  fi
  checked=$((checked + 1))

  # Walk the yaml tracking indentation: openbank: (0) -> outbox: (2sp) ->
  # dispatch-enabled: (4sp). Any other 2sp-indented key under openbank:
  # ends the outbox block; any 0-indent key ends the openbank: block.
  value=$(awk '
    /^[^[:space:]#]/            { in_ob = ($0 ~ /^openbank:/); in_outbox = 0; next }
    in_ob && /^  [^[:space:]#]/ { in_outbox = ($0 ~ /^  outbox:/); next }
    in_outbox && /^    dispatch-enabled:[[:space:]]/ {
      v = $0; sub(/^    dispatch-enabled:[[:space:]]*/, "", v); sub(/[[:space:]]*(#.*)?$/, "", v)
      print v; found = 1
    }
    END { if (!found) print "MISSING" }
  ' "$yml")

  if [ "$value" != "true" ] && [ "$value" != "\"true\"" ]; then
    fail=1
    disp=$(find "$moddir/src/main/kotlin" -iname '*OutboxDispatcher.kt' | head -1)
    echo "::error file=$yml::$moddir has $(basename "$disp") but openbank.outbox.dispatch-enabled is '$value', not true — this service will never dispatch outbox events at runtime (no error, attempt_count stays 0). Set 'openbank: outbox: dispatch-enabled: true' in application.yaml."
  fi
done < <(
  find "$root" -maxdepth 1 -type d -name 'openbank-*' -not -path '*/.claude/*' \
    | while read -r d; do
        find "$d/src/main/kotlin" -iname '*OutboxDispatcher.kt' -not -path '*/build/*' 2>/dev/null | head -1 | grep -q . && echo "$d"
      done \
    | sort
)

echo "check-outbox-dispatch-enabled: $checked service(s) with an OutboxDispatcher checked, $skipped skipped, $( [ "$fail" -eq 0 ] && echo "all set dispatch-enabled: true." || echo "VIOLATIONS above." )"
exit "$fail"
