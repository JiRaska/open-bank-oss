#!/usr/bin/env bash
# check-adr-status.sh — ADR status field gate (OSS-readiness E2)
#
# Every ADR in docs/adr/*.md (except TEMPLATE.md and README.md) must declare
# a decision status via one of the accepted patterns:
#
#   Status: Accepted          (frontmatter key, plain)
#   **Status:** Accepted      (bold Markdown inline label — asterisks wrap "Status:")
#   | Status | Accepted |     (plain table cell without bold)
#   | **Status** | Accepted | (bold table cell — asterisks wrap just "Status")
#   Decision-Status: Accepted (frontmatter variant used by some services)
#
# Valid values: Accepted, Proposed, Superseded, Deprecated
#
# Exit 0 when all ADRs pass; exit 1 on any violation found.
#
# Usage:
#   bash .github/scripts/check-adr-status.sh [docs/adr]
#
# The optional argument overrides the default search root (useful in tests).

set -euo pipefail

ADR_DIR="${1:-docs/adr}"
VIOLATIONS=0

for file in "$ADR_DIR"/*.md; do
  base="$(basename "$file")"

  # Skip meta-files that are not decision records.
  [[ "$base" == "TEMPLATE.md" ]] && continue
  [[ "$base" == "README.md" ]]   && continue

  # Accept any of the known patterns for a status declaration.
  #
  # Pattern breakdown:
  #   ^Status[[:space:]]*:          — "Status: Accepted" (plain frontmatter)
  #   ^\*\*Status:\*\*              — "**Status:** Accepted" (bold inline, colon inside)
  #   ^\|[[:space:]]*Status[[:space:]]*\|  — "| Status | Accepted |" (plain table)
  #   ^\|[[:space:]]*\*\*Status\*\*[[:space:]]*\|  — "| **Status** | Accepted |" (bold table)
  #   ^Decision-Status[[:space:]]*: — "Decision-Status: Accepted" (frontmatter variant)
  #   | Decision-Status  | Accepted | (table variant without colon)
  #
  # The value must be one of the four canonical words (checked on the same line).
  if grep -qE \
    '(^Status[[:space:]]*:|^\*\*Status:\*\*|^\|[[:space:]]*(\*\*)?Status(\*\*)?[[:space:]]*\||^Decision-Status[[:space:]]*:|^\|[[:space:]]*Decision-Status[[:space:]]*\|)[[:space:]]*(Accepted|Proposed|Superseded|Deprecated)' \
    "$file"; then
    continue
  fi

  echo "VIOLATION $file: no Status field"
  VIOLATIONS=$(( VIOLATIONS + 1 ))
done

if [[ "$VIOLATIONS" -eq 0 ]]; then
  echo "OK: all ADRs have a Status field"
  exit 0
else
  exit 1
fi
