// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('card detail loading contract', () => {
  it('announces loading and names the refresh action without changing card flows', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/cards/[id]/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={20} aria-hidden="true"')
    expect(source).toContain('type="button" className="btn btn-ghost btn-sm"')
    expect(source).toContain("aria-label={t('Obnovit kartu', 'Refresh card')}")
    expect(source).toContain('onClick={reload}')
  })
})
