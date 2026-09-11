// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/temporal/page.tsx'), 'utf8')
const rawColour = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])|rgba\(/
const legacyColourFallback = /var\(--(?:color-|muted-bg)[^)]+,\s*#[0-9a-fA-F]{3,6}/

describe('Temporal theme contract', () => {
  it('expresses lifecycle and architecture semantics through shared tokens', () => {
    expect(source).not.toMatch(rawColour)
    expect(source).not.toMatch(legacyColourFallback)
    for (const tone of ['accent', 'success', 'warning', 'danger']) {
      expect(source).toContain(`var(--${tone}-text)`)
    }
    for (const tone of ['accent', 'success', 'warning']) {
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
    expect(source).toContain('var(--info-text)')
  })
})
