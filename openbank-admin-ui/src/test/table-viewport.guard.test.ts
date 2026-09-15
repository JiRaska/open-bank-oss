import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const migratedTables = [
  'src/app/accounts/page.tsx',
  'src/app/parties/page.tsx',
  'src/app/kyc/page.tsx',
  'src/app/fraud/page.tsx',
  'src/app/lending/page.tsx',
  'src/app/aml/page.tsx',
  'src/app/clearing/page.tsx',
  'src/app/approvals/page.tsx',
  'src/app/sanctions/page.tsx',
  'src/app/fees/page.tsx',
  'src/app/interest/page.tsx',
  'src/app/merchants/page.tsx',
  'src/app/standing-orders/page.tsx',
  'src/app/temporal/page.tsx',
  'src/app/campaigns/page.tsx',
  'src/app/campaigns/[id]/page.tsx',
  'src/app/communication/page.tsx',
  'src/app/approvals/communication/page.tsx',
  'src/app/document-templates/page.tsx',
  'src/app/feedback/page.tsx',
  'src/app/delegations/page.tsx',
  'src/app/lending/compliance-packs/page.tsx',
  'src/app/loyalty/page.tsx',
  'src/app/parties/[id]/page.tsx',
  'src/app/pid/page.tsx',
  'src/app/regulatory/page.tsx',
  'src/app/infrastructure/page.tsx',
  'src/app/system/inventory/page.tsx',
  'src/app/temporal/flow/page.tsx',
  'src/components/notifications/NotificationsPage.tsx',
  'src/components/lending/risk/PolicyTables.tsx',
  'src/components/sbom/SbomViewer.tsx',
  'src/app/docs/compliance/page.tsx',
  'src/app/docs/customer-app/page.tsx',
  'src/app/docs/flags/page.tsx',
  'src/components/campaigns/PeopleSummary.tsx',
  'src/components/cards/CardCapabilityMatrix.tsx',
  'src/app/lending/risk/page.tsx',
  'src/app/system/readiness/page.tsx',
  'src/app/reporting/page.tsx',
  'src/app/day-end/page.tsx',
  'src/app/disputes/page.tsx',
  'src/app/ledger/page.tsx',
]

describe('responsive banking table viewport', () => {
  it('keeps the audited wide workflows on the shared accessible boundary', () => {
    for (const file of migratedTables) {
      expect(readFileSync(resolve(process.cwd(), file), 'utf8')).toContain('<TableViewport')
    }
  })

  it('retains keyboard and mobile discovery semantics in the primitive', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/components/ui/TableViewport.tsx'), 'utf8')
    expect(source).toContain('role="region"')
    expect(source).toContain('tabIndex={0}')
    expect(source).toContain('{hint}')
  })
})
