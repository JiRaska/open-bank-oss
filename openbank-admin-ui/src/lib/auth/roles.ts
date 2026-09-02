// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// OpenBank RBAC — role definitions and permission matrix
// Aligned with EBA ICT Risk, PSD2, AML 5AMLD, CNB requirements

export const ROLES = {
  ADMIN:      "ROLE_ADMIN",
  OPERATOR:   "ROLE_OPERATOR",
  VIEWER:     "ROLE_VIEWER",
  COMPLIANCE: "ROLE_COMPLIANCE",
  PAYMENTS:   "ROLE_PAYMENTS",
  AUDITOR:    "ROLE_AUDITOR",
  API:        "ROLE_API",
  SUPERVISOR: "ROLE_SUPERVISOR",
  LENDING_OFFICER: "ROLE_LENDING_OFFICER",
  CREDIT_RISK: "ROLE_CREDIT_RISK",
  KYC:        "ROLE_KYC",
  KYC_OPENER: "ROLE_KYC_OPENER",
  KYC_REVIEWER: "ROLE_KYC_REVIEWER",
  DEMO:       "ROLE_DEMO",
  CATALOG_READ: "CATALOG_SCOPE_READ",
  CATALOG_AUTHOR: "CATALOG_SCOPE_AUTHOR",
  CATALOG_PUBLISH: "CATALOG_SCOPE_PUBLISH",
} as const

export type Role = typeof ROLES[keyof typeof ROLES]

