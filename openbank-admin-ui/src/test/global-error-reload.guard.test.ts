// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('global error recovery contract', () => {
  it('keeps reload recovery an explicit accessible button', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/global-error.tsx'), 'utf8')
    expect(source).toContain('<button type="button" aria-label="Reload admin console / Načíst konzoli"')
    expect(source).toContain('window.location.reload()')
  })
})
