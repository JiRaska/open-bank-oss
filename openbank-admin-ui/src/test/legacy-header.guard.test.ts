import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

describe('legacy operational pages use the shared PageHeader contract', () => {
  it('keeps a single structured header with breadcrumb and decorative icon', () => {
    for (const file of ['finops/allocation/page.tsx', 'observability/stack/page.tsx']) {
      const source = fs.readFileSync(path.join(process.cwd(), 'src/app', file), 'utf8')
      expect(source).toContain('PageHeader')
      expect(source).toContain('className="breadcrumb"')
      expect(source).toContain('aria-hidden="true"')
      expect(source).not.toMatch(/<h1\b/)
    }
  })
})
