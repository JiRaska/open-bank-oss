---
date: 2026-07-23
decision-status: proposed
delivery-status: partial
authors: [jiri.raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [ai-agents, psd2-api, authz]
summary: "Expose a first-party MCP server over curated PSD2/admin read tools plus the existing HITL write-proposal flow, every tool-call authorized by the ADR-0034 OPA PDP as principal type AI_AGENT — reusing the agent-governance plane."
followup: "#1922 — the ADR-0030-shape threat model and MCP spec-conformance suite this ADR calls a required deliverable, not a follow-up, are still outstanding"
---

# ADR-0181 — MCP server exposing PSD2 and admin read APIs to governed AI agents

**Delivery note (2026-08-18).** `openbank-mcp-service` is built and live in the sandbox
(port 8150, version 0.15.1): the 5 curated tools (4 read-only + `propose_payment` HITL) from the
Decision section, gated by the shared ADR-0034 OPA PDP as `AI_AGENT` (PR #2104 phase 1, PR #2142 +
#2152 phase-2 OPA wiring — `tools/call` verified live against both `agents.allow` and the REST
bridge). What this ADR's own Assurance clause treats as part of the deliverable rather than a
follow-up has not shipped: no `docs/threat-models/mcp-service.md`, and no verification against the
MCP spec conformance suite — both still gate enabling the surface outside the sandbox.

## Context

External agentic-banking demand is now concrete: Plaid and Stripe both run
hosted MCP servers over their banking APIs, and third-party "agentic banking"
directories track which institutions expose an MCP surface. No open-source
banking platform ships a first-party MCP server today.

This platform already owns the hard part. ADR-0031 defines an `AI_AGENT`
principal type, per-agent charters in `agents.yaml`, a kill switch, and
human-in-the-loop write proposals; ADR-0034 puts a single OPA PDP in front of
both MCP tool-calls and REST endpoints. `openbank-agent-service` already
verifies SPIFFE SVIDs, runs a prompt-injection guard, and records every run in
the ADR-0086 audit chain. What is missing is an actual MCP *transport* over the
read surfaces an external agent (a customer's assistant, a TPP's automation)
would want — balances, transactions, consents — plus a consent-scoped path to
the existing `propose_payment` HITL flow.

Doing this well is a category first and a live demonstration of the whole
agent-governance stack. Doing it badly is a data-exfiltration and
unauthorized-payment surface. The decision is about which, and the safeguards
that decide it.

## Decision

We will build `openbank-mcp-service`, a Model Context Protocol server
(streamable HTTP transport, MCP spec 2025-06 or later) that exposes a **curated,
read-first** tool set and reuses the existing governance plane end to end:

1. **Tools.** Consent-scoped reads — `list_accounts`, `get_balance`,
   `list_transactions`, `list_consents` — plus the existing HITL
   `propose_payment` write-proposal (never a direct debit; the proposal lands
   in the same maker-checker queue a human agent uses). No tool bypasses an
   existing REST/domain boundary; each is a thin MCP adapter over a
   customer-edge/PSD2 read that already exists.
2. **Authorization.** Every tool-call is authorized by the ADR-0034 OPA PDP
   with `input.principal.type == "AI_AGENT"` — no new authz plane. The scope an
   agent may reach is the intersection of its `agents.yaml` charter and the
   PSD2 consent presented (an MCP agent is modelled as a TPP-like consumer, so
   account access is exactly the consent's `grantedAccounts`).
3. **Identity and consent.** OAuth 2.1 per the MCP spec; the granted scope maps
   onto PSD2 consents (ADR-0126), so a revoked consent revokes the agent's
   reach with no separate mechanism. SPIFFE SVID verification and the
   prompt-injection guard from `openbank-agent-service` are reused, not
   reimplemented.
4. **Licensing.** Ships under AGPL-3.0-only, inside the ADR-0136 agent-services
   open-core boundary (it is an agent-plane service, dependency-direction
   enforced by `rules.yaml`).
5. **Assurance.** The service's system prompts (if any) register under
   ADR-0148; a threat model (ADR-0030 shape) ships with it, and conformance is
   proven against the MCP spec test suite plus one reference client before the
   surface is enabled outside the sandbox.

The differentiator claim is only worth making if it is demonstrably safe: the
threat model and the OPA policy bundle are part of the deliverable, not a
follow-up.

## Alternatives considered

- **Point external agents at the existing PSD2 REST/XS2A API directly, no MCP.**
  Rejected as a missed opportunity, not as wrong: XS2A already works for
  classic TPPs, but an MCP surface is what agent runtimes (Claude, others)
  actually discover and call, and shipping the first governed one in OSS
  banking is the point. XS2A remains for non-agent TPPs.
- **A hosted/third-party MCP gateway (wrap the API in a SaaS MCP proxy).**
  Rejected: it puts an un-audited translation layer and a second identity
  system outside the ADR-0086 audit chain and the ADR-0034 PDP, for a
  money-adjacent surface. First-party keeps every tool-call inside the same
  policy and audit plane as human and internal-agent access.
- **Expose write tools (direct payment execution) over MCP now.** Rejected for
  this ADR: reads plus the HITL proposal flow are the safe, demonstrable
  surface. Direct agent-initiated execution is deferred to ADR-0182 (AP2
  mandate verification), which is where the authorization-evidence model for an
  autonomous payment belongs.

## Consequences

**Positive**
- First governed MCP server in OSS banking; a concrete, external demonstration
  of the ADR-0031/0034 agent-governance stack rather than an internal-only one.
- Zero new authorization or audit primitives — the MCP surface inherits OPA
  deny-by-default, SPIFFE identity, the kill switch, and the audit chain.
- Consent revocation and charter scoping already bound the blast radius; an
  MCP agent can never see more than the presented PSD2 consent allows.

**Negative**
- A new externally-reachable surface with its own transport, OAuth flow, and
  spec-conformance burden to maintain as the MCP spec evolves.
- Prompt-injection and tool-confusion risk is real for any agent surface; the
  guard reduces but does not eliminate it (ADR-0031 already names prompt
  injection as the primary residual risk).

**Neutral**
- Modelling an MCP agent as a TPP-like PSD2 consumer reuses consent mechanics
  but ties MCP scope evolution to PSD2 consent evolution.

## Compliance impact

- PCI DSS: not applicable — no card data (PAN) is exposed by any tool.
- DORA:    ICT surface addition; the service enters the ADR-0174 register and its threat model (ADR-0030) is required before enablement.
- GDPR:    personal financial data is reachable via the read tools; access is bounded by PSD2 consent and OPA charter scope, and every access is audited (ADR-0086). Read tools return only the consented account set (data minimisation).
- PSD2:    an MCP agent is treated as a TPP-like consumer; account access is the presented consent's `grantedAccounts` (ADR-0126). SCA/dynamic-linking still governs any `propose_payment` outcome via the existing HITL flow.
- CNB:     not applicable — no new regulatory-reporting or supervisory-submission surface.

## References

- ADR-0031 — AI agent governance and operations
- ADR-0034 — Unified OPA authorization (MCP + REST)
- ADR-0126 — PSD2 consent lifecycle
- ADR-0136 — Agent services AGPL open-core boundary
- ADR-0148 — AI assurance (prompt registry, evals, EU AI Act mapping)
- ADR-0182 — AP2 agent-payment mandate verification (agent-initiated execution)
- Model Context Protocol specification
