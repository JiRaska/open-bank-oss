import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/cards/page.tsx'), 'utf8')

describe('cards filter accessibility', () => {
  it('exposes state and safe button semantics for status and type filters', () => {
    const source = read()
    expect(source).toContain('aria-label={t(\'Filtr podle stavu\', \'Filter by status\')}')
    expect(source).toContain('aria-label={t(\'Filtr podle typu\', \'Filter by type\')}')
    expect(source).toContain('type="button" aria-pressed={statusFilter === ALL}')
    expect(source).toContain('type="button" aria-pressed={active}')
    expect(source).toContain('setStatusFilter(active ? ALL : s)')
    expect(source).toContain('setTypeFilter(active ? ALL : ct)')
  })
})
