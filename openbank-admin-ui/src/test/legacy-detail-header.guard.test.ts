import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('legacy detail pages use shared PageHeader', () => {
  it('keeps structured breadcrumbs and a single semantic title', () => {
    for (const file of [
      'iaops/cases/[caseId]/page.tsx',
      'iaops/agents/[agentId]/page.tsx',
      'temporal/page.tsx',
    ]) {
      const source = fs.readFileSync(path.join(process.cwd(), 'src/app', file), 'utf8')
      expect(source).toContain('PageHeader')
      expect(source).toContain('className="breadcrumb"')
      expect(source).toContain('aria-hidden="true"')
      expect(source).not.toMatch(/<h1\b/)
    }

    const temporal = fs.readFileSync(path.join(process.cwd(), 'src/app/temporal/page.tsx'), 'utf8')
    expect(temporal).toContain("const dateLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(temporal).not.toMatch(/toLocaleTimeString\(\)/)
  })
})
