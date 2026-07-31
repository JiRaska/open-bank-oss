---
date: 2026-07-30
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [governance, admin-ui, authz]
summary: "Sixteen per-service ApprovalResources become one canonical ApprovalItem contract and one supervisor inbox; disposal happens only in the governed UI with SCA for money-path, never in a chat."
---

# ADR-0227 — Unified approval inbox: one disposition point for human and agent proposals

Relates: ADR-0155 (four-eyes mechanism), ADR-0223 D4 (four-eyes rollout),
ADR-0224 D3 (action-class propose/dispose), ADR-0031 (agents propose, humans dispose).

## Context

Maker-checker exists in the platform — sixteen times over, once per service
(account, balance, billing, clearing, consent, domestic-payment, fx, ledger,
lending, notification, party, sanctions, sepa-instant, sepa-payment, swift,
transaction), each with its own `ApprovalResource`, its own item shape, and
its own idea of where the checker looks. The admin UI's `/approvals` screen
covers exactly ONE of these sources: the AI agent's proposal queue
(`/api/agent/proposals`).

The 2026-07 admin-UI audit found the consequences:

1. **A supervisor cannot answer "what is waiting on me, bank-wide?"** without
   walking sixteen service UIs or APIs. In practice they don't — approvals
   age invisibly inside each domain.
2. **Disposal UX is inconsistent**: some services surface their queue in the
   admin UI, some in customer-edge, some nowhere at all (lending's credit
   decisions have no UI surface today).
3. The platform's control philosophy is already *propose → dispose*
   (ProposedOnly guard, ops-message maker-checker, AI agent proposals), but
   there is no shared vocabulary for a proposal across domains, so every new
   approvable operation re-invents it.
4. ADR-0224 D3 extends the same philosophy to the MCP channel: any
   money-path/destructive action becomes a proposal — and needs one governed
   place where it is disposed, with SCA for payment-affecting actions.

## Decision

We will make approvals a single platform concept with one disposition surface.

**D1 — Canonical `ApprovalItem` contract** (in openbank-libs): `{ id,
domain, action, summary, amount?, currency?, riskClass, maker { id, channel,
at }, state, payloadRef }`. Services keep their domain workflows but project
their pending items into this shape — the existing sixteen ApprovalResources
gain a canonical projection rather than a rewrite.

**D2 — One inbox API.** audit-service is NOT the owner (it is an
append-only store); the inbox is a BFF-federated query: the admin UI calls
`/api/approvals` which fans out to each service's canonical pending list and
merges by `(riskClass, at)`. A future event-fed store may replace federation
when volume justifies it — the contract (D1) does not change.

**D3 — `/approvals` becomes the real inbox**: filterable by domain, amount
band, risk class, age; bulk-approve is FORBIDDEN for money-path (each
disposal is individually SCA-bound per ADR-0089). The AI agent proposal
queue becomes one filter facet of the same screen, not a separate concept.

**D4 — Disposal only in the governed UI.** Approving from a chat, an MCP
client, or a raw API client is refused server-side for money-path and
destructive classes (ADR-0224 D3); the disposal endpoint requires the
approver's own authenticated UI session plus SCA where applicable, and
self-approval is refused uniformly (the
`SelfApprovalNotAllowedException` pattern generalised).

## Alternatives considered

- **Keep per-service approval screens** — rejected: the audit evidence shows
  the supervisor's aggregate view does not exist today and cannot be built
  from sixteen shapes; the cost is paid in invisible aging approvals.
- **Event-fed central approval store now** — rejected as phase 1: it
  duplicates pending-state into a new service with its own failure modes;
  federation over the canonical contract delivers the inbox without the
  store, and the contract survives the later swap.

## Consequences

**Positive**
- One queue, one SLA view, one place DORA/SOX evidence points at when asked
  "who approved this".
- New approvable operations inherit the inbox by implementing one contract —
  no new UI per domain.
- The MCP channel's proposals (ADR-0224) and human UI proposals share one
  disposition discipline.

**Negative**
- Sixteen projection adapters to write (thin, but each needs a test).
- Federation adds latency to the inbox page (parallel fan-out, cached
  per-domain; acceptable at current volumes).

**Neutral**
- None.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data environment scope change.
- DORA: a single approval trail supports ICT change-control evidence.
- GDPR: not applicable — approval metadata carries staff, not customer, PII.
- PSD2: D3/D4 keep per-transaction SCA at disposal for payment-affecting
  approvals (ADR-0089 regime).
- CNB: segregation-of-duties evidence is queryable from one surface.

## References

- ADR-0155, ADR-0176 (ops-message maker-checker), ADR-0223 D4, ADR-0224 D3,
  ADR-0031
- The sixteen `*ApprovalResource.kt` files across the fleet; admin-ui
  `/approvals` (agent-proposal-only today)
