#!/usr/bin/env bash
# Guard: every advisory/planned rule in rules.yaml must carry a
# target_enforce_date, and that date must not have passed while the rule
# is still not enforced (ADR-0144).
#
# WHY THIS EXISTS: an advisory gate with no deadline decays to permanently
# advisory the moment its producing layer ships and nobody remembers to flip
# the switch — exactly what happened to `authz.enforce` on 14 money-path
# services (ADR-0034 Phase 5) and to `new_service_with_outbox` (this ADR's
# own governance sweep found it shippable the same day it was written).
#
# The escape hatch described in ADR-0144 ("the same PR that would otherwise
# fail also moves the date forward with a one-line reason in the commit
# body") needs no special handling here: this script only ever compares the
# date IN THE WORKING TREE against today. A PR that pushes the date forward
# is, by construction, evaluated against its own new (future) date — it
# passes. The "one-line reason in the commit body" is a human-review
# convention (a reviewer can see WHY a deadline moved in the diff/commit),
# not something this script parses.
#
# A rule newly added with enforced: advisory|planned and no
# target_enforce_date at all is a harder failure — ADR-0144: "a rule may
# not be added to rules.yaml without a target_enforce_date."
#
# stdlib-only (awk); no PyYAML/yamllint dependency, matching
# check-app-version-override.sh / check-outbox-dispatch-enabled.sh.
# ENFORCED.
# Usage: check-gate-graduation.sh [rules.yaml path]   (default: openbank-libs/governance/rules.yaml)
set -euo pipefail
rules="${1:-openbank-libs/governance/rules.yaml}"
[ -f "$rules" ] || { echo "::error::check-gate-graduation: $rules not found" >&2; exit 1; }

today="$(date -u +%Y-%m-%d)"
fail=0
checked=0

# Walk the file; whenever a line matches `enforced: advisory` or
# `enforced: planned`, remember its line number and scan the next few lines
# for target_enforce_date. `enforced: enforce`, `enforced: block`,
# `enforced: active`, and `enforced: deferred` are out of scope (deferred is
# a distinct, deliberately-external-dependency state this ADR does not cover
# — see infra_apply's own long-horizon date as the pattern for that case
# instead of a bare "deferred").
awk -v today="$today" '
  /enforced:[[:space:]]*(advisory|planned)([[:space:]]|$|#)/ {
    # A new enforced: line while the previous one is still unresolved means
    # the previous one never got its target_enforce_date within the lookahead
    # window — flag it as MISSING now, dont let it be silently overwritten.
    if (pending) print pending ":MISSING"
    pending = NR; next
  }
  pending && /target_enforce_date:[[:space:]]*"?[0-9]{4}-[0-9]{2}-[0-9]{2}"?/ {
    line = $0
    sub(/.*target_enforce_date:[[:space:]]*"?/, "", line)
    sub(/".*/, "", line)
    print pending ":" line
    pending = 0
    next
  }
  # Any other non-blank, non-comment-only line closes the lookahead window
  # (target_enforce_date must be within a few lines of its enforced: line).
  pending && NF && !/^[[:space:]]*#/ && (NR - pending > 3) {
    print pending ":MISSING"
    pending = 0
  }
  END {
    if (pending) print pending ":MISSING"
  }
' "$rules" > /tmp/.gate-graduation-findings.$$

while IFS=: read -r lineno date; do
  checked=$((checked + 1))
  if [ "$date" = "MISSING" ]; then
    echo "::error file=$rules,line=$lineno::advisory/planned rule has no target_enforce_date within 3 lines (ADR-0144: a rule may not be added without one)."
    fail=1
  elif [[ "$date" < "$today" ]]; then
    echo "::error file=$rules,line=$lineno::target_enforce_date $date has passed and this rule is still advisory/planned (ADR-0144). Either ship the producer and flip to enforce/block, or move the date forward with a one-line reason in the commit body."
    fail=1
  fi
done < /tmp/.gate-graduation-findings.$$
rm -f /tmp/.gate-graduation-findings.$$

echo "check-gate-graduation: $checked advisory/planned rule(s) checked, $( [ "$fail" -eq 0 ] && echo "all carry a live target_enforce_date." || echo "VIOLATIONS above." )"
exit "$fail"
