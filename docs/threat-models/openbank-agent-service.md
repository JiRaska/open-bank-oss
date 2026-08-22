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
                                                              └──▶ AuditEventPublisher
                                                                     (DurableAgentAuditPublisher)
                                                                        │
                                                                        ▼
                                                              agent_audit_outbox (local Postgres,
                                                              same tx as the audited operation)
                                                                        │  AgentAuditOutboxDispatcher
                                                                        ▼  (@Scheduled, gated off)
                                                              Kafka openbank.agent.audit.events
                                                                        │  AgentAuditConsumer
                                                                        ▼
                                                              audit-service → audit_entries
                                                              (append-only, hash-chained)
```

Trust boundaries: (a) browser→BFF (NextAuth session), (b) BFF→agent-service (Keycloak bearer),
(c) agent-service→downstream services (service bearer), (d) agent-service→OPA (localhost sidecar),
(e) agent-service→Kafka (mTLS, KafkaUser `agent-service`, `Write, Describe` on
`openbank.agent.audit.events` and deliberately no `Read` — a component may append to the trail
about itself and may not read it back; `openbank-infra/gitops/components/agent/kafka-agent-mtls.yaml`).

## 2. STRIDE

### Spoofing

- **T-S1 — agent-identity forgery (privilege selection).** The `/mcp` surface authenticates the
  *operator* but the `X-Agent-Id` header names *which agent charter* to run. Without a binding, any
  authenticated operator could assert a higher-privileged charter (e.g. `ROLE_OPERATOR` asserting
  `compliance-officer`) and inherit its read tools — a vertical privilege escalation through a free
  header. **Mitigation (this change, D3a):** `AgentIdentityBinding` binds the assertable agent-id to
  the operator's *verified* Keycloak roles, **deny-by-default**; a rejected assertion yields no
  identity (deny-by-default at the gate), an empty `tools/list` (no disclosure), and an audited
  `agent.identity.rejected` event attributed to the OIDC subject. **D3b (verify side, this change):**
  `AgentSvidVerifier` accepts a per-run OpenBao-issued client cert (CN = agent id) proven via a
  PoP signature (`X-Agent-Cert` / `X-Agent-PoP` / `-Ts` / `-Nonce`) — chain to the `pki-agent` CA
  (#2405), cert-validity window, signature, timestamp freshness and single-use nonce. **D3b fully
  enforced (#2488):** `AGENT_IDENTITY_SVID_ENFORCED=true` — no SVID = rejected; the D3a header
  binding is now %dev/%test only. **D3b hardening (#2488):** after cert-chain verify, the CN is
  additionally cross-checked against the operator’s D3a role binding (`method=svid_cn_binding`
  audit) — a `compliance-officer` CN is rejected even with a valid cert if the operator holds only
  `ROLE_OPERATOR`. **Residual:** OpenBao outage → BFF mint fails → `/mcp` rejected (fail-secure;
  GitOps break-glass: flip `SVID_ENFORCED=false` and redeploy). OpenBao is HA (Raft 3-node).
- **T-S2 — anonymous reach.** `@RolesAllowed` blocks unauthenticated callers in prod (OIDC on); the
  binding additionally fails closed. In `%dev/%test` OIDC is off (anonymous) and the legacy header
  trust is preserved — acceptable because those profiles never touch real data or a real cluster.
- **T-S3 — security-control off-switches.** Two env flags:
  `AGENT_IDENTITY_BINDING_ENFORCED=false` (disables the D3a role binding) and
  `AGENT_IDENTITY_SVID_ENFORCED` (true = SVID required; false = rollout fallback). Flipping either
  is a change-controlled GitOps PR. `BINDING_ENFORCED=false` must never be set in production;
  `SVID_ENFORCED=false` is the break-glass if the BFF mint path is degraded.

### Tampering

- **T-T1 — audit tampering.** Agent actions emit `AuditEvent`s to the append-only audit-service,
  protected by the per-event hash chain + externally-signed anchors (ADR-0086 / ADR-0031 D5, #2383).
- **T-T2 — policy bypass.** The in-process charter allow-list (ADR-0080) denies out-of-charter
  capabilities *before* the PDP, so a compromised/unreachable OPA sidecar cannot widen privilege
  (fail-closed; `block` mode keeps blocking). PDP connectivity errors degrade to advisory only for
  *non-charter-denied* tools, logged at WARN.

### Repudiation

- **T-R1.** Every decision (ALLOW/DENY), tool execution, model completion and identity rejection
  is audited with an attributable actor (AI_AGENT for agent actions, the OIDC subject for operator
  actions). Kill-switch flips record the OIDC subject, never a body field.
- **T-R2 (issue #6191, ADR-0031 D5).** Those events reach a durable record and not only a log line.
  Until #6209 the fleet's only `AuditEventPublisher` implementation was the log-only fallback, so
  the DFD arrow above credited a Kafka delivery that no code performed — the evidence for every AI
  action was a log line written by the same process whose behaviour a dispute would put in question.
  `DurableAgentAuditPublisher` (an `@Alternative`) now enqueues into `agent_audit_outbox` inside the
  audited operation's own transaction, so an audit call returns only after the source database has
  acknowledged the row; `AgentAuditOutboxDispatcher` drains it and marks a row published only on
  Kafka ack, leaving a failed send claimed for retry with `last_error` recorded.
  `AgentAuditConsumer` acks the Kafka offset only after `audit_entries` has committed, and the
  producer's `eventId` is the row's `entry_id`, so an at-least-once redelivery de-duplicates rather
  than double-chaining (`AgentAuditRedeliveryIT`).
  **Residual, stated rather than implied:**
  - **The transport is off by default.** `AGENT_AUDIT_KAFKA_ENABLED` defaults to `false` (#6209's
    deliberate rollout choice), and the gitops `group.id`/`auto.offset.reset` override that
    audit-service needs is deliberately not added yet. Until both land, AI provenance is durable in
    agent-service's *local* outbox and has not reached the tamper-evident trail — the outbox grows
    unbounded and no `audit_entries` row exists. This is a rollout state, not a design gap, but it
    is the state today and no green test contradicts it.
  - **`aggregate_id` degrades to the `unknown` sentinel.** The envelope spells the acted-on
    resource `aggregateId`; `AuditConsumer.inferAggregateId` derives that column from a fixed chain
    of business id field names and cannot see it. The row is therefore attributable to the actor
    but not joinable to the resource, and `audit_entries` is append-only so it can never be
    corrected. Measured by `AgentAuditEventPersistenceIT`, which asserts the sentinel rather than
    agreeing with it silently.

### Information disclosure

- **T-I1 — tool-schema disclosure (pentest FIND-S4-03).** `tools/list` advertises only the calling
  agent's charter tools (ADR-0080). A *rejected* identity returns an empty list. **Residual (accepted):**
  an authenticated operator that presents *no* identity at all (no SVID, no `X-Agent-Id`) still sees the
  full tool-schema list — this is the legacy least-restrictive default and is bounded by the call path,
  which denies every actual invocation at the gate (no charter ⇒ deny-by-default). It is not a data
  path, only schema names; closing it (default-deny on no-identity `tools/list`) is a follow-up.
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

- ✅ **D3b SHIPPED:** short-TTL OpenBao `pki-agent` cert per run + PoP; agent identity from a verified
  credential, not a header (#2405 issuer, #2412 verify, #2439 mint). Verify side is flag-gated
  (`svid.enforced`); flip to enforced after the BFF is live (runbook 0007).
- **D3b hardening:** a *verified* SVID currently bypasses the D3a role binding (the CN is trusted as
  the identity). The OpenBao `agent-run` role is constrained to the `ui-assistant` CN so a compromised
  minter cannot forge a higher-privileged charter; a deeper defense (verify the CN against the
  operator's roles) is a follow-up. Requires admin-ui pod compromise to exploit; deny tier untouched.
- **D3b:** author≠approver codified in agent policy (not only GitHub branch protection).
- Explicit per-run OTel trace already live (D7, #2385); LLM-level Langfuse observability planned.
