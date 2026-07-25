#!/usr/bin/env bash
# Add SPDX license headers to source files that lack them -- PATH-AWARE.
#
# This repository is a MULTI-LICENSE tree (ADR-0136): the platform is Apache-2.0 and the
# agent-plane services are AGPL-3.0-only + a parallel commercial licence (open-core). The
# header a file gets therefore depends on WHICH MODULE it lives in.
#
# The AGPL module list is read at runtime from the single source of truth,
# openbank-libs/governance/rules.yaml -> dependencies.license_boundary_exceptions[0].agpl_modules.
# It is never duplicated here: this script hardcoding Apache-2.0 for every path is what
# stamped Apache-2.0 headers into AGPL modules and let the published NOTICE understate the
# AGPL set (#2280 -- the ADR-0136 "make this script path-aware" follow-up).
#
# Scope: .kt/.kts/.ts/.tsx only. Deliberately NOT .sql -- Flyway checksums migration files
# including comments, so stamping a header onto an applied migration makes every affected
# service fail at boot with "Migration checksum mismatch". Existing .sql files with the wrong
# licence are declared out-of-tree in REUSE.toml instead.
#
# Usage:
#   ./scripts/add-license-headers.sh            # dry-run, list files that would be modified
#   ./scripts/add-license-headers.sh --apply    # actually modify files
#
# Verify the result with: .github/scripts/check-license-headers.py

set -euo pipefail

APPLY="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RULES="$ROOT/openbank-libs/governance/rules.yaml"

APACHE_HEADER='// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.
'

AGPL_HEADER='// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) OpenBank contributors. Licensed under the GNU Affero General Public License v3.0 only.
// A commercial licence is available from the maintainers as an alternative to the AGPL-3.0.
// See LICENSES/AGPL-3.0-only.txt or https://www.gnu.org/licenses/agpl-3.0.html for details.
'

# Canonical AGPL module list, straight out of rules.yaml. Fail loudly rather than silently
# falling back to "everything is Apache-2.0" -- that fallback is the original bug.
if [[ ! -f "$RULES" ]]; then
  echo "FATAL: cannot read $RULES -- refusing to guess licences." >&2
  exit 1
fi
AGPL_MODULES="$(python3 -c '
import sys, yaml
with open(sys.argv[1], encoding="utf-8") as fh:
    doc = yaml.safe_load(fh) or {}
ex = (doc.get("dependencies") or {}).get("license_boundary_exceptions") or []
mods = ex[0].get("agpl_modules") if ex else None
if not mods:
    sys.exit("FATAL: dependencies.license_boundary_exceptions[0].agpl_modules missing/empty")
print("\n".join(mods))
' "$RULES")"

echo "AGPL-3.0-only modules (from rules.yaml): $(echo "$AGPL_MODULES" | wc -l | tr -d ' ')"

# Is the file inside an AGPL module? Compare on the repo-relative first path segment.
license_for() {
  local rel="${1#"$ROOT"/}"
  local module="${rel%%/*}"
  if grep -qxF "$module" <<<"$AGPL_MODULES"; then
    echo agpl
  else
    echo apache
  fi
}

count_total=0
count_modified=0
count_agpl=0

process_file() {
  local file="$1"
  count_total=$((count_total + 1))

  # A file that already declares a licence is left alone. Whether that declaration is the
  # CORRECT one is check-license-headers.py's job, not this script's -- rewriting an existing
  # header here would silently relicense code.
  if head -3 "$file" | grep -q "SPDX-License-Identifier"; then
    return
  fi

  local header kind
  kind="$(license_for "$file")"
  if [[ "$kind" == agpl ]]; then
    header="$AGPL_HEADER"
    count_agpl=$((count_agpl + 1))
  else
    header="$APACHE_HEADER"
  fi

  count_modified=$((count_modified + 1))
  if [[ "$APPLY" == "--apply" ]]; then
    local tmp
    tmp="$(mktemp)"
    { printf '%s\n' "$header"; cat "$file"; } > "$tmp"
    mv "$tmp" "$file"
    echo "  modified [$kind]: $file"
  else
    echo "  would modify [$kind]: $file"
  fi
}

echo "== Kotlin sources =="
while IFS= read -r -d '' f; do
  process_file "$f"
done < <(find "$ROOT" \
  -type d \( -name node_modules -o -name build -o -name .gradle -o -name .next -o -name attic -o -name .sisyphus \) -prune -o \
  -type f \( -name '*.kt' -o -name '*.kts' \) -print0 2>/dev/null)

echo "== TypeScript / TSX sources =="
while IFS= read -r -d '' f; do
  process_file "$f"
done < <(find "$ROOT" \
  -type d \( -name node_modules -o -name build -o -name .gradle -o -name .next -o -name attic -o -name .sisyphus -o -name dist \) -prune -o \
  -type f \( -name '*.ts' -o -name '*.tsx' \) -print0 2>/dev/null)

echo
echo "Files scanned:       $count_total"
echo "Files to modify:     $count_modified"
echo "  of which AGPL:     $count_agpl"
if [[ "$APPLY" != "--apply" ]]; then
  echo
  echo "Dry-run. Re-run with --apply to actually modify files."
fi
