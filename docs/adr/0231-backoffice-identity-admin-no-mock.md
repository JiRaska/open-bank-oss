---
date: 2026-07-30
decision-status: proposed
delivery-status: planned
authors: [Jiri Raska]
supersedes: []
superseded-by: []
delivery-repos: []
tags: [admin-ui, governance, security-ops]
summary: "Role administration moves from raw Keycloak console to an audited, four-eyes UI flow (or a documented refusal); mock data is banned from production admin UI; entity links become a shared standard."
---

# ADR-0231 — Backoffice identity administration, no mock data in production UI, entity navigation standard

Relates: ADR-0229 (roles single source), ADR-0226 (audit of admin actions),
ADR-0228 (entity resolution powering the navigation standard).

## Context

Three audit findings that share one theme — the admin UI is not honest
about what it is:

1. **There is no identity administration surface at all.** Managing staff
   users, roles and offboarding happens in the raw Keycloak console with no
   four-eyes, no audit trail in the bank's own store, and no connection to
   the audit screen. Meanwhile `/settings` renders a hardcoded fantasy
   ("Admin User, admin@openbank.local, Superuser · All permissions").
2. **Mock data is presented as real.** `/technical-accounts` is a fully
   hardcoded page (183 lines, zero fetches) sitting behind a route guard as
   if it were live ledger data; `/regulatory`'s "generate report" button
   fires `alert("demo — in production this would be submitted via API")`;
   `/settings` shows the fabricated profile above. In a bank, a screen that
   lies is worse than a screen that is missing.
3. **Entities are dead ends.** Detail pages link only back to their own
   list (`parties/[id]` → `/parties`, `accounts/[id]` → `/accounts`); the
   daily party → accounts → transactions → case walk is manual.

## Decision

**D1 — Identity administration gets a governed surface or a documented
refusal.** Phase 1 (chosen): a deliberate **refusal recorded as decision** —
role administration stays in Keycloak, but the admin UI links it from one
place (`/system` → "Identity & roles" deep-links to the Keycloak console),
the `/settings` page renders the REAL signed-in profile from the session,
and staff role changes become visible in `/audit` (realm events forwarded
as audit entries, ADR-0226 channel `api`). Phase 2 (optional, separate
ADR): a four-eyes role-assignment UI over the Keycloak Admin API.

**D2 — No mock data in production UI, enforced.** Hardcoded business values
in pages are a CI-checked defect class (a lint guard scanning admin-ui
pages for inline business entities — amounts, account numbers, fabricated
profiles). `/technical-accounts` is wired to the real endpoint or REMOVED
from navigation until it is; demo content lives behind an explicit
per-environment demo flag only (never in the production build).

**D3 — Entity navigation standard.** A shared `EntityChip` component
(type + resolved label + route, fed by ADR-0228's resolver); every
cross-entity reference on a detail page (party on an account, account on a
transaction, party on a KYC case) renders as a chip, and every detail page
offers a "Related entities" panel from the resolver's reverse lookups.

## Alternatives considered

- **Full role-administration UI in phase 1** — rejected: it is a new
  security-critical surface (role assignment is the highest-value attack in
  the system) that deserves its own design and threat model, not a ride
  along; the D1 refusal is explicit and audited rather than absent.
- **Keep mock pages as demos** — rejected: a demo that looks like live
  ledger data is an operational hazard, not a showcase; the demo flag keeps
  the ability without the lie.

## Consequences

**Positive**
- The admin UI stops fabricating data in three named places.
- Staff role changes enter the bank's own audit trail even before any
  role-admin UI exists.
- Cross-entity navigation becomes a component, not a per-page afterthought.

**Negative**
- The CI mock-data guard needs a false-positive budget (fixtures in test
  files are excluded by path).
- Removing `/technical-accounts` from nav until wired shrinks the menu —
  honestly.

**Neutral**
- None.

## Compliance impact

- PCI DSS: not applicable.
- DORA: identity administration actions enter the ICT audit trail (D1).
- GDPR: staff identity changes are auditable; mock customer-shaped data
  stops being indistinguishable from real PII in screenshots.
- PSD2: not applicable.
- CNB: not applicable.

## References

- Audit evidence: `/technical-accounts` (hardcoded), `/settings` (fabricated
  profile), `/regulatory` (demo alert), detail-page back-links only
- ADR-0226/0228/0229
