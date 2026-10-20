import { Linter } from 'eslint'
import parser from '@typescript-eslint/parser'
import { describe, expect, it } from 'vitest'
import {
  domainBoundariesPlugin,
  DOMAIN_IMPORT_BOUNDARIES,
  DOMAIN_ROOTS,
  findBoundaryViolation,
  PAYMENTS_DOMAIN_BOUNDARY,
  PUBLIC_SHARED_CONTRACTS,
} from '../../scripts/domain-boundaries.mjs'

const linter = new Linter({ configType: 'eslintrc' })
linter.defineParser('typescript', parser as never)
linter.defineRule('domain-boundaries/no-private-cross-import', domainBoundariesPlugin.rules['no-private-cross-import'])

function lint(source: string, filename: string) {
  return linter.verify(source, {
    parser: 'typescript',
    parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    rules: { 'domain-boundaries/no-private-cross-import': 'error' },
  }, filename)
}

describe('feature import boundaries', () => {
  it('preserves the selected financial domain bans', () => {
    const cases = [
      ["import { x } from '@/lib/ledger/privateLedger'", '/repo/openbank-admin-ui/src/components/payments/Widget.tsx'],
      ["import type { x } from '../balance-sheet/private'", '/repo/openbank-admin-ui/src/app/treasury/page.tsx'],
      ["export { x } from '@/components/balance-sheet/model'", '/repo/openbank-admin-ui/src/lib/approvals/model.ts'],
      ["export * from '../../treasury/positions'", '/repo/openbank-admin-ui/src/components/payments/details/Panel.tsx'],
      ["const x = import('@/components/balance-sheet/model')", '/repo/openbank-admin-ui/src/components/pension/PensionQueue.tsx'],
      ["import { x } from '@/lib/payments/../ledger/privateLedger'", '/repo/openbank-admin-ui/src/components/payments/Widget.tsx'],
    ]
    for (const [source, filename] of cases) {
      expect(lint(source, filename)).toEqual([
        expect.objectContaining({ ruleId: 'domain-boundaries/no-private-cross-import', severity: 2 }),
      ])
    }
  })

  it('blocks non-app cross-owner imports unless the exact target is a public contract', () => {
    const importer = '/repo/openbank-admin-ui/src/components/payments/PaymentDetails.tsx'
    const privateImports = [
      "import { isUuid } from '@/lib/party/resolveParty'",
      "import type { PartyMatch } from '../../lib/party/resolveParty'",
      "export { resolveParty } from '@/lib/party/resolveParty'",
      "export * from '@/lib/party/resolveParty'",
      "const party = import('@/lib/party/resolveParty')",
    ]
    for (const source of privateImports) {
      expect(lint(source, importer)).toEqual([
        expect.objectContaining({ ruleId: 'domain-boundaries/no-private-cross-import', severity: 2 }),
      ])
    }

    expect(lint("import { isUuid } from '@/lib/party/contracts'", importer)).toEqual([])
    expect(lint("import type { LedgerJournalEntry } from '@/lib/ledger/contracts'", '/repo/openbank-admin-ui/src/components/treasury/DealSummary.tsx')).toEqual([])
  })

  it('blocks app route modules as imports from libraries and components, including type and re-export forms', () => {
    const sources = [
      "import type { ServiceHealthEntry } from '@/app/api/services/health/route'",
      "export { x } from '@/app/api/devops/insights/route'",
      "export * from '../../app/api/loyalty/route'",
      "const x = import('@/app/api/ledger/route')",
    ]
    for (const source of sources) {
      expect(lint(source, '/repo/openbank-admin-ui/src/lib/features/consumer.ts')).toEqual([
        expect.objectContaining({ ruleId: 'domain-boundaries/no-private-cross-import', severity: 2 }),
      ])
    }
    expect(lint(sources[0], '/repo/openbank-admin-ui/src/components/features/Consumer.tsx')).toHaveLength(1)
    expect(lint(sources[0], '/repo/openbank-admin-ui/src/app/features/page.tsx')).toEqual([])
  })

  it('allows same-domain imports, app composition, and explicit shared contracts', () => {
    const source = [
      "import { StatusBadge } from '@/components/ui'",
      "import { AppShell } from '@/components/layout/AppShell'",
      "import { formatMoney } from '@/lib/format/money'",
      "import { parsePension } from '@/lib/pension/contracts'",
      "import { getJson } from '@/lib/services/resourceClient'",
      "import { isIsoDate } from '@/lib/format/bankDate'",
      "import { principalNameFromToken } from '@/lib/auth/principal'",
      "import { PaymentWidget } from '@/components/payments/PaymentWidget'",
      "import { TreasuryWidget } from '@/components/treasury/TreasuryWidget'",
    ].join('\n')
    expect(lint(source, '/repo/openbank-admin-ui/src/app/pension/page.tsx')).toEqual([])
    expect(lint("import { x } from '@/components/balance-sheet/model'", '/repo/openbank-admin-ui/src/app/balance-sheet/page.tsx')).toEqual([])
    expect(lint("import { isUuid } from '@/lib/party/resolveParty'", '/repo/openbank-admin-ui/src/app/payments/page.tsx')).toEqual([])
    expect(PAYMENTS_DOMAIN_BOUNDARY.sharedImports).toContain('@/components/ui')
    expect(PUBLIC_SHARED_CONTRACTS).toContain('src/lib/party/contracts')
    expect(DOMAIN_ROOTS.some(domain => domain.owner === 'treasury')).toBe(true)
    expect(DOMAIN_IMPORT_BOUNDARIES.some(boundary => boundary.owners.includes('treasury'))).toBe(true)
  })

  it('resolves relative paths to the importer and ignores package files outside src', () => {
    expect(findBoundaryViolation('../../../lib/ledger/contracts', '/repo/openbank-admin-ui/src/components/payments/details/Panel.tsx'))
      .toMatchObject({ kind: 'domain', domain: 'ledger' })
    expect(lint("import { x } from '@/lib/ledger/private'", '/repo/openbank-admin-ui/scripts/check.mjs')).toEqual([])
  })
})
