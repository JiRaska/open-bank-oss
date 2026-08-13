# Admin UI audit — 2026-08-13

## Outcome

The operator console has strong safety foundations: an authenticated same-origin BFF
(ADR-0056), nonce CSP, bilingual state handling, graceful unavailable states, and a
shared status vocabulary. Its main gap is not a missing visual style; it is that
implementation is uneven across a now-large surface. The next work must be tranches,
not a visual rewrite.

This audit deliberately treats an unmeasured value as unavailable. A polished dashboard
that presents a health-check proxy as security, compliance, p99 latency, throughput or
CPU is less safe than a smaller dashboard that says only what it knows.

## Changes landed with this audit

- `/dashboard` now displays only current discovery/health-check facts: healthy and
  deployed service counts, health-check latency when observed, and planned/not-deployed
  count. It no longer invents security, compliance, error-rate, p99, throughput, or
  CPU metrics from health checks.
- Dashboard shortcuts use the same role permission matrix as the sidebar, so a shortcut
  cannot lead an operator into a predictable 403.
- Header help and approval icons are actionable links rather than inert controls.
- `/api/iaops/rca` now authenticates and requires `system:view` itself before invoking
  the internal RCA service. It returns a generic upstream envelope rather than exposing
  provider or cluster diagnostics.

## ADR delivery assessment

| ADR | Current evidence | Delivery assessment | Next bounded tranche |
| --- | --- | --- | --- |
| ADR-0208 — primitives and status vocabulary | 102 `page.tsx`; 10 import `PageHeader`, 7 `StatCard`, 21 `StatusBadge`; 96 still contain inline styles and 62 raw-colour pages remain. | Partially delivered. The primitive layer exists but is not yet the default way to build a screen. | Migrate one coherent, high-traffic workspace at a time (dashboard, approvals, accounts), add a path-scoped guard, and stop each tranche when its browser test passes. |
| ADR-0228 — entity resolution and palette | The palette and resolver exist, but the palette type supports only `party` and `account`. | Partially delivered. It is useful, not yet the universal backoffice entry point promised by the ADR. | Add payment, transaction and card references only after their provider contracts return minimised/audited results. Do not add browser-side fan-out. |
| ADR-0229 — generated role model and persona IA | KYC/SUPERVISOR roles and persona quick links exist, while `middleware.ts` still contains a handwritten route-guard list and the UI matrix is not generated from governance. | Partially delivered. Navigation is role-aware; route policy can still drift. | Generate or CI-compare UI roles, route permissions and realm roles from the governance matrix; then derive middleware guards from that projection. |
| ADR-0227 — unified approval inbox | `/approvals` and a pending-approval route exist, but the ADR remains proposed/planned and its full federated canonical contract is not evidenced by the UI alone. | Do not call complete. | Verify the canonical `ApprovalItem` contract across each source before adding filters, bulk actions or new mutation controls. |
| ADR-0230 — hybrid lending/fraud/SDD consoles | Read surfaces exist for all three domains. | Read-first direction is present; proposal-only mutation posture needs a dedicated contract-by-contract review. | Prove every state-changing control creates an approval proposal; forbid direct mutation paths where that proof is absent. |

## Architecture and security findings

1. **Truthfulness is a UX and control requirement.** Health reachability is not SLO,
   p99, request error rate, throughput, security posture, or compliance. Each claim
   needs its own source and freshness metadata.
2. **API routes must authorise themselves.** Middleware is useful defence in depth but
   is not a replacement for a route's server-side session/permission check, especially
   for expensive relays and endpoints that return operational detail.
3. **The BFF boundary is correctly established but needs a ratchet.** Existing direct
   browser calls should migrate to `svcUrl()`/BFF routes in focused tranches. Do not
   create a second browser-to-service path to simplify an individual page.
4. **The console is desktop-first by design, but keyboard and assistive use remain
   release criteria.** New controls need names, focus behaviour, and browser checks;
   visual acceptance cannot be inferred from TypeScript alone.

## Prioritised roadmap

1. **P0 — retain this audit's truthfulness and route-auth tests.** Any new dashboard
   metric needs a named source, timestamp and semantics; any new server relay needs
   authn/authz plus a safe upstream-error envelope.
2. **P1 — complete ADR-0229's single policy projection.** This is the highest-leverage
   safety/UX work: one role model should drive realm, backend, sidebar, route guard,
   and action affordance.
3. **P1 — finish ADR-0228 by provider, not by UI mock.** Extend typed, audited result
   contracts and then add the matching palette group. Never expose a full PAN or an
   unminimised customer payload.
4. **P2 — migrate the first three operator workspaces to ADR-0208 primitives.** Add
   browser verification to each tranche; no global CSS rewrite and no new design
   dependency until a real page needs it.
5. **P2 — assess every mutable screen against ADR-0227/0230.** For money-path or
   destructive actions, the UI creates a proposal and the approval inbox disposes it;
   direct writes require an explicit, separately approved exception.

## Verification performed

- Focused Vitest coverage for fleet-health truthfulness, role/persona behaviour, and
  IAOps RCA authentication/error handling.
- TypeScript type check and ESLint for all edited source and test files.
- Playwright browser verification of the dashboard's real CSS, observed metrics, and
  actionable header links.
- `npm audit --omit=dev --audit-level=high`: no known production dependency
  vulnerabilities at the time of the audit.
