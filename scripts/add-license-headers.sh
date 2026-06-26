#!/usr/bin/env bash
# Add SPDX MPL-2.0 license headers to source files that lack them.
#
# MPL-2.0 itself does not require per-file headers (unlike Apache-2.0 / GPL),
# but SPDX identifiers improve license-scanner accuracy and downstream clarity.
#
# Usage:
#   ./scripts/add-license-headers.sh            # dry-run, list files that would be modified
#   ./scripts/add-license-headers.sh --apply    # actually modify files

set -euo pipefail

APPLY="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

KT_HEADER='// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
'

TS_HEADER='// SPDX-License-Identifier: MPL-2.0
// Copyright (c) OpenBank contributors. Licensed under the Mozilla Public License 2.0.
// See LICENSE in the repository root or https://www.mozilla.org/MPL/2.0/ for details.
'

count_total=0
count_modified=0

process_file() {
  local file="$1"
  local header="$2"
  count_total=$((count_total + 1))

  if head -3 "$file" | grep -q "SPDX-License-Identifier"; then
    return
  fi

  count_modified=$((count_modified + 1))
  if [[ "$APPLY" == "--apply" ]]; then
    local tmp
    tmp="$(mktemp)"
    { printf '%s\n' "$header"; cat "$file"; } > "$tmp"
    mv "$tmp" "$file"
    echo "  modified: $file"
  else
    echo "  would modify: $file"
  fi
}

echo "== Kotlin sources =="
while IFS= read -r -d '' f; do
  process_file "$f" "$KT_HEADER"
done < <(find "$ROOT" \
  -type d \( -name node_modules -o -name build -o -name .gradle -o -name .next -o -name attic -o -name .sisyphus \) -prune -o \
  -type f \( -name '*.kt' -o -name '*.kts' \) -print0 2>/dev/null)

echo "== TypeScript / TSX sources =="
while IFS= read -r -d '' f; do
  process_file "$f" "$TS_HEADER"
done < <(find "$ROOT" \
  -type d \( -name node_modules -o -name build -o -name .gradle -o -name .next -o -name attic -o -name .sisyphus -o -name dist \) -prune -o \
  -type f \( -name '*.ts' -o -name '*.tsx' \) -print0 2>/dev/null)

echo
echo "Files scanned:    $count_total"
echo "Files to modify:  $count_modified"
if [[ "$APPLY" != "--apply" ]]; then
  echo
  echo "Dry-run. Re-run with --apply to actually modify files."
fi
