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
