---
date: 2026-07-30
decision-status: proposed
delivery-status: partial
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authn, authz, ai-agents]
summary: "Staff drive MCP tools under their own realm roles via RFC 8693 on-behalf-of exchange — bounded role subset, act-chain, short TTL; mutations follow an action-class propose/dispose matrix, never channel-based rules."
---

# ADR-0224 — Human-operator MCP channel: on-behalf-of token exchange with bounded role subsets

Extends: ADR-0195 (MCP caller authentication and consent binding).
Relates: ADR-0031 (agent governance), ADR-0034 (unified OPA),
ADR-0177 (workload identity, proposed), ADR-0223 (channel-agnostic authz).

## Context

The MCP plane (ADR-0181/0195) is built for EXTERNAL PSD2 agents: identity
`agent:<id>`, charter capabilities, live consent binding. Backoffice staff
with realm roles (`ROLE_OPERATOR`, `ROLE_COMPLIANCE`, …) have no identity
path to MCP at all — yet the same governed tool surface is exactly what an
operator copilot / AI assistant needs, and the platform direction
(ADR-0203/0217/0222) is agentic operations.

Three ways to admit a human operator were on the table:

1. **Token passthrough** — hand the operator's access token to an arbitrary
   MCP client. Rejected: the token is not audience-bound to mcp-service, the
   client is uncontrolled (CLI agents, IDEs), there is no privilege
   downgrade, and a leak equals full identity. This is the confused-deputy
   shape the MCP authorization guidance warns against.
2. **Charters-per-persona** — define agent charters mirroring staff roles.
   Rejected: it duplicates the role vocabulary into a second matrix — the
   defect class of #2404, which ADR-0223 D5 exists to kill.
3. **On-behalf-of token exchange (RFC 8693)** — the operator exchanges their
   token for an audience-restricted, role-bounded, short-lived token whose
   `act` (actor) claim records the agent session. OPA then evaluates the
   SAME role vocabulary as for REST (ADR-0034's whole point).

ADR-0195 already anticipates this trajectory: the token "MAY start as a
standard Keycloak grant … and SHOULD converge on RFC 8693 once ADR-0177
lands". ADR-0177 is still proposed, so this ADR must not hard-depend on it.

## Decision

We will admit human staff to the MCP plane as a second principal class via
on-behalf-of token exchange, with proposal semantics decided by action
class, not by channel or actor kind.

**D1 — Second principal class on mcp-service: HUMAN staff tokens.** The
operator's access token is exchanged at Keycloak (RFC 8693) for a token
that: is audience-restricted to `openbank-mcp-service`; carries
`roles = requested ∩ granted` (a bounded subset the operator actually
holds); carries an `act` claim identifying the agent session and client;
has a TTL in minutes. mcp-service validates it like any OIDC token and
classifies the principal as `HUMAN` (the classifier
`AuthorizeInterceptor.principalType()` already exists).

**D2 — Session binding instead of consent binding.** Staff hold no PSD2
consent; the binding equivalent is an **agent session** issued from the
admin UI after step-up authentication. The session records the role ceiling
and purpose. mcp-service validates the session LIVE on every call — the
revoke-aware pattern ADR-0195 established with consent-service — so ending
the session (or offboarding the operator) kills outstanding tokens'
usefulness immediately.

**D3 — Action-class matrix, not channel rules.** Money-path and destructive
actions (the same classification as ADR-0223 D4) are ALWAYS propose→dispose,
regardless of whether the maker is a human in the UI, a human via MCP, or an
AI agent — and disposal happens only in the governed UI surface with SCA,
never by free-text chat confirmation. Read-only and low-risk actions execute
directly under `@Authorize`. This removes today's inconsistency where the
UI shows a Freeze button that 403s on click.

**D4 — MCP client registry.** Only allow-listed clients (client_id +
attestation + per-client scope ceiling + kill-switch) may complete the
exchange. Two profiles: *embedded console* (chat surface inside admin UI —
the exchanged token never leaves the BFF; the BFF relays calls server-side)
and *external client* (the OBO token is handed to the client, bounded and
short-lived; DPoP sender-constraining is a documented follow-up, not a
blocker).

**D5 — OPA input carries the act chain.** For MCP calls by humans the PDP
input includes `principal.type=HUMAN`, the bounded roles, and
`act: [agent-session, client]`. Shared predicates (ADR-0034 D1) evaluate
identically to REST; policy can additionally gate on delegation depth and
client identity. Audit events carry channel + act chain (ADR-0226).

## Alternatives considered

- **Token passthrough** — rejected (Context item 1): no audience binding, no
  downgrade, uncontrolled clients, confused deputy.
- **Charters-per-persona** — rejected (Context item 2): duplicates the role
  matrix into a second vocabulary.
- **Sender-constrained tokens (DPoP/mTLS) from day one** — deferred: adds
  client-side key management to every MCP client before the first operator
  benefit lands; recorded as follow-up for external clients.

## Consequences

**Positive**
- One role vocabulary, two channels, one PDP — operators get agentic tooling
  without a parallel permission system.
- Bounded, short-lived, audience-restricted tokens cap the blast radius of
  an MCP client compromise far below an operator's full identity.
- propose/dispose by action class unifies the human and agent control
  philosophy the platform already shipped (ProposedOnly, SCA disposal).
- The embedded-console profile lets the operator copilot ship with zero
  token exposure to any client.

**Negative**
- Keycloak token-exchange flows and the session-validation call add latency
  and a runtime dependency on the identity plane per tool call (cached
  validation with short TTL mitigates; fail-closed on outage).
- Two client profiles double the client-governance surface (registry,
  attestation, kill-switch).

**Neutral**
- When ADR-0177 lands, session issuance SHOULD converge on
  platform-attested exchange; the resource-server contract (D1–D5) is
  unchanged, exactly as ADR-0195 predicted for its own evolution.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data environment scope change.
- DORA: act-chain audit and client registry support ICT asset control and
  incident forensics (third-channel access is inventoried and revocable).
- GDPR: bounded role subsets and purpose-bound sessions support
  data-minimisation and purpose limitation for staff access to PII.
- PSD2: D3 keeps per-transaction SCA at disposal for payment-affecting
  actions (ADR-0089 regime); no change to customer-facing SCA.
- CNB: not applicable — no prudential reporting scope change.

## References

- ADR-0181, ADR-0195 (MCP plane), ADR-0031/0034 (agent governance, unified
  OPA), ADR-0177 (workload identity), ADR-0203/0217/0222 (business-plane
  agents), ADR-0223/0225/0226 (this programme)
- RFC 8693 (OAuth 2.0 Token Exchange)
- openbank-mcp-service McpToolRegistry / McpEndpoint (capability mapping,
  absent = refused)
