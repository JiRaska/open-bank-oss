// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('screen feedback loading contract', () => {
  it('announces the existing initial loading state', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/feedback/page.tsx'), 'utf8')
    expect(source).toContain('<p role="status" aria-live="polite">')
    expect(source).toContain("cs ? 'Načítám…' : 'Loading…'")
    expect(source).toContain("aria-label={cs ? 'Obnovit zpětnou vazbu k obrazovkám' : 'Refresh screen feedback'}")
    expect(source).toContain("fetch('/api/feedback/screen-feedback', { cache: 'no-store' })")
  })
})
