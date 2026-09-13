// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/payments/page.tsx'), 'utf8')
const rawSemanticLiteral = /#(?:16a34a|d97706|dc2626|6366f1|0ea5e9)\b/i
const undefinedLegacyToken = /var\(--(?:red|green|yellow)\)/
const tokenAlphaSuffix = /var\(--[^)]+\)[0-9a-f]{2}/i

describe('payment workflow theme contract', () => {
  it('uses complete semantic triplets for money-path states', () => {
    expect(source).not.toMatch(rawSemanticLiteral)
    expect(source).not.toMatch(undefinedLegacyToken)
    expect(source).not.toMatch(tokenAlphaSuffix)
    for (const tone of ['accent', 'success', 'warning', 'danger', 'info']) {
      expect(source).toContain(`var(--${tone}-text)`)
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
    expect(source).toContain('aria-live="polite"')
  })
})
