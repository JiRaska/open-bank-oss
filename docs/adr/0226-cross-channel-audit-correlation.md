---
date: 2026-07-30
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [audit, authz, admin-ui]
summary: "One identity, one trail: every audit event carries channel (ui|mcp|api), act-chain and session id; the audit surface answers 'what did person X do' across all channels in one query."
---

# ADR-0226 — Cross-channel audit correlation: one identity, one trail

Relates: ADR-0224 (OBO act chain — the identity source), ADR-0223
(sidecar-only enforcement — the decision source), ADR-0031 (agent audit).

## Context

The platform is gaining a second operator channel (MCP, ADR-0224). Audit
today is channel-shaped, not person-shaped:

1. UI actions flow through the BFF with the operator's bearer; MCP tool
   calls are recorded by `McpCallAuditor` with `ACTOR_TYPE = "AI_AGENT"`
   only — there is no field for a HUMAN principal arriving via MCP, and no
   place to record the delegation (`act`) chain ADR-0224 introduces.
2. The forensic question "what did person X do?" is unanswerable across
   channels: an operator who searched in the UI and then acted through an
   agent session leaves two unlinked trails. DORA incident investigation
   and SOX evidence both presume the question is answerable.
3. The `/audit` admin screen searches by raw aggregate UUID — even within
   one channel, person-centric queries are not how the UI works (the audit
   UUID pain is tracked in the unified-search workstream; this ADR fixes
   the data model that makes it fixable).

## Decision

We will extend the audit event schema with channel and delegation
dimensions and expose one person-centric query across channels.

**D1 — Schema: every audit event carries** `channel: ui|mcp|api`,
`act_chain[]` (ordered delegation chain from ADR-0224's `act` claim; empty
for direct action), and `session_id` (browser session or agent session).
Existing correlation-id propagation (outbox/audit plumbing in
openbank-libs) is reused; no new transport is built.

**D2 — `McpCallAuditor` accepts HUMAN principals.** Principal type comes
from the validated OBO token; the actor chain is recorded from `act`. PII
minimisation is unchanged: argument KEYS, never values, exactly as today.

**D3 — One query surface.** audit-service's query API and the `/audit` UI
gain a "person across channels" filter (subject id, optional channel and
session facets). The filter is backed by indexed columns, not by joining
per-channel tables at query time.

**D4 — Retention and immutability are unchanged.** The new dimensions are
indexes on the existing tamper-evident trail, not a new store.

## Alternatives considered

- **Separate trails, join at query time** — rejected: per-channel schemas
  drift; forensic queries during an incident need one predicate, not a
  federation exercise.
- **Correlate in the SIEM instead of the platform** — rejected: it pushes a
  platform duty into an optional integration; deployments without the SIEM
  (including the OSS reference) would lose the guarantee.

## Consequences

**Positive**
- "What did person X do, everywhere?" becomes one query — the answer DORA
  forensics and SOX walkthroughs actually ask.
- Agent-session actions become attributable to the human who opened the
  session (act chain), closing the "the AI did it" accountability gap.
- The data model stops blocking person-centric audit search in the UI.

**Negative**
- Schema migration on the audit store (additive columns + indexes); event
  volume unchanged, storage grows marginally.
- Every audit producer must populate the new fields — a fleet sweep with a
  CI guard (missing `channel` = invalid event).

**Neutral**
- Employee-monitoring proportionality (GDPR works-council documentation)
  should be recorded when the person-across-channels filter ships — the
  capability is forensically required and access-controlled, and the note
  belongs with the rollout, not this decision.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data environment scope change.
- DORA: cross-channel forensic reconstruction supports incident
  investigation and ICT audit duties (Context item 2).
- GDPR: act-chain attribution processes staff activity data; access to the
  person-across-channels filter must be role-restricted and itself audited
  (proportionality note under Consequences).
- PSD2: not applicable — no change to consent or SCA records.
- CNB: not applicable — no prudential reporting scope change.

## References

- openbank-mcp-service McpCallAuditor (`ACTOR_TYPE = "AI_AGENT"` today)
- ADR-0224 (act chain), ADR-0223 (decision_reason into AuditEvent),
  ADR-0031 (agent audit), ADR-0029 (governance as code)
