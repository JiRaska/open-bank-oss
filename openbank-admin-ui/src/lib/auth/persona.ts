// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

// ADR-0229 D4 (first cut): persona derivation from realm roles. One operator = one primary
// persona (strongest match wins); it drives the pinned "My workspace" quick links at the top
// of the sidebar. The full menu below is untouched — the workspace is an additive landing,
// not a wall. Persona home dashboards and generated-matrix workspaces are later ADR-0229 steps.

import { ROLES, type Role } from './roles'

export type Persona = 'backoffice' | 'payments' | 'compliance' | 'supervisor' | 'platform'

export type WorkspaceLink = { href: string; nameCs: string; nameEn: string }

const WORKSPACES: Record<Persona, WorkspaceLink[]> = {
  backoffice: [
    { href: '/parties', nameCs: 'Klienti', nameEn: 'Parties' },
    { href: '/accounts', nameCs: 'Účty', nameEn: 'Accounts' },
    { href: '/transactions', nameCs: 'Transakce', nameEn: 'Transactions' },
    { href: '/onboarding', nameCs: 'Onboarding', nameEn: 'Onboarding' },
  ],
  payments: [
    { href: '/payments', nameCs: 'Platby', nameEn: 'Payments' },
    { href: '/standing-orders', nameCs: 'Trvalé příkazy', nameEn: 'Standing Orders' },
    { href: '/clearing', nameCs: 'Clearing', nameEn: 'Clearing' },
    { href: '/fx', nameCs: 'FX', nameEn: 'FX' },
  ],
  compliance: [
    { href: '/kyc', nameCs: 'KYC', nameEn: 'KYC' },
    { href: '/aml', nameCs: 'AML', nameEn: 'AML' },
    { href: '/sanctions', nameCs: 'Sankce', nameEn: 'Sanctions' },
    { href: '/audit', nameCs: 'Audit', nameEn: 'Audit' },
  ],
  supervisor: [
    { href: '/approvals', nameCs: 'Schvalování', nameEn: 'Approvals' },
    { href: '/audit', nameCs: 'Audit', nameEn: 'Audit' },
    { href: '/day-end', nameCs: 'Závěrky', nameEn: 'Closings' },
    { href: '/ledger', nameCs: 'Hlavní kniha', nameEn: 'Ledger' },
  ],
  platform: [
    { href: '/system/health', nameCs: 'Zdraví systému', nameEn: 'System Health' },
    { href: '/devops', nameCs: 'DevOps', nameEn: 'DevOps' },
    { href: '/observability', nameCs: 'Observability', nameEn: 'Observability' },
    { href: '/services', nameCs: 'Služby', nameEn: 'Services' },
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
