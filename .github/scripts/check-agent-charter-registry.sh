#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# Agent charter parity gate (ADR-0031 / ADR-0156 "agent charters as markdown").
#
# WHY THIS EXISTS
#
#   openbank-libs/governance/agents.yaml is the enforced, machine-readable agent
#   registry (consumed by the OPA gate + the agent runtime). docs/agents/<id>.md
#   is its narrative companion, read by humans and rendered by the admin-ui at
#   /iaops/agents/<id>. The two are deliberately separate files maintained by
#   hand — which means they can silently drift apart the same way the ADR
#   registry did before check-adr-registry.sh existed (see that script's header
#   for the 2026-06 defect class this mirrors): an agent added to agents.yaml
#   with no matching narrative doc, or a stale doc left behind for an agent that
#   was removed.
#
#   This gate makes drift structurally impossible to merge: every agents.yaml
#   agent id needs a docs/agents/<id>.md file, and every docs/agents/<id>.md
#   file needs a matching agents.yaml agent id. It does NOT check content parity
#   (the whole point of the split is that the Markdown is prose, not a second
#   copy of enforced fields) — see docs/agents/README.md for that boundary.
#
# Exit: 0 = clean, 1 = at least one violation.
# -----------------------------------------------------------------------------
set -euo pipefail

AGENTS_YAML="${1:-openbank-libs/governance/agents.yaml}"
DOCS_DIR="${2:-docs/agents}"
cd "$(git rev-parse --show-toplevel 2>/dev/null || echo .)"

if [[ ! -f "$AGENTS_YAML" ]]; then
  echo "::error::check-agent-charter-registry: '$AGENTS_YAML' not found." >&2
  exit 1
fi
if [[ ! -d "$DOCS_DIR" ]]; then
  echo "::error::check-agent-charter-registry: docs directory '$DOCS_DIR' not found." >&2
  exit 1
fi

fail=0
err() { echo "::error title=Agent charter registry::$*" >&2; fail=1; }

# --- ids declared in agents.yaml ---------------------------------------------
# Plain grep, not a YAML parser: every entry is a top-level list item shaped
# exactly "  - id: <id>" under the `agents:` key (matches the file's own style
# guide — see the header comment in agents.yaml). Avoids a Python/yq dependency
# for a two-field extraction.
yaml_ids=$(grep -E '^  - id:' "$AGENTS_YAML" | sed -E 's/^  - id:[[:space:]]*//' | sort -u)
if [[ -z "$yaml_ids" ]]; then
  echo "::error::check-agent-charter-registry: no '  - id: <id>' entries found under agents: in '$AGENTS_YAML'." >&2
  exit 1
fi

# --- ids with a narrative doc --------------------------------------------------
shopt -s nullglob
doc_files=("$DOCS_DIR"/*.md)
doc_ids=$(
  for f in "${doc_files[@]}"; do
    base=$(basename "$f" .md)
    [[ "$base" == "README" ]] && continue
    echo "$base"
  done | sort -u
)

# --- 1. Every agents.yaml id has a matching doc ------------------------------
while IFS= read -r id; do
  [[ -z "$id" ]] && continue
  if [[ ! -f "$DOCS_DIR/$id.md" ]]; then
    err "agents.yaml declares agent '$id' with no matching '$DOCS_DIR/$id.md'. Add the narrative charter (see $DOCS_DIR/README.md)."
  fi
done <<< "$yaml_ids"

# --- 2. Every doc has a matching agents.yaml id ------------------------------
while IFS= read -r id; do
  [[ -z "$id" ]] && continue
  if ! grep -qxF "$id" <<< "$yaml_ids"; then
    err "'$DOCS_DIR/$id.md' has no matching agent id in agents.yaml. Either the agent was renamed/removed (delete the stale doc) or the id is a typo."
  fi
done <<< "$doc_ids"

# --- 3. Frontmatter 'id:' matches the filename -------------------------------
# Catches a copy-pasted charter left with the wrong frontmatter id (the
# docs/adr H1-vs-filename off-by-one defect class, same root cause: hand-copy).
for f in "${doc_files[@]}"; do
  base=$(basename "$f" .md)
  [[ "$base" == "README" ]] && continue
  fm_id=$(awk '/^---$/{n++; next} n==1 && /^id:/{sub(/^id:[[:space:]]*/,""); print; exit} n>=2{exit}' "$f")
  if [[ "$fm_id" != "$base" ]]; then
    err "$f: frontmatter 'id: $fm_id' does not match filename '$base.md'."
  fi
done

if [[ "$fail" -ne 0 ]]; then
  echo "::error::check-agent-charter-registry: agent charter registry has integrity violations (see above)." >&2
  exit 1
fi
agent_count=$(grep -c . <<< "$yaml_ids")
echo "check-agent-charter-registry: OK — $agent_count agent(s), full parity between agents.yaml and $DOCS_DIR/."
