// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.
import { describe, expect, it } from 'vitest'
import { PERMISSIONS, ROLES } from '@/lib/auth/roles'
import { PERMISSION_DESCRIPTIONS, ROLE_DESCRIPTIONS, matchesPermissionSearch } from '@/lib/auth/access-descriptions'

describe('access preview descriptions', () => {
  it('covers every catalog entry in both languages without stale entries', () => {
    expect(Object.keys(PERMISSION_DESCRIPTIONS).sort()).toEqual(Object.keys(PERMISSIONS).sort())
    expect(Object.keys(ROLE_DESCRIPTIONS).sort()).toEqual(Object.values(ROLES).sort())
    for (const [id, copy] of Object.entries({ ...ROLE_DESCRIPTIONS, ...PERMISSION_DESCRIPTIONS })) {
      for (const language of ['cs', 'en'] as const) {
        expect(copy.name[language].trim()).not.toBe('')
        expect(copy.name[language]).not.toBe(id)
        expect(copy.description[language].trim().length).toBeGreaterThan(20)
      }
    }
  })
  it('finds permissions by either language, description or identifier', () => {
    for (const query of ['SCHVÁLENÍ PLATBY', 'schvaleni platby', 'Approve payment', 'awaiting approval', 'payments:approve', '  ']) {
      expect(matchesPermissionSearch('payments:approve', query)).toBe(true)
    }
    expect(matchesPermissionSearch('payments:approve', 'no-such-permission')).toBe(false)
  })
})
