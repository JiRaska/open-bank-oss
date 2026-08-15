---
date: 2026-07-08
decision-status: accepted
delivery-status: shipped
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, admin-ui, docs]
summary: "Add a per-agent Markdown narrative layer under docs/agents alongside agents.yaml, with a CI id-parity gate and an admin-ui drill-down page, while enforced fields stay singly sourced in the YAML."
---

# ADR-0156 — Agent charters as Markdown alongside agents.yaml

**Delivery note (2026-07-10):** all four decision points are merged and live —
`docs/agents/<id>.md` charters + the id-parity CI gate (PR #598), the admin-ui
bundling, BFF route and `/iaops/agents/<id>` drill-down page (PR #599, deployed via
`admin-ui-deploy.yml` on 2026-07-09), and the `ProposalResource` `agentId` filter with
the `openapi.yaml` documentation of `/api/v1/proposals*` (PR #600, released as
agent-service 1.14.0). The finops cost bridge mentioned in
`docs/agents/finops-agent.md` remains a separate, out-of-scope follow-up.

## Context

`agents.yaml` (ADR-0031 D1) is the single machine-readable source of truth for what each AI agent
may see, call, and do — it is consumed directly by the OPA policy gate and the agent runtime, and
the admin-ui's `/iaops` page already parses it live to render an "Agent roster" of charter cards.

That roster is flat and dense: seven agents, each card packed with tool-tier chips, data-scope
lists and limits, because the YAML charter is the only source the page has to draw from. Two gaps
follow from that:

1. **No narrative.** `agents.yaml` intentionally carries only what OPA and the runtime need to
   enforce — allow/deny lists, limits, `requires_human` triggers. It does not, and should not,
   carry the *why*: why `ui-assistant`'s scope was cut back after a pentest finding, why
   `finops-agent` only ever opens a PR and never writes to AWS, what's still a stub in
   `devops-agent`. That context currently lives scattered across ADRs, PR descriptions and this
   session's own conversation — not in one place a reviewer or auditor can read per agent.
2. **No drill-down.** The roster is a dead-end: seven cards, no click-through, no way to see one
   agent's proposal history, cost trend or kill-switch state without leaving the page and querying
   `/approvals`, `/api/finops/ai-costs` and the agent-service API separately.

Both gaps get worse, not better, as more agents are added — flat cards get denser, and tribal
knowledge about each agent's history gets harder to reconstruct.

## Decision

We will add a **Markdown narrative layer** alongside `agents.yaml`, and use it to power a
**per-agent drill-down page** in the admin-ui.

1. **`docs/agents/<id>.md`** — one file per agent, `<id>` matching an `agents.yaml` `agents[].id`
   exactly. Prose only: mission, why the agent exists, the human-oversight story, known gaps. It
   never restates an enforced field (tool lists, limits, data scope) — those stay singly-sourced in
   `agents.yaml`; the Markdown links to it instead. This mirrors the existing `docs/adr/` +
   `docs/threat-models/` split: YAML/code is what's enforced, Markdown is what's explained.
2. **`.github/scripts/check-agent-charter-registry.sh`** (gate `agent-charter-registry-parity`
   in `.github/gates/gates.yaml`; the standalone `agent-charter-registry.yml` workflow it
   originally shipped with was a duplicate of that gate and was removed in #4339) — a CI
   gate enforcing full id parity between `agents.yaml` and `docs/agents/*.md`, mirroring
   `check-adr-registry.sh`'s role for the ADR registry. It checks parity only, deliberately not
   content — content sync stays a PR-review concern, the same way an ADR's prose isn't
   mechanically checked against the code it describes.
3. **Admin-ui**: the existing `docs/adr` + `docs/threat-models` Dockerfile collector pattern gets a
   third corpus, `docs/agents`, bundled into the image the same way. A new BFF route merges the
   live `agents.yaml` charter with the bundled Markdown and the agent's proposal history (filtered
   by `proposed_by`), and a new page at `/iaops/agents/<id>` renders both together. The existing
   roster cards on `/iaops` become links into that page instead of terminal dead-ends.
4. **`ProposalResource`** (`openbank-agent-service`) gains an optional `agentId` filter on
   `GET /api/v1/proposals` — no schema change, since `proposed_by` already identifies the
   proposing agent; this is a query-parameter addition, documented in `openapi.yaml` alongside the
   endpoint (previously undocumented — fixed as part of this change, not scope creep, since the
   endpoint is the one being extended).

## Alternatives considered

- **Make Markdown the source of truth, generate `agents.yaml` from it.** Rejected for now — it
  would require re-deriving the OPA Rego bundle generation and the runtime's charter loader from a
  Markdown parser instead of YAML, a materially bigger and riskier change than adding a narrative
  layer next to an already-enforced file. The chosen split gets most of the value (a place to write
  and read the *why*) at a fraction of the risk. Revisiting the direction is possible later without
  re-architecting: the registry gate only checks id parity, not which file is "primary".
- **Put the narrative directly in the ADRs that already govern each agent** (ADR-0031/0112/0119/
  0088/0089). Rejected — an ADR records a decision at a point in time; it is not meant to be a
  living per-agent status page that changes as an agent's known gaps close. Splitting narrative
  into its own per-agent file lets it be updated on its own PR cadence without reopening or
  amending a settled ADR.
- **Skip the Markdown layer, just make the roster cards on `/iaops` link to a detail page that
  re-renders the same YAML fields, larger.** Rejected — solves the drill-down gap but not the
  narrative gap; a bigger card with the same fields still can't answer "why is this agent scoped
  this way".

## Consequences

**Positive**
- Each agent's history and reasoning has one canonical, reviewable location instead of being
  scattered across ADRs and tribal knowledge.
- The drill-down page turns `/iaops` from a status board into a starting point for investigating a
  specific agent — its charter, its proposal history, and (once the finops cost bridge referenced
  in `docs/agents/finops-agent.md` lands) its running cost, together.
- The registry gate makes the two-file split self-enforcing: an agent added to `agents.yaml` with
  no narrative doc fails CI instead of silently shipping undocumented.

**Negative**
- Two files per agent to keep roughly in sync (id, at minimum) instead of one. Mitigated by the
  registry gate checking structural parity, and by the explicit rule that content never duplicates
  enforced fields — there is nothing for the two files to disagree about substantively, only
  narrative staleness, which is a normal doc-review concern.
- The admin-ui BFF route for the drill-down page depends on `ProposalResource`'s new `agentId`
  filter; until that PR lands, the detail page's proposal-history section degrades through the
  existing graceful-state rule (admin-ui CLAUDE.md rule #1) rather than blocking.

**Neutral**
- Does not change anything about what any agent is allowed to do — `agents.yaml`, the OPA gate and
  the runtime are untouched. This is a documentation and read-only-UI change layered on top of an
  already-enforced system.

## Compliance impact

- PCI DSS: neutral — no change to least-privilege enforcement (Req. 7); the narrative layer
  documents *why* scopes are drawn as they are, supporting Req. 12 security-policy documentation.
- DORA: neutral-to-positive — Art. 13 (ICT third-party/model provider register) and Art. 17
  (incident reconstruction) both benefit from a documented per-agent history that's easier to
  produce on request than reconstructing it from ADRs and PR history each time.
- GDPR: not applicable — no change to data scope or processing; PII masking stays enforced in
  `agents.yaml`, unchanged.
- PSD2: not applicable — no change to SCA/consent gating.
- CNB: neutral-to-positive — supports auditability (a documented per-agent oversight story) without
  changing the underlying deny-by-default enforcement.

## References

- ADR-0031 (AI agent governance and operations — `agents.yaml` as source of truth, the roadmap this
  decision extends)
- ADR-0112 (AI-FinOps Agent), ADR-0119 (AI DevOps Agent), ADR-0088 (HolmesGPT RCA Agent), ADR-0089
  (Customer Copilot) — the per-agent ADRs whose narrative this change gives a durable home outside
  the ADR itself
- ADR-0029 (versioning, release and governance as code — the `docs/adr/` + `docs/threat-models/`
  collector pattern this change extends to `docs/agents/`)
- ADR-0048 (two independent version axes — the `openapi.yaml` update accompanying the
  `ProposalResource` `agentId` filter follows this rule)
- `docs/agents/README.md` — the convention this ADR establishes, in its operational form
