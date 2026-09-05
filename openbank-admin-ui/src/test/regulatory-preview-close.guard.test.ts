// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('regulatory export preview close contract', () => {
  it('keeps preview close explicit and decorative', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/regulatory/page.tsx'), 'utf8')
    expect(source).toContain('type="button" className="btn btn-secondary"')
    expect(source).toContain("aria-label={t('Zavřít náhled exportu', 'Close export preview')}")
    expect(source).toContain('<X size={15} aria-hidden="true" />')
    expect(source).toContain('<Dialog.Close asChild>')
    expect(source).toContain('onOpenChange={open => { if (!open) setPreview(null) }}')
  })
})
