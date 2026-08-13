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

# --- self-test ------------------------------------------------------------------------
# Every agent declared in agents.yaml must have a narrative charter, and every charter must
# name a declared agent. Both directions matter: an undocumented agent is one nobody reviews,
# and a stale charter describes powers some agent no longer has — which is worse than none,
# because it reads as current.
if [ "${1:-}" = "--self-test" ]; then
  set +e
  td=$(mktemp -d); trap 'rm -rf "$td"' EXIT
  fails=0
  # A charter carries frontmatter whose `id:` must equal its filename — the third thing this
  # gate checks, and the one my first fixtures missed entirely: they had no frontmatter, so
  # every case failed for a reason I had not read the script closely enough to expect.
  setup() { rm -rf "$td/docs"; mkdir -p "$td/docs"; printf '%b' "$1" > "$td/agents.yaml"; shift
            for d in "$@"; do printf -- '---\nid: %s\n---\n# charter\n' "$d" > "$td/docs/$d.md"; done; }
  expect() { local label="$1" want="$2" sub="${3:-}" out rc
    out=$(bash "$0" "$td/agents.yaml" "$td/docs" 2>&1); rc=$?
    if [ "$rc" -ne "$want" ]; then echo "::error::self-test: $label — want rc=$want got $rc: $out" >&2; fails=$((fails+1))
    elif [ -n "$sub" ] && ! printf '%s' "$out" | grep -qF -- "$sub"; then
      echo "::error::self-test: $label — rc right, reason wrong (no '$sub'): $out" >&2; fails=$((fails+1)); fi; }

  setup 'agents:\n  - id: alpha\n  - id: beta\n' alpha beta
  expect "matching ids and charters are clean" 0
  setup 'agents:\n  - id: alpha\n  - id: beta\n' alpha
  expect "a declared agent with no charter is FLAGGED" 1 "no matching"
  setup 'agents:\n  - id: alpha\n' alpha ghost
  expect "a charter with no declared agent is FLAGGED" 1 "no matching agent id"
  # README.md is documentation ABOUT the charters, not a charter — excluding it is deliberate.
  setup 'agents:\n  - id: alpha\n' alpha README
  expect "README.md is not treated as a charter" 0
  # The third direction: a charter whose frontmatter id disagrees with its filename. A rename
  # that updates one and not the other leaves a doc describing the wrong agent.
  setup 'agents:\n  - id: alpha\n' alpha
  printf -- '---\nid: something-else\n---\n' > "$td/docs/alpha.md"
  expect "frontmatter id must match the filename" 1 "does not match filename"
  # An agents.yaml with no ids is a parse failure, not an empty fleet: reporting clean there
  # would mean the gate passes hardest exactly when it can read nothing.
  setup 'agents: []\n' alpha
  expect "an agents.yaml with no ids FAILS rather than reporting clean" 1 "no '  - id: <id>' entries"

  if [ "$fails" -gt 0 ]; then echo "self-test FAILED ($fails case(s))" >&2; exit 1; fi
  echo "self-test ok: agent charter registry parity is falsifiable (6 cases)"
  exit 0
fi

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
# `|| true` is load-bearing: under `set -euo pipefail` a grep that matches nothing exits 1
# and kills the script HERE — before the explicit "no ids found" error below, which was
# therefore unreachable dead code. Measured: an agents.yaml with no ids failed the gate with
# rc=1 and ZERO output, so CI showed a red check and no reason for it. Found by writing this
# script's first self-test.
yaml_ids=$(grep -E '^  - id:' "$AGENTS_YAML" | sed -E 's/^  - id:[[:space:]]*//' | sort -u || true)
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
