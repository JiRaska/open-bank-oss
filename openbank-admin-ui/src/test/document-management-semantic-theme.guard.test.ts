import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = readFileSync(join(process.cwd(), 'src/app/docs/document-management/page.tsx'), 'utf8')

describe('document management semantic theme contract', () => {
  it('uses semantic tokens instead of fixed presentation colours', () => {
    expect(page).not.toMatch(/#[0-9a-f]{3,8}\b/i)
    expect(page).not.toContain('rgba(')
    expect(page).toContain("const SUCCESS = 'var(--success)'")
    expect(page).toContain("const WARNING = 'var(--warning)'")
    expect(page).toContain('var(--success-bg)')
    expect(page).toContain('var(--info-bg)')
    expect(page).toContain('var(--shadow-sm)')
  })

  it('preserves the educational and security model', () => {
    expect(page).toContain('DOCUMENT_SIGNED')
    expect(page).toContain('SIGNATURE_CEREMONY_COMPLETED')
    expect(page).toContain('ClientSignatureIssuerPort')
    expect(page).toContain('SignatureSealPort')
    expect(page).toContain('non-money-path')
    expect(page).toContain('/docs/adr/0161-object-storage-standard-for-application-documents')
    expect(page).toContain('/docs/adr/0162-document-management-templating-and-e-signature-architecture')
  })
})
