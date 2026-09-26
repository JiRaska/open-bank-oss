// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('global error recovery contract', () => {
  it('keeps in-place recovery explicit and accessible', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/global-error.tsx'), 'utf8')
    expect(source).toContain('role="alert" aria-labelledby="global-error-title"')
    expect(source).toContain('<button type="button" aria-label="Try loading the admin console again / Zkusit znovu načíst konzoli"')
    expect(source).toContain('onClick={reset}')
    expect(source).not.toContain('window.location.reload()')
  })
})
