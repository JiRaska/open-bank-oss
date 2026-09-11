// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { hasPermission, permissionForPath, ROLES } from '@/lib/auth/roles'

const page = fs.readFileSync(path.resolve(process.cwd(), 'src/app/business-onboarding/page.tsx'), 'utf8')

describe('business onboarding RBAC contract', () => {
  it('mirrors kyb-service roles on the representation routes', () => {
    expect(permissionForPath('/business-onboarding')).toBe('business-onboarding:view')
    expect(hasPermission([ROLES.ADMIN], 'business-onboarding:attest')).toBe(true)
    expect(hasPermission([ROLES.OPERATOR], 'business-onboarding:attest')).toBe(true)
    expect(hasPermission([ROLES.KYC], 'business-onboarding:attest')).toBe(true)
    // Compliance may READ the queue and must not confirm a signing rule: kyb-service's
    // @RolesAllowed on those routes is OPERATOR/ADMIN/KYC, and a console that offered the
    // button would render a control that 403s on click.
    expect(hasPermission([ROLES.COMPLIANCE], 'business-onboarding:view')).toBe(true)
    expect(hasPermission([ROLES.COMPLIANCE], 'business-onboarding:attest')).toBe(false)
    expect(hasPermission([ROLES.DEMO], 'business-onboarding:view')).toBe(false)
  })

  it('keeps the read behind view and the confirmation behind attest', () => {
    expect(page).toContain('<AuthGuard permission="business-onboarding:view">')
    expect(page).toContain('permission="business-onboarding:attest"')
    expect(page).toContain('/api/v1/kyb/representation/${scheme}/${identifier}')
  })
})
