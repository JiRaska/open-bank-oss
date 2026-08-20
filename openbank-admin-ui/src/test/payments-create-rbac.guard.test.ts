import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(path.resolve(__dirname, '../app/payments/page.tsx'), 'utf8')
const roles = readFileSync(path.resolve(__dirname, '../lib/auth/roles.ts'), 'utf8')

describe('payment initiation access boundary', () => {
  it('keeps payment viewing and payment initiation as separate permissions', () => {
    expect(roles).toContain('"payments:view"')
    expect(roles).toContain('"payments:create":      [ROLES.ADMIN, ROLES.OPERATOR, ROLES.PAYMENTS]')
    expect(page).toContain('<AuthGuard permission="payments:view">')
    expect(page).toContain("hasPermission(session?.user?.roles ?? [], 'payments:create')")
    expect(page).toContain('{canCreate && (')
  })

  it('does not change the backend-backed idempotent money path', () => {
    expect(page).toContain("'Idempotency-Key': crypto.randomUUID()")
    expect(page).toContain('const SEPA_API         = \'/api/sepa-payments\'')
    expect(page).toContain('const DOMESTIC_API     = \'/api/domestic-payments\'')
    expect(page).toContain('f.vopStatus === \'no_match\'')
  })
})
