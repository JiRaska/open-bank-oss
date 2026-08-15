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
  "dashboard:view":           [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.PAYMENTS, ROLES.AUDITOR],
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
  // Generic Product Studio. Scope-derived roles make the same UI usable with a provider-neutral
  // standalone OIDC issuer; OpenBank OPERATOR/ADMIN remain compatible personas.
  "catalog:read":         [ROLES.ADMIN, ROLES.OPERATOR, ROLES.CATALOG_READ, ROLES.CATALOG_AUTHOR, ROLES.CATALOG_PUBLISH],
  "catalog:author":       [ROLES.ADMIN, ROLES.OPERATOR, ROLES.CATALOG_AUTHOR],
  "catalog:publish":      [ROLES.ADMIN, ROLES.OPERATOR, ROLES.CATALOG_PUBLISH],
  // Parties / KYC / Onboarding — KYC_OPENER may NOT approve (four-eyes, ADR-0116);
  // KYC_REVIEWER may NOT open. The UI mirrors the backend @RolesAllowed split so a
  // holder of only one KYC role gets exactly their half of the workflow.
  "parties:view":         [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE, ROLES.KYC, ROLES.KYC_OPENER, ROLES.KYC_REVIEWER],
  "parties:edit":         [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  "kyc:view":             [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE, ROLES.KYC, ROLES.KYC_OPENER, ROLES.KYC_REVIEWER],
  "kyc:approve":          [ROLES.ADMIN, ROLES.COMPLIANCE, ROLES.KYC_REVIEWER],
  "onboarding:view":      [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE, ROLES.KYC, ROLES.KYC_OPENER, ROLES.KYC_REVIEWER],
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
  // Operator-initiated customer messaging (ADR-0176 D4/D5). Matches the backend's actual rego
  // grants exactly (opsmessage-compose / opsmessage-approve in rest.rego) — any
  // ROLE_OPERATOR/ROLE_ADMIN may compose or decide, with self-approval refused server-side by
  // SelfApprovalNotAllowedException, not by a narrower UI-side role split. Unlike
  // notifications:view above, this one DOES mirror a real backend check (opsmessage.compose is
  // also four-eyes gated, which no UI permission could substitute for either way).
  "opsmessage:compose":       [ROLES.ADMIN, ROLES.OPERATOR],
  "opsmessage:approve":       [ROLES.ADMIN, ROLES.OPERATOR],
  // System
  "system:view":              [ROLES.ADMIN, ROLES.OPERATOR],
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
