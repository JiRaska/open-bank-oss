// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/security/page.tsx'), 'utf8')
const rawColour = /#[0-9a-fA-F]{6}\b|#[0-9a-fA-F]{3}(?![0-9a-fA-F])|rgba?\(/
const tokenAlphaSuffix = /var\(--[^)]+\)[0-9a-f]{2}/i

describe('security scanner theme contract', () => {
  it('uses valid shared semantics for every risk and grade state', () => {
    expect(source).not.toMatch(rawColour)
    expect(source).not.toMatch(tokenAlphaSuffix)
    for (const tone of ['accent', 'success', 'warning', 'danger', 'info']) {
      expect(source).toContain(`var(--${tone}-text)`)
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
    expect(source).toContain("language === 'cs' ? 'cs-CZ' : 'en-GB'")
    expect(source).toContain('role="alert"')
  })
})
