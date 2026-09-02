import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('legacy shell pages use the shared header contract', () => {
  it('keeps one structured title and decorative icons', () => {
    for (const file of ['delegations/[id]/page.tsx', 'feedback/page.tsx']) {
      const source = fs.readFileSync(path.join(process.cwd(), 'src/app', file), 'utf8')
      expect(source).toContain('PageHeader')
      expect(source).toContain('aria-hidden="true"')
      expect(source).not.toMatch(/<h1\b/)
    }

    const detail = fs.readFileSync(path.join(process.cwd(), 'src/app/delegations/[id]/page.tsx'), 'utf8')
    const coverageProbe = fs.readFileSync(
      path.join(process.cwd(), 'src/components/delegations/CoverageProbe.tsx'),
      'utf8',
    )
    expect(detail).toContain('className="breadcrumb"')
    expect(coverageProbe).toContain("fetch('/api/delegations/check'")
  })
})
