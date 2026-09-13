// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = resolve(__dirname, '..')
const page = readFileSync(resolve(root, 'app/lending/risk/page.tsx'), 'utf8')
const roles = readFileSync(resolve(root, 'lib/auth/roles.ts'), 'utf8')
const sidebar = readFileSync(resolve(root, 'components/layout/Sidebar.tsx'), 'utf8')
const resource = readFileSync(
  resolve(root, '../../openbank-lending-service/src/main/kotlin/com/openbank/lending/infrastructure/rest/CreditRiskResource.kt'),
  'utf8',
)

describe('credit-risk console permissions mirror the service roles', () => {
  it('gates the page and the nav entry on lending:risk:view', () => {
    expect(roles).toContain('"lending:risk:view":          [ROLES.ADMIN, ROLES.COMPLIANCE, ROLES.CREDIT_RISK, ROLES.LENDING_OFFICER]')
    expect(roles).toContain("['lending:risk:view', ['/lending/risk']]")
    expect(page).toContain('<AuthGuard permission="lending:risk:view">')
    expect(sidebar).toContain("href: '/lending/risk'")
    expect(sidebar).toContain("permission: 'lending:risk:view'")
  })

  it('grants the UI exactly the roles CreditRiskResource admits — no wider', () => {
    const declared = /@RolesAllowed\(([^)]*)\)/.exec(resource)?.[1] ?? ''
    const serviceRoles = [...declared.matchAll(/"(ROLE_[A-Z_]+)"/g)].map(m => m[1]).sort()
    expect(serviceRoles).toEqual(['ROLE_ADMIN', 'ROLE_COMPLIANCE', 'ROLE_CREDIT_RISK', 'ROLE_LENDING_OFFICER'])
    const uiLine = /"lending:risk:view":\s*\[([^\]]*)\]/.exec(roles)?.[1] ?? ''
    const uiRoles = [...uiLine.matchAll(/ROLES\.([A-Z_]+)/g)]
      .map(m => `ROLE_${m[1]}`)
      .sort()
    expect(uiRoles).toEqual(serviceRoles)
  })

  it('stays read-only: no mutating call from the console', () => {
    expect(page).not.toMatch(/method:\s*'(POST|PUT|PATCH|DELETE)'/)
    expect(page).toContain('ADR-0227')
  })
})
