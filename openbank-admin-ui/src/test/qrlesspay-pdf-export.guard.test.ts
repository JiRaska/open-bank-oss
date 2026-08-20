import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const read = (file: string) => fs.readFileSync(path.resolve(process.cwd(), 'src', file), 'utf8')

describe('QRlessPay print/PDF export contract', () => {
  it('offers a localized native print action and print layout', () => {
    const page = read('app/docs/qrlesspay/page.tsx')
    const css = read('app/globals.css')
    expect(page).toContain('window.print()')
    expect(page).toContain("t('Exportovat PDF', 'Export PDF')")
    expect(page).toContain('qrlesspay-export-action')
    expect(css).toContain('@media print')
    expect(css).toContain('.qrlesspay-doc .card')
  })
})
