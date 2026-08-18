import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const app = path.resolve(__dirname, '../app')

describe('FinOps and IAOps shared shell', () => {
  for (const route of ['finops', 'iaops']) {
    it(`${route} uses the shared page header with an accessible hierarchy`, () => {
      const page = readFileSync(path.join(app, route, 'page.tsx'), 'utf8')
      expect(page).toContain("import { PageHeader } from '@/components/ui/PageHeader'")
      expect(page).toContain('<PageHeader')
      expect(page).toContain('className="breadcrumb"')
      expect(page).toContain('aria-hidden="true"')
    })
  }
})
