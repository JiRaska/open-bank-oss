import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

const page = (name: string) => readFileSync(resolve(process.cwd(), 'src/app', name, 'page.tsx'), 'utf8')

describe('operational refresh timestamps use active locale', () => {
  it('does not fall back to the browser locale for visible refresh/summary values', () => {
    const files = [
      'dashboard', 'observability', 'devops', 'iaops', 'system/health', 'system/config',
    ].map(page)
    for (const source of files) {
      expect(source).not.toMatch(/toLocale(?:TimeString|DateString|String)\(\)/)
      expect(source).toMatch(/dateLocale/)
    }
  })
})
