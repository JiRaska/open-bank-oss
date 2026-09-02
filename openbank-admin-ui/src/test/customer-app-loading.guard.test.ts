// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('customer app dossier loading contract', () => {
  it('announces the existing status fetch loading state', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/docs/customer-app/page.tsx'), 'utf8')
    expect(source).toContain('{loading && <div role="status" aria-live="polite"')
    expect(source).toContain("t('Načítám…', 'Loading…')")
    expect(source).toContain("fetch('/api/app-status'")
  })
})
