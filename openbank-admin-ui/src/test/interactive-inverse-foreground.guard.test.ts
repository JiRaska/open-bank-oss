// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const interactiveSurfaces = [
  'app/settings/page.tsx',
  'app/product-catalog/page.tsx',
  'app/fx/page.tsx',
  'app/lending/risk/page.tsx',
  'app/lending/page.tsx',
  'app/docs/api/page.tsx',
  'app/parties/[id]/page.tsx',
]

describe('interactive inverse foregrounds', () => {
  it.each(interactiveSurfaces)('%s delegates filled-control contrast to the shared theme', relativePath => {
    const source = readFileSync(path.resolve(__dirname, '..', relativePath), 'utf8')
    expect(source).not.toMatch(/#[fF]{3}(?:[fF]{3})?\b/)
    expect(source).toContain('var(--text-inverse)')
  })
})
