---
date: 2026-07-30
decision-status: accepted
delivery-status: shipped
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, ai-agents, security-ops]
summary: "MCP tool discovery is capability-shaped: tools/list evaluates each tool's OPA capability against the caller and returns only callable tools — the model never learns about forbidden operations."
---

# ADR-0225 — Policy-filtered MCP tool discovery

Relates: ADR-0031 (agent governance), ADR-0034 (unified OPA),
ADR-0224 (human-operator MCP channel — the caller-identity source that
makes per-caller filtering meaningful for staff).

## Context

Today `McpEndpoint` answers `tools/list` with the FULL registry
(`ToolsListResult(registry.tools)`); authorization happens only at
`tools/call` time (absent capability mapping = refused, OPA deny =
refused). Discovery therefore reveals the entire operational vocabulary to
every caller, including tools the caller can never invoke.

That is a real, if quiet, exposure:

1. **Prompt-injection surface.** A model that can see a tool's name,
   description and input schema can be talked into attempting it; the deny
   at call time stops the damage but not the attempt, and every attempt is
   an audit-log noise event an attacker can use for reconnaissance.
2. **Reconnaissance.** The tool list is an unauthenticated-in-spirit map of
   bank operations (it is authenticated, but not capability-shaped) —
   exactly the "unauthenticated-info-disclosure" class ADR-0080 P0 closed
   for REST, one layer down.
3. **`tools/list` is not audited at all today** (it "touches no data"), so
   discovery reconnaissance leaves no trace.

The world-class pattern for governed agent surfaces is capability-shaped
discovery: you cannot see what you cannot call.

## Decision

We will make MCP tool discovery policy-filtered and audited.

**D1 — `tools/list` is authorized per caller.** For each registered tool,
its OPA capability mapping is evaluated against the caller principal; only
callable tools are returned. Evaluation is a single batched PDP query (not
N round-trips), with a short-TTL per-principal cache; PDP unavailability
fails closed to an empty list.

**D2 — Only set membership is filtered, never schemas.** Every caller sees
the same schema for a tool they share; per-caller schema mutation is
forbidden (client stability, cacheability).

**D3 — Deny semantics are unchanged.** Calling a tool that was filtered out
(or is unknown) returns the same error as today's absent-capability deny —
fail-closed behaviour does not depend on the caller having seen the list.

**D4 — `tools/list` calls are audited.** Low-volume and security-relevant:
principal, tool count returned, and filter outcome are recorded. On role or
session change the server SHOULD emit `notifications/tools/list_changed`
so well-behaved clients re-list.

## Alternatives considered

- **Status quo — full registry, call-time deny** — rejected: the deny stops
  damage but leaks the operations map and invites injection attempts
  (Context items 1–3).
- **Static per-charter tool lists** — rejected: a second source of truth
  outside OPA; it would drift from the actual policy, and ADR-0034's
  single-PDP model exists precisely to avoid that.
- **List names, hide schemas** — rejected: names alone reveal the
  operations vocabulary; half-measures keep the reconnaissance value.

## Consequences

**Positive**
- The model's action space shrinks to what policy allows — prompt-injection
  has fewer tools to aim at, by construction.
- Discovery reconnaissance becomes visible (D4) and less rewarding (D1).
- No protocol deviation: filtering membership is within MCP semantics, and
  `tools/list_changed` is the spec's own invalidation mechanism.

**Negative**
- A PDP round-trip (batched, cached) joins the discovery path; a PDP outage
  makes clients see zero tools (fail-closed — availability trade-off
  accepted deliberately).
- Clients that cache the list need `list_changed` handling to notice
  privilege changes promptly; misbehaving clients learn at next call-time
  deny, same as today.

**Neutral**
- None.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data environment scope change.
- DORA: reduced attack surface and auditable discovery support ICT risk
  reduction and monitoring duties.
- GDPR: not applicable — tool metadata carries no personal data.
- PSD2: not applicable — discovery filtering does not change consent or SCA
  semantics (ADR-0195 governs those at call time).
- CNB: not applicable — no prudential reporting scope change.

## References

- openbank-mcp-service McpEndpoint (`tools/list` → full registry today),
  McpToolRegistry (capability mapping, absent = refused)
- ADR-0031/0034 (agents gate, unified OPA), ADR-0080 (info-disclosure
  class), ADR-0224 (staff caller identity)
- MCP specification: `tools/list`, `notifications/tools/list_changed`
