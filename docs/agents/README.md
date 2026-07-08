# Agent charters — narrative companion to `agents.yaml`

Every AI agent running in OpenBank has **two** charter documents, deliberately split by audience:

| | [`openbank-libs/governance/agents.yaml`](../../openbank-libs/governance/agents.yaml) | `docs/agents/<id>.md` (this directory) |
|---|---|---|
| Audience | OPA policy gate + agent runtime | humans (operators, reviewers, auditors) |
| Content | data scope, tool allow/deny, `requires_human`, limits | mission, why it exists, human-oversight story, known gaps |
| Source of truth for | **enforcement** — what the agent may actually see/call | **narrative** — why those boundaries are drawn that way |
| Changed via | PR touching `agents.yaml` (OPA bundle + runtime reload) | PR touching the matching `.md` (no runtime effect) |

**Never duplicate enforced fields here.** A tool list or a token limit copy-pasted into prose will
drift the first time `agents.yaml` changes and nobody remembers to edit both files. If you need to
say what an agent can do, link to `agents.yaml` or the admin-ui roster (`/iaops`) — don't restate it.

## Convention

- One file per agent, named `<id>.md` where `<id>` matches an `agents:[].id` entry in `agents.yaml`
  exactly (e.g. `finops-agent.md` for `id: finops-agent`).
- Every agent in `agents.yaml` must have a matching file here, and vice versa — enforced by
  `.github/scripts/check-agent-charter-registry.sh` (see the `agent-charter-registry` CI check).
- Frontmatter carries only identifiers a human needs to navigate (`id`, `plane`, `adr`) — never
  policy data.

## Where this renders

The admin-ui bundles this directory into the `openbank-admin-ui` image (same pattern as
`docs/adr/` and `docs/threat-models/`) and serves it read-only at **Intelligence → agent roster →
click an agent** (`/iaops/agents/<id>`). The page merges this narrative with the live `agents.yaml`
charter, so an operator sees "why" and "what's actually enforced" side by side without leaving the
console.

## Governing decision

The split itself — and why a Markdown layer was added on top of an already-enforced YAML registry —
is recorded in [ADR-0156](../adr/0156-agent-charters-as-markdown-alongside-agents-yaml.md).
