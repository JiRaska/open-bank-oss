// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/system/tests/page.tsx'), 'utf8')
const rawColour = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])|rgba\(/

describe('system tests theme contract', () => {
  it('expresses evidence states and guidance through shared semantic tokens', () => {
    expect(source).not.toMatch(rawColour)
    for (const tone of ['accent', 'success', 'warning', 'danger']) {
      expect(source).toContain(`var(--${tone}-text)`)
    }
    expect(source).toContain('var(--accent-bg)')
    expect(source).toContain('var(--warning-bg)')
    expect(source).toContain('var(--accent-border)')
    expect(source).toContain('var(--warning-border)')
  })
})
