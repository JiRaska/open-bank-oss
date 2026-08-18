import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src/app')

describe('operator surfaces use the active locale for timestamps', () => {
  it('does not leave implicit or fixed-locale date formatting in approvals, notifications, or audit', () => {
    for (const file of ['approvals/page.tsx', 'notifications/page.tsx', 'audit/page.tsx']) {
      const source = fs.readFileSync(path.join(root, file), 'utf8')
      expect(source).toContain("const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
      expect(source).not.toMatch(/toLocaleString\(\)/)
      expect(source).not.toMatch(/toLocaleString\(['"]cs-CZ['"]\)/)
      expect(source).not.toMatch(/toLocaleString\(['"]en-GB['"]\)/)
      expect(source).toMatch(/toLocaleString\(dateLocale\)/)
    }
  })
})
