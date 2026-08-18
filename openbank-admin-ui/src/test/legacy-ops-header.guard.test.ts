import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('legacy operational pages use shared PageHeader', () => {
  it('keeps one structured header and active timestamp locale', () => {
    for (const file of ['iaops/cases/page.tsx', 'system/tests/page.tsx']) {
      const source = fs.readFileSync(path.join(process.cwd(), 'src/app', file), 'utf8')
      expect(source).toContain('PageHeader')
      expect(source).toContain('className="breadcrumb"')
      expect(source).toContain('aria-hidden="true"')
      expect(source).not.toMatch(/<h1\b/)
    }

    const tests = fs.readFileSync(path.join(process.cwd(), 'src/app/system/tests/page.tsx'), 'utf8')
    expect(tests).toContain("const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(tests).not.toMatch(/toLocaleTimeString\(\)/)
    expect(tests).not.toMatch(/toLocaleString\(\)/)
    expect(tests).not.toMatch(/toLocaleDateString\(\)/)
  })
})
