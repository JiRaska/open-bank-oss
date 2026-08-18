import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const files = [
  'src/app/payments/page.tsx',
  'src/app/swift/page.tsx',
  'src/app/security/page.tsx',
  'src/app/audit/page.tsx',
  'src/app/onboarding/page.tsx',
]

describe('interactive table keyboard guard', () => {
  it('keeps click-to-open rows keyboard reachable', () => {
    for (const file of files) {
      const source = readFileSync(resolve(process.cwd(), file), 'utf8')
      expect(source).toContain('tabIndex={0}')
      expect(source).toContain('onKeyDown=')
      expect(source).toMatch(/(?:e|event)\.key === 'Enter' \|\| (?:e|event)\.key === ' '/)
    }
  })
})
