import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/security/incidents/page.tsx'), 'utf8')

describe('ICT incident register truthfulness', () => {
  it('distinguishes an empty, successfully loaded register from every unavailable state', () => {
    const source = read()
    expect(source).toContain("data.reason === 'unauthorized'")
    expect(source).toContain("data.reason === 'not_deployed'")
    expect(source).toContain("data.reason === 'unreachable'")
    expect(source).toContain('This does not confirm that no incidents exist.')
    expect(source).toContain('contains no records.')
  })

  it('keeps the read-only register accessible and exposes the incident category', () => {
    const source = read()
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain('role="alert"')
    expect(source).toContain('<caption className="sr-only">')
    expect(source).toContain("{i.category || '—'}")
    expect(source).toContain("i.reportedToRegulator ? t('Oznámeno', 'Reported')")
  })
})
