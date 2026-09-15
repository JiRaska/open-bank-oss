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
