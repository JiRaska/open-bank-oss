import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const auditedFiles = [
  'src/app/lending/risk/page.tsx',
  'src/app/observability/stack/page.tsx',
  'src/app/system/agent/page.tsx',
  'src/app/temporal/page.tsx',
  'src/app/docs/customer-app/page.tsx',
  'src/app/docs/page.tsx',
  'src/app/docs/sensors/page.tsx',
  'src/app/docs/identity-dedup/page.tsx',
  'src/app/cards/[id]/page.tsx',
  'src/app/security/excellence/page.tsx',
  'src/app/infrastructure/page.tsx',
  'src/app/regulatory/page.tsx',
  'src/app/finops/page.tsx',
  'src/app/docs/qrlesspay/page.tsx',
  'src/app/system/health/page.tsx',
]

describe('responsive grid minimum contract', () => {
  it('bounds wide auto-fit and auto-fill tracks by their available width', () => {
    for (const file of auditedFiles) {
      const source = readFileSync(resolve(process.cwd(), file), 'utf8')
      expect(source).not.toMatch(/repeat\(auto-(?:fit|fill), minmax\((?:[3-9][0-9]{2})px, 1fr\)\)/)
    }
  })
})
