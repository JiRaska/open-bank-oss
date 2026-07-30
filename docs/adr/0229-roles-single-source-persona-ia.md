---
date: 2026-07-30
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [authz, admin-ui, governance]
summary: "One role vocabulary generated from rules.yaml into backend, realm and admin UI; navigation becomes persona-driven workspaces instead of one domain menu for everyone."
---

# ADR-0229 — Roles single source of truth and persona-driven information architecture

Relates: ADR-0223 D5 (the codegen mechanism this ADR scopes), ADR-0034
(unified OPA), issue #2404 (parallel invented role vocabularies).

## Context

The 2026-07 admin-UI audit found the role model exists in three drifting
copies:

1. **Backend canonical** (`openbank-libs-domain Roles.kt`): 11 roles —
   ADMIN, OPERATOR, VIEWER, COMPLIANCE, AUDITOR, SUPERVISOR, KYC,
   KYC_OPENER, KYC_REVIEWER, PAYMENTS, API.
2. **Admin UI** (`src/lib/auth/roles.ts`): 8 roles — missing SUPERVISOR,
   KYC, KYC_OPENER, KYC_REVIEWER; inventing ROLE_DEMO, which no backend or
   realm issues for staff. A KYC reviewer holding ONLY `ROLE_KYC_REVIEWER`
   sees no KYC menu at all; a ROLE_DEMO-holding menu state pretends to be a
   control it is not.
3. **Keycloak realm** — kept in parity with code by the #2540 gate, but the
   UI is outside that parity.

And the information architecture ignores roles anyway: one domain-grouped
sidebar for everyone (a teller, a compliance officer and a platform admin
share the same nine sections), middleware guards 7 of ~60 routes, and only
4 of 88 pages check permissions before rendering mutation buttons — the
rest let the user click into a 403. This is the #2404 defect class
(parallel role vocabularies) one layer up, and the audit trail shows it was
already expensive once.

## Decision

We will make the role vocabulary and the navigation it drives single-sourced.

**D1 — `rules.yaml` is the only edit point.** The role→permission matrix
lives there as data (the ADR-0223 matrix, same PR train). Backend
(`Roles.kt`), the realm templates, and the admin-UI permission matrix are
GENERATED or CI-guarded from it — hand edits to any of the three fail CI.
(ADR-0223 D5 owns the codegen mechanism; this ADR owns the parity scope:
UI included, not just realm+code.)

**D2 — UI vocabulary completes the backend one.** SUPERVISOR, KYC,
KYC_OPENER, KYC_REVIEWER enter the UI matrix; ROLE_DEMO leaves the
production matrix (demo state becomes an explicit, per-environment feature
flag, not a fake role).

**D3 — Route guards cover every route, from the same matrix.** The
middleware table is generated from the matrix (not hand-listed 7 entries);
mutation buttons hide/disable by the same projection — clicking into a 403
becomes a bug class with a CI guard, not a UX norm. UI gates remain
rendering-only; enforcement stays at the sidecar (ADR-0223 D1).

**D4 — Persona-driven workspaces.** Navigation groups by the operator's
primary persona — Backoffice (accounts/customers/cases), Payments ops
(payments/clearing/fx/swift/sdd), Compliance (aml/sanctions/disputes/audit),
Supervisor (inbox/readings/reports), Platform (system/devops/finops) — with
a role-specific home dashboard instead of one dashboard for all. The full
menu stays reachable; the workspace is the default landing, not a wall.

## Alternatives considered

- **Hand-maintained parallel matrices (status quo)** — rejected: measured
  drift in three copies today; the #2404 incident already showed where
  hand-sync ends.
- **Persona as separate apps** — rejected: one app with persona workspaces
  keeps one deployment, one auth session, one audit channel; the personas
  share too many screens (customer 360, search).

## Consequences

**Positive**
- Adding a role or permission becomes one reviewed diff in rules.yaml,
  visible to governance — not three edits hoping to agree.
- KYC/SUPERVISOR staff get working menus for the first time; demo state
  stops masquerading as RBAC.
- Operators land in a workspace shaped like their job, not in a 60-item
  domain menu.

**Negative**
- Generated UI matrix removes hand-tuning of visibility per screen —
  deliberate (ADR-0223 D5); teams must learn the single edit point.
- Persona workspaces need real usage input to get the grouping right;
  expect one iteration after backoffice feedback.

**Neutral**
- None.

## Compliance impact

- PCI DSS: not applicable — no cardholder-data environment scope change.
- DORA: role definitions and changes become reviewable configuration (ICT
  access-control governance).
- GDPR: persona scoping supports need-to-know access presentation (the
  enforcement half is ADR-0223).
- PSD2: not applicable.
- CNB: segregation-of-duties roles (KYC_OPENER/REVIEWER) become assignable
  end-to-end (ADR-0116 intent finally reachable in UI).

## References

- Audit evidence: `roles.ts` (ROLE_DEMO, missing KYC*/SUPERVISOR),
  `middleware.ts` (7 guarded routes), 4/88 pages with action checks,
  `Sidebar.tsx` (one menu for all)
- Issue #2404, #2540 parity gate; ADR-0223 D5, ADR-0116
