// SPDX-License-Identifier: Apache-2.0
// Copyright (c) OpenBank contributors. Licensed under the Apache License, Version 2.0.

import { readFileSync } from 'node:fs'
import path from 'node:path'
import { describe, expect, it } from 'vitest'

const source = readFileSync(path.resolve(__dirname, '../app/regulatory/page.tsx'), 'utf8')
const legacySemanticLiteral = /#(?:2563eb|6b7280|d97706|16a34a|eff6ff|bfdbfe|1e40af|dbeafe|991b1b|fef2f2|fecaca|92400e|fffbeb|fde68a|b45309)\b/i

describe('regulatory reporting theme contract', () => {
  it('uses shared semantics for evidence and export safety states', () => {
    expect(source).not.toMatch(legacySemanticLiteral)
    for (const tone of ['info', 'success', 'warning', 'danger']) {
      expect(source).toContain(`var(--${tone}-text)`)
    }
    for (const tone of ['info', 'warning', 'danger']) {
      expect(source).toContain(`var(--${tone}-bg)`)
      expect(source).toContain(`var(--${tone}-border)`)
    }
    expect(source).toContain('data-testid="test-data-watermark"')
    expect(source).toContain('data-testid="export-readiness"')
  })
})
