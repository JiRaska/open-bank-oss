import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = () => fs.readFileSync(path.join(process.cwd(), 'src/app/security/incidents/page.tsx'), 'utf8')

describe('ICT incident register truthfulness', () => {
  it('distinguishes an empty, successfully loaded register from every unavailable state', () => {
    const source = read()
    expect(source).toContain("failure === 'unauthorized'")
    expect(source).toContain("failure === 'not_deployed'")
    expect(source).toContain("failure === 'invalid_response'")
    expect(source).toContain('Showing the last successfully verified snapshot.')
    expect(source).toContain('The register was verified and contains no incidents.')
  })

  it('keeps the read-only register accessible and exposes the incident category', () => {
    const source = read()
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain('role="alert"')
    expect(source).toContain('<caption className="sr-only">')
    expect(source).toContain('<StatusBadge status="REPORTED"')
    expect(source).toContain('item.regulatoryReportId')
  })
})
