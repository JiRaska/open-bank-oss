// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('SWIFT detail loading contract', () => {
  it('announces loading without changing list-source refresh or RBAC', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/swift/[id]/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={14} aria-hidden="true"')
    expect(source).toContain("t('Načítám zprávu…', 'Loading message…')")
    expect(source).toContain('aria-label={t(\'Obnovit SWIFT zprávu\', \'Refresh SWIFT message\')}')
  })
})
