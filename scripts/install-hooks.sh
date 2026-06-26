#!/usr/bin/env bash
# Install project git hooks. Run: bash scripts/install-hooks.sh
set -euo pipefail
REPO=$(git rev-parse --show-toplevel)
SRC="$REPO/scripts/git-hooks"; DST="$REPO/.git/hooks"
[[ -d "$SRC" ]] || { echo "ERROR: $SRC not found" >&2; exit 1; }
for hook in "$SRC"/*; do
  name=$(basename "$hook"); dst="$DST/$name"
  chmod +x "$hook"
  if [[ -L "$dst" && "$(readlink "$dst")" == "$hook" ]]; then echo "  already linked: $name"
  else ln -sf "$hook" "$dst"; echo "  installed: $name"; fi
done
echo "Done. Use 'git push --no-verify' to bypass."
