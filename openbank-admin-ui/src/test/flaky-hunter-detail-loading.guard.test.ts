// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('flaky finding detail loading contract', () => {
  it('announces detail loading without changing finding fetch flow', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/iaops/flaky-test-hunter/[id]/page.tsx'), 'utf8')
    expect(source).toContain('<div role="status" aria-live="polite"')
    expect(source).toContain('<RefreshCw size={16} aria-hidden="true"')
    expect(source).toContain("t('Načítám nález…', 'Loading finding…')")
  })
})
