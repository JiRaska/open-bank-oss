// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
// See LICENSE in the repository root or https://www.apache.org/licenses/LICENSE-2.0 for details.

import { describe, expect, it } from 'vitest'
import { personaForRoles, personaLabel, workspaceFor } from '@/lib/auth/persona'
import { ROLES } from '@/lib/auth/roles'

describe('personaForRoles (ADR-0229 D4)', () => {
  it('platform admin wins over every other role', () => {
    expect(personaForRoles([ROLES.ADMIN, ROLES.COMPLIANCE, ROLES.PAYMENTS])).toBe('platform')
  })

  it('supervisor beats compliance and payments', () => {
    expect(personaForRoles([ROLES.SUPERVISOR, ROLES.COMPLIANCE])).toBe('supervisor')
  })

  it('compliance family maps to compliance — including auditors and the KYC split', () => {
    expect(personaForRoles([ROLES.COMPLIANCE])).toBe('compliance')
    expect(personaForRoles([ROLES.AUDITOR])).toBe('compliance')
    expect(personaForRoles([ROLES.KYC_OPENER])).toBe('compliance')
    expect(personaForRoles([ROLES.KYC_REVIEWER])).toBe('compliance')
  })

  it('payments role maps to payments ops', () => {
    expect(personaForRoles([ROLES.PAYMENTS])).toBe('payments')
  })

  it('plain operator, viewer and empty roles land in backoffice', () => {
    expect(personaForRoles([ROLES.OPERATOR])).toBe('backoffice')
    expect(personaForRoles([ROLES.VIEWER])).toBe('backoffice')
    expect(personaForRoles([])).toBe('backoffice')
  })
})

describe('workspaceFor / personaLabel', () => {
  it('every persona has a non-empty workspace with absolute routes', () => {
    for (const persona of ['backoffice', 'payments', 'compliance', 'supervisor', 'platform'] as const) {
      const links = workspaceFor(persona)
      expect(links.length).toBeGreaterThan(0)
      for (const link of links) {
        expect(link.href.startsWith('/')).toBe(true)
        expect(link.nameCs.length).toBeGreaterThan(0)
        expect(link.nameEn.length).toBeGreaterThan(0)
      }
    }
  })

  it('labels localise', () => {
    expect(personaLabel('compliance', 'cs')).toBe('Compliance')
    expect(personaLabel('payments', 'en')).toBe('Payments Ops')
  })
})

// The Sidebar looks each workspace link up in its nav by href and inherits that entry's
// permission; a link with no match would inherit `undefined` and render ungated. Assert the
// hrefs against the Sidebar source so a renamed destination fails here, not in production.
describe('workspace links resolve to a real nav destination', () => {
  it('every persona href appears in the sidebar nav', async () => {
    const { readFileSync } = await import('node:fs')
    const nav = readFileSync('src/components/layout/Sidebar.tsx', 'utf8')
    const navHrefs = new Set([...nav.matchAll(/href: '([^']+)'/g)].map(m => m[1]))
    expect(navHrefs.size).toBeGreaterThan(10)
    for (const persona of ['backoffice', 'payments', 'compliance', 'supervisor', 'platform'] as const) {
      for (const link of workspaceFor(persona)) {
        expect(navHrefs.has(link.href), `${persona} -> ${link.href} is not a sidebar destination`).toBe(true)
      }
    }
  })
})

