// SPDX-License-Identifier: Apache-2.0
import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

describe('onboarding filter clear contract', () => {
  it('keeps stage filter reset explicit and decorative', () => {
    const source = readFileSync(path.resolve(__dirname, '../app/onboarding/page.tsx'), 'utf8')
    expect(source).toContain('<button type="button"')
    expect(source).toContain("onClick={() => handleStageFilter('')}")
    expect(source).toContain("aria-label={t('Zrušit filtr', 'Clear filter')}")
    expect(source).toContain('<X size={11} aria-hidden="true" />')
  })
})
