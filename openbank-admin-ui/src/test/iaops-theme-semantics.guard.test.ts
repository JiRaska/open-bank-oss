// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/iaops/page.tsx'), 'utf8')
const legacySemanticLiteral = /#(?:6366f1|818cf8|ede9fe|eef2ff|4338ca|16a34a|dcfce7|dc2626|fee2e2|fca5a5|d97706|fef9c3|92400e|0891b2|047857|a7f3d0|fff7ed|fed7aa|c2410c)\b/i

describe('IAOps semantic theme contract', () => {
  it('keeps live governance and operator states on shared tokens', () => {
    expect(source).not.toMatch(legacySemanticLiteral)
    for (const tone of ['accent', 'success', 'warning', 'danger', 'info']) {
      expect(source).toContain(`var(--${tone}-text)`)
    }
    for (const tone of ['accent', 'success', 'warning', 'danger']) {
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
    expect(source).toContain('role="alert"')
  })
})
