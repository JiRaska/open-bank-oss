---
date: 2026-07-30
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, lending, fraud]
summary: "Deployed backends with no admin UI (lending's full credit lifecycle, fraud scoring, SDD mandates) get hybrid consoles: deterministic read views plus mutations only as approval proposals, never direct writes."
---

# ADR-0230 — Backoffice hybrid consoles for lending, fraud and SDD

Relates: ADR-0227 (unified approval inbox — the mutation surface these
consoles write into), ADR-0211/0213 (credit lifecycle), ADR-0217 (credit
agents), ADR-0229 (persona workspaces hosting them).

## Context

The 2026-07 audit's coverage finding: three deployed backends expose a
complete operational surface to NOBODY:

1. **Lending** — the full credit lifecycle already exists server-side:
   applications (maker/checker), disbursement, repayment schedules,
   repayments, write-off, restructuring, collateral (four-eyes), IFRS 9
   staging/ECL. There is no `/lending` page. A credit officer today uses
   curl or nothing.
2. **Fraud** — payment intents are scored (ALLOW/CHALLENGE/REVIEW/DECLINE)
   with no review queue, no console: a REVIEW verdict goes into a void no
   analyst can see.
3. **SDD** — the full mandate lifecycle (confirm/suspend/resume/cancel/
   amend, collection authorisation, refund claims) has no UI; direct-debit
   ops are invisible to the payments workspace.

The naive fix — three classic CRUD admin applications — is the slowest and
the least safe: every mutation becomes a new direct-write surface to
threat-model, and the build cost is measured in quarters.

## Decision

We will cover these domains with **hybrid consoles**, not CRUD apps.

**D1 — Deterministic read views first.** List + detail screens rendered
from the existing read endpoints (loans, applications, schedules, fraud
verdicts with REVIEW state, mandates, collections) inside the persona
workspaces (ADR-0229). Read-only screens ship fast and answer 80% of
backoffice questions.

**D2 — Mutations exist ONLY as approval proposals.** Every state-changing
operation the console offers (credit decision, disbursement, write-off,
restructure, fraud verdict resolution, mandate suspend/resume/cancel) is
created as an `ApprovalItem` (ADR-0227) from the console, and disposed in
the unified inbox with SCA where money-path. The console itself never
writes — so the most dangerous class of UI bug (a screen that can move
money or kill debt) is absent by construction, matching the platform's
propose/dispose philosophy (ADR-0224 D3).

**D3 — Domain priority: lending → fraud → SDD.** Lending first (money-path,
largest API surface already shipped, IFRS 9 evidence already produced);
fraud second (the REVIEW queue is a compliance gap, not just UX); SDD third.

**D4 — No new backend for phase 1.** Consoles consume existing endpoints;
missing read projections (e.g. a fraud REVIEW list) are added as
read-only endpoints, not workflow changes.

## Alternatives considered

- **Classic CRUD admin apps per domain** — rejected: slower, and every
  mutation is a fresh direct-write threat surface; propose-only consoles
  get the same work done with a smaller security envelope.
- **Expose the existing admin APIs to operators via tooling (curl/Postman
  runbooks)** — rejected: that IS today's state; it is unreviewable,
  unauditable at the UI layer, and unacceptable for money-path operations.

## Consequences

**Positive**
- Three deployed-but-invisible backends become operable; the audit's
  "backend functionality with no UI" gap closes for the three biggest
  domains.
- Every mutation in the new consoles is born four-eyes — no exceptions to
  retrofit later.
- Fraud REVIEW verdicts get an owner and an SLA for the first time.

**Negative**
- Read projections may need small backend additions per domain (bounded,
  read-only).
- Proposal-only mutations are one click slower for the operator than direct
  writes — deliberate: the click is the control.

**Neutral**
- None.

## Compliance impact

- PCI DSS: not applicable.
- DORA: formerly invisible operational domains become auditable (mutations
  via the approval trail, ADR-0226).
- GDPR: credit and fraud screens expose PII — persona-scoped visibility
  (ADR-0229) and audited search (ADR-0228) apply.
- PSD2: not applicable.
- CNB: credit decisions (lending) and AML-adjacent fraud reviews gain
  four-eyes evidence (ADR-0216 AI-Act readiness for high-risk credit).

## References

- Audit evidence: `LendingResource.kt` (write-off/restructure/collateral/
  IFRS9), `FraudResource.kt` (score only), `SddResource.kt` (full mandate
  lifecycle) — none reachable from admin UI; BFF `SERVICE_MAP` gaps
- ADR-0211/0213/0217 (credit platform), ADR-0227/0229
