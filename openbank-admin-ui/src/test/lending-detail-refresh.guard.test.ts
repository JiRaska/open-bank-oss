// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('lending detail refresh contract', () => {
  it('exposes localized busy semantics without changing evidence loading', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/lending/applications/[id]/page.tsx'), 'utf8')
    expect(source).toContain('type="button"')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Obnovit žádost o úvěr', 'Refresh lending application')}")
    expect(source).toContain('onClick={() => void load()}')
  })
})
