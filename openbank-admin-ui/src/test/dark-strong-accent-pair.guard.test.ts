// SPDX-License-Identifier: Apache-2.0

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const globals = readFileSync(path.resolve(__dirname, '../app/globals.css'), 'utf8')

describe('dark strong-accent foreground pair', () => {
  it('overrides both halves of the accent fill pair in the dark token block', () => {
    const dark = globals.slice(globals.indexOf('.dark {'), globals.indexOf('\n}', globals.indexOf('.dark {')))
    expect(dark).toContain('--accent-strong: #a5b4fc;')
    expect(dark).toContain('--text-inverse: #0f172a;')
  })
})
