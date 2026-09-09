import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('operator surfaces use the active locale for timestamps', () => {
  it('does not leave implicit or fixed-locale date formatting in approvals, notifications, or audit', () => {
    for (const file of [
      'src/app/approvals/page.tsx',
      'src/components/notifications/NotificationsPage.tsx',
      'src/app/audit/page.tsx',
    ]) {
      const source = fs.readFileSync(path.resolve(process.cwd(), file), 'utf8')
      expect(source).toContain("const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
      expect(source).not.toMatch(/toLocaleString\(\)/)
      expect(source).not.toMatch(/toLocaleString\(['"]cs-CZ['"]\)/)
      expect(source).not.toMatch(/toLocaleString\(['"]en-GB['"]\)/)
      expect(source).toMatch(/toLocaleString\(dateLocale\)/)
    }
  })
})