// Permission matrix — what each role can access
export const PERMISSIONS = {
  // Dashboard
  // Every interactive staff persona needs the workspace landing page. Without
  // the KYC and supervisor entries their role-specific workspace can be
  // derived, but the role matrix says they may not view the dashboard — a
  // contradiction ADR-0229 D4 explicitly rules out.
  "dashboard:view":           [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR, ROLES.SUPERVISOR, ROLES.KYC, ROLES.KYC_OPENER, ROLES.KYC_REVIEWER],
  // Accounts
  "accounts:view":            [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS],
  "accounts:create":          [ROLES.ADMIN, ROLES.OPERATOR],
  "accounts:freeze":          [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  "accounts:close":           [ROLES.ADMIN],
  // Transactions
  "transactions:view":        [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR],
  "transactions:create":      [ROLES.ADMIN, ROLES.OPERATOR, ROLES.PAYMENTS],
  "transactions:reverse":     [ROLES.ADMIN, ROLES.OPERATOR],
  // Closings (EoD/EoM close cockpit, ADR-0069 D3) — the manual catch-up trigger
  // mirrors statement-service's CloseRunResource POST gate (ROLE_OPERATOR/ADMIN)
  "closings:run":             [ROLES.ADMIN, ROLES.OPERATOR],
  // Payments
  "payments:view":        [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.PAYMENTS, ROLES.SUPERVISOR],
  "payments:create":      [ROLES.ADMIN, ROLES.OPERATOR, ROLES.PAYMENTS],
  "payments:approve":     [ROLES.ADMIN, ROLES.PAYMENTS, ROLES.SUPERVISOR],
  // InterestResource accrual reads accept these human roles only; ROLE_API is M2M.
  // Do not inherit PAYMENTS/SUPERVISOR from the wider payments workspace.
  "interest:view":        [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER],
  // Lending compliance-pack reads include the operational lending roles accepted by
  // CompliancePackResource.listActive; maker/checker writes remain compliance/admin only.
  "lending:compliance:view":    [ROLES.ADMIN, ROLES.COMPLIANCE, ROLES.CREDIT_RISK, ROLES.LENDING_OFFICER],
  "lending:compliance:propose": [ROLES.ADMIN, ROLES.COMPLIANCE],
  "lending:compliance:decide":  [ROLES.ADMIN, ROLES.COMPLIANCE],
  // Campaign-service audience endpoints use campaign.read for catalogue/preview, while
  // creation and lifecycle transitions are restricted to HUMAN operators/admins.
  "campaign:view":          [ROLES.ADMIN, ROLES.OPERATOR, ROLES.AUDITOR],
  "campaign:create":        [ROLES.ADMIN, ROLES.OPERATOR],
  "campaign:submit":        [ROLES.ADMIN, ROLES.OPERATOR],
  "campaign:activate":      [ROLES.ADMIN, ROLES.OPERATOR],
  // Card-issuance deliberately has a narrower read role than the wider payments
  // workspace: its GET endpoints accept only VIEWER/OPERATOR/ADMIN, and every
  // lifecycle writes except block/cancel accept only OPERATOR/ADMIN. Compliance
  // retains the service-authorized emergency block/cancel pair, not limit or
  // channel-control changes. Keep the console's controls aligned with that split.
  "cards:view":            [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER],
  "cards:issue":           [ROLES.ADMIN, ROLES.OPERATOR],
  "cards:manage":          [ROLES.ADMIN, ROLES.OPERATOR],
  "cards:block":           [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  // Generic Product Studio. Scope-derived roles make the same UI usable with a provider-neutral
  // standalone OIDC issuer; OpenBank OPERATOR/ADMIN remain compatible personas.
  "catalog:read":         [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.PAYMENTS, ROLES.CATALOG_READ, ROLES.CATALOG_AUTHOR, ROLES.CATALOG_PUBLISH],
  "catalog:author":       [ROLES.ADMIN, ROLES.OPERATOR, ROLES.CATALOG_AUTHOR],
  "catalog:publish":      [ROLES.ADMIN, ROLES.OPERATOR, ROLES.CATALOG_PUBLISH],
  // Parties / KYC / Onboarding — party-service's list/search/detail GETs accept
  // VIEWER/OPERATOR/ADMIN/KYC only (PartyResource.kt). Compliance and the
  // split KYC opener/reviewer roles use their own case endpoints and must not
  // receive a PII party directory link that the backend will 403.
  "parties:view":         [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.KYC],
  // Mirrors party-service POST /api/v1/parties: a viewer may inspect parties but
  // must never be offered a customer-creation workflow.
  "parties:create":       [ROLES.ADMIN, ROLES.OPERATOR, ROLES.KYC],
  "parties:edit":         [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  // PID-service's party list/detail/create and BankID sync endpoints accept only
  // OPERATOR/ADMIN. Keep this PII workspace narrower than the generic payments area.
  "pid:view":             [ROLES.ADMIN, ROLES.OPERATOR],
  "kyc:view":             [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE, ROLES.KYC, ROLES.KYC_OPENER, ROLES.KYC_REVIEWER],
  "kyc:approve":          [ROLES.ADMIN, ROLES.COMPLIANCE, ROLES.KYC_REVIEWER],
  // ROLES.DEMO here (and nowhere else in this file) because onboarding-service's own
  // @RolesAllowed(Roles.VIEWER, ...) is the one backend in this matrix that already accepts
  // a plain viewer — verified by reading OnboardingResource.kt, not assumed. Every other
  // *:view permission demo might plausibly want (kyc, audit, delegations, notifications:
  // checked; compliance/regulatory/technical-accounts/templates/system: not yet checked)
  // gates a backend or OPA rule with no viewer-equivalent tier, so adding DEMO there would
  // render a nav link that 403s on click — worse than today's hidden link, and the opposite
  // of what a demo account is for. Tracked as issue #5020 before widening
  // further; do not add ROLES.DEMO to another line here without first confirming its
  // backend accepts VIEWER-tier reads.
  "onboarding:view":      [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE, ROLES.KYC, ROLES.KYC_OPENER, ROLES.KYC_REVIEWER, ROLES.DEMO],
  // PID identity cases expose PII and four-eyes decisions only to the roles accepted by
  // VerificationCaseResource; KYC split roles and demo must not see a 403 cockpit.
  "identity-cases:view":  [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  "identity-cases:decide":[ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  // Delegated access (ADR-0232 / ADR-0230). Mirrors delegation-service's own class-level
  // @RolesAllowed(ROLE_API, ROLE_OPERATOR, ROLE_ADMIN) minus ROLE_API, which is the M2M
  // identity and never a console session — listing it here would render a section for a
  // principal that cannot hold a browser session anyway. Nav/route gating only, not a
  // security control: the BFF relays the operator's own bearer and delegation-service +
  // OPA (delegation.list / delegation.read) decide the real answer.
  //
  // There is deliberately NO "delegations:propose" yet. The bank-side mutations
  // (suspend/reinstate/revoke) have no maker-checker store to land in, so this console
  // ships read-only and there is no action for such a permission to gate — see
  // src/test/delegations-no-mutation.guard.test.ts. Adding the permission before the
  // action exists would be a role matrix that lies about what the UI can do.
  "delegations:view":     [ROLES.ADMIN, ROLES.OPERATOR],
  // Audit
  "audit:view":           [ROLES.ADMIN, ROLES.AUDITOR, ROLES.COMPLIANCE, ROLES.SUPERVISOR],
  // Compliance / Regulatory
  "compliance:view":      [ROLES.ADMIN, ROLES.COMPLIANCE, ROLES.AUDITOR, ROLES.SUPERVISOR],
  // Sanctions service exposes viewer-tier reads, but reserves screening, list management and
  // four-eyes decisions for operators/admins. Keep this split separate from compliance:view so
  // the console never promises a mutation that the backend will reject.
  "sanctions:view":       [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER],
  "sanctions:screen":     [ROLES.ADMIN, ROLES.OPERATOR],
  "sanctions:manage":     [ROLES.ADMIN, ROLES.OPERATOR],
  "sanctions:review":     [ROLES.ADMIN, ROLES.OPERATOR],
  "regulatory:view":          [ROLES.ADMIN, ROLES.COMPLIANCE],
  "regulatory:submit":        [ROLES.ADMIN, ROLES.COMPLIANCE],
  // Technical accounts
  "technical-accounts:view":  [ROLES.ADMIN, ROLES.OPERATOR, ROLES.AUDITOR],
  "technical-accounts:edit":  [ROLES.ADMIN],
  // Document templates (ADR-0162 D6) — legal/compliance template authoring.
  // No dedicated "legal" role exists yet, so ROLE_COMPLIANCE (the closest
  // legal/ops persona) gets edit rights alongside ROLE_ADMIN; ROLE_OPERATOR is
  // view-only (mirrors the ADR's RBAC note verbatim).
  "templates:view":           [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  "templates:edit":           [ROLES.ADMIN, ROLES.COMPLIANCE],
  // Customer message history (ADR-0176 D7). ADMIN/OPERATOR only — the intersection of
  // the two backend gates, which disagree in both directions: notification-service's
  // @RolesAllowed lists ROLE_VIEWER (whom OPA then denies — no rest.rego rule fires for a
  // pure viewer), while rest.rego's compliance-read-any admits ROLE_COMPLIANCE (whom
  // @RolesAllowed then denies). Listing either here would render a tab that 403s.
  //
  // NOT a security control — UX/nav gating only. rest.rego's operator-read-any grants
  // .read/.list on ANY resource to every operator, and the BFF proxy relays the operator's
  // own bearer with no permission check, so anyone who can reach the console can already
  // call the endpoint directly. This decides what we *render*, not what they can *fetch*.
  // Real metadata/body separation needs a policy change — issue #1326.
  "notifications:view":       [ROLES.ADMIN, ROLES.OPERATOR],
  // Screen feedback carries free-text comments and screenshot keys (ADR-0192), so it is
  // intentionally narrower than general docs/viewer access.
  "feedback:view":            [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  // Operator-initiated customer messaging (ADR-0176 D4/D5). Matches the backend's actual rego
  // grants exactly (opsmessage-compose / opsmessage-approve in rest.rego) — any
  // ROLE_OPERATOR/ROLE_ADMIN may compose or decide, with self-approval refused server-side by
  // SelfApprovalNotAllowedException, not by a narrower UI-side role split. Unlike
  // notifications:view above, this one DOES mirror a real backend check (opsmessage.compose is
  // also four-eyes gated, which no UI permission could substitute for either way).
  "opsmessage:compose":       [ROLES.ADMIN, ROLES.OPERATOR],
  "opsmessage:approve":       [ROLES.ADMIN, ROLES.OPERATOR],
  // Flaky Test Hunter (ADR-0168, issue #5499) — mirrors FlakyTestResource's own
  // @RolesAllowed exactly: GET /findings(/{id}) takes ROLE_ADMIN + ROLE_VIEWER,
  // POST /check/trigger takes ROLE_ADMIN only. The page itself is reached under
  // /iaops (system:view), so :view only gates the finding-detail deep link;
  // :trigger is what hides the "Run check now" button from anyone the backend
  // would 403 anyway — UX only, the backend re-checks on every call.
  "flaky-test-hunter:view":    [ROLES.ADMIN, ROLES.VIEWER],
  "flaky-test-hunter:trigger": [ROLES.ADMIN],
  // System
  // ROLES.DEMO added 2026-08-16 (issue #5020), verified safe two independent ways before
  // adding: proxy.ts's routeGuards array has a pattern for /system/config (ADMIN only,
  // the mutation path — untouched) but NONE for the general /system/* view pages, so no
  // route-level role check exists to conflict with; and every BFF route these pages call
  // (finops/*, devops/*, security, observability/*, temporal/status) either has no
  // permission check of its own at all, or (api/iaops/rca) checks this exact
  // hasPermission(roles, 'system:view') — same source of truth, so widening it here widens
  // consistently everywhere it is read. Unlike onboarding:view above, there is no backend
  // @RolesAllowed/rego to have verified against, because these pages proxy telemetry
  // (Prometheus, Holmes, k8s) rather than calling a service with its own RBAC.
  "system:view":              [ROLES.ADMIN, ROLES.OPERATOR, ROLES.DEMO],
  // MCP agent-service accepts only these human roles; keep demo out of the tool cockpit and
  // expose compliance's authorized read/execute path instead.
  "agent:view":               [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  "agent:execute":            [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  // Agent proposal reads/decisions are exposed by ProposalResource to these human roles;
  // demo/system-view users must not see an actionable approval queue that the backend rejects.
  "approvals:view":           [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  "agent:decide":             [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  // DevOps findings are readable by system:view, but the devops-agent POST approval/rejection
  // endpoints are ADMIN-only. Keep HITL decision authority explicit in the UI matrix.
  "devops:decide":             [ROLES.ADMIN],
  "system:config":            [ROLES.ADMIN],
  // Docs
  "docs:view":                [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR],
  // Settings — demo user is blocked
  "settings:view":            [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR],
} as const

export type Permission = keyof typeof PERMISSIONS

export function hasPermission(roles: string[], permission: Permission): boolean {
  const allowed = PERMISSIONS[permission] as readonly string[]
  return roles.some(r => allowed.includes(r))
}

/**
 * One UI route-to-permission projection used by the edge gate and route-coverage tests.
 *
 * This is deliberately a route manifest, not a list of roles: adding a role to a permission
 * immediately changes every matching page consistently, while the backend/OPA remains the
 * enforcement authority for data and mutations. Longest prefixes win, so `/system/config` can
 * be stricter than `/system` without an exception in a page component.
 */
const ROUTE_PREFIXES: ReadonlyArray<readonly [Permission, readonly string[]]> = [
  ['dashboard:view', ['/dashboard']],
  ['system:config', ['/system/config']],
  ['catalog:read', ['/product-studio']],
  ['templates:view', ['/document-templates']],
  ['delegations:view', ['/delegations']],
  ['feedback:view', ['/feedback']],
  ['regulatory:view', ['/regulatory']],
  ['audit:view', ['/audit']],
  ['kyc:view', ['/kyc']],
  ['onboarding:view', ['/onboarding']],
  ['identity-cases:view', ['/identity-cases']],
  ['pid:view', ['/pid']],
  ['parties:create', ['/parties/new']],
  ['parties:view', ['/parties']],
  ['transactions:view', ['/transactions']],
  ['interest:view', ['/interest']],
  ['accounts:create', ['/accounts/new']],
  ['accounts:view', ['/accounts', '/ledger', '/day-end']],
  ['cards:view', ['/cards']],
  ['payments:view', [
    '/payments', '/product-catalog', '/standing-orders', '/sdd', '/sepa-instant', '/clearing',
      '/fx', '/swift', '/fees', '/lending',
  ]],
  ['sanctions:view', ['/sanctions']],
  ['compliance:view', [
    '/aml', '/fraud', '/disputes', '/consents', '/customer-360',
    '/docs/compliance', '/docs/bcp',
  ]],
  ['campaign:view', ['/segments']],
  ['campaign:create', ['/segments/new']],
  ['campaign:view', ['/campaigns']],
  ['campaign:create', ['/campaigns/new']],
  ['lending:compliance:view', ['/lending/compliance-packs']],
  ['approvals:view', ['/approvals']],
  ['system:view', [
    '/devops', '/finops', '/iaops', '/infrastructure', '/observability', '/temporal',
    '/security', '/system',
  ]],
  ['notifications:view', ['/notifications']],
  ['agent:view', ['/system/agent']],
  ['docs:view', ['/docs', '/services']],
  ['settings:view', ['/settings']],
]

export function permissionForPath(pathname: string): Permission | undefined {
  let match: { permission: Permission; length: number } | undefined
  for (const [permission, prefixes] of ROUTE_PREFIXES) {
    for (const prefix of prefixes) {
      if ((pathname === prefix || pathname.startsWith(`${prefix}/`)) && (!match || prefix.length > match.length)) {
        match = { permission, length: prefix.length }
      }
    }
  }
  return match?.permission
}

export function hasRole(roles: string[], role: Role): boolean {
  return roles.includes(role)
}

export function hasAnyRole(roles: string[], ...required: Role[]): boolean {
  return required.some(r => roles.includes(r))
}

export const ROLE_LABELS: Record<string, { label: string; color: string; bg: string }> = {
  ROLE_ADMIN:      { label: "Admin",      color: "#dc2626", bg: "#fef2f2" },
  ROLE_OPERATOR:   { label: "Operator",   color: "#2563eb", bg: "#eff6ff" },
  ROLE_VIEWER:     { label: "Viewer",     color: "#6b7280", bg: "#f9fafb" },
  ROLE_COMPLIANCE: { label: "Compliance", color: "#7c3aed", bg: "#faf5ff" },
  ROLE_PAYMENTS:   { label: "Payments",   color: "#059669", bg: "#f0fdf4" },
  ROLE_AUDITOR:    { label: "Auditor",    color: "#d97706", bg: "#fffbeb" },
  ROLE_API:        { label: "API",        color: "#0891b2", bg: "#ecfeff" },
  ROLE_SUPERVISOR: { label: "Supervisor", color: "#be185d", bg: "#fdf2f8" },
  ROLE_KYC:        { label: "KYC",        color: "#0d9488", bg: "#f0fdfa" },
  ROLE_KYC_OPENER: { label: "KYC Opener", color: "#0d9488", bg: "#f0fdfa" },
  ROLE_KYC_REVIEWER: { label: "KYC Reviewer", color: "#115e59", bg: "#ccfbf1" },
  ROLE_DEMO:       { label: "Demo",       color: "#64748b", bg: "#f8fafc" },
}
