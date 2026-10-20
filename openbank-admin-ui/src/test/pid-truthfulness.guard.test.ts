import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

describe('PID lookup truthfulness', () => {
  it('does not render a dead detail button when no detail route exists', () => {
    const source = readFileSync(resolve(process.cwd(), 'src/app/pid/page.tsx'), 'utf8')
    expect(source).not.toMatch(/<button[^>]*>\s*\{t\(\[['"]Detail/)
    expect(source).toMatch(/role="status"[\s\S]*Details unavailable/)
    expect(source).toMatch(/toLocaleDateString\(dateLocale\)/)
    expect(source).not.toContain('/api/v1/pids')
    expect(source).toContain('/api/v1/parties/by-external-id')
    expect(source).toContain("kind === 'not_found'")
  })
})
