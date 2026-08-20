// SPDX-License-Identifier: Apache-2.0
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

  it('admits both halves of the KYC four-eyes split to the case queue, but only the reviewer to approve', () => {
    expect(hasPermission([ROLES.KYC_OPENER], 'kyc:view')).toBe(true)
    expect(hasPermission([ROLES.KYC_REVIEWER], 'kyc:view')).toBe(true)
    expect(hasPermission([ROLES.KYC_REVIEWER], 'kyc:approve')).toBe(true)
    // ADR-0116: the opener must never self-approve — the UI hides the action exactly as the
    // backend refuses it.
    expect(hasPermission([ROLES.KYC_OPENER], 'kyc:approve')).toBe(false)
  })

  it('matches party-service PII read roles without widening the backend gate', () => {
    expect(hasPermission([ROLES.ADMIN], 'parties:view')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'parties:view')).toBe(true)
    expect(hasPermission([ROLES.VIEWER], 'parties:view')).toBe(true)
    expect(hasPermission([ROLES.KYC], 'parties:view')).toBe(true)
    expect(hasPermission([ROLES.COMPLIANCE], 'parties:view')).toBe(false)
    expect(hasPermission([ROLES.KYC_OPENER], 'parties:view')).toBe(false)
    expect(hasPermission([ROLES.KYC_REVIEWER], 'parties:view')).toBe(false)
  })

  it('admits the supervisor to payment approval and audit reading, not to creation', () => {
    expect(hasPermission([ROLES.SUPERVISOR], 'payments:approve')).toBe(true)
    expect(hasPermission([ROLES.SUPERVISOR], 'audit:view')).toBe(true)
    expect(hasPermission([ROLES.SUPERVISOR], 'payments:create')).toBe(false)
  })

  // Issue #5020: the demo account (ROLE_VIEWER + ROLE_DEMO only, no write-capable role — see
  // fix/demo-account-viewer-only) is admitted to exactly the *:view permissions verified safe
  // against their backend: onboarding-service accepts a plain VIEWER, and the system pages
  // proxy telemetry with no backend RBAC of their own. It must NOT be admitted to any
  // permission whose backend has no viewer-equivalent tier, or the nav would render a link
  // that 403s on click — worse than hiding it. That verification lives in each permission's
  // own comment in roles.ts; this test only pins the resulting boolean so a future edit that
  // silently widens or narrows demo's reach is caught here.
  it('admits the demo account to the two verified-safe view permissions, nothing else', () => {
    expect(hasPermission([ROLES.DEMO], 'onboarding:view')).toBe(true)
    expect(hasPermission([ROLES.DEMO], 'system:view')).toBe(true)
    // Structurally blocked — no backend viewer tier exists (verified by reading the
    // service's @RolesAllowed / rego, not assumed):
    expect(hasPermission([ROLES.DEMO], 'kyc:view')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'audit:view')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'delegations:view')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'notifications:view')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'regulatory:view')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'templates:view')).toBe(false)
    // Deliberate policy exclusion, not a technical gap — dispute-service's own rego documents
    // ROLE_VIEWER as excluded from compliance data on PII grounds; a product/compliance call,
    // not something this matrix should silently widen.
    expect(hasPermission([ROLES.DEMO], 'compliance:view')).toBe(false)
    // Demo must never gain a write-capable permission, regardless of what view access it holds.
    expect(hasPermission([ROLES.DEMO], 'accounts:create')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'transactions:reverse')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'payments:approve')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'system:config')).toBe(false)
  })

  // Flaky Test Hunter (ADR-0168, issue #5499): 'flaky-test-hunter:trigger' mirrors
  // FlakyTestResource's POST /check/trigger @RolesAllowed("ROLE_ADMIN") exactly — a viewer or
  // operator can read the findings list (via system:view on the /iaops page) but must never see
  // the "Run check now" action, same as the backend would 403 it.
  it('admits only ROLE_ADMIN to trigger a flaky-test-hunter check', () => {
    expect(hasPermission([ROLES.ADMIN], 'flaky-test-hunter:trigger')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'flaky-test-hunter:trigger')).toBe(false)
    expect(hasPermission([ROLES.VIEWER], 'flaky-test-hunter:trigger')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'flaky-test-hunter:trigger')).toBe(false)
  })

  it('admits ROLE_ADMIN and ROLE_VIEWER to flaky-test-hunter:view, mirroring the GET /findings roles', () => {
    expect(hasPermission([ROLES.ADMIN], 'flaky-test-hunter:view')).toBe(true)
    expect(hasPermission([ROLES.VIEWER], 'flaky-test-hunter:view')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'flaky-test-hunter:view')).toBe(false)
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
