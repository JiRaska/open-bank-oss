import { Linter } from 'eslint'
import parser from '@typescript-eslint/parser'
import { describe, expect, it } from 'vitest'
import {
  domainBoundariesPlugin,
  PAYMENTS_DOMAIN_BOUNDARY,
} from '../../scripts/domain-boundaries.mjs'

const linter = new Linter({ configType: 'eslintrc' })
linter.defineParser('typescript', parser as never)
linter.defineRule(
  'domain-boundaries/no-payments-private-cross-domain',
  domainBoundariesPlugin.rules['no-payments-private-cross-domain'],
)

function lint(source: string, filename: string) {
  return linter.verify(source, {
    parser: 'typescript',
    parserOptions: { ecmaVersion: 'latest', sourceType: 'module' },
    rules: { 'domain-boundaries/no-payments-private-cross-domain': 'error' },
  }, filename)
}

describe('payments domain import boundary', () => {
  it('rejects alias, relative, type-only, re-export, and literal dynamic imports into private sibling domains', () => {
    const imports = [
      "import { x } from '@/lib/ledger/privateLedger'",
      "import type { x } from '../ledger/privateLedger'",
      "export { x } from '@/components/balance-sheet/model'",
      "export * from '../../components/treasury/positions'",
      "const x = import('@/app/ledger/page')",
      "import { x } from '@/lib/payments/../ledger/privateLedger'",
    ]

    for (const source of imports) {
      expect(lint(source, '/repo/openbank-admin-ui/src/components/payments/Widget.tsx')).toEqual([
        expect.objectContaining({ ruleId: 'domain-boundaries/no-payments-private-cross-domain', severity: 2 }),
      ])
    }
  })

  it('allows shared shell and UI primitives, shared libraries, and cross-root imports within payments', () => {
    const source = [
      "import { StatusBadge } from '@/components/ui'",
      "import { AppShell } from '@/components/layout/AppShell'",
      "import { AuthGuard } from '@/components/auth/AuthGuard'",
      "import { formatMoney } from '@/lib/format/money'",
      "import { parsePayment } from '@/lib/payments/contracts'",
      "import { PaymentWidget } from '@/components/payments/PaymentWidget'",
      "import { Page } from '@/app/payments/page'",
    ].join('\n')

    expect(lint(source, '/repo/openbank-admin-ui/src/app/payments/page.tsx')).toEqual([])
    expect(PAYMENTS_DOMAIN_BOUNDARY.sharedImports).toContain('@/components/ui')
  })

  it('resolves relative paths that escape the payments folder into a forbidden sibling', () => {
    const diagnostics = lint(
      "import type { LedgerEntry } from '../../../lib/ledger/contracts'",
      '/repo/openbank-admin-ui/src/components/payments/details/Panel.tsx',
    )

    expect(diagnostics).toHaveLength(1)
    expect(diagnostics[0].message).toContain('ledger')
  })

  it('does not police other domains or package files outside src', () => {
    expect(lint("import { x } from '@/lib/ledger/private'", '/repo/openbank-admin-ui/src/app/ledger/page.tsx')).toEqual([])
    expect(lint("import { x } from '@/lib/ledger/private'", '/repo/openbank-admin-ui/scripts/check.mjs')).toEqual([])
  })
})
