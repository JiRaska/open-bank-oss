---
date: 2026-08-05
decision-status: proposed
delivery-status: partial
followup: "#5708 — backend read model is live; the read-only Control Room UI and evidence provenance are in delivery"
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, admin-ui, observability, governance]
summary: "The admin-ui swarm thread view is a read-only projection of Temporal case-workflow history — a chronological, per-agent-attributed thread under /iaops, with no new data model, no graph dependency, and no case mutation from the UI."
---

# ADR-0246 — Admin-ui swarm thread view over Temporal case history

**Delivery note (2026-08-19).** The backend half is live on `main` while this ADR read `delivery-status: planned`: `CaseThread` (*"Read model for the ADR-0246 swarm thread view (Phase 2, #4185)"*), `CaseThreadProjection` (*"Pure projection from persistence rows to the ADR-0246 thread view"*) and `CaseCoordinatorResource`'s ADR-0246 thread endpoint, in `openbank-case-coordinator-agent`. The admin-ui rendering this ADR is named for is the remaining half, which is why this is `partial` and not `shipped`. Found by the code->ADR evidence read described in #5708.

**Delivery clarification (2026-08-22).** `agents.yaml` is a charter registry, not
runtime proof. A solid topology edge may be rendered only from an observed durable
record: a persisted contribution, an event published by the transactional outbox,
a Temporal-history observation, or an OPA allow decision. The current projection
exposes the first two with source, evidence id, correlation id, observation time
and precise stage. Persistence is not mislabeled as a separately observed Temporal
consumption event. An outbox `SENT` status proves publication to the broker, not
consumption by the HITL inbox. Declared-only relationships remain non-runtime
claims and never become solid edges.


## Context

ADR-0244 introduces agent swarms: chartered agents collaborating in real time on one
case workflow, joining mid-run via signals, pre-empting steps and synthesizing a
single HITL proposal. That machinery is invisible to operators today — and an
orchestration nobody can see reads as magic, not as governance.

What the admin-ui already offers on the agent plane: `/iaops` (charters from
`agents.yaml`, cost anomalies, agent decisions), `/iaops/agents/[agentId]` (charter
drill-down, ADR-0156), `/system/agent` (MCP tool inventory), `AgentDock` (chat) and
`AgentInsightsPanel` (canonical surface for agent findings on operator pages). None
of these shows *orchestration*: who is working which case, what was contributed,
what was pre-empted, and why the swarm concluded what it concluded. There is no
graph/visualization library in the admin-ui (mermaid renders docs only).

The key enabler is already decided: ADR-0244 D2/D5/D7 put every swarm event —
accepted and rejected signals, pre-emptions, synthesis — into Temporal workflow
history as AI-attributed audit. The visualization is therefore a *projection
problem*, not a data problem.

Constraints inherited from the estate:

- **ADR-0056**: the admin-ui BFF is the sole browser→cluster path.
- **ADR-0226**: one identity, one audit trail — the UI surface is channel `ui` of
  the same trail the MCP channel writes.
- **ADR-0227**: disposition lives in the unified approval inbox, never scattered
  across pages.
- **ADR-0231**: no mock data in production UI — a surface either shows live data or
  an honest empty state.
- **ADR-0244**: case content exists only once case workflows exist; this ADR ships
  no fake threads.

## Decision

**D1 — The thread view is a read-only projection of durable case runtime evidence.**
The swarm thread page renders the case workflow's persisted read model and
transactional-outbox evidence — case opened,
contribution accepted/rejected (with contributor principal id and type), step
pre-empted (`superseded-by-evidence`), budget state, synthesis, outcome — mapped
one-to-one from observed records. A later Temporal-history adapter may add another
evidence source, but absence of that adapter is shown honestly. No new write path
or second orchestration store is introduced.

**D2 — Rendering offers a thread, evidence timeline and minimal topology.** The
thread remains the canonical chronological view with per-agent attribution. The
topology is a dependency-free list of observed edges, not a workflow designer or
a second control plane. Solid edges require runtime evidence; charter-only claims
are never promoted to facts. Each observation has expandable provenance.

**D3 — The view lives under `/iaops` and reuses the BFF path.** The page sits in
the agent-ops information architecture (`/iaops/cases`, drill-down
`/iaops/cases/[caseId]`), linked from the agent detail page and from
`AgentInsightsPanel` when a finding has a backing case. Data flows over the
admin-ui BFF (ADR-0056), which reads the projection from a read port on the
agent-plane side; the browser never talks to Temporal.

**D4 — Authorization mirrors the agent governance model.** Viewing a case requires
the operator's role to pass the same OPA sidecar check that governs the rest of
`/iaops`; case lists are capability-filtered in the spirit of ADR-0225 — an
operator sees only cases their role permits. PII stays masked per charter defaults
(ADR-0031); every view joins the cross-channel audit trail as channel `ui`
(ADR-0226).

**D5 — The UI never mutates a case.** The thread view offers no signal sending, no
invitations, no budget edits. Disposition of the case's outcome proposal happens
only in the unified approval inbox (ADR-0227). Human participation *inside* a
running case via the MCP on-behalf-of channel (ADR-0224) is a possible later slice
with its own action-class review — it is explicitly out of v1.

**D6 — Status honesty over demo appeal.** Until case workflows run in an
environment, the page renders an explicit empty state ("no cases — case workflows
are not deployed here"), never a fabricated thread (ADR-0231). The page must not
repeat the "healthy service rendered as not deployed" failure class: it
distinguishes *no cases*, *projection unavailable* and *case plane not deployed*
as three different states.

## Alternatives considered

- **Graph/DAG visualization first (reactflow, dagre)**: rejected for v1 — a new
  dependency to render structure the linear history does not have; the thread is
  the honest projection. Revisit once multi-case fleet views exist.
- **Embedding the Temporal Web UI**: rejected — a separate tool with its own authn,
  no OPA role filtering, and it exposes workflow internals (payloads, retries)
  rather than a governed, PII-masked operator view.
- **Waiting for the unified approval inbox (ADR-0227)**: the inbox answers "what
  must I decide", the thread view answers "what is the swarm doing" — orthogonal
  questions; the inbox links into the thread for context, it does not replace it.

## Consequences

- Operators gain visibility into swarm orchestration with zero new orchestration
  infrastructure: the projection exposes durable records the case coordinator
  already writes.
- v1 needs one read port serving the history projection to the BFF — the only new
  server-side surface, read-only by construction.
- The thread view becomes the natural link target for proposals in the ADR-0227
  inbox ("see how the swarm got here"), raising the value of both.
- Contested outcomes (ADR-0244 D6) render their dissent inline — the timeline
  format makes disagreement visible without extra UI machinery.
- Follow-ups: the history-projection read port; BFF route + page implementation;
  link from `AgentInsightsPanel`; later, the ADR-0208-style interactive explainer
  and (separately reviewed) human case participation over ADR-0224.

## Compliance impact

Read-only operator surface under the existing OPA model; no new data processing —
the projection republishes already-audited events to the same roles that may read
the audit trail. PII masking inherited from charters (ADR-0031); EU AI Act posture
unchanged (human disposes, machine proposes — now visibly so, which strengthens the
human-oversight evidence). No money-path interaction.

## References

- ADR-0244 (agent swarm coordination), ADR-0031 (agent governance),
  ADR-0056 (BFF sole path), ADR-0156 (charter drill-down),
  ADR-0208 (interactive flow explainer), ADR-0224 (MCP OBO channel),
  ADR-0225 (policy-filtered discovery), ADR-0226 (cross-channel audit),
  ADR-0227 (unified approval inbox), ADR-0231 (no mock data)
