// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('interest loading contract', () => {
  it('announces the existing loading state without changing rate data flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/interest/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={20} aria-hidden="true"')
    expect(source).toContain("t('Načítám…', 'Loading…')")
  })

  it('never hides a visible retry behind a passive-effect single-flight latch', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/interest/page.tsx'), 'utf8')
    expect(source).not.toContain('reloadInFlight')
  })
})
