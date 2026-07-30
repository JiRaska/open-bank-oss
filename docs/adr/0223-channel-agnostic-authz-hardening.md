---
date: 2026-07-30
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, governance, audit, libs]
summary: "Authorization enforcement lives ONLY at the OPA sidecar for every ingress channel (BFF, MCP); operator-read-any is retired for a role→action matrix as data; four-eyes flips per-service on a graduated plan."
---

# ADR-0223 — Channel-agnostic authorization hardening: sidecar-only enforcement, operator-read-any retirement, four-eyes rollout

Amends: ADR-0034 (unified OPA), ADR-0155 (four-eyes mechanism).
Relates: ADR-0176 (ops-message namespace), ADR-0143 (billing go-live gate),
ADR-0224 (human-operator MCP channel).

## Context

The 2026-07 admin-UI audit found the authorization posture is enforced in the
wrong places and too coarsely in the right one:

1. **`operator-read-any` (rest.rego) grants every `ROLE_OPERATOR`/`ROLE_ADMIN`
   `.list`/`.read` on ANY resource.** The admin UI's own permission matrix
   admits it is "NOT a security control — UX/nav gating only". Hiding a menu
   item is the current control for over-broad reads — including blanket PII
   reads that GDPR need-to-know does not justify for every operator.
2. **Enforcement logic is creeping into ingress adapters.** The admin UI
   middleware guards 7 of ~60 routes; 4 of 88 pages check permissions before
   rendering mutation buttons. A second ingress channel (MCP for human
   operators, ADR-0224) would make any ingress-level enforcement silently
   incomplete — each new channel would have to re-derive the same rules, and
   each would drift.
3. **Four-eyes is computed but never enforced.** `four_eyes_required` is
   evaluated in rego and surfaced in decision attributes, yet
   `authz.four-eyes.enforce` is OFF fleet-wide; billing's production go-live
   is gated on it (ADR-0143) with no rollout plan.
4. The admin UI invents roles the backend does not have (`ROLE_DEMO`) and
   lacks roles the backend canonicalizes (`SUPERVISOR`, `KYC`, `KYC_OPENER`,
   `KYC_REVIEWER`) — two matrices of truth, the defect class of #2404 (three
   parallel invented role vocabularies that answered 403 to every caller).

ADR-0034 already decided the architecture that fixes this: one OPA sidecar
per service authorizes REST and MCP from a single bundle. What is missing is
the *policy-level* completion of that decision.

## Decision

We will complete ADR-0034 at the policy layer and forbid authorization logic
anywhere but the OPA sidecar.

**D1 — Enforcement lives exclusively at the OPA sidecar PDP.** Every ingress
channel (admin-UI BFF, MCP endpoint, future channels) is a dumb identity
relay: it authenticates, forwards the principal, and never evaluates
authorization itself. UI-side permission checks remain for RENDERING only
(menu/button visibility) and must never be cited as a control. A new ingress
channel may not ship with its own authz logic — this is a CI-checked
invariant (no authz evaluation code outside openbank-libs and the OPA
bundles).

**D2 — `operator-read-any` is retired and replaced by a role→action matrix
as data.** The matrix lives in `rules.yaml` (the same data plane as
money_path_services/value_bands/four_eyes), keyed by canonical
`{domain}.{verb}` actions. Rollout: (a) the matrix ships alongside
operator-read-any with decision-reason counters; (b) dashboards show what
operator-read-any admits that the matrix would deny — every delta is triaged
as "grant intentionally" or "was over-reach"; (c) operator-read-any is
deleted, not deprecated-in-place.

**D3 — Canonical action taxonomy with orphan guards.** Every `@Authorize`
action in the fleet MUST have at least one matrix grant, and every matrix
action MUST map to existing code — CI fails on orphans in either direction
(same style as `check-roles-allowed-realm.py`).

**D4 — Four-eyes enforcement flips per-service on a graduated plan.** Order:
billing (go-live gate, ADR-0143) → money-path mutations in rules.yaml →
non-money-path destructive verbs. Each service passes advisory-count →
enforce-new-operations → enforce-all. M2M identities are excluded explicitly
(the `service-account-` idiom already in rest.rego), never implicitly by
role shape.

**D5 — One matrix, two projections.** The UI permission matrix
(`openbank-admin-ui/src/lib/auth/roles.ts`) is GENERATED from the same
`rules.yaml` data (codegen step in the admin-ui build), eliminating the
ROLE_DEMO/SUPERVISOR drift. UI hides, backend denies — and a CI guard proves
the two projections describe the same world.

**D6 — Step-up authentication binds to disposal, not to clicks.**
Destructive/money-path actions require SCA at the point of disposal
(approval flow), not per-action in the channel — see ADR-0224 D3 for the
action-class matrix.

## Alternatives considered

- **Keep operator-read-any, tighten UI hiding** — rejected: the UI comment
  already concedes this is cosmetic ("NOT a security control"); a second
  channel (MCP) makes it untenable. Cosmetic controls fail audits and
  pentests, not users.
- **Enforce in the BFF proxy** — rejected: it is one of N ingress adapters;
  enforcement there is bypassed by any channel that does not pass through
  it. The sidecar is the only point every channel shares.
- **Full ABAC via Keycloak user attributes** — rejected for now: the
  role→action matrix as data captures most of the value with a reviewable
  diff surface; attribute plumbing can extend the matrix later without
  changing the enforcement point.

## Consequences

**Positive**
- One enforcement point, one matrix, two channels — a mental model an
  auditor can hold.
- operator-read-any retirement turns "who can read what" into a reviewable
  git diff instead of a rego subtlety; blanket PII read access for all
  operators ends.
- decision_reason/policy_version already flow into AuditEvent — the
  migration counters in D2(b) are answerable in SQL from day one.
- Four-eyes stops being a latent capability and becomes a plan with dates;
  ADR-0143's billing go-live gate gets an owner and an order.

**Negative**
- D2(b) triage is real work: every operator-read-any delta needs a human
  decision; expect a multi-week tail across services.
- The generated UI matrix removes the ability to hand-tune UI visibility —
  deliberate, but teams must learn the new edit point (rules.yaml).

**Neutral**
- ADR-0034's bundle-distribution deviation (per-service ConfigMaps vs the
  signed OCI artifact sketched in D2 there) is unaffected and remains open.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data environment scope change.
- DORA: access-control reviewability and forensic readiness (decision_reason
  audit trail) support ICT risk management and incident investigation
  duties; cited in this ADR's Context items 1–3.
- GDPR: D2 removes the blanket PII read grant operators hold today via
  operator-read-any (need-to-know, data minimisation).
- PSD2: D6 keeps SCA bound to disposal of payment-affecting actions; no
  change to the SCA regime itself (ADR-0089).
- CNB: not applicable — no prudential reporting scope change.

## References

- ADR-0034 (unified OPA authz), ADR-0155 (four-eyes), ADR-0143 (billing),
  ADR-0176 (ops messaging), ADR-0224 (human MCP channel)
- openbank-libs/governance/policies/rest.rego (`operator-read-any`,
  `four_eyes_required`)
- openbank-admin-ui/src/lib/auth/roles.ts ("NOT a security control" comment)
- Issue #2404 (parallel invented role vocabularies)
