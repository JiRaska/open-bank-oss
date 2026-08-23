// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('document template workflow controls contract', () => {
  it('keeps action and lookup controls explicit and truthful', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/document-templates/page.tsx'), 'utf8')
    expect(source).toContain('type="button" onClick={openCreateModal} disabled={loading}')
    expect(source).toContain('type="button" onClick={() => setPendingAction(null)} disabled={actioning}')
    expect(source).toContain('aria-busy={actioning}')
    expect(source).toContain('aria-busy={loading}')
    expect(source).toContain("aria-label={t('Vyhledat dokument podle ID', 'Look up document by ID')}")
    expect(source).toContain('type="button" onClick={() => runAction(pendingAction.id, pendingAction.kind)}')
  })
})
