<!--
SPDX-License-Identifier: Apache-2.0
Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
-->
# Threat model — openbank-agent-service

STRIDE/DFD threat model for the governed agent runtime (the MCP policy-enforcement point + the
reasoning loop), per ADR-0030 D2. Money-path-adjacent: agents may *propose* but never *execute* on
the money path; the `deny` tool tier (`money.transfer`, `money.post.ledger`, `gh.pr.merge`,
`gh.pr.approve`, `secrets.read.raw`) is hard-forbidden for every charter.

- **Status:** Draft (first pass; opened with the ADR-0031 D3 identity-binding change)
- **Last reviewed:** 2026-06-28
- **Owner:** agent-service CODEOWNERS
- **Related ADRs:** ADR-0031 (AI agent governance — this service), ADR-0080 (security hardening /
  pentest follow-ups), ADR-0034 (unified OPA authz), ADR-0002 (hexagonal), ADR-0008 (OTel),
  ADR-0017 (Vault/OpenBao secrets), ADR-0086 (audit hash chain), ADR-0029/0030 (release & supply-chain)

## 0. Posture (read first)

Phase 1 (ADR-0031 D9) is live and **enforcing** (deny-by-default + `block`). Every tool call passes
the `AgentPolicyGate` (in-process charter allow-list + OPA sidecar PDP) and is audited (D5). No agent
takes a state-changing money-path action. The surfaces that accept external input:

- **`/mcp`** (MCP server) — `@RolesAllowed(ROLE_OPERATOR, ROLE_ADMIN, ROLE_COMPLIANCE)`; the agent
  identity is asserted by `X-Agent-Id`.
- **`/agent/chat`** — admin-ui assistant turn (runs as the in-process `ui-assistant` identity).
- **`/api/v1/admin/agents`** — runtime kill-switch, `@RolesAllowed(ROLE_ADMIN)`; actor is the OIDC
  subject, never a body field.
- Scheduled `OversightService` sweep — runs in-process as `compliance-officer` (no external identity).

## 1. Data flow (DFD)

```
operator ──bearer──▶ admin-ui BFF ──bearer + X-Agent-Id──▶ /mcp ──▶ AgentPolicyGate ──▶ OPA sidecar
                                                              │                         (agents.rego)
                                                              ├──▶ McpToolRegistry ──▶ downstream svc REST
                                                              └──▶ AuditEventPublisher ──▶ Kafka audit-events-out
```

Trust boundaries: (a) browser→BFF (NextAuth session), (b) BFF→agent-service (Keycloak bearer),
(c) agent-service→downstream services (service bearer), (d) agent-service→OPA (localhost sidecar).

## 2. STRIDE

### Spoofing

- **T-S1 — agent-identity forgery (privilege selection).** The `/mcp` surface authenticates the
  *operator* but the `X-Agent-Id` header names *which agent charter* to run. Without a binding, any
  authenticated operator could assert a higher-privileged charter (e.g. `ROLE_OPERATOR` asserting
  `compliance-officer`) and inherit its read tools — a vertical privilege escalation through a free
  header. **Mitigation (this change, D3a):** `AgentIdentityBinding` binds the assertable agent-id to
  the operator's *verified* Keycloak roles, **deny-by-default**; a rejected assertion yields no
  identity (deny-by-default at the gate), an empty `tools/list` (no disclosure), and an audited
  `agent.identity.rejected` event attributed to the OIDC subject. **Residual:** the header is still
  *named* by the caller; full per-run cryptographic identity (short-TTL SPIFFE/SVID, signing root in
  OpenBao) is **D3b (planned)** and removes the header entirely. The binding remains a defense-in-depth
  backstop after SVID lands.
- **T-S2 — anonymous reach.** `@RolesAllowed` blocks unauthenticated callers in prod (OIDC on); the
  binding additionally fails closed. In `%dev/%test` OIDC is off (anonymous) and the legacy header
  trust is preserved — acceptable because those profiles never touch real data or a real cluster.

### Tampering

- **T-T1 — audit tampering.** Agent actions emit `AuditEvent`s to the append-only audit-service,
  protected by the per-event hash chain + externally-signed anchors (ADR-0086 / ADR-0031 D5, #2383).
- **T-T2 — policy bypass.** The in-process charter allow-list (ADR-0080) denies out-of-charter
  capabilities *before* the PDP, so a compromised/unreachable OPA sidecar cannot widen privilege
  (fail-closed; `block` mode keeps blocking). PDP connectivity errors degrade to advisory only for
  *non-charter-denied* tools, logged at WARN.

### Repudiation

- **T-R1.** Every decision (ALLOW/DENY), tool execution, model completion and now identity rejection
  is audited with an attributable actor (AI_AGENT for agent actions, the OIDC subject for operator
  actions). Kill-switch flips record the OIDC subject, never a body field.

### Information disclosure

- **T-I1 — tool-schema disclosure (pentest FIND-S4-03).** `tools/list` advertises only the calling
  agent's charter tools (ADR-0080). This change closes the gap where a *rejected* identity fell
  through to the full list — a rejected assertion now returns an empty list.
- **T-I2 — prompt-injection exfiltration (FIND-S4-05).** Untrusted tool results are wrapped in
  data markers; the system prompt forbids following embedded instructions; the charter allow-list +
  gate bound what any missed phrasing could reach. PII is masked on every agent data scope.

### Denial of service

- **T-D1.** Per-charter budgets (`CharterRateLimiter`: runs_per_day pre-flight + tokens_per_run) and
  the kill switch (config baseline + runtime break-glass) bound runaway loops and cost.

### Elevation of privilege

- **T-E1 — charter escalation.** Covered by T-S1 (identity binding) + T-T2 (charter allow-list) +
  the hard-forbidden `deny` tier. No agent can reach `money.transfer` / `gh.pr.merge` regardless of
  identity. Segregation of duties (a dev agent may open a PR but not merge/approve — author ≠
  approver) is enforced by GitHub branch protection + CODEOWNERS today; codifying it in the agent
  policy is **D3b (planned)**.

## 3. Open items (tracked under issue #2386)

- **D3b:** short-TTL SPIFFE/SVID per run; agent identity from a verified credential, not a header.
- **D3b:** author≠approver codified in agent policy (not only GitHub branch protection).
- Explicit per-run OTel trace already live (D7, #2385); LLM-level Langfuse observability planned.
