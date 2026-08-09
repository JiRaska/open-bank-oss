#!/usr/bin/env bash
# Guard: a service whose *OutboxDispatcher class actually GATES dispatch on
# openbank.outbox.dispatch-enabled must set it to true in application.yaml.
#
# openbank.outbox.dispatch-enabled defaults to false in the shared dispatch
# pattern (a dispatcher extending AbstractOutboxDispatcher, reading the flag
# via @ConfigProperty). A service using that pattern can have a fully working
# outbox entity and correct tests — and still never actually publish an event
# in a real deployment, silently, with no error and attempt_count stuck at 0,
# because nobody added one line to application.yaml. This footgun is
# documented in CLAUDE.md but was, until this script, purely tribal
# knowledge: nothing caught it.
#
# NOT every dispatcher uses the gated pattern: openbank-sepa-instant and
# openbank-security-scanner ship a hand-rolled @Scheduled dispatcher that
# runs unconditionally and never reads the flag at all — for them, a
# missing/false config key is a no-op, not a bug, and flagging it would be a
# false positive (confirmed by reading both classes directly, not just their
# application.yaml). So this script only checks a service whose dispatcher
# source references the config property in the first place; the two
# unconditional dispatchers above are correctly out of scope, not "skipped
# because non-compliant."
#
# Scope: any RELEASED component (sibling version.txt present, same scoping rule
# as check-app-version-override.sh) that has at least one *OutboxDispatcher.kt
# under src/main/kotlin REFERENCING dispatch-enabled/dispatchEnabled/
# AbstractOutboxDispatcher. A module without such a dispatcher (no gated
# outbox, or no outbox at all) has nothing to guard and is skipped.
#
# Value resolution: the raw YAML value is judged as "enabled" if it is the
# bare literal true/"true", OR the SmallRye env-var-with-default syntax
# ${VAR:true}/"${VAR:true}" whose default term is true. Everything else fails
# closed: a bare false, ${VAR:false}, an env-var with NO default (${VAR} —
# we refuse to guess what that resolves to at deploy time), or a missing key
# entirely. (Fixed in open-bank-oss#620 — the original literal-only match
# false-negatived on the ${VAR:true} form, which is this repo's normal
# pattern for a soft-toggle elsewhere, e.g. notification-service's
# `enabled: ${SLACK_WEBHOOK_ENABLED:false}`.)
#
# stdlib-only (awk + shell); no PyYAML/yamllint dependency, matching
# check-app-version-override.sh. ENFORCED — as of this script's introduction,
# every in-scope service already sets dispatch-enabled: true (0 violations on
# origin/main), so this is a regression guard, not a fleet-sweep-in-waiting.
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
  disp=$(find "$moddir/src/main/kotlin" -iname '*OutboxDispatcher.kt' -not -path '*/build/*' 2>/dev/null | head -1)
  if [ -z "$disp" ] || ! grep -qE 'dispatch-enabled|dispatchEnabled|AbstractOutboxDispatcher' "$disp"; then
    skipped=$((skipped + 1)); continue
  fi
  yml="$moddir/src/main/resources/application.yaml"
  if [ ! -f "$yml" ]; then
    fail=1
    echo "::error file=$moddir::has a dispatch-gated OutboxDispatcher ($(basename "$disp")) but no src/main/resources/application.yaml — cannot verify dispatch-enabled."
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

  # Strip a single layer of surrounding double-quotes, if present, e.g.
  # "${OPENBANK_OUTBOX_DISPATCH_ENABLED:true}" -> ${OPENBANK_OUTBOX_DISPATCH_ENABLED:true}.
  unquoted="$value"
  case "$unquoted" in
    \"*\") unquoted="${unquoted#\"}"; unquoted="${unquoted%\"}" ;;
  esac

  # Resolve SmallRye env-var-with-default syntax: ${VAR:default} -> default.
  # A bare literal (true/false/MISSING) is left untouched by this pattern and
  # falls through to the plain comparison below. ${VAR} with NO ':default' at
  # all does not match this case either — it stays as-is and is judged (and
  # rejected) as an ambiguous, non-"true" value, same as today: we refuse to
  # guess what an env-var-with-no-default resolves to at deploy time.
  resolved="$unquoted"
  case "$unquoted" in
    '${'*:*'}')
      body="${unquoted#\$\{}"   # strip leading ${
      body="${body%\}}"          # strip trailing }
      resolved="${body#*:}"      # everything after the first ':' is the default
      ;;
  esac

  if [ "$resolved" != "true" ]; then
    fail=1
    echo "::error file=$yml::$moddir has a dispatch-gated $(basename "$disp") but openbank.outbox.dispatch-enabled is '$value' (resolves to '$resolved'), not true — this service will never dispatch outbox events at runtime (no error, attempt_count stays 0). Set 'openbank: outbox: dispatch-enabled: true' (or an env-var default that resolves to true) in application.yaml."
  fi
done < <(
  find "$root" -maxdepth 1 -type d -name 'openbank-*' -not -path '*/.claude/*' \
    | while read -r d; do
        find "$d/src/main/kotlin" -iname '*OutboxDispatcher.kt' -not -path '*/build/*' 2>/dev/null | head -1 | grep -q . && echo "$d"
      done \
    | sort
)

echo "SUBJECTS=$checked"
echo "check-outbox-dispatch-enabled: $checked dispatch-gated service(s) checked, $skipped skipped (no gated dispatcher), $( [ "$fail" -eq 0 ] && echo "all set dispatch-enabled: true." || echo "VIOLATIONS above." )"
exit "$fail"
