// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/fx/page.tsx'), 'utf8')

describe('FX scroll regions', () => {
  it('keeps every bounded table region keyboard-scrollable and named', () => {
    const boundedScrollRegions = source.match(/overflowY: 'auto'/g) ?? []
    const keyboardRegions = source.match(/role="region"[^>]+aria-label=\{t\([^>]+tabIndex=\{0\}/g) ?? []

    expect(boundedScrollRegions).toHaveLength(5)
    expect(keyboardRegions).toHaveLength(boundedScrollRegions.length)
    expect(source).toContain("'Scrollable bank rate sheet'")
    expect(source).toContain("'Scrollable recent conversions table'")
  })
})
