import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const roles = readFileSync(path.resolve(__dirname, '../lib/auth/roles.ts'), 'utf8')
const sidebar = readFileSync(path.resolve(__dirname, '../components/layout/Sidebar.tsx'), 'utf8')
const listPage = readFileSync(path.resolve(__dirname, '../app/cards/page.tsx'), 'utf8')
const detailPage = readFileSync(path.resolve(__dirname, '../app/cards/[id]/page.tsx'), 'utf8')

describe('card issuance access boundary', () => {
  it('keeps the card route aligned with the card-issuance GET roles', () => {
    expect(roles).toContain('"cards:view":            [ROLES.ADMIN, ROLES.OPERATOR, ROLES.VIEWER]')
    expect(roles).toContain("['cards:view', ['/cards']]")
    expect(sidebar).toContain("href: '/cards',             icon: CreditCard,permission: 'cards:view'")
    expect(listPage).toContain('<AuthGuard permission="cards:view">')
    expect(detailPage).toContain('<AuthGuard permission="cards:view">')
  })

  it('does not offer issuance or operator-only lifecycle writes to a read-only card viewer', () => {
    expect(roles).toContain('"cards:issue":           [ROLES.ADMIN, ROLES.OPERATOR]')
    expect(roles).toContain('"cards:manage":          [ROLES.ADMIN, ROLES.OPERATOR]')
    expect(roles).toContain('"cards:block":           [ROLES.ADMIN, ROLES.OPERATOR, ROLES.COMPLIANCE]')
    expect(listPage).toContain("hasPermission(session?.user?.roles ?? [], 'cards:issue')")
    expect(listPage).toContain("hasPermission(session?.user?.roles ?? [], 'cards:manage')")
    expect(listPage).toContain('{canIssue && (')
    expect(listPage).toContain("hasPermission(session?.user?.roles ?? [], 'cards:block')")
    expect(listPage).toContain('{(canManage || canBlock) && <CardTransitionButtons')
    expect(detailPage).toContain("hasPermission(session?.user?.roles ?? [], 'cards:manage')")
    expect(detailPage).toContain("hasPermission(session?.user?.roles ?? [], 'cards:block')")
    expect(detailPage).toContain('{(canManage || canBlock) && <CardTransitionButtons')
    expect(detailPage).toContain('{canManage && (')
  })

  it('keeps the emergency compliance actions separate from operator-only transitions', () => {
    const transitions = readFileSync(path.resolve(__dirname, '../components/cards/CardTransitionButtons.tsx'), 'utf8')
    expect(transitions).toContain("tr.action === 'block' || tr.action === 'cancel' ? canBlock : canManage")
    expect(transitions).toContain('canBlock: boolean')
  })

  it('preserves PCI-safe card presentation and the idempotent issue flow', () => {
    expect(listPage).toContain('full card number and CVV are not available here (PCI DSS)')
    expect(readFileSync(path.resolve(__dirname, '../lib/cards/useCardOperations.ts'), 'utf8'))
      .toContain("headers: { 'Idempotency-Key': idempotencyKey }")
  })
})
