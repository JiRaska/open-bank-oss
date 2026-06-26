// SPDX-License-Identifier: MPL-2.0
import { describe, it, expect } from 'vitest'
import { hasPermission, hasRole, hasAnyRole, ROLES, PERMISSIONS } from '@/lib/auth/roles'

describe('hasPermission', () => {
  it('returns true for admin on any permission', () => {
    const admin = [ROLES.ADMIN]
    for (const perm of Object.keys(PERMISSIONS) as (keyof typeof PERMISSIONS)[]) {
      expect(hasPermission(admin, perm)).toBe(true)
    }
  })

  it('returns true when role is in allowed list', () => {
    expect(hasPermission([ROLES.COMPLIANCE], 'kyc:view')).toBe(true)
    expect(hasPermission([ROLES.AUDITOR], 'audit:view')).toBe(true)
    expect(hasPermission([ROLES.PAYMENTS], 'payments:create')).toBe(true)
  })

  it('returns false when role is not in allowed list', () => {
    expect(hasPermission([ROLES.VIEWER], 'accounts:create')).toBe(false)
    expect(hasPermission([ROLES.VIEWER], 'system:config')).toBe(false)
    expect(hasPermission([ROLES.API], 'payments:approve')).toBe(false)
  })

  it('returns false for empty roles array', () => {
    expect(hasPermission([], 'dashboard:view')).toBe(false)
  })

  it('returns true when at least one role matches', () => {
    expect(hasPermission([ROLES.VIEWER, ROLES.PAYMENTS], 'payments:approve')).toBe(true)
  })
})

describe('hasRole', () => {
  it('returns true when role is present', () => {
    expect(hasRole([ROLES.ADMIN, ROLES.VIEWER], ROLES.ADMIN)).toBe(true)
  })

  it('returns false when role is absent', () => {
    expect(hasRole([ROLES.VIEWER], ROLES.ADMIN)).toBe(false)
  })

  it('returns false for empty roles', () => {
    expect(hasRole([], ROLES.OPERATOR)).toBe(false)
  })
})

describe('hasAnyRole', () => {
  it('returns true if any required role matches', () => {
    expect(hasAnyRole([ROLES.VIEWER], ROLES.ADMIN, ROLES.VIEWER)).toBe(true)
  })

  it('returns false if no required role matches', () => {
    expect(hasAnyRole([ROLES.API], ROLES.ADMIN, ROLES.COMPLIANCE)).toBe(false)
  })
})
