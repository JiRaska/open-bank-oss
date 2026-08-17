import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const app = path.resolve(__dirname, '../app')
const routes = ['aml', 'disputes', 'clearing', 'fees', 'standing-orders']

describe('read-only operations tables', () => {
  it('names their search controls and formats displayed values in the active locale', () => {
    for (const route of routes) {
      const page = readFileSync(path.join(app, route, 'page.tsx'), 'utf8')
      expect(page, route).toContain("const numberLocale = language === 'cs' ? 'cs-CZ' : 'en-GB'")
      expect(page, route).toContain('aria-label={t(')
      expect(page, route).toContain('toLocaleString(numberLocale')
    }
  })

  it('keeps search illustrations out of the accessibility tree', () => {
    for (const route of routes) {
      const page = readFileSync(path.join(app, route, 'page.tsx'), 'utf8')
      expect(page, route).toContain('<Search size=')
      expect(page, route).toContain('aria-hidden="true"')
    }
  })
})
