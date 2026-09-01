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
    // This assertion used to read `'Idempotency-Key': crypto.randomUUID()` — it pinned
    // the DEFECT in place under a name that claimed the opposite. A fresh UUID per
    // submit throws away the server-side idempotency the payment services genuinely
    // enforce, which is precisely what #7172 reported. What the money path must
    // preserve is that a key is SENT and that it is STABLE across a retry; the
    // behaviour is asserted for real (request counts and key equality) in
    // payments-create-single-flight.test.tsx — this line only keeps the wiring honest.
    expect(page).toContain("'Idempotency-Key': domesticIdem.forPayload(payload)")
    expect(page).toContain("'Idempotency-Key': sepaIdem.forPayload(payload)")
    expect(page).not.toContain('crypto.randomUUID()')
    expect(page).toContain('const SEPA_API         = \'/api/sepa-payments\'')
    expect(page).toContain('const DOMESTIC_API     = \'/api/domestic-payments\'')
    expect(page).toContain('f.vopStatus === \'no_match\'')
  })
})
