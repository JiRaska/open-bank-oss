// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0229 D4 (first cut): persona derivation from realm roles. One operator = one primary
// persona (strongest match wins); it drives the pinned "My workspace" quick links at the top
// of the sidebar and the dashboard. The full menu below is untouched — the workspace is an
// additive landing, not a wall.

import { ROLES, type Permission, type Role } from './roles'

export type Persona = 'backoffice' | 'payments' | 'compliance' | 'supervisor' | 'platform'

/** A persona shortcut carries its UI permission so every consumer can suppress
 * a destination before it becomes a 403 trap (ADR-0229 D3/D4). */
export type WorkspaceLink = { href: string; nameCs: string; nameEn: string; permission: Permission }

const WORKSPACES: Record<Persona, WorkspaceLink[]> = {
  backoffice: [
    { href: '/parties', nameCs: 'Klienti', nameEn: 'Parties', permission: 'parties:view' },
    { href: '/accounts', nameCs: 'Účty', nameEn: 'Accounts', permission: 'accounts:view' },
    { href: '/transactions', nameCs: 'Transakce', nameEn: 'Transactions', permission: 'transactions:view' },
    { href: '/onboarding', nameCs: 'Onboarding', nameEn: 'Onboarding', permission: 'onboarding:view' },
  ],
  payments: [
    { href: '/payments', nameCs: 'Platby', nameEn: 'Payments', permission: 'payments:view' },
    // NOT /standing-orders (#7790): StandingOrderResource admits ROLE_API/OPERATOR/ADMIN and
    // no ROLE_PAYMENTS on any method, so this persona's own shortcut was a 403 trap. It
    // passed review only because the old `payments:view` bucket granted twelve routes to
    // five personas at once. /sdd is the payment-ops queue that sepa-direct-debit really
    // does admit ROLE_PAYMENTS on.
    { href: '/sdd', nameCs: 'Inkasa (SDD)', nameEn: 'Direct Debits', permission: 'sdd:view' },
    { href: '/clearing', nameCs: 'Clearing', nameEn: 'Clearing', permission: 'clearing:view' },
    { href: '/fx', nameCs: 'FX', nameEn: 'FX', permission: 'fx:view' },
  ],
  compliance: [
    { href: '/kyc', nameCs: 'KYC', nameEn: 'KYC', permission: 'kyc:view' },
    { href: '/aml', nameCs: 'AML', nameEn: 'AML', permission: 'compliance:view' },
    { href: '/sanctions', nameCs: 'Sankce', nameEn: 'Sanctions', permission: 'compliance:view' },
    { href: '/audit', nameCs: 'Audit', nameEn: 'Audit', permission: 'audit:view' },
  ],
  supervisor: [
    // A supervisor can review, but cannot use the platform-admin approvals queue or the
    // operator-only closing trigger. Keep this workspace aligned to the actual RBAC matrix.
    //
    // There is deliberately NO payment-oversight link here any more (#7790). ROLE_SUPERVISOR is
    // admitted by none of the payment read endpoints — not sepa-payment, domestic-payment,
    // sepa-instant, clearing, sdd, fx or swift — so this shortcut led straight to a 403. The
    // supervisor's real payment authority is `payments:approve`, which gates an action, not this
    // page. Restoring the link needs a backend that accepts the role, not a wider UI permission.
    { href: '/aml', nameCs: 'AML dohled', nameEn: 'AML oversight', permission: 'compliance:view' },
    { href: '/sanctions', nameCs: 'Sankční dohled', nameEn: 'Sanctions oversight', permission: 'compliance:view' },
    { href: '/audit', nameCs: 'Audit', nameEn: 'Audit', permission: 'audit:view' },
  ],
  platform: [
    { href: '/system/tests', nameCs: 'Test Intelligence', nameEn: 'Test Intelligence', permission: 'system:view' },
    { href: '/system/health', nameCs: 'Zdraví systému', nameEn: 'System Health', permission: 'system:view' },
    { href: '/devops', nameCs: 'DevOps', nameEn: 'DevOps', permission: 'system:view' },
    { href: '/observability', nameCs: 'Observability', nameEn: 'Observability', permission: 'system:view' },
  ],
}

const PERSONA_LABELS: Record<Persona, { cs: string; en: string }> = {
  backoffice: { cs: 'Backoffice', en: 'Backoffice' },
  payments: { cs: 'Platební operace', en: 'Payments Ops' },
  compliance: { cs: 'Compliance', en: 'Compliance' },
  supervisor: { cs: 'Supervize', en: 'Supervision' },
  platform: { cs: 'Platforma', en: 'Platform' },
}

const has = (roles: string[], r: Role) => roles.includes(r)

/**
 * Strongest persona wins, in this order: platform admin > supervisor > compliance > payments
 * > backoffice (the default for any authenticated staff member, including plain OPERATOR).
 */
export function personaForRoles(roles: string[]): Persona {
  if (has(roles, ROLES.ADMIN)) return 'platform'
  if (has(roles, ROLES.SUPERVISOR)) return 'supervisor'
  if (has(roles, ROLES.COMPLIANCE) || has(roles, ROLES.AUDITOR) ||
      has(roles, ROLES.KYC) || has(roles, ROLES.KYC_OPENER) || has(roles, ROLES.KYC_REVIEWER)) {
    return 'compliance'
  }
  if (has(roles, ROLES.PAYMENTS)) return 'payments'
  return 'backoffice'
}

export function workspaceFor(persona: Persona): WorkspaceLink[] {
  return WORKSPACES[persona]
}

export function personaLabel(persona: Persona, lang: 'cs' | 'en'): string {
  return PERSONA_LABELS[persona][lang]
}
