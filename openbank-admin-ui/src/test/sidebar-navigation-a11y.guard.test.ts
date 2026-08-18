import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(resolve(__dirname, '../components/layout/Sidebar.tsx'), 'utf8')

describe('sidebar navigation accessibility contract', () => {
  it('announces the active route on both internal and same-origin external links', () => {
    expect(source).toContain("aria-current={active ? 'page' : undefined}")
    expect(source).toMatch(/<a key=\{item\.href\}[^>]*aria-current=\{active \? 'page' : undefined\}/)
    expect(source).toMatch(/<Link key=\{item\.href\}[^>]*aria-current=\{active \? 'page' : undefined\}/)
  })
})
