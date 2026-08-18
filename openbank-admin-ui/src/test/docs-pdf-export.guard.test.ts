import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(process.cwd(), 'src')
const read = (file: string) => fs.readFileSync(path.join(root, file), 'utf8')

describe('long-form documentation PDF export contract', () => {
  it('adds print export only to static, review-oriented docs', () => {
    const button = read('components/docs/PrintDocumentButton.tsx')
    const readiness = read('app/docs/qrlesspay-readiness/page.tsx')
    const threat = read('app/docs/threat-models/[service]/page.tsx')
    const adr = read('app/docs/adr/[slug]/page.tsx')
    const compliance = read('app/docs/compliance/page.tsx')
    const documentManagement = read('app/docs/document-management/page.tsx')
    const identityDedup = read('app/docs/identity-dedup/page.tsx')
    const css = read('app/globals.css')
    expect(button).toContain('window.print()')
    expect(button).toContain("t('Exportovat PDF', 'Export PDF')")
    for (const source of [readiness, threat, adr, compliance, documentManagement, identityDedup]) {
      expect(source).toContain('PrintDocumentButton')
      expect(source).toContain('className="docs-printable"')
    }
    expect(css).toContain('.docs-printable .card')
  })
})
