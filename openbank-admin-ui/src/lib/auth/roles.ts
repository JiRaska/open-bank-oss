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
  DEMO:       "ROLE_DEMO",
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
  "payments:view":            [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.PAYMENTS],
  "payments:create":          [ROLES.ADMIN, ROLES.OPERATOR, ROLES.PAYMENTS],
  "payments:approve":         [ROLES.ADMIN, ROLES.PAYMENTS],
  // Parties / KYC / Onboarding
  "parties:view":             [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER, ROLES.COMPLIANCE],
  "parties:edit":             [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  "kyc:view":                 [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  "kyc:approve":              [ROLES.ADMIN, ROLES.COMPLIANCE],
  "onboarding:view":          [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE],
  // Audit
  "audit:view":               [ROLES.ADMIN, ROLES.AUDITOR, ROLES.COMPLIANCE],
  // Compliance / Regulatory
  "compliance:view":          [ROLES.ADMIN, ROLES.COMPLIANCE, ROLES.AUDITOR],
  "regulatory:view":          [ROLES.ADMIN, ROLES.COMPLIANCE],
  "regulatory:submit":        [ROLES.ADMIN, ROLES.COMPLIANCE],
  // Technical accounts
  "technical-accounts:view":  [ROLES.ADMIN, ROLES.OPERATOR, ROLES.AUDITOR],
  "technical-accounts:edit":  [ROLES.ADMIN],
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
  ROLE_DEMO:       { label: "Demo",       color: "#64748b", bg: "#f8fafc" },
}
