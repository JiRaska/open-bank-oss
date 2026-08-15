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
# --- self-test ------------------------------------------------------------------------
# `openbank.outbox.dispatch-enabled` DEFAULTS TO FALSE. A service with an outbox entity that
# does not set it true never dispatches an event — with no error, and `attempt_count` stays 0
# forever. Nothing else observes that: the pod is healthy, the rows are written, and the
# absence is the quiet path. This guard is the only thing standing between that default and
# production, and it shipped with no falsification of its own.
#
# The fixtures are a real directory tree run through the REAL script (it already takes a root
# argument), not a re-implementation of its awk. They are named `openbank-*` because that is
# the module glob: the first draft used plain names, nothing matched, zero subjects were
# checked and every must-fail case exited 0 — a self-test passing because it never reached
# its subject.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d)
  trap 'rm -rf "$td"' EXIT
  fails=0

  mk() { # mk <name> <dispatcher?> <application.yaml body|NONE> <version.txt?>
    local n="$1" disp="$2" body="$3" ver="$4"
    mkdir -p "$td/$n/src/main/kotlin" "$td/$n/src/main/resources"
    [ "$ver" = "yes" ] && echo "1.0.0" > "$td/$n/version.txt"
    [ "$disp" = "yes" ] && printf 'class FooOutboxDispatcher : AbstractOutboxDispatcher()\n' \
      > "$td/$n/src/main/kotlin/FooOutboxDispatcher.kt"
    [ "$body" != "NONE" ] && printf '%b' "$body" > "$td/$n/src/main/resources/application.yaml"
    return 0
  }
  run_one() { # isolate a single fixture so one case cannot mask another
    local keep="$1" out rc
    local box; box=$(mktemp -d)
    cp -R "$td/$keep" "$box/$keep"
    out=$(bash "$0" "$box" 2>&1); rc=$?
    rm -rf "$box"
    printf '%s\n' "$out"
    return $rc
  }
  expect() { # expect <label> <fixture> <want-rc> [want-substring]
    local label="$1" fx="$2" want="$3" sub="${4:-}" out rc
    out=$(run_one "$fx"); rc=$?
    if [ "$rc" -ne "$want" ]; then
      echo "::error::self-test: $label — expected rc=$want, got rc=$rc" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong: no '$sub' in: $out" >&2; fails=$((fails+1))
    fi
  }

  ENABLED='openbank:\n  outbox:\n    dispatch-enabled: true\n'
  DISABLED='openbank:\n  outbox:\n    dispatch-enabled: false\n'
  ABSENT='openbank:\n  outbox:\n    poll-interval: 5s\n'
  ENVDEF='openbank:\n  outbox:\n    dispatch-enabled: "${OB_DISPATCH:true}"\n'
  ENVNODEF='openbank:\n  outbox:\n    dispatch-enabled: "${OB_DISPATCH}"\n'
  ELSEWHERE='quarkus:\n  outbox:\n    dispatch-enabled: true\n'

  mk openbank-ok-true       yes "$ENABLED"   yes
  mk openbank-bad-false     yes "$DISABLED"  yes
  mk openbank-bad-absent    yes "$ABSENT"    yes
  mk openbank-ok-envdefault yes "$ENVDEF"    yes
  mk openbank-bad-envnodef  yes "$ENVNODEF"  yes
  mk openbank-bad-noyaml    yes NONE         yes
  mk openbank-no-dispatcher no  "$DISABLED"  yes
  mk openbank-bad-wrongkey  yes "$ELSEWHERE" yes

  # The only shape that may pass. Without it, a script that failed everything would look fine.
  expect "dispatch-enabled: true is clean"              openbank-ok-true       0
  # THE DEFECT: shipped false, dispatches nothing, says nothing.
  expect "dispatch-enabled: false is caught"            openbank-bad-false     1 "not true"
  # The same outage with no key at all — the DEFAULT, and the likeliest way to arrive here.
  expect "an absent dispatch-enabled is caught"         openbank-bad-absent    1 "MISSING"
  # SmallRye env-var-with-default must resolve to its default, or every deployed service reads
  # as broken and the gate gets switched off.
  expect "an env var defaulting to true is clean"       openbank-ok-envdefault 0
  # ...but an env var with NO default is not knowable from this repo, and must not be guessed.
  expect "an env var with no default is refused"        openbank-bad-envnodef  1
  # A gated dispatcher with no application.yaml cannot be verified — unverifiable is not clean.
  expect "a missing application.yaml is caught"         openbank-bad-noyaml    1 "cannot verify"
  # SCOPE: a service with no gated dispatcher is not this gate's business.
  expect "a service with no dispatcher is skipped"      openbank-no-dispatcher 0
  # The key must be read under openbank.outbox specifically — the same leaf name under another
  # parent is a different property and must NOT satisfy the check.
  expect "the key under a DIFFERENT parent does not count" openbank-bad-wrongkey 1 "MISSING"

  if [ "$fails" -gt 0 ]; then
    echo "self-test FAILED ($fails case(s))" >&2; exit 1
  fi
  echo "self-test ok: outbox dispatch-enabled guard is falsifiable (8 cases)"
  exit 0
fi

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
