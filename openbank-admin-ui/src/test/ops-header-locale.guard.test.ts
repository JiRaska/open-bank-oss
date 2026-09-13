import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('legacy operator surfaces use the shared header and active locale', () => {
  it('migrates the three pages without implicit or fixed locale timestamps', () => {
    for (const file of ['swift/page.tsx', 'infrastructure/page.tsx', 'system/inventory/page.tsx']) {
      const source = fs.readFileSync(path.join(process.cwd(), 'src/app', file), 'utf8')
      expect(source).toContain('PageHeader')
      expect(source).toContain("const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
      expect(source).not.toMatch(/toLocaleTimeString\(\)/)
      expect(source).not.toMatch(/toLocaleTimeString\(['"](?:cs-CZ|en-US|en-GB)['"]\)/)
      expect(source).not.toMatch(/toLocaleDateString\(\)/)
      expect(source).not.toMatch(/toLocaleDateString\(['"](?:cs-CZ|en-US|en-GB)['"]\)/)
      expect(source).not.toMatch(/toLocaleString\(['"](?:cs-CZ|en-US|en-GB)['"]\)/)
    }
    const swift = fs.readFileSync(path.join(process.cwd(), 'src/app/swift/page.tsx'), 'utf8')
    expect(swift).toContain('const numberLocale = dateLocale')
    expect(swift).toMatch(/toLocaleString\(numberLocale/)
    expect(swift).toMatch(/toLocaleDateString\(dateLocale\)/)
  })
})
