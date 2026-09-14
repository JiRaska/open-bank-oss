// SPDX-License-Identifier: Apache-2.0

import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import path from 'node:path'

const source = readFileSync(path.resolve(__dirname, '../app/docs/api/page.tsx'), 'utf8')

describe('API catalog semantic theme contract', () => {
  it('uses shared domain and status semantics without a page-local palette', () => {
    expect(source).not.toMatch(/#[0-9a-f]{6}\b|#[0-9a-f]{3}(?![0-9a-f])|rgba?\(/i)
    for (const token of [
      '--map-core', '--map-identity', '--map-compliance', '--map-payment',
      '--map-psd2', '--map-platform', '--map-cards',
      '--info-bg', '--info-border', '--info-text',
      '--success-bg', '--success-border', '--success-text',
      '--warning-bg', '--warning-border', '--warning-text',
      '--danger-bg', '--danger-border', '--danger-text',
      '--accent-bg', '--accent-border', '--accent-text',
    ]) {
      expect(source).toContain(`var(${token})`)
    }
  })

  it('keeps runtime event colours theme-safe when applying opacity', () => {
    expect(source).toContain('color-mix(in srgb, ${item.color}')
    expect(source).not.toContain('${item.color}15')
    expect(source).not.toContain('${item.color}30')
  })
})
