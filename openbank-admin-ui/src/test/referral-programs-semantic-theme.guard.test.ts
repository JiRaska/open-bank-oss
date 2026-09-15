// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/campaigns/referrals/page.tsx'), 'utf8')

describe('referral programme semantic theme', () => {
  it('keeps programme evidence adaptive and its truthful states actionable', () => {
    expect(source).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(/iu)
    expect(source).not.toMatch(/\b(?:bg|text|border|hover:bg|hover:text|hover:border)-(?:white|slate|violet|emerald|rose|amber|red|indigo)-/u)
    for (const token of ['surface', 'surface-2', 'border', 'text-primary', 'text-secondary', 'text-tertiary', 'accent-bg', 'accent-border', 'accent-text', 'success-text', 'warning-bg', 'warning-text']) {
      expect(source).toContain(`var(--${token})`)
    }
    expect(source).toContain('<LoadingState')
    expect(source).toContain('<EmptyState')
    expect(source).toContain('onClick={() => void loadPrograms()}')
    expect(source).toContain('permission="campaign:view"')
  })
})
