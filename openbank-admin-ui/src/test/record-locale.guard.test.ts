import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const source = (route: string) => readFileSync(resolve(process.cwd(), 'src/app', route, 'page.tsx'), 'utf8')

describe('banking record dates use active locale', () => {
  it('does not expose browser-dependent date formatting', () => {
    const routes = ['sdd', 'pid', 'cards', 'parties/[id]', 'payments/[id]', 'docs/cluster']
    for (const route of routes) {
      const file = source(route)
      expect(file).not.toMatch(/toLocale(?:String|DateString|TimeString)\(\)/)
      expect(file).toMatch(/(?:dateLocale|numberLocale)/)
    }
    expect(source('parties/[id]')).toMatch(/row\.sentAt[\s\S]*toLocaleString\(dateLocale\)/)
    expect(source('parties/[id]')).toMatch(/row\.readAt[\s\S]*toLocaleString\(dateLocale\)/)
  })
})
